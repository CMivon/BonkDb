
package Storage;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;


//TODO: play around with the types. Need this to work with more types
/**
 * Converts a single row to a byte array and back.
 *
 * Needs the table's column types ("DOUBLE" or "STRING", the same strings
 * Engine.Table uses) because the bytes carry no type information of their
 * own -- the schema is the only thing that says how to read them.
 *
 * Format, column by column, in order:
 *   DOUBLE  8 bytes, big-endian IEEE 754
 *   STRING  2-byte unsigned length N, then N bytes of UTF-8
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

    public static byte[] serialize(Object[] row, List<String> types) {
        if (row.length != types.size()) {
            throw new IllegalArgumentException("Row has " + row.length + " values but the table has "
                + types.size() + " columns");
        }

        // First pass: validate everything and work out the exact size, so
        // nothing gets half-written if a later column turns out to be bad.
        byte[][] encodedStrings = new byte[row.length][];
        int size = 0;
        for (int i = 0; i < row.length; i++) {
            Object value = row[i];
            String type = types.get(i);
            if (value == null) {
                throw new IllegalArgumentException("Column " + i + " is null; NULL isn't supported");
            }
            if (type.equals("DOUBLE")) {
                if (!(value instanceof Double)) {
                    throw new IllegalArgumentException("Column " + i + " expects DOUBLE, got "
                        + value.getClass().getSimpleName());
                }
                size += 8;
            } else if (type.equals("STRING")) {
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
            } else {
                throw new IllegalArgumentException("Unknown column type: " + type);
            }
        }

        ByteBuffer buf = ByteBuffer.allocate(size); // big-endian by default
        for (int i = 0; i < row.length; i++) {
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
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        Object[] row = new Object[types.size()];
        try {
            for (int i = 0; i < row.length; i++) {
                String type = types.get(i);
                if (type.equals("DOUBLE")) {
                    row[i] = buf.getDouble();
                } else if (type.equals("STRING")) {
                    int length = buf.getShort() & 0xFFFF;
                    if (length > buf.remaining()) {
                        throw new IllegalArgumentException("Column " + i + " claims " + length
                            + " bytes but only " + buf.remaining() + " remain");
                    }
                    byte[] encoded = new byte[length];
                    buf.get(encoded);
                    row[i] = new String(encoded, StandardCharsets.UTF_8);
                } else {
                    throw new IllegalArgumentException("Unknown column type: " + type);
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
}