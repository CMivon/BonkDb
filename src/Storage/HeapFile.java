
package Storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A table's rows, stored in a PageFile. This is the first layer that deals in
 * rows (Object[] values matching the table's Schema) instead of bytes or pages.
 *
 * Rows are unordered: an insert goes wherever there is room. Each row has a
 * RowId (page number + slot) that stays valid for as long as the row exists
 * and isn't moved by an update.
 *
 * Finding room: the file keeps, in memory, how large a row each page could
 * accept right now. It is rebuilt by reading every page when the file is
 * opened, so it can never disagree with the pages themselves, and nothing
 * extra is stored on disk. An insert tries the page it last inserted into,
 * then the first page with enough room (so space freed by deletes is
 * reused), and only then adds a new page.
 *
 * Updates: if the new version fits in the row's page, it is changed in
 * place and keeps its RowId. If not, the row MOVES to another page and gets
 * a new RowId, which update() returns. Callers holding the old RowId must
 * switch to the new one.
 *
 * A NULL value is a Java null inside the row array, in both directions.
 * Columns the schema marks NOT NULL refuse it.
 *
 * Limits: a row must fit in a single page (there are no overflow pages).
 * There is no transaction log: each insert, delete, or
 * update is a few separate page writes, so a crash partway through one can
 * leave it half-done. A relocating update inserts the new copy before
 * deleting the old one, so a failure in between leaves a duplicate rather
 * than losing the row.
 *
 * Not thread-safe. Reads every page from disk on every access; there is
 * no cache yet.
 */
public class HeapFile implements AutoCloseable {

    private final PageFile pageFile;
    private final Schema schema;
    private final int maxRowSize;

    // capacity[p] = the largest row data page p can accept right now. Only
    // this object modifies the file, so these stay exact.
    private int[] capacity;
    private int lastInsertPage = 0; // 0 = none yet
    private int modCount = 0; // bumped by every change; lets scans detect interference

    private HeapFile(PageFile pageFile) {
        this.pageFile = pageFile;
        this.schema = pageFile.schema();
        this.maxRowSize = new Page(pageFile.pageSize()).maxRowSize();
        this.capacity = new int[Math.max(16, pageFile.dataPageCount() + 1)];
    }

    // ---------- Creating and opening ----------

    public static HeapFile create(Path path, int pageSize, Schema schema) throws IOException {
        return new HeapFile(PageFile.create(path, pageSize, schema));
    }

    // Opens an existing table. Reads every page once, both to check each one
    // is intact and to rebuild the free-space table.
    public static HeapFile open(Path path) throws IOException {
        PageFile pageFile = PageFile.open(path);
        try {
            HeapFile heap = new HeapFile(pageFile);
            for (int pageNo = 1; pageNo <= pageFile.dataPageCount(); pageNo++) {
                heap.capacity[pageNo] = pageFile.readPage(pageNo).maxInsertSize();
            }
            return heap;
        } catch (IOException | RuntimeException e) {
            try {
                pageFile.close();
            } catch (IOException ignored) {
                // the original failure is the one worth reporting
            }
            throw e;
        }
    }

    // ---------- Information ----------

    public Schema schema() {
        return schema;
    }

    public int pageSize() {
        return pageFile.pageSize();
    }

    // Number of data pages currently in the file (including empty ones).
    public int dataPageCount() {
        return pageFile.dataPageCount();
    }

    // The largest row, in serialized bytes, that this table can store.
    public int maxRowSize() {
        return maxRowSize;
    }

    // ---------- Operations ----------

    // Stores a row and returns its address.
    public RowId insert(Object[] row) throws IOException {
        return insertBytes(encode(row));
    }

    // Returns the row at id (its values may include nulls), or null itself if
    // that row has been deleted.
    // Throws IndexOutOfBoundsException if the page or slot doesn't exist.
    public Object[] get(RowId id) throws IOException {
        Page page = pageFile.readPage(id.pageNo);
        byte[] bytes = page.read(id.slot);
        if (bytes == null) {
            return null;
        }
        return decode(id, bytes);
    }

    // Deletes the row at id. Returns false if it was already deleted.
    public boolean delete(RowId id) throws IOException {
        Page page = pageFile.readPage(id.pageNo);
        if (!page.delete(id.slot)) {
            return false;
        }
        pageFile.writePage(id.pageNo, page);
        capacity[id.pageNo] = page.maxInsertSize();
        modCount++;
        return true;
    }

    // Replaces the row at id and returns its address afterwards: the same
    // RowId if it fit in place, or a new one if the row had to move.
    // Throws IllegalArgumentException if the row at id has been deleted.
    public RowId update(RowId id, Object[] row) throws IOException {
        byte[] bytes = encode(row);
        Page page = pageFile.readPage(id.pageNo);
        if (!page.isLive(id.slot)) {
            throw new IllegalArgumentException("Row " + id + " has been deleted");
        }

        if (page.update(id.slot, bytes)) {
            pageFile.writePage(id.pageNo, page);
            capacity[id.pageNo] = page.maxInsertSize();
            modCount++;
            return id;
        }

        // Doesn't fit where it is: add the new copy first, then remove the old one.
        RowId moved = insertBytes(bytes);
        delete(id);
        return moved;
    }

    // Every live row, one page at a time, in page-then-slot order. Each call
    // starts a fresh scan. I/O problems surface as UncheckedIOException, and
    // changing the file during a scan makes the next step throw
    // ConcurrentModificationException.
    public Iterable<HeapRow> scan() {
        return ScanIterator::new;
    }

    public void flush() throws IOException {
        pageFile.flush();
    }

    @Override
    public void close() throws IOException {
        pageFile.close();
    }

    // ---------- Internals ----------

    private byte[] encode(Object[] row) {
        byte[] bytes = RowSerializer.serialize(row, schema);
        if (bytes.length > maxRowSize) {
            throw new IllegalArgumentException("Row needs " + bytes.length + " bytes but a "
                + pageFile.pageSize() + "-byte page holds at most " + maxRowSize
                + " per row (rows can't span pages)");
        }
        return bytes;
    }

    private Object[] decode(RowId id, byte[] bytes) throws IOException {
        try {
            return RowSerializer.deserialize(bytes, schema.columnTypes());
        } catch (IllegalArgumentException e) {
            throw new IOException("Row " + id + " in " + pageFile.path() + " is damaged: " + e.getMessage(), e);
        }
    }

    // Stores already-serialized row bytes on a page with room, adding a page if needed.
    private RowId insertBytes(byte[] bytes) throws IOException {
        int pageNo = choosePageFor(bytes.length);
        while (pageNo != 0) {
            Page page = pageFile.readPage(pageNo);
            int slot = page.insert(bytes);
            if (slot >= 0) {
                pageFile.writePage(pageNo, page);
                capacity[pageNo] = page.maxInsertSize();
                lastInsertPage = pageNo;
                modCount++;
                return new RowId(pageNo, slot);
            }
            // The table said there was room but there wasn't. That shouldn't
            // happen, but if it ever does, correct the entry and look again.
            capacity[pageNo] = page.maxInsertSize();
            pageNo = choosePageFor(bytes.length);
        }

        pageNo = pageFile.allocatePage();
        if (pageNo >= capacity.length) {
            capacity = Arrays.copyOf(capacity, Math.max(pageNo + 1, capacity.length * 2));
        }
        capacity[pageNo] = maxRowSize; // a brand-new page is empty
        Page page = new Page(pageFile.pageSize());
        int slot = page.insert(bytes); // fits: encode() already enforced maxRowSize
        pageFile.writePage(pageNo, page);
        capacity[pageNo] = page.maxInsertSize();
        lastInsertPage = pageNo;
        modCount++;
        return new RowId(pageNo, slot);
    }

    // The page number to try for a row of this size, or 0 if no page has room.
    private int choosePageFor(int rowSize) {
        if (lastInsertPage != 0 && capacity[lastInsertPage] >= rowSize) {
            return lastInsertPage;
        }
        for (int pageNo = 1; pageNo <= pageFile.dataPageCount(); pageNo++) {
            if (capacity[pageNo] >= rowSize) {
                return pageNo;
            }
        }
        return 0;
    }

    private class ScanIterator implements Iterator<HeapRow> {
        private final int expectedModCount = modCount;
        private int pageNo = 0; // page currently loaded; 0 = none loaded yet
        private Page page;
        private int[] slots = new int[0];
        private int next = 0;
        private HeapRow ready; // the row next() will return, once found
        private boolean finished = false;

        @Override
        public boolean hasNext() {
            checkUnchanged();
            if (ready == null && !finished) {
                advance();
            }
            return ready != null;
        }

        @Override
        public HeapRow next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            HeapRow row = ready;
            ready = null;
            return row;
        }

        private void checkUnchanged() {
            if (modCount != expectedModCount) {
                throw new ConcurrentModificationException("The heap file was changed during a scan");
            }
        }

        private void advance() {
            try {
                while (true) {
                    if (next < slots.length) {
                        int slot = slots[next++];
                        RowId id = new RowId(pageNo, slot);
                        ready = new HeapRow(id, decode(id, page.read(slot)));
                        return;
                    }
                    pageNo++;
                    if (pageNo > pageFile.dataPageCount()) {
                        finished = true;
                        return;
                    }
                    page = pageFile.readPage(pageNo);
                    slots = page.liveSlots();
                    next = 0;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}