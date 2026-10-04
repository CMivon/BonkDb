package Engine;

import static Engine.TestKit.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The rules every kind of Table must follow, written against the abstract
 * Table class and run once for each implementation. Today that is only
 * MemoryTable. A table backed by a file should be added to runAll() and has
 * to pass these exact checks, so the rest of the engine can't tell them apart.
 *
 * Every table here is declared with column types, since a file-backed table
 * can't exist without them.
 *
 * Run on its own with
 *   java Engine.TableContractTests
 */
public class TableContractTests {

    interface Factory {
        Table create(String name, List<String> columns, List<String> types);
    }

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        runFor("MemoryTable", (name, columns, types) -> new MemoryTable(name, columns, types));
    }

    static void runFor(String kind, Factory f) {
        String p = "contract [" + kind + "]: ";
        run(p + "insert and scan", () -> insertAndScan(f));
        run(p + "insert keeps its own copy of the row", () -> insertCopies(f));
        run(p + "insert refuses bad rows and stores nothing", () -> insertRefusals(f));
        run(p + "deleteWhere", () -> deleteWhere(f));
        run(p + "deleteWhere is all-or-nothing", () -> deleteAllOrNothing(f));
        run(p + "updateWhere", () -> updateWhere(f));
        run(p + "updateWhere is all-or-nothing", () -> updateAllOrNothing(f));
        run(p + "a random mix of operations matches a plain list", () -> randomOperations(f));
        run(p + "save and load round trip", () -> saveAndLoad(f));
    }

    // ---------- Helpers ----------

    private static final List<String> COLUMNS = List.of("id", "name");
    private static final List<String> TYPES = List.of("DOUBLE", "STRING");

    private static Table people(Factory f) {
        return f.create("people", COLUMNS, TYPES);
    }

    private static Object[] row(double id, String name) {
        return new Object[] { id, name };
    }

    // A copy of everything in the table, in scan order.
    private static List<Object[]> contents(Table t) {
        List<Object[]> out = new ArrayList<>();
        for (Object[] row : t.scan()) {
            out.add(row.clone());
        }
        return out;
    }

    private static String show(List<Object[]> rows) {
        StringBuilder sb = new StringBuilder("[");
        for (Object[] r : rows) sb.append(Arrays.toString(r));
        return sb.append("]").toString();
    }

    private static void assertContents(Table t, String message, Object[]... expected) {
        List<Object[]> actual = contents(t);
        List<Object[]> wanted = Arrays.asList(expected);
        boolean same = actual.size() == wanted.size();
        for (int i = 0; same && i < actual.size(); i++) {
            same = Arrays.equals(actual.get(i), wanted.get(i));
        }
        assertTrue(same, message + ": expected " + show(wanted) + " but the table holds " + show(actual));
    }

    // Order-insensitive comparison, for tests where the table's own order isn't promised.
    private static void assertSameRows(List<Object[]> expected, Table t, String message) {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (Object[] r : expected) a.add(Arrays.toString(r));
        for (Object[] r : contents(t)) b.add(Arrays.toString(r));
        Collections.sort(a);
        Collections.sort(b);
        assertEquals(a, b, message);
    }

    private static Table filled(Factory f, int rows) {
        Table t = people(f);
        for (int i = 1; i <= rows; i++) {
            t.insert((double) i, "n" + i);
        }
        return t;
    }

    // ---------- Tests ----------

    static void insertAndScan(Factory f) {
        Table t = people(f);
        assertEquals("people", t.name, "name");
        assertEquals(COLUMNS, t.columns, "columns");
        assertEquals(TYPES, t.types, "types");
        assertTrue(t.isEmpty() && t.rowCount() == 0, "a new table is empty");
        assertTrue(!t.scan().iterator().hasNext(), "and scans as nothing");

        t.insert(1.0, "a");
        t.insert(2.0, "b");
        t.insert(3.0, "c");
        assertEquals(3, t.rowCount(), "three rows");
        assertTrue(!t.isEmpty(), "no longer empty");
        assertContents(t, "rows come back in insertion order", row(1, "a"), row(2, "b"), row(3, "c"));
        assertContents(t, "and scanning again gives the same", row(1, "a"), row(2, "b"), row(3, "c"));
    }

    static void insertCopies(Factory f) {
        Table t = people(f);
        Object[] mine = { 1.0, "a" };
        t.insert(mine);
        mine[1] = "changed afterwards";
        mine[0] = 99.0;
        assertContents(t, "later changes to the caller's array don't reach the table", row(1, "a"));
    }

    static void insertRefusals(Factory f) {
        Table t = people(f);
        t.insert(1.0, "keep");
        expectThrows(RuntimeException.class, () -> t.insert(1.0), "too few values");
        expectThrows(RuntimeException.class, () -> t.insert(1.0, "a", "b"), "too many values");
        try {
            t.insert("oops", "a");
            throw new AssertionError("a String in a DOUBLE column should be refused");
        } catch (RuntimeException e) {
            assertEquals("Column 'id' expects DOUBLE, got String (oops)", e.getMessage(), "message");
        }
        try {
            t.insert(1.0, 2.0);
            throw new AssertionError("a Double in a STRING column should be refused");
        } catch (RuntimeException e) {
            assertEquals("Column 'name' expects STRING, got Double (2.0)", e.getMessage(), "message");
        }
        expectThrows(RuntimeException.class, () -> t.insert(null, "a"), "null in a DOUBLE column");
        assertContents(t, "none of the refused inserts left anything behind", row(1, "keep"));
    }

    static void deleteWhere(Factory f) {
        Table t = filled(f, 6);
        int[] calls = { 0 };
        int removed = t.deleteWhere(row -> {
            calls[0]++;
            return ((Double) row[0]) % 2 == 0;
        });
        assertEquals(3, removed, "three even ids removed");
        assertEquals(6, calls[0], "the predicate saw every row exactly once");
        assertContents(t, "the others remain, in their original order", row(1, "n1"), row(3, "n3"), row(5, "n5"));
        assertEquals(3, t.rowCount(), "row count");

        assertEquals(0, t.deleteWhere(row -> false), "matching nothing removes nothing");
        assertEquals(3, t.rowCount(), "so nothing changed");
        assertEquals(3, t.deleteWhere(row -> true), "matching everything removes everything");
        assertTrue(t.isEmpty() && !t.scan().iterator().hasNext(), "leaving an empty table");
        assertEquals(0, t.deleteWhere(row -> true), "deleting from an empty table");

        // The table is still usable afterwards.
        t.insert(7.0, "after");
        assertContents(t, "inserting after deleting everything", row(7, "after"));
    }

    static void deleteAllOrNothing(Factory f) {
        Table t = filled(f, 6);
        int[] calls = { 0 };
        expectThrows(IllegalStateException.class, () -> t.deleteWhere(row -> {
            if (++calls[0] == 4) throw new IllegalStateException("the predicate fails on the 4th row");
            return true; // the first three rows WOULD be deleted
        }), "a predicate that fails partway");
        assertEquals(6, t.rowCount(), "no rows were deleted");
        assertContents(t, "the table is exactly as it was",
            row(1, "n1"), row(2, "n2"), row(3, "n3"), row(4, "n4"), row(5, "n5"), row(6, "n6"));
    }

    static void updateWhere(Factory f) {
        Table t = filled(f, 5);
        int[] tested = { 0 };
        int[] changed = { 0 };
        int count = t.updateWhere(
            row -> {
                tested[0]++;
                return ((Double) row[0]) > 3;
            },
            row -> {
                changed[0]++;
                return new Object[] { row[0], "big" + ((Double) row[0]).intValue() };
            });
        assertEquals(2, count, "two rows replaced");
        assertEquals(5, tested[0], "the predicate saw every row once");
        assertEquals(2, changed[0], "and the change was computed only for the selected rows");
        assertContents(t, "selected rows are replaced in place; the rest are untouched",
            row(1, "n1"), row(2, "n2"), row(3, "n3"), row(4, "big4"), row(5, "big5"));
        assertEquals(5, t.rowCount(), "the number of rows doesn't change");

        assertEquals(0, t.updateWhere(row -> false, row -> row), "matching nothing changes nothing");
        assertEquals(5, t.updateWhere(row -> true, row -> new Object[] { row[0], "all" }), "matching everything");
        assertContents(t, "every row replaced, order kept",
            row(1, "all"), row(2, "all"), row(3, "all"), row(4, "all"), row(5, "all"));
    }

    static void updateAllOrNothing(Factory f) {
        Table t = filled(f, 6);
        Object[][] original = { row(1, "n1"), row(2, "n2"), row(3, "n3"), row(4, "n4"), row(5, "n5"), row(6, "n6") };

        // The predicate fails partway through the scan...
        int[] calls = { 0 };
        expectThrows(IllegalStateException.class, () -> t.updateWhere(
            row -> {
                if (++calls[0] == 5) throw new IllegalStateException("fails on the 5th row");
                return true;
            },
            row -> new Object[] { row[0], "changed" }), "a predicate that fails partway");
        assertContents(t, "nothing was updated", original);

        // ...or the function that builds the new row does.
        int[] built = { 0 };
        expectThrows(IllegalStateException.class, () -> t.updateWhere(
            row -> true,
            row -> {
                if (++built[0] == 3) throw new IllegalStateException("fails on the 3rd row");
                return new Object[] { row[0], "changed" };
            }), "a change function that fails partway");
        assertContents(t, "still nothing was updated", original);
    }

    // Thousands of random inserts, deletes and updates, checked against a plain
    // Java list. Compared ignoring order, because a table stored on disk is
    // free to reuse the space of a deleted row.
    static void randomOperations(Factory f) {
        Random rnd = new Random(2024);
        Table t = people(f);
        List<Object[]> model = new ArrayList<>();

        for (int step = 0; step < 2500; step++) {
            int op = rnd.nextInt(10);
            if (op < 5) {
                double id = rnd.nextInt(60);
                String name = "n" + rnd.nextInt(6);
                t.insert(id, name);
                model.add(row(id, name));
            } else {
                final int modulus = 2 + rnd.nextInt(4);
                final int remainder = rnd.nextInt(modulus);
                java.util.function.Predicate<Object[]> selected = r -> ((Double) r[0]).intValue() % modulus == remainder;
                int expected = 0;
                for (Object[] r : model) if (selected.test(r)) expected++;

                if (op < 7) {
                    assertEquals(expected, t.deleteWhere(selected), "step " + step + ": rows deleted");
                    model.removeIf(selected);
                } else {
                    final String newName = "u" + step;
                    assertEquals(expected, t.updateWhere(selected, r -> new Object[] { r[0], newName }), "step " + step + ": rows updated");
                    for (Object[] r : model) if (selected.test(r)) r[1] = newName;
                }
            }
            assertEquals(model.size(), t.rowCount(), "step " + step + ": row count");
            if (step % 50 == 49) {
                assertSameRows(model, t, "step " + step + ": contents");
            }
        }
        assertSameRows(model, t, "at the end");
    }

    static void saveAndLoad(Factory f) throws IOException {
        Table t = people(f);
        String[] names = { "plain", "has,comma", "back\\slash", "new\nline", "", "caf\u00e9 \u65e5\u672c \uD83D\uDE00", " spaced " };
        for (int i = 0; i < names.length; i++) {
            t.insert((double) i, names[i]);
        }
        Path dir = Files.createTempDirectory("bonk-contract-test");
        Path file = dir.resolve("people.tbl");
        try {
            t.save(file.toString());
            MemoryTable loaded = Table.load(file.toString());
            assertEquals("people", loaded.name, "table name");
            assertEquals(COLUMNS, loaded.columns, "columns");
            assertEquals(TYPES, loaded.types, "types");
            assertSameRows(contents(t), loaded, "rows after a save and load");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
