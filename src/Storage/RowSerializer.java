
package Storage;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Converts a single row to a byte array and back. A NULL value is a Java
 * null in the row array.
 *
 * Needs the table's column types ("DOUBLE" or "STRING", the same strings
 * Engine.Table uses) because the bytes carry no type information of their
 * own -- the schema is the only thing that says how to read them.
 *
 * Format:
 *   null bitmap   ceil(columns / 8) bytes. Bit i (counting from the least
 *                 significant bit of byte 0) is set if column i is NULL.
 *                 Unused bits in the last byte must be zero.
 *   then, for each column that is NOT null, in column order:
 *     DOUBLE  8 bytes, big-endian IEEE 754
 *     STRING  2-byte unsigned length N, then N bytes of UTF-8
 *
 * A NULL column takes no space beyond its bit in the bitmap. Every row is
 * therefore at least one byte long, even a row where every column is NULL.
 * (Files written before NULL support had no bitmap; that was file format
 * version 1, which PageFile no longer reads.)
 *
 * Strings are the reason rows vary in size, which is why pages need a
 * slot array instead of fixed-width row positions.
 *
 * This class knows nothing about pages, files, or the Engine package.
 */
public class RowSerializer {

    // The length prefix is an unsigned 16-bit value.
    public static final int MAX_STRING_BYTES = 65535;

    private RowSerializer() {}

    // Serializes a row in which every column may be NULL.
    public static byte[] serialize(Object[] row, List<String> types) {
        return serialize(row, types, null);
    }

    // Serializes a row, refusing NULL in any column the schema marks NOT NULL.
    public static byte[] serialize(Object[] row, Schema schema) {
        return serialize(row, schema.columnTypes(), schema);
    }

    private static byte[] serialize(Object[] row, List<String> types, Schema schema) {
        if (row.length != types.size()) {
            throw new IllegalArgumentException("Row has " + row.length + " values but the table has "
                + types.size() + " columns");
        }

        // First pass: validate everything and work out the exact size, so
        // nothing gets half-written if a later column turns out to be bad.
        byte[] bitmap = new byte[bitmapBytes(row.length)];
        byte[][] encodedStrings = new byte[row.length][];
        int size = bitmap.length;
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            String type = types.get(i);
            if (!type.equals("DOUBLE") && !type.equals("STRING")) {
                throw new IllegalArgumentException("Unknown column type: " + type);
            }
            if (value == null) {
                if (schema != null && !schema.isNullable(i)) {
                    throw new IllegalArgumentException("Column " + i + " ('" + schema.columnNames().get(i)
                        + "') is NOT NULL but got NULL");
                }
                bitmap[i / 8] |= (byte) (1 << (i % 8));
                continue;
            }
            if (type.equals("DOUBLE")) {
                if (!(value instanceof Double)) {
                    throw new IllegalArgumentException("Column " + i + " expects DOUBLE, got "
                        + value.getClass().getSimpleName());
                }
                size += 8;
            } else {
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("Column " + i + " expects STRING, got "
                        + value.getClass().getSimpleName());
                }
                byte[] encoded = ((String) value).getBytes(StandardCharsets.UTF_8);
                if (encoded.length > MAX_STRING_BYTES) {
                    throw new IllegalArgumentException("Column " + i + " is " + encoded.length
                        + " bytes as UTF-8; the limit is " + MAX_STRING_BYTES);
                }
                encodedStrings[i] = encoded;
                size += 2 + encoded.length;
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(size); // big-endian by default
        buf.put(bitmap);
        for (int i = 0; i < row.length; i++) {
            if (row[i] == null) {
                continue;
            }
            if (types.get(i).equals("DOUBLE")) {
                buf.putDouble((Double) row[i]);
            } else {
                buf.putShort((short) encodedStrings[i].length);
                buf.put(encodedStrings[i]);
            }
        }
        return buf.array();
    }

    public static Object[] deserialize(byte[] bytes, List<String> types) {
        int columns = types.size();
        int bitmapLength = bitmapBytes(columns);
        if (bytes.length < bitmapLength) {
            throw new IllegalArgumentException("Row bytes are truncated");
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte[] bitmap = new byte[bitmapLength];
        buf.get(bitmap);
        if (columns % 8 != 0) {
            int unusedBits = (0xFF << (columns % 8)) & 0xFF;
            if ((bitmap[bitmapLength - 1] & unusedBits) != 0) {
                throw new IllegalArgumentException("Null bitmap has bits set beyond the last column");
            }
        }

        Object[] row = new Object[columns];
        try {
            for (int i = 0; i < columns; i++) {
                String type = types.get(i);
                if (!type.equals("DOUBLE") && !type.equals("STRING")) {
                    throw new IllegalArgumentException("Unknown column type: " + type);
                }
                if ((bitmap[i / 8] & (1 << (i % 8))) != 0) {
                    continue; // NULL: nothing stored, row[i] stays null
                }
                if (type.equals("DOUBLE")) {
                    row[i] = buf.getDouble();
                } else {
                    int length = buf.getShort() & 0xFFFF;
                    if (length > buf.remaining()) {
                        throw new IllegalArgumentException("Column " + i + " claims " + length
                            + " bytes but only " + buf.remaining() + " remain");
                    }
                    byte[] encoded = new byte[length];
                    buf.get(encoded);
                    row[i] = new String(encoded, StandardCharsets.UTF_8);
                }
            }
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Row bytes are truncated");
        }
        if (buf.hasRemaining()) {
            throw new IllegalArgumentException(buf.remaining() + " unexpected trailing bytes after the last column");
        }
        return row;
    }

    // Size of the null bitmap for a row with this many columns.
    private static int bitmapBytes(int columns) {
        return (columns + 7) / 8;
    }
}