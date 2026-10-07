package Storage;

import java.util.Arrays;

/**
 * One row produced by HeapFile.scan(): where it lives, and its values.
 * The values array belongs to the caller; changing it does not change the file.
 */
public final class HeapRow {

    public final RowId id;
    public final Object[] values;

    HeapRow(RowId id, Object[] values) {
        this.id = id;
        this.values = values;
    }

    @Override
    public String toString() {
        return id + " " + Arrays.toString(values);
    }
}
