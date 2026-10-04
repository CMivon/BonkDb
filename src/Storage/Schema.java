package Storage;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A table's structure: an ordered list of column names and types. Immutable.
 *
 * Types are the same two strings Engine.Table uses ("DOUBLE" and "STRING"),
 * so a Schema's columnTypes() can be handed straight to RowSerializer.
 * The Storage package keeps its own copy of the idea rather than depending
 * on Engine.
 *
 * Encoded form (used in a PageFile's header page), all big-endian:
 *   column count            u16
 *   per column:  type code  u8   (0 = DOUBLE, 1 = STRING)
 *                name length u16
 *                name        UTF-8 bytes
 */
public final class Schema {

    public static final String DOUBLE = "DOUBLE";
    public static final String STRING = "STRING";
    public static final int MAX_COLUMNS = 65535;
    private static final int MAX_NAME_BYTES = 65535;

    private final List<String> names;
    private final List<String> types;

    public Schema(List<String> columnNames, List<String> columnTypes) {
        if (columnNames.size() != columnTypes.size()) {
            throw new IllegalArgumentException(columnNames.size() + " column names but "
                + columnTypes.size() + " types");
        }
        if (columnNames.isEmpty()) {
            throw new IllegalArgumentException("A table needs at least one column");
        }
        if (columnNames.size() > MAX_COLUMNS) {
            throw new IllegalArgumentException("Too many columns: " + columnNames.size());
        }

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columnNames.size(); i++) {
            String name = columnNames.get(i);
            String type = columnTypes.get(i);
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("Column " + i + " has no name");
            }
            if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
                throw new IllegalArgumentException("Column name '" + name.substring(0, 20) + "...' is too long");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException("Duplicate column name: " + name);
            }
            if (!DOUBLE.equals(type) && !STRING.equals(type)) {
                throw new IllegalArgumentException("Column '" + name + "' has unknown type: " + type);
            }
        }

        this.names = List.copyOf(columnNames);
        this.types = List.copyOf(columnTypes);
    }

    public int columnCount() {
        return names.size();
    }

    public List<String> columnNames() {
        return names;
    }

    public List<String> columnTypes() {
        return types;
    }

    byte[] encode() {
        byte[][] encodedNames = new byte[names.size()][];
        int size = 2;
        for (int i = 0; i < names.size(); i++) {
            encodedNames[i] = names.get(i).getBytes(StandardCharsets.UTF_8);
            size += 1 + 2 + encodedNames[i].length;
        }

        ByteBuffer buf = ByteBuffer.allocate(size);
        buf.putShort((short) names.size());
        for (int i = 0; i < names.size(); i++) {
            buf.put((byte) (types.get(i).equals(DOUBLE) ? 0 : 1));
            buf.putShort((short) encodedNames[i].length);
            buf.put(encodedNames[i]);
        }
        return buf.array();
    }

    // Reads a schema from the buffer's current position. Any malformed input
    // is reported as IllegalArgumentException.
    static Schema decode(ByteBuffer buf) {
        try {
            int count = buf.getShort() & 0xFFFF;
            List<String> names = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int code = buf.get();
                if (code == 0) {
                    types.add(DOUBLE);
                } else if (code == 1) {
                    types.add(STRING);
                } else {
                    throw new IllegalArgumentException("Unknown type code " + code + " for column " + i);
                }
                int length = buf.getShort() & 0xFFFF;
                if (length > buf.remaining()) {
                    throw new IllegalArgumentException("Column " + i + " name claims " + length
                        + " bytes but only " + buf.remaining() + " remain");
                }
                byte[] encoded = new byte[length];
                buf.get(encoded);
                names.add(new String(encoded, StandardCharsets.UTF_8));
            }
            return new Schema(names, types);
        } catch (BufferUnderflowException e) {
            throw new IllegalArgumentException("Schema bytes are truncated");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof Schema)) return false;
        Schema that = (Schema) other;
        return names.equals(that.names) && types.equals(that.types);
    }

    @Override
    public int hashCode() {
        return Objects.hash(names, types);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Schema(");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.get(i)).append(' ').append(types.get(i));
        }
        return sb.append(')').toString();
    }
}
