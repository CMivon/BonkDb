package Engine;

import static Engine.TestKit.*;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tests for MemoryTable (the in-memory kind of table) and for the text-file
 * format shared by all tables (Table.save and Table.load). Unlike the SQL-level tests, these look
 * directly at the table's fields, so they describe the in-memory
 * implementation and will need rewriting when that changes. The file format
 * tests matter beyond that: existing .tbl files on disk depend on it.
 * Run on its own with
 *   java Engine.TableTests
 *
 * Tests named KNOWN BUG or QUIRK pin behavior that is wrong or surprising
 * today; see QueryTests for what that means.
 */
public class TableTests {

    private static Path dir;

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        try {
            dir = Files.createTempDirectory("bonk-table-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            run("table: construction", TableTests::construction);
            run("table: inserting rows", TableTests::inserting);
            run("table: finding columns", TableTests::findingColumns);
            run("memory table: how it stores rows", TableTests::memoryTableStorage);
            run("file: the exact format", TableTests::fileFormat);
            run("file: round trip of ordinary data", TableTests::roundTrip);
            run("file: awkward strings survive a round trip", TableTests::awkwardStrings);
            run("file: awkward numbers survive a round trip", TableTests::awkwardNumbers);
            run("file: column types are preserved", TableTests::typesPreserved);
            run("file: loading what isn't there", TableTests::loadMissing);
            run("QUIRK: an untyped table gets types guessed when saved", TableTests::quirkUntypedTables);
            run("KNOWN BUG: a carriage return inside a string corrupts the file", TableTests::bugCarriageReturn);
            run("KNOWN BUG: an empty string in a one-column table is lost", TableTests::bugEmptyOnlyColumn);
            run("KNOWN BUG: loading a malformed file fails with the wrong kind of error", TableTests::bugMalformedFiles);
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------- Helpers ----------

    private static String path(String name) {
        return dir.resolve(name).toString();
    }

    private static String read(String file) throws IOException {
        return Files.readString(java.nio.file.Paths.get(file)).replace("\r\n", "\n");
    }

    private static BonkParser.Column col(String name) {
        return new BonkParser.Column(name);
    }

    private static BonkParser.Column col(String table, String name) {
        return new BonkParser.Column(table, name);
    }

    // Saves the table, loads it back, and checks every row matches.
    private static MemoryTable roundTrip(MemoryTable table, String file) throws IOException {
        table.save(file);
        MemoryTable back = Table.load(file);
        assertEquals(table.name, back.name, "table name");
        assertEquals(table.columns, back.columns, "column names");
        assertEquals(table.rows.size(), back.rows.size(), "row count");
        for (int i = 0; i < table.rows.size(); i++) {
            assertTrue(Arrays.equals(table.rows.get(i), back.rows.get(i)),
                "row " + i + ": wrote " + Arrays.toString(table.rows.get(i)) + " but read " + Arrays.toString(back.rows.get(i)));
        }
        return back;
    }

    // ---------- MemoryTable ----------

    static void construction() {
        MemoryTable t = new MemoryTable("t", List.of("a", "b"), List.of("DOUBLE", "STRING"));
        assertEquals("t", t.name, "name");
        assertEquals(List.of("a", "b"), t.columns, "columns");
        assertEquals(List.of("DOUBLE", "STRING"), t.types, "types");
        assertTrue(t.rows.isEmpty(), "starts empty");

        assertEquals(null, new MemoryTable("u", List.of("a")).types, "no types given means no declared schema");
        expectThrows(RuntimeException.class,
            () -> new MemoryTable("bad", List.of("a", "b"), List.of("DOUBLE")), "fewer types than columns");
        expectThrows(RuntimeException.class,
            () -> new MemoryTable("bad", List.of("a"), List.of("DOUBLE", "STRING")), "more types than columns");
    }

    static void inserting() {
        MemoryTable typed = new MemoryTable("t", List.of("n", "s"), List.of("DOUBLE", "STRING"));
        typed.insert(1.5, "x");
        assertEquals(1, typed.rows.size(), "one row");
        assertTrue(Arrays.equals(new Object[] { 1.5, "x" }, typed.rows.get(0)), "row contents");

        expectThrows(RuntimeException.class, () -> typed.insert(1.5), "too few values");
        expectThrows(RuntimeException.class, () -> typed.insert(1.5, "x", "y"), "too many values");
        try {
            typed.insert("oops", "x");
            throw new AssertionError("a String in a DOUBLE column should be refused");
        } catch (RuntimeException e) {
            assertEquals("Column 'n' expects DOUBLE, got String (oops)", e.getMessage(), "message");
        }
        try {
            typed.insert(1.0, 2.0);
            throw new AssertionError("a Double in a STRING column should be refused");
        } catch (RuntimeException e) {
            assertEquals("Column 's' expects STRING, got Double (2.0)", e.getMessage(), "message");
        }
        try {
            typed.insert(1.0, null);
            throw new AssertionError("null should be refused in a typed table");
        } catch (RuntimeException e) {
            assertEquals("Column 's' expects STRING, got null (null)", e.getMessage(), "message");
        }
        assertEquals(1, typed.rows.size(), "refused inserts add nothing");

        // A table with no declared types checks nothing but the number of values.
        MemoryTable untyped = new MemoryTable("u", List.of("a", "b"));
        untyped.insert("anything", 42);
        untyped.insert(null, 1.5);
        assertEquals(2, untyped.rows.size(), "an untyped table accepts any values");
        expectThrows(RuntimeException.class, () -> untyped.insert(1), "but still the right count");
    }

    // What is specific to the in-memory kind of table. (What every table must do is
    // in TableContractTests.)
    static void memoryTableStorage() {
        MemoryTable t = new MemoryTable("t", List.of("a"), List.of("DOUBLE"));

        Object[] mine = { 1.0 };
        t.insert(mine);
        assertTrue(t.rows.get(0) != mine, "insert stores a copy, not the caller's array");

        // append() is the unchecked, no-copy way in, used for building result tables and loading files.
        Object[] owned = { 2.0 };
        t.append(owned);
        assertTrue(t.rows.get(1) == owned, "append keeps the very array it is given");
        t.append(new Object[] { "not a number", "and too many values" });
        assertEquals(3, t.rowCount(), "append does no validation at all");

        // scan() hands out the live storage, with no copying.
        assertTrue(t.scan() == t.rows, "scan returns the stored list itself");
        for (Object[] row : t.scan()) {
            assertTrue(row == t.rows.get(0) || row == t.rows.get(1) || row == t.rows.get(2), "and the stored arrays");
        }
        // That is why changing a table while scanning it is refused rather than allowed to go wrong.
        expectThrows(java.util.ConcurrentModificationException.class, () -> {
            for (Object[] row : t.scan()) {
                t.insert(5.0);
            }
        }, "inserting during a scan");
    }

    static void findingColumns() {
        MemoryTable t = new MemoryTable("users", List.of("id", "name"));
        assertEquals(0, t.indexOf("id"), "indexOf");
        assertEquals(1, t.indexOf("name"), "indexOf");
        try {
            t.indexOf("nope");
            throw new AssertionError("unknown column should be refused");
        } catch (RuntimeException e) {
            assertEquals("Unknown column 'nope' in table users", e.getMessage(), "message");
        }

        // A Column from a parsed query: with or without a qualifier.
        assertEquals(1, t.indexOfColumn(col("name")), "unqualified");
        assertEquals(1, t.indexOfColumn(col("users", "name")), "qualified with the table's own name");
        expectThrows(RuntimeException.class, () -> t.indexOfColumn(col("other", "name")), "qualified with some other name");
        expectThrows(RuntimeException.class, () -> t.indexOfColumn(col("users", "nope")), "known qualifier, unknown column");

        // A joined table names its columns "label.column", and those are matched in full.
        MemoryTable joined = new MemoryTable("a_JOIN_b", List.of("a.id", "b.id"));
        assertEquals(0, joined.indexOfColumn(col("a", "id")), "first joined column");
        assertEquals(1, joined.indexOfColumn(col("b", "id")), "second joined column");
        expectThrows(RuntimeException.class, () -> joined.indexOfColumn(col("id")), "no unqualified match in a join");
        assertEquals(1, joined.indexOf("b.id"), "the qualified text is the column's whole name");
    }

    // ---------- File format ----------

    static void fileFormat() throws IOException {
        MemoryTable t = new MemoryTable("users", List.of("name", "age"), List.of("STRING", "DOUBLE"));
        t.insert("Alice", 30.0);
        t.insert("Bob", 17.0);
        String file = path("format.tbl");
        t.save(file);

        // Existing .tbl files depend on this exact layout.
        assertEquals("TABLE users\nCOLUMNS name,age\nTYPES STRING,DOUBLE\nROWS\nAlice,30.0\nBob,17.0\n", read(file),
            "file contents");

        // A hand-written file in that layout loads.
        Files.writeString(java.nio.file.Paths.get(file), "TABLE pets\nCOLUMNS kind,legs\nTYPES STRING,DOUBLE\nROWS\ncat,4.0\nbird,2.0\n");
        MemoryTable loaded = Table.load(file);
        assertEquals("pets", loaded.name, "name comes from the file");
        assertEquals(List.of("kind", "legs"), loaded.columns, "columns");
        assertEquals(List.of("STRING", "DOUBLE"), loaded.types, "types");
        assertEquals(2, loaded.rows.size(), "rows");
        assertTrue(Arrays.equals(new Object[] { "bird", 2.0 }, loaded.rows.get(1)), "second row");

        // Blank lines between rows are skipped.
        Files.writeString(java.nio.file.Paths.get(file), "TABLE t\nCOLUMNS a,b\nTYPES DOUBLE,STRING\nROWS\n1.0,x\n\n2.0,y\n\n");
        assertEquals(2, Table.load(file).rows.size(), "blank lines ignored");
    }

    static void roundTrip() throws IOException {
        MemoryTable t = new MemoryTable("people", List.of("id", "name", "score"), List.of("DOUBLE", "STRING", "DOUBLE"));
        for (int i = 0; i < 250; i++) {
            t.insert((double) i, "person" + i, i * 1.25);
        }
        MemoryTable back = roundTrip(t, path("people.tbl"));
        assertEquals(List.of("DOUBLE", "STRING", "DOUBLE"), back.types, "types after reload");
        // Saving again overwrites rather than appends.
        t.insert(1000.0, "late", 1.0);
        assertEquals(251, roundTrip(t, path("people.tbl")).rows.size(), "overwritten, not appended");
    }

    static void awkwardStrings() throws IOException {
        // Commas and backslashes are escaped by the writer; these must come back unchanged.
        String[] tricky = {
            "plain", "has,comma", ",", ",,", "back\\slash", "ends with backslash\\", "\\", "\\\\", "\\n literal backslash-n",
            "new\nline", "\n", "line1\nline2\nline3", "tab\there", "trailing space ", " leading space", "  ",
            "", "null", "NULL", "TABLE x", "COLUMNS a,b", "ROWS", "caf\u00e9 \u65e5\u672c \uD83D\uDE00", "comma, and\nnewline\\ together"
        };
        // A second column keeps every line non-empty (see the one-column bug below).
        MemoryTable t = new MemoryTable("weird", List.of("txt", "n"), List.of("STRING", "DOUBLE"));
        for (int i = 0; i < tricky.length; i++) {
            t.insert(tricky[i], (double) i);
        }
        roundTrip(t, path("weird.tbl"));

        // Strings in other positions, and several awkward columns at once.
        MemoryTable wide = new MemoryTable("wide", List.of("a", "b", "c"), List.of("STRING", "STRING", "STRING"));
        wide.insert("x,y", "p\nq", "r\\s");
        wide.insert("", "", "");
        wide.insert(",", ",", ",");
        roundTrip(wide, path("wide.tbl"));
    }

    static void awkwardNumbers() throws IOException {
        MemoryTable t = new MemoryTable("nums", List.of("label", "n"), List.of("STRING", "DOUBLE"));
        double[] values = { 0.0, -0.0, 1.0, -1.0, 0.1, 1.0 / 3, 123456789.987654321, 1e-9, 1e21, Double.MAX_VALUE,
            Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 4.9e-324 };
        for (int i = 0; i < values.length; i++) {
            t.insert("v" + i, values[i]);
        }
        roundTrip(t, path("nums.tbl"));
    }

    static void typesPreserved() throws IOException {
        // A table with declared types keeps them, including when it has no rows.
        MemoryTable empty = new MemoryTable("e", List.of("a", "b"), List.of("DOUBLE", "STRING"));
        MemoryTable back = roundTrip(empty, path("empty.tbl"));
        assertEquals(List.of("DOUBLE", "STRING"), back.types, "types of an empty table");
        // ...which means the reloaded table still checks what it's given.
        expectThrows(RuntimeException.class, () -> back.insert("text", "x"), "reloaded typed table refuses a bad value");
        back.insert(1.0, "ok");

        MemoryTable allText = new MemoryTable("t", List.of("a", "b"), List.of("STRING", "STRING"));
        allText.insert("1", "2"); // digits that are text must stay text
        MemoryTable backText = roundTrip(allText, path("text.tbl"));
        assertEquals("1", backText.rows.get(0)[0], "numeric-looking text stays text");
    }

    static void loadMissing() {
        expectThrows(FileNotFoundException.class, () -> Table.load(path("no-such-file.tbl")), "missing file");
        expectThrows(IOException.class, () -> new MemoryTable("t", List.of("a")).save(path("no-such-dir/file.tbl")),
            "saving into a folder that doesn't exist");
    }

    // ---------- Quirks and bugs ----------

    // A MemoryTable built without declared types (the CLI's sample tables are made
    // this way) has no schema, so MemoryTable.save guesses each column's type from
    // its first row, and calls everything text if there are no rows. The
    // reloaded table is then typed, which it wasn't before.
    static void quirkUntypedTables() throws IOException {
        MemoryTable untyped = new MemoryTable("u", List.of("n", "s"));
        untyped.insert(1.5, "x");
        MemoryTable back = roundTrip(untyped, path("untyped.tbl"));
        assertEquals(List.of("DOUBLE", "STRING"), back.types, "types guessed from the first row");

        MemoryTable emptyUntyped = new MemoryTable("eu", List.of("n", "s"));
        emptyUntyped.save(path("emptyuntyped.tbl"));
        assertEquals(List.of("STRING", "STRING"), Table.load(path("emptyuntyped.tbl")).types,
            "with no rows to look at, every column is guessed to be text");
    }

    // KNOWN BUG: save() escapes backslash, comma and newline, but not a
    // carriage return. A string containing one is written raw, and load() then
    // treats it as the end of a line, splitting one row into two (here the
    // first row comes back as a one-value row, followed by two more).
    // When this is fixed, a round trip should return exactly the rows written.
    static void bugCarriageReturn() throws IOException {
        MemoryTable t = new MemoryTable("cr", List.of("s", "n"), List.of("STRING", "DOUBLE"));
        t.insert("a\rb", 1.0);
        t.insert("next", 2.0);
        String file = path("cr.tbl");
        t.save(file);
        MemoryTable back = Table.load(file);
        assertEquals(3, back.rows.size(), "two rows were written but three come back");
        assertEquals(1, back.rows.get(0).length, "the first 'row' has only one value");
        assertEquals("a", back.rows.get(0)[0], "it holds the text before the carriage return");
    }

    // KNOWN BUG: a row of an empty string in a table with only one column is
    // written as a blank line, and load() skips blank lines, so the row is
    // silently lost. (With two or more columns the line isn't blank, so this
    // doesn't happen.) When this is fixed, all three rows should come back.
    static void bugEmptyOnlyColumn() throws IOException {
        MemoryTable t = new MemoryTable("one", List.of("s"), List.of("STRING"));
        t.insert("first");
        t.insert("");
        t.insert("third");
        String file = path("one.tbl");
        t.save(file);
        assertEquals("TABLE one\nCOLUMNS s\nTYPES STRING\nROWS\nfirst\n\nthird\n", read(file), "the file has a blank line");
        MemoryTable back = Table.load(file);
        assertEquals(2, back.rows.size(), "three rows were written but two come back");
        assertEquals("first", back.rows.get(0)[0], "first row survives");
        assertEquals("third", back.rows.get(1)[0], "third row survives");
    }

    // KNOWN BUG: load() reports a file that isn't a valid table with whatever
    // exception happens to occur (NullPointerException, NumberFormatException,
    // a count mismatch), rather than a clear IOException. Callers only
    // catch IOException, so these escape. (The CLI's .load command is one.)
    // When this is fixed, these should all be IOExceptions with a useful message.
    static void bugMalformedFiles() throws IOException {
        String file = path("malformed.tbl");
        // {description, file contents, "loads" if this is accepted today}
        String[][] cases = {
            { "an empty file", "", "" },
            { "plain text", "this is not a table file\n", "" },
            { "only the first line", "TABLE x\n", "" },
            { "a number that isn't one", "TABLE x\nCOLUMNS a\nTYPES DOUBLE\nROWS\nnotanumber\n", "" },
            { "more columns than types", "TABLE x\nCOLUMNS a,b\nTYPES DOUBLE\nROWS\n1.0\n", "" },
            // These two are accepted without complaint: a missing ROWS line is
            // tolerated, and a row with too few values loads as a short row.
            { "a header with no ROWS line", "TABLE x\nCOLUMNS a\nTYPES DOUBLE\n", "loads" },
            { "a row with too few values", "TABLE x\nCOLUMNS a,b\nTYPES DOUBLE,DOUBLE\nROWS\n1.0\n", "loads" },
        };
        for (String[] c : cases) {
            Files.writeString(java.nio.file.Paths.get(file), c[1]);
            try {
                Table.load(file);
                assertTrue(c[2].equals("loads"), c[0] + ": this was expected to fail but loaded");
            } catch (IOException e) {
                throw new AssertionError(c[0] + ": this now fails with an IOException, which is the fix. Update this test. (" + e + ")");
            } catch (RuntimeException e) {
                assertTrue(!c[2].equals("loads"), c[0] + ": this was expected to load but failed with " + e);
                // today's behavior: an unchecked exception instead of an IOException
            }
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
