package Engine;

import static Engine.TestKit.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for what is specific to a table stored in a file. What every table must
 * do is in TableContractTests, which also runs against HeapTable. Run on its own with
 *   java Engine.HeapTableTests
 */
public class HeapTableTests {

    private static final List<String> COLUMNS = List.of("id", "name");
    private static final List<String> TYPES = List.of("DOUBLE", "STRING");

    private static FileTables files;

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        try (FileTables f = new FileTables()) {
            files = f;
            run("file table: survives closing and reopening", HeapTableTests::persistence);
            run("file table: columns it can't describe are refused", HeapTableTests::refusesBadColumns);
            run("file table: rows it can't hold are refused", HeapTableTests::refusesBadRows);
            run("file table: a refused update changes nothing", HeapTableTests::refusedUpdatesChangeNothing);
            run("file table: the largest row an update can store", HeapTableTests::largestUpdate);
            run("file table: updates that make rows bigger move them", HeapTableTests::growingUpdates);
            run("file table: closed means closed", HeapTableTests::closing);
        }
    }

    private static Path path(String name) {
        return files.dir().resolve(name);
    }

    private static HeapTable people(String file) throws IOException {
        return HeapTable.create(path(file), "people", COLUMNS, TYPES);
    }

    private static List<String> names(Table t) {
        List<String> out = new ArrayList<>();
        for (Object[] row : t.scan()) {
            out.add((String) row[1]);
        }
        return out;
    }

    static void persistence() throws IOException {
        Path p = path("persist.bonk");
        HeapTable t = HeapTable.create(p, "people", COLUMNS, TYPES);
        t.insert(1.0, "a");
        t.insert(2.0, "caf\u00e9 \u65e5\u672c");
        t.close();

        HeapTable back = HeapTable.open(p, "people");
        try {
            assertEquals("people", back.name, "name");
            assertEquals(COLUMNS, back.columns, "the columns come from the file");
            assertEquals(TYPES, back.types, "and so do the types");
            assertEquals(List.of("a", "caf\u00e9 \u65e5\u672c"), names(back), "rows survive");
            // The reopened table is fully usable.
            back.insert(3.0, "c");
            assertEquals(3, back.rowCount(), "insert after reopen");
            assertEquals(1, back.deleteWhere(row -> ((Double) row[0]) == 1.0), "delete after reopen");
            assertEquals(List.of("caf\u00e9 \u65e5\u672c", "c"), names(back), "and the result");
        } finally {
            back.close();
        }

        // The table's name isn't stored in its file: whoever opens it supplies one.
        HeapTable renamed = HeapTable.open(p, "other");
        try {
            assertEquals("other", renamed.name, "name given on open");
            assertEquals(2, renamed.rowCount(), "same rows under a new name");
        } finally {
            renamed.close();
        }
        expectThrows(NoSuchFileException.class, () -> HeapTable.open(path("missing.bonk"), "x"), "a file that isn't there");
    }

    static void refusesBadColumns() {
        Path p = path("dup.bonk");
        try {
            HeapTable.create(p, "dup", List.of("a", "a"), List.of("DOUBLE", "STRING"));
            throw new AssertionError("a repeated column name should be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Duplicate column name: a"), "message: " + e.getMessage());
        } catch (IOException e) {
            throw new AssertionError("expected IllegalArgumentException, got " + e);
        }
        assertTrue(!Files.exists(p), "and no file was left behind");
        expectThrows(IllegalArgumentException.class, () -> HeapTable.create(path("empty.bonk"), "e", List.of(), List.of()), "no columns");
        expectThrows(IllegalArgumentException.class, () -> HeapTable.create(path("noname.bonk"), "e", List.of(""), List.of("DOUBLE")), "an empty column name");
    }

    static void refusesBadRows() throws IOException {
        HeapTable t = people("bad-rows.bonk");
        try {
            t.insert(1.0, "keep");
            // Too big for a page: refused cleanly, nothing stored.
            try {
                t.insert(2.0, "z".repeat(5000));
                throw new AssertionError("a row bigger than a page should be refused");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("rows can't span pages"), "message: " + e.getMessage());
            }
            // Wrong types are refused by the table, as for any table.
            expectThrows(RuntimeException.class, () -> t.insert("x", "y"), "text in a number column");
            assertEquals(List.of("keep"), names(t), "only the good row is there");
            // A row as large as a page allows is fine.
            t.insert(3.0, "q".repeat(4000));
            assertEquals(2, t.rowCount(), "a large row that fits");
        } finally {
            t.close();
        }
    }

    // A file-backed table checks what an update would store BEFORE changing anything,
    // so one bad replacement among many leaves every row as it was.
    static void refusedUpdatesChangeNothing() throws IOException {
        HeapTable t = people("refused.bonk");
        try {
            for (int i = 1; i <= 5; i++) {
                t.insert((double) i, "n" + i);
            }
            List<String> before = names(t);

            // The third replacement is too big for a page.
            expectThrows(IllegalArgumentException.class, () -> t.updateWhere(row -> true,
                row -> new Object[] { row[0], ((Double) row[0]) == 3.0 ? "z".repeat(5000) : "changed" }),
                "one replacement too big");
            assertEquals(before, names(t), "none of the five rows changed");

            // A replacement of the wrong type.
            expectThrows(IllegalArgumentException.class, () -> t.updateWhere(row -> true,
                row -> new Object[] { row[0], ((Double) row[0]) == 4.0 ? (Object) 99.0 : (Object) "changed" }),
                "one replacement of the wrong type");
            assertEquals(before, names(t), "still none changed");

            // This is why corrupting a cell (appending text to a number) can't work on a file-backed table.
            expectThrows(IllegalArgumentException.class, () -> t.updateWhere(row -> ((Double) row[0]) == 1.0,
                row -> new Object[] { row[0] + "TEST", row[1] }), "text where a number belongs");
            assertEquals(before, names(t), "and nothing changed");
        } finally {
            t.close();
        }
    }

    // A row can be at most 4088 bytes (a 4096-byte page, less 8 for its header and one slot).
    // Here that is 1 (null bitmap) + 8 (the number) + 2 (text length) + 4077 characters.
    static void largestUpdate() throws IOException {
        HeapTable t = people("largest.bonk");
        try {
            t.insert(1.0, "a");
            assertEquals(1, t.updateWhere(row -> true, row -> new Object[] { row[0], "x".repeat(4077) }),
                "a replacement of exactly the maximum size is accepted");
            assertEquals(4077, names(t).get(0).length(), "and stored in full");
            expectThrows(IllegalArgumentException.class,
                () -> t.updateWhere(row -> true, row -> new Object[] { row[0], "y".repeat(4078) }), "one character more");
            assertEquals(4077, names(t).get(0).length(), "the refused update left the row alone");
        } finally {
            t.close();
        }
    }

    // About 8 of these rows fit on a page. Making a third of them 3x bigger
    // overflows pages, so the heap file has to move rows around.
    static void growingUpdates() throws IOException {
        HeapTable t = people("grow.bonk");
        try {
            String small = "s".repeat(400);
            String big = "B".repeat(1500);
            for (int i = 1; i <= 30; i++) {
                t.insert((double) i, small + i);
            }
            int changed = t.updateWhere(row -> ((Double) row[0]) % 3 == 0,
                row -> new Object[] { row[0], big + ((Double) row[0]).intValue() });
            assertEquals(10, changed, "ten rows grew");
            assertEquals(30, t.rowCount(), "no row was lost or duplicated");

            Map<Integer, String> found = new HashMap<>();
            for (Object[] row : t.scan()) {
                found.put(((Double) row[0]).intValue(), (String) row[1]);
            }
            assertEquals(30, found.size(), "thirty different ids");
            for (int i = 1; i <= 30; i++) {
                assertEquals(i % 3 == 0 ? big + i : small + i, found.get(i), "row " + i);
            }
        } finally {
            t.close();
        }
    }

    static void closing() throws IOException {
        HeapTable t = people("closing.bonk");
        t.insert(1.0, "a");
        t.close();
        t.close(); // closing twice is harmless
        expectThrows(IllegalStateException.class, () -> t.insert(2.0, "b"), "insert after close");
        expectThrows(IllegalStateException.class, () -> t.scan().iterator().hasNext(), "scan after close");
        expectThrows(IllegalStateException.class, () -> t.deleteWhere(row -> true), "delete after close");
        assertEquals("people", t.name, "plain getters still work");
    }
}
