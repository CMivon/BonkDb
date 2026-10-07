package Storage;

import static Storage.TestKit.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Tests for RowId and HeapFile. Runs in a temporary directory that is deleted
 * afterwards. Run on its own with
 *   java Storage.HeapFileTest
 */
public class HeapFileTest {

    private static Path dir;

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        try {
            dir = Files.createTempDirectory("bonk-heapfile-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            run("rowid: validation, equality, ordering", HeapFileTest::rowIdBasics);
            run("heap: insert and get", HeapFileTest::insertAndGet);
            run("heap: rows survive close and reopen", HeapFileTest::persistence);
            run("heap: delete, and slot reuse", HeapFileTest::deleteAndReuse);
            run("heap: update in place keeps the address", HeapFileTest::updateInPlace);
            run("heap: update that doesn't fit moves the row", HeapFileTest::updateRelocates);
            run("heap: rows larger than a page are refused cleanly", HeapFileTest::oversizeRows);
            run("heap: invalid rows are refused and change nothing", HeapFileTest::invalidRows);
            run("heap: NULL values in rows", HeapFileTest::nullValues);
            run("heap: a one-column table of NULLs and empty strings", HeapFileTest::oneColumnNulls);
            run("heap: NOT NULL columns are enforced", HeapFileTest::notNullColumns);
            run("heap: NULLs make rows smaller, so more fit on a page", HeapFileTest::nullsAndPageFit);
            run("heap: turning a NULL into a value can force a row to move", HeapFileTest::nullToValueRelocates);
            run("heap: invalid addresses", HeapFileTest::invalidAddresses);
            run("heap: many rows across many pages", HeapFileTest::manyRows);
            run("heap: freed space is reused, even after reopening", HeapFileTest::freedSpaceIsReused);
            run("heap: scan basics", HeapFileTest::scanBasics);
            run("heap: changing the file during a scan is detected", HeapFileTest::scanDetectsChanges);
            run("heap: damaged row data is reported", HeapFileTest::damagedRowData);
            run("heap: a damaged page stops open, and cleanly", HeapFileTest::damagedPageAtOpen);
            run("heap: close rules", HeapFileTest::closeRules);
            run("heap: randomized operations vs a model", HeapFileTest::randomizedOperations);
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------- Helpers ----------

    private static Schema schema() {
        return new Schema(List.of("id", "name", "score"), List.of("DOUBLE", "STRING", "DOUBLE"));
    }

    // Two columns, so a row with a 9-character name is exactly 20 bytes
    // (1 byte of null bitmap + 8 + 2 + 9). With 128-byte pages that packs 5
    // rows per page.
    private static Schema pairSchema() {
        return new Schema(List.of("id", "name"), List.of("DOUBLE", "STRING"));
    }

    private static Object[] row(double id, String name, double score) {
        return new Object[] { id, name, score };
    }

    private static Object[] pair(double id, String name) {
        return new Object[] { id, name };
    }

    private static Path file(String name) {
        return dir.resolve(name);
    }

    private static List<HeapRow> scanAll(HeapFile heap) {
        List<HeapRow> rows = new ArrayList<>();
        for (HeapRow r : heap.scan()) {
            rows.add(r);
        }
        return rows;
    }

    private static void assertRow(Object[] expected, Object[] actual, String message) {
        assertTrue(actual != null && Arrays.equals(expected, actual),
            message + ": expected " + Arrays.toString(expected) + " but got " + Arrays.toString(actual));
    }

    // ---------- RowId ----------

    static void rowIdBasics() {
        RowId a = new RowId(2, 5);
        assertEquals(a, new RowId(2, 5), "equal ids");
        assertEquals(a.hashCode(), new RowId(2, 5).hashCode(), "equal hash codes");
        assertTrue(!a.equals(new RowId(2, 6)), "different slot");
        assertTrue(!a.equals(new RowId(3, 5)), "different page");
        assertTrue(a.compareTo(new RowId(2, 6)) < 0, "slot orders within a page");
        assertTrue(a.compareTo(new RowId(3, 0)) < 0, "page orders before slot");
        assertEquals(0, a.compareTo(new RowId(2, 5)), "compare equal");
        expectThrows(IllegalArgumentException.class, () -> new RowId(0, 0), "page 0 is the header");
        expectThrows(IllegalArgumentException.class, () -> new RowId(-1, 0), "negative page");
        expectThrows(IllegalArgumentException.class, () -> new RowId(1, -1), "negative slot");
    }

    // ---------- Basic operations ----------

    static void insertAndGet() throws IOException {
        try (HeapFile h = HeapFile.create(file("basic.bonk"), 1024, schema())) {
            Object[] a = row(1, "alpha", 1.5);
            RowId ra = h.insert(a);
            RowId rb = h.insert(row(2, "beta", 2.5));
            RowId rc = h.insert(row(3, "gamma", 3.5));

            assertEquals(new RowId(1, 0), ra, "first row");
            assertEquals(new RowId(1, 1), rb, "second row");
            assertEquals(new RowId(1, 2), rc, "third row");
            assertEquals(1, h.dataPageCount(), "all on one page");

            assertRow(row(1, "alpha", 1.5), h.get(ra), "get first");
            assertRow(row(2, "beta", 2.5), h.get(rb), "get second");
            assertRow(row(3, "gamma", 3.5), h.get(rc), "get third");

            // Neither the array passed in nor the one returned is shared with the file.
            a[1] = "mutated";
            assertEquals("alpha", h.get(ra)[1], "inserting copies the row");
            Object[] got = h.get(ra);
            got[1] = "changed by caller";
            assertEquals("alpha", h.get(ra)[1], "get returns a fresh array");
        }
    }

    static void persistence() throws IOException {
        Path p = file("persist.bonk");
        RowId ra, rb;
        try (HeapFile h = HeapFile.create(p, 1024, schema())) {
            ra = h.insert(row(1, "alpha", 1.5));
            rb = h.insert(row(2, "beta \u00e9\u65e5", 2.5));
        }
        try (HeapFile h = HeapFile.open(p)) {
            assertEquals(schema(), h.schema(), "schema survived");
            assertRow(row(1, "alpha", 1.5), h.get(ra), "first row after reopen");
            assertRow(row(2, "beta \u00e9\u65e5", 2.5), h.get(rb), "second row after reopen");
            List<HeapRow> rows = scanAll(h);
            assertEquals(2, rows.size(), "scan finds both");
            assertEquals(ra, rows.get(0).id, "scan order");
            assertEquals(rb, rows.get(1).id, "scan order");
        }
    }

    static void deleteAndReuse() throws IOException {
        try (HeapFile h = HeapFile.create(file("delete.bonk"), 1024, schema())) {
            RowId ra = h.insert(row(1, "a", 1));
            RowId rb = h.insert(row(2, "b", 2));
            RowId rc = h.insert(row(3, "c", 3));

            assertTrue(h.delete(rb), "delete a live row");
            assertTrue(h.get(rb) == null, "deleted row reads as null");
            assertTrue(!h.delete(rb), "deleting again returns false");
            List<HeapRow> rows = scanAll(h);
            assertEquals(2, rows.size(), "scan skips the deleted row");
            assertEquals(ra, rows.get(0).id, "first survivor");
            assertEquals(rc, rows.get(1).id, "second survivor");

            // A freed address can be handed out again: documented behavior.
            assertEquals(rb, h.insert(row(4, "d", 4)), "the freed slot is reused");
            assertRow(row(4, "d", 4), h.get(rb), "the new row lives at the reused address");
        }
    }

    static void updateInPlace() throws IOException {
        try (HeapFile h = HeapFile.create(file("inplace.bonk"), 1024, schema())) {
            RowId other = h.insert(row(9, "bystander", 9));
            RowId id = h.insert(row(1, "short", 1));

            assertEquals(id, h.update(id, row(1, "a considerably longer name than before", 2)), "growing within the page");
            assertRow(row(1, "a considerably longer name than before", 2), h.get(id), "grown row");

            assertEquals(id, h.update(id, row(1, "s", 3)), "shrinking");
            assertRow(row(1, "s", 3), h.get(id), "shrunk row");

            assertRow(row(9, "bystander", 9), h.get(other), "neighbour untouched");
            assertEquals(1, h.dataPageCount(), "never needed a second page");
        }
    }

    static void updateRelocates() throws IOException {
        try (HeapFile h = HeapFile.create(file("relocate.bonk"), 128, pairSchema())) {
            // 20-byte rows, 5 per 128-byte page: page 1 fills up, the 6th goes to page 2.
            List<RowId> ids = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                ids.add(h.insert(pair(i, "abcdefghi")));
            }
            assertEquals(new RowId(1, 0), ids.get(0), "first row");
            assertEquals(new RowId(2, 0), ids.get(5), "sixth row starts page 2");
            assertEquals(2, h.dataPageCount(), "two pages");

            // Growing row (1,0) from 20 to 30 bytes can't fit in the full page 1.
            Object[] bigger = pair(0, "abcdefghijklmnopqrst");
            RowId moved = h.update(ids.get(0), bigger);

            assertTrue(!moved.equals(ids.get(0)), "the row got a new address");
            assertEquals(new RowId(2, 1), moved, "it moved to the page with room");
            assertTrue(h.get(ids.get(0)) == null, "the old address is now empty");
            assertRow(bigger, h.get(moved), "the row at its new address");
            for (int i = 1; i < 6; i++) {
                assertRow(pair(i, "abcdefghi"), h.get(ids.get(i)), "row " + i + " unaffected");
            }
            assertEquals(6, scanAll(h).size(), "still exactly six rows");
            assertEquals(2, h.dataPageCount(), "no extra page was needed");
        }
    }

    static void oversizeRows() throws IOException {
        Schema textOnly = new Schema(List.of("text"), List.of("STRING"));
        try (HeapFile h = HeapFile.create(file("oversize.bonk"), 128, textOnly)) {
            assertEquals(120, h.maxRowSize(), "max row size for 128-byte pages");

            // Refused before anything is written: not even an empty page gets added.
            // 1 (null bitmap) + 2 (length) + 118 characters = 121 bytes, one over.
            expectThrows(IllegalArgumentException.class,
                () -> h.insert(new Object[] { "x".repeat(118) }), "121-byte row");
            assertEquals(0, h.dataPageCount(), "a refused insert allocates nothing");

            RowId exact = h.insert(new Object[] { "y".repeat(117) }); // 1 + 2 + 117 = 120 bytes
            assertEquals(new RowId(1, 0), exact, "a row of exactly the maximum size fits");
            assertEquals(117, ((String) h.get(exact)[0]).length(), "and reads back intact");

            RowId small = h.insert(new Object[] { "z" });
            int pages = h.dataPageCount();
            expectThrows(IllegalArgumentException.class,
                () -> h.update(small, new Object[] { "q".repeat(118) }), "growing a row past the page size");
            assertRow(new Object[] { "z" }, h.get(small), "the row is unchanged after a refused update");
            assertEquals(pages, h.dataPageCount(), "a refused update allocates nothing");
            assertEquals(2, scanAll(h).size(), "row count unchanged");
        }
    }

    static void invalidRows() throws IOException {
        try (HeapFile h = HeapFile.create(file("invalid.bonk"), 1024, schema())) {
            expectThrows(IllegalArgumentException.class,
                () -> h.insert(new Object[] { 1.0, "a" }), "too few values");
            expectThrows(IllegalArgumentException.class,
                () -> h.insert(new Object[] { "x", "a", 1.0 }), "text in a DOUBLE column");
            expectThrows(IllegalArgumentException.class,
                () -> h.insert(new Object[] { 1.0, 2.0, 3.0 }), "number in a STRING column");
            assertEquals(0, h.dataPageCount(), "refused inserts allocate nothing");
            assertEquals(0, scanAll(h).size(), "and store nothing");

            RowId id = h.insert(row(1, "ok", 1));
            expectThrows(IllegalArgumentException.class,
                () -> h.update(id, new Object[] { "bad", "ok", 1.0 }), "invalid replacement row");
            assertRow(row(1, "ok", 1), h.get(id), "row unchanged after a refused update");
        }
    }

    // ---------- NULL ----------

    static void nullValues() throws IOException {
        Path p = file("nulls.bonk");
        Object[][] rows = {
            { 1.0, null, 2.5 },
            { null, "x", null },
            { null, null, null },
            { 3.0, "", 0.0 },
            { null, "", null },
        };
        List<RowId> ids = new ArrayList<>();
        try (HeapFile h = HeapFile.create(p, 1024, schema())) {
            for (Object[] r : rows) {
                ids.add(h.insert(r));
            }
            for (int i = 0; i < rows.length; i++) {
                assertRow(rows[i], h.get(ids.get(i)), "row " + i);
            }
            // NULL is not an empty string and not zero.
            assertEquals("", h.get(ids.get(3))[1], "an empty string is a value");
            assertEquals(0.0, h.get(ids.get(3))[2] ,"zero is a value");
            assertTrue(h.get(ids.get(2))[0] == null, "NULL is NULL");
        }

        // Everything, NULLs included, survives closing and reopening, and shows up in a scan.
        try (HeapFile h = HeapFile.open(p)) {
            for (int i = 0; i < rows.length; i++) {
                assertRow(rows[i], h.get(ids.get(i)), "row " + i + " after reopen");
            }
            List<HeapRow> scanned = scanAll(h);
            assertEquals(rows.length, scanned.size(), "scan finds every row, including the all-NULL one");
            for (int i = 0; i < rows.length; i++) {
                assertRow(rows[i], scanned.get(i).values, "scanned row " + i);
            }
        }

        // Updating to and from NULL.
        try (HeapFile h = HeapFile.open(p)) {
            RowId id = ids.get(0); // { 1.0, null, 2.5 }
            assertEquals(id, h.update(id, new Object[] { 1.0, "now it has text", 2.5 }), "NULL -> value, fits in place");
            assertRow(new Object[] { 1.0, "now it has text", 2.5 }, h.get(id), "after NULL -> value");
            assertEquals(id, h.update(id, new Object[] { null, null, null }), "everything -> NULL shrinks in place");
            assertRow(new Object[] { null, null, null }, h.get(id), "after everything -> NULL");
            assertEquals(id, h.update(id, new Object[] { 9.0, "back", 8.0 }), "and back again");
            assertRow(new Object[] { 9.0, "back", 8.0 }, h.get(id), "after NULL -> values");

            // A row of all NULLs is a real row. Deleting it makes it read as
            // deleted (null), which is different from reading as [null, null, null].
            RowId allNull = ids.get(2);
            assertTrue(h.get(allNull) != null, "an all-NULL row exists");
            assertTrue(h.delete(allNull), "it can be deleted");
            assertTrue(h.get(allNull) == null, "and then reads as deleted");
            assertEquals(rows.length - 1, scanAll(h).size(), "and is gone from the scan");
        }
    }

    // One column means the whole row is either the bitmap alone (NULL) or
    // the bitmap plus a string, so every row is non-empty on disk.
    static void oneColumnNulls() throws IOException {
        Path p = file("onecolumn.bonk");
        Schema one = new Schema(List.of("s"), List.of("STRING"));
        Object[] values = { "first", "", null, "third", null };
        List<RowId> ids = new ArrayList<>();
        try (HeapFile h = HeapFile.create(p, 128, one)) {
            for (Object v : values) {
                ids.add(h.insert(new Object[] { v }));
            }
        }
        try (HeapFile h = HeapFile.open(p)) {
            for (int i = 0; i < values.length; i++) {
                Object[] got = h.get(ids.get(i));
                assertTrue(got != null && got.length == 1, "row " + i + " exists with one value");
                assertTrue(java.util.Objects.equals(values[i], got[0]),
                    "row " + i + ": expected " + values[i] + " but got " + got[0]);
            }
            assertEquals(5, scanAll(h).size(), "all five rows are found by a scan, including the empty string and the NULLs");
        }
    }

    static void notNullColumns() throws IOException {
        Path p = file("notnull.bonk");
        Schema schema = new Schema(List.of("id", "name", "score"), List.of("DOUBLE", "STRING", "DOUBLE"),
            List.of(false, true, true));
        RowId kept;
        try (HeapFile h = HeapFile.create(p, 1024, schema)) {
            try {
                h.insert(new Object[] { null, "x", 1.0 });
                throw new AssertionError("NULL in a NOT NULL column should be refused");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("'id'") && e.getMessage().contains("NOT NULL"),
                    "message names the column: " + e.getMessage());
            }
            assertEquals(0, h.dataPageCount(), "a refused insert allocates nothing");
            assertEquals(0, scanAll(h).size(), "and stores nothing");

            // NULL is fine in the nullable columns.
            kept = h.insert(new Object[] { 1.0, null, null });
            assertRow(new Object[] { 1.0, null, null }, h.get(kept), "NULLs in the nullable columns");

            // An update can't put NULL into the NOT NULL column either, and leaves the row alone.
            expectThrows(IllegalArgumentException.class,
                () -> h.update(kept, new Object[] { null, "y", 2.0 }), "update to NULL in a NOT NULL column");
            assertRow(new Object[] { 1.0, null, null }, h.get(kept), "row unchanged after the refused update");
            // But it can be set to NULL in a nullable one.
            assertEquals(kept, h.update(kept, new Object[] { 5.0, "z", null }), "update within the rules");
        }

        // The rule is part of the file, so it still applies after reopening.
        try (HeapFile h = HeapFile.open(p)) {
            assertTrue(!h.schema().isNullable(0), "id is still NOT NULL");
            expectThrows(IllegalArgumentException.class,
                () -> h.insert(new Object[] { null, "x", 1.0 }), "NOT NULL enforced after reopen");
            assertRow(new Object[] { 5.0, "z", null }, h.get(kept), "data intact");
        }
    }

    // A NULL stores nothing but its bit, so rows with NULLs are smaller and a
    // page holds more of them. With 128-byte pages: a {number, NULL} row is
    // 1 + 8 = 9 bytes plus a 4-byte slot, so 9 rows fit; the tenth starts a new page.
    static void nullsAndPageFit() throws IOException {
        try (HeapFile h = HeapFile.create(file("fit.bonk"), 128, pairSchema())) {
            for (int i = 0; i < 9; i++) {
                assertEquals(1, h.insert(new Object[] { (double) i, null }).pageNo, "row " + i + " fits on page 1");
            }
            assertEquals(1, h.dataPageCount(), "nine NULL-bearing rows fit on one page");
            assertEquals(2, h.insert(new Object[] { 9.0, null }).pageNo, "the tenth starts page 2");
            assertEquals(2, h.dataPageCount(), "two pages");
        }
        // The same rows with a 9-character name are 20 bytes: only 5 per page.
        try (HeapFile h = HeapFile.create(file("fit2.bonk"), 128, pairSchema())) {
            for (int i = 0; i < 6; i++) {
                h.insert(pair(i, "abcdefghi"));
            }
            assertEquals(2, h.dataPageCount(), "six full-size rows need two pages");
        }
    }

    // Filling in a NULL makes a row bigger. If the page has no room for the
    // growth, the row has to move, exactly as with any other growing update.
    static void nullToValueRelocates() throws IOException {
        try (HeapFile h = HeapFile.create(file("nullmove.bonk"), 128, pairSchema())) {
            List<RowId> ids = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                ids.add(h.insert(new Object[] { (double) i, null })); // page 1 is now almost full
            }
            assertEquals(1, h.dataPageCount(), "nine 9-byte rows fill page 1");

            Object[] grown = { 0.0, "abcdefgh" }; // 1 + 8 + 2 + 8 = 19 bytes, 10 more than before
            RowId moved = h.update(ids.get(0), grown);
            assertTrue(!moved.equals(ids.get(0)), "the grown row no longer fits in place, so it moved");
            assertEquals(new RowId(2, 0), moved, "to a newly added page");
            assertTrue(h.get(ids.get(0)) == null, "the old address is empty");
            assertRow(grown, h.get(moved), "the row at its new address");
            for (int i = 1; i < 9; i++) {
                assertRow(new Object[] { (double) i, null }, h.get(ids.get(i)), "row " + i + " unaffected");
            }
            assertEquals(9, scanAll(h).size(), "still nine rows");
        }
    }

    static void invalidAddresses() throws IOException {
        try (HeapFile h = HeapFile.create(file("addresses.bonk"), 1024, schema())) {
            RowId id = h.insert(row(1, "a", 1));
            RowId gone = h.insert(row(2, "b", 2));
            h.delete(gone);

            expectThrows(IndexOutOfBoundsException.class, () -> h.get(new RowId(5, 0)), "get: page past the end");
            expectThrows(IndexOutOfBoundsException.class, () -> h.get(new RowId(1, 99)), "get: slot past the end");
            expectThrows(IndexOutOfBoundsException.class, () -> h.delete(new RowId(5, 0)), "delete: page past the end");
            expectThrows(IndexOutOfBoundsException.class, () -> h.delete(new RowId(1, 99)), "delete: slot past the end");
            expectThrows(IndexOutOfBoundsException.class,
                () -> h.update(new RowId(5, 0), row(1, "a", 1)), "update: page past the end");

            try {
                h.update(gone, row(2, "b", 2));
                throw new AssertionError("updating a deleted row should be refused");
            } catch (IllegalArgumentException e) {
                // The heap's own message names the row's address; Page's lower-level one only says "Slot N".
                assertTrue(e.getMessage().contains("(page 1, slot 1)") && e.getMessage().contains("deleted"),
                    "message should name the row and say why: " + e.getMessage());
            }
            assertRow(row(1, "a", 1), h.get(id), "live row unaffected by all the failed calls");
        }
    }

    static void manyRows() throws IOException {
        Path p = file("many.bonk");
        Random rnd = new Random(5);
        List<RowId> ids = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();

        try (HeapFile h = HeapFile.create(p, 256, pairSchema())) {
            for (int i = 0; i < 1000; i++) {
                Object[] r = pair(i, "n".repeat(rnd.nextInt(31)));
                ids.add(h.insert(r));
                rows.add(r);
            }
            assertTrue(h.dataPageCount() > 10, "1000 rows should need many 256-byte pages");
            assertEquals(1000, new HashSet<>(ids).size(), "every row got a distinct address");
            verifyAll(h, ids, rows);
        }
        try (HeapFile h = HeapFile.open(p)) {
            verifyAll(h, ids, rows);
            System.out.println("      1000 rows across " + h.dataPageCount() + " pages");
        }
    }

    private static void verifyAll(HeapFile h, List<RowId> ids, List<Object[]> rows) throws IOException {
        for (int i = 0; i < ids.size(); i++) {
            assertRow(rows.get(i), h.get(ids.get(i)), "row " + i);
        }
        List<HeapRow> scanned = scanAll(h);
        assertEquals(ids.size(), scanned.size(), "scan count");
        for (int i = 1; i < scanned.size(); i++) {
            assertTrue(scanned.get(i - 1).id.compareTo(scanned.get(i).id) < 0, "scan is in address order");
        }
    }

    static void freedSpaceIsReused() throws IOException {
        Path p = file("reuse.bonk");
        try (HeapFile h = HeapFile.create(p, 128, pairSchema())) {
            // 5 rows per page: 20 rows fill pages 1-4 exactly.
            List<RowId> ids = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                ids.add(h.insert(pair(i, "abcdefghi")));
            }
            assertEquals(4, h.dataPageCount(), "20 rows fill 4 pages");
            for (int i = 0; i < 20; i++) {
                assertEquals(i / 5 + 1, ids.get(i).pageNo, "row " + i + " is on the expected page");
            }

            // Empty page 2 completely, and leave page 4 with only 2 of its 5 rows.
            for (int i = 5; i < 10; i++) h.delete(ids.get(i));
            for (int i = 15; i < 18; i++) h.delete(ids.get(i));

            // 8 new rows must fill those 8 freed spots, not add pages.
            int inPage2 = 0, inPage4 = 0;
            for (int i = 0; i < 8; i++) {
                RowId id = h.insert(pair(100 + i, "abcdefghi"));
                if (id.pageNo == 2) inPage2++;
                else if (id.pageNo == 4) inPage4++;
                else throw new AssertionError("new row went to " + id + " instead of a page with freed space");
            }
            assertEquals(5, inPage2, "page 2 refilled");
            assertEquals(3, inPage4, "page 4 refilled");
            assertEquals(4, h.dataPageCount(), "no page was added while freed space remained");

            assertEquals(5, h.insert(pair(200, "abcdefghi")).pageNo, "only now does it add page 5");
            assertEquals(5, h.dataPageCount(), "page count");
        }

        // Reopen: the in-memory free-space table is rebuilt from the pages.
        try (HeapFile h = HeapFile.open(p)) {
            // Page 5 holds a single row, so it has room for 4 more. Nothing has
            // been deleted since the reopen, so only a correctly rebuilt table
            // can know that; without it these would all land on a new page 6.
            for (int i = 0; i < 4; i++) {
                assertEquals(5, h.insert(pair(300 + i, "abcdefghi")).pageNo, "after reopen, page 5's spare room is found");
            }
            assertEquals(5, h.dataPageCount(), "no page added while page 5 had room");

            // And space freed after the reopen is picked up too.
            for (HeapRow r : scanAll(h)) {
                if (r.id.pageNo == 1) h.delete(r.id);
            }
            for (int i = 0; i < 5; i++) {
                assertEquals(1, h.insert(pair(400 + i, "abcdefghi")).pageNo, "freed page 1 is reused");
            }
            assertEquals(5, h.dataPageCount(), "still 5 pages");
        }
    }

    // ---------- Scan ----------

    static void scanBasics() throws IOException {
        try (HeapFile h = HeapFile.create(file("scan.bonk"), 1024, schema())) {
            Iterator<HeapRow> empty = h.scan().iterator();
            assertTrue(!empty.hasNext(), "an empty table has nothing to scan");
            expectThrows(NoSuchElementException.class, empty::next, "next() on an empty scan");

            RowId r1 = h.insert(row(1, "a", 1));
            RowId r2 = h.insert(row(2, "b", 2));

            // Each scan() call is independent, so two can be in progress at once.
            Iterator<HeapRow> first = h.scan().iterator();
            Iterator<HeapRow> second = h.scan().iterator();
            assertEquals(r1, first.next().id, "first scan, row 1");
            assertEquals(r1, second.next().id, "second scan, row 1");
            assertEquals(r2, first.next().id, "first scan, row 2");
            assertEquals(r2, second.next().id, "second scan, row 2");
            assertTrue(!first.hasNext() && !first.hasNext(), "finished scan stays finished");
            expectThrows(NoSuchElementException.class, first::next, "next() past the end");

            // Calling hasNext() repeatedly doesn't skip rows.
            Iterator<HeapRow> again = h.scan().iterator();
            assertTrue(again.hasNext() && again.hasNext() && again.hasNext(), "repeated hasNext");
            assertEquals(r1, again.next().id, "no row skipped");

            // Scan results are copies: changing one doesn't change the file.
            HeapRow copy = scanAll(h).get(0);
            copy.values[1] = "tampered";
            assertEquals("a", h.get(r1)[1], "the file is unaffected by editing a scanned row");
        }
    }

    static void scanDetectsChanges() throws IOException {
        try (HeapFile h = HeapFile.create(file("scanchange.bonk"), 1024, schema())) {
            RowId r1 = h.insert(row(1, "a", 1));
            h.insert(row(2, "b", 2));
            h.insert(row(3, "c", 3));

            Iterator<HeapRow> it = h.scan().iterator();
            it.next();
            h.insert(row(4, "d", 4));
            expectThrows(ConcurrentModificationException.class, it::hasNext, "insert during a scan");

            it = h.scan().iterator();
            it.next();
            h.delete(r1);
            expectThrows(ConcurrentModificationException.class, it::next, "delete during a scan");

            RowId live = scanAll(h).get(0).id;
            it = h.scan().iterator();
            it.next();
            h.update(live, row(9, "changed", 9));
            expectThrows(ConcurrentModificationException.class, it::next, "update during a scan");

            // A refused change isn't a change.
            it = h.scan().iterator();
            it.next();
            expectThrows(IllegalArgumentException.class, () -> h.insert(new Object[] { 1.0 }), "bad row");
            assertTrue(it.hasNext(), "a rejected insert doesn't disturb a scan");
        }
    }

    // ---------- Damage ----------

    static void damagedRowData() throws IOException {
        Path p = file("badrow.bonk");
        RowId good;
        try (HeapFile h = HeapFile.create(p, 1024, schema())) {
            good = h.insert(row(1, "fine", 1));
        }
        // Slip a row of garbage bytes into page 1, behind the heap file's back.
        // The page itself stays structurally valid; only the row is nonsense.
        int garbageSlot;
        try (PageFile pf = PageFile.open(p)) {
            Page page = pf.readPage(1);
            garbageSlot = page.insert(new byte[] { 1, 2, 3, 4, 5 });
            pf.writePage(1, page);
        }

        try (HeapFile h = HeapFile.open(p)) {
            RowId bad = new RowId(1, garbageSlot);
            assertRow(row(1, "fine", 1), h.get(good), "the intact row still reads fine");
            try {
                h.get(bad);
                throw new AssertionError("a garbage row should be reported");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("damaged") && e.getMessage().contains("page 1"),
                    "message should name the row: " + e.getMessage());
            }

            Iterator<HeapRow> it = h.scan().iterator();
            assertEquals(good, it.next().id, "scan returns the intact row first");
            expectThrows(UncheckedIOException.class, it::hasNext, "scan reports the garbage row");
        }
    }

    static void damagedPageAtOpen() throws IOException {
        Path p = file("badpage.bonk");
        try (HeapFile h = HeapFile.create(p, 128, pairSchema())) {
            for (int i = 0; i < 12; i++) {
                h.insert(pair(i, "abcdefghi")); // 3 pages
            }
        }
        byte[] valid = Files.readAllBytes(p);

        byte[] tampered = valid.clone();
        ByteBuffer.wrap(tampered).putShort(2 * 128, (short) 5000); // page 2's slot count
        Files.write(p, tampered);
        try (HeapFile h = HeapFile.open(p)) {
            throw new AssertionError("opening a file with a damaged page should fail");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Page 2"), "message names the page: " + e.getMessage());
        }

        // The failed open must have released the file: restoring it makes it open again.
        Files.write(p, valid);
        try (HeapFile h = HeapFile.open(p)) {
            assertEquals(12, scanAll(h).size(), "all rows present after restoring the file");
        }
    }

    static void closeRules() throws IOException {
        HeapFile h = HeapFile.create(file("closed.bonk"), 1024, schema());
        RowId id = h.insert(row(1, "a", 1));
        h.flush();
        Iterator<HeapRow> it = h.scan().iterator();
        h.close();
        h.close(); // closing twice is harmless

        expectThrows(IllegalStateException.class, () -> h.insert(row(2, "b", 2)), "insert after close");
        expectThrows(IllegalStateException.class, () -> h.get(id), "get after close");
        expectThrows(IllegalStateException.class, () -> h.delete(id), "delete after close");
        expectThrows(IllegalStateException.class, () -> h.update(id, row(1, "z", 1)), "update after close");
        expectThrows(IllegalStateException.class, it::hasNext, "scan step after close");
        assertEquals(1024, h.pageSize(), "plain getters still work");
    }

    // ---------- Randomized ----------

    // Thousands of random operations against a small-page heap file, checked
    // against a HashMap of what should be in it. The file is closed and reopened
    // along the way, which also exercises rebuilding the free-space table.
    static void randomizedOperations() throws IOException {
        final int pageSize = 256;
        String[] pieces = { "a", "b", "c", "X", " ", "0", "\u00e9", "\u65e5", "\uD83D\uDE00" };
        Random rnd = new Random(31337);
        Path p = file("heap-random.bonk");
        Map<RowId, Object[]> model = new HashMap<>();

        int inserts = 0, deletes = 0, updatesInPlace = 0, updatesMoved = 0, gets = 0, reopens = 0;
        HeapFile h = HeapFile.create(p, pageSize, schema());
        try {
            for (int step = 0; step < 6000; step++) {
                int op = rnd.nextInt(100);
                String where = "step " + step;

                if (model.isEmpty() || op < 40) {
                    Object[] r = randomRow(rnd, pieces);
                    RowId id = h.insert(r);
                    assertTrue(!model.containsKey(id), where + ": insert returned the live address " + id);
                    model.put(id, r);
                    inserts++;
                } else if (op < 60) {
                    RowId id = randomId(model, rnd);
                    assertTrue(h.delete(id), where + ": delete of live row " + id);
                    assertTrue(!h.delete(id), where + ": second delete of " + id);
                    assertTrue(h.get(id) == null, where + ": deleted row still readable");
                    model.remove(id);
                    deletes++;
                } else if (op < 90) {
                    RowId id = randomId(model, rnd);
                    Object[] r = randomRow(rnd, pieces);
                    RowId now = h.update(id, r);
                    if (now.equals(id)) {
                        updatesInPlace++;
                    } else {
                        assertTrue(!model.containsKey(now), where + ": moved onto the live address " + now);
                        assertTrue(h.get(id) == null, where + ": old address " + id + " not emptied");
                        model.remove(id);
                        updatesMoved++;
                    }
                    model.put(now, r);
                } else {
                    RowId id = randomId(model, rnd);
                    assertRow(model.get(id), h.get(id), where + ": get " + id);
                    gets++;
                }

                if (step % 250 == 249) {
                    compareWithModel(h, model, where);
                }
                if (step % 700 == 699) {
                    h.close();
                    h = HeapFile.open(p);
                    reopens++;
                    compareWithModel(h, model, where + " (after reopen)");
                }
            }
            compareWithModel(h, model, "end");
            System.out.println("      " + inserts + " inserts, " + deletes + " deletes, " + updatesInPlace
                + " in-place updates, " + updatesMoved + " moving updates, " + gets + " gets, " + reopens
                + " reopens, " + model.size() + " rows left in " + h.dataPageCount() + " pages");
        } finally {
            h.close();
        }
        assertTrue(updatesInPlace > 0, "the test should have done in-place updates");
        assertTrue(updatesMoved > 0, "the test should have done moving updates");
    }

    private static Object[] randomRow(Random rnd, String[] pieces) {
        StringBuilder text = new StringBuilder();
        int count = rnd.nextInt(50);
        for (int i = 0; i < count; i++) {
            text.append(pieces[rnd.nextInt(pieces.length)]);
        }
        Object[] r = row(rnd.nextInt(100000), text.toString(), rnd.nextDouble() * 1000);
        for (int i = 0; i < r.length; i++) {
            if (rnd.nextInt(100) < 15) {
                r[i] = null; // about one value in seven is NULL
            }
        }
        return r;
    }

    private static RowId randomId(Map<RowId, Object[]> model, Random rnd) {
        RowId[] ids = model.keySet().toArray(new RowId[0]);
        Arrays.sort(ids);
        return ids[rnd.nextInt(ids.length)];
    }

    // The scan must return exactly the rows in the model, in address order.
    private static void compareWithModel(HeapFile h, Map<RowId, Object[]> model, String where) {
        List<HeapRow> scanned = scanAll(h);
        assertEquals(model.size(), scanned.size(), where + ": row count");
        RowId previous = null;
        for (HeapRow r : scanned) {
            Object[] expected = model.get(r.id);
            assertTrue(expected != null, where + ": scan returned unexpected address " + r.id);
            assertRow(expected, r.values, where + ": scanned " + r.id);
            assertTrue(previous == null || previous.compareTo(r.id) < 0, where + ": scan order");
            previous = r.id;
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
