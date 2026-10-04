package Engine;

import Storage.HeapFile;
import Storage.HeapRow;
import Storage.RowId;
import Storage.RowSerializer;
import Storage.Schema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A table whose rows are stored in a file, through Storage.HeapFile. Unlike a
 * MemoryTable it lasts between runs.
 *
 * A stored table must have declared column types (the file records them), and
 * enforces them: a row whose values don't match is refused, whichever way it
 * arrives. That includes updateWhere, which a MemoryTable lets through
 * unchecked. A row must also fit in one page (about 4 KB).
 *
 * The table's name is not stored in its file. The caller supplies it when
 * creating or opening, which will let the catalog decide what tables are called.
 *
 * I/O problems surface as UncheckedIOException, since the Table operations
 * don't declare IOException.
 *
 * There is no transaction log yet. deleteWhere and updateWhere decide
 * everything (and, for updates, check that every new row will be accepted)
 * before changing anything, so a bad condition or a bad new row leaves the
 * table untouched. A failure of the disk itself partway through cannot be
 * undone.
 */
public class HeapTable extends Table {

    static final int PAGE_SIZE = 4096;

    private final Schema schema;
    private final HeapFile heap;

    private HeapTable(String name, Schema schema, HeapFile heap) {
        super(name, schema.columnNames(), schema.columnTypes());
        this.schema = schema;
        this.heap = heap;
    }

    // Creates a new, empty table in a new file. Refuses a column list the file
    // can't describe (such as a repeated column name) before creating anything.
    static HeapTable create(Path file, String name, List<String> columns, List<String> types) throws IOException {
        Schema schema = new Schema(columns, types);
        return new HeapTable(name, schema, HeapFile.create(file, PAGE_SIZE, schema));
    }

    // Opens a table created earlier. Its columns and types come from the file.
    static HeapTable open(Path file, String name) throws IOException {
        HeapFile heap = HeapFile.open(file);
        return new HeapTable(name, heap.schema(), heap);
    }

    @Override
    int rowCount() {
        int count = 0;
        for (HeapRow ignored : heap.scan()) {
            count++;
        }
        return count;
    }

    @Override
    boolean isEmpty() {
        return !heap.scan().iterator().hasNext();
    }

    @Override
    Iterable<Object[]> scan() {
        return () -> {
            Iterator<HeapRow> rows = heap.scan().iterator();
            return new Iterator<Object[]>() {
                @Override
                public boolean hasNext() {
                    return rows.hasNext();
                }

                @Override
                public Object[] next() {
                    return rows.next().values;
                }
            };
        };
    }

    @Override
    void store(Object[] values) {
        try {
            heap.insert(values);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    int deleteWhere(Predicate<Object[]> matches) {
        // Phase 1: look at every row. The file can't be changed during a scan.
        List<RowId> hits = new ArrayList<>();
        for (HeapRow row : heap.scan()) {
            if (matches.test(row.values)) {
                hits.add(row.id);
            }
        }
        // Phase 2: delete.
        try {
            for (RowId id : hits) {
                heap.delete(id);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hits.size();
    }

    @Override
    int updateWhere(Predicate<Object[]> matches, UnaryOperator<Object[]> change) {
        // Phase 1: choose the rows.
        List<HeapRow> hits = new ArrayList<>();
        for (HeapRow row : heap.scan()) {
            if (matches.test(row.values)) {
                hits.add(row);
            }
        }
        // Phase 2: work out every new row, and make sure the file will accept
        // each one, before anything is changed.
        List<Object[]> replacements = new ArrayList<>(hits.size());
        for (HeapRow row : hits) {
            Object[] changed = change.apply(row.values);
            checkAcceptable(changed);
            replacements.add(changed);
        }
        // Phase 3: apply. A row that no longer fits where it is moves to
        // another page, which the heap file handles.
        try {
            for (int i = 0; i < hits.size(); i++) {
                heap.update(hits.get(i).id, replacements.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hits.size();
    }

    // Throws if the heap file would refuse this row: wrong number of values,
    // a value of the wrong type, or too large for a page.
    private void checkAcceptable(Object[] row) {
        byte[] bytes = RowSerializer.serialize(row, schema);
        if (bytes.length > heap.maxRowSize()) {
            throw new IllegalArgumentException("Row needs " + bytes.length + " bytes but a " + heap.pageSize()
                + "-byte page holds at most " + heap.maxRowSize() + " per row (rows can't span pages)");
        }
    }

    @Override
    void close() throws IOException {
        heap.close();
    }
}
