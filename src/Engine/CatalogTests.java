package Engine;

import static Engine.TestKit.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Tests for Catalog: the database folder, one file per table. Run on its own with
 *   java Engine.CatalogTests
 */
public class CatalogTests {

    private static Path root;

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        try {
            root = Files.createTempDirectory("bonk-catalog-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            run("catalog: a new folder is created, empty", CatalogTests::openCreatesFolder);
            run("catalog: tables survive closing and reopening", CatalogTests::tablesPersist);
            run("catalog: file names", CatalogTests::fileNames);
            run("catalog: which table names are allowed", CatalogTests::validNames);
            run("catalog: names that differ only in case", CatalogTests::caseClash);
            run("catalog: two table files that differ only in case", CatalogTests::caseClashOnDisk);
            run("catalog: one session at a time", CatalogTests::oneSessionAtATime);
            run("catalog: a damaged table file doesn't stop the rest", CatalogTests::damagedTable);
            run("catalog: files that aren't tables are left alone", CatalogTests::otherFiles);
            run("catalog: tables are listed in name order", CatalogTests::sortedNames);
            run("catalog: creating a table from another table", CatalogTests::addFrom);
            run("catalog: closing closes the tables", CatalogTests::closing);
        } finally {
            deleteRecursively(root);
        }
    }

    // ---------- Helpers ----------

    private static Path newDir(String name) {
        return root.resolve(name);
    }

    // Runs a CREATE TABLE statement the way the CLI does: through the executor, with the catalog's factory.
    private static void create(Catalog catalog, String sql) {
        ExecutionDemo.executeCreateTable((MiniParser.CreateTableStmt) MiniParser.parseSql(sql), catalog.tables(), catalog.factory());
    }

    private static List<String> names(Catalog catalog) {
        return new ArrayList<>(catalog.tables().keySet());
    }

    private static List<String> rows(Table table) {
        List<String> out = new ArrayList<>();
        for (Object[] row : table.scan()) {
            out.add(Arrays.toString(row));
        }
        return out;
    }

    // ---------- Tests ----------

    static void openCreatesFolder() throws IOException {
        Path db = newDir("fresh/nested");
        assertTrue(!Files.exists(db), "to start with, no folder");
        try (Catalog c = Catalog.open(db)) {
            assertTrue(Files.isDirectory(db), "the folder and its parents are created");
            assertTrue(c.tables().isEmpty(), "no tables");
            assertTrue(c.wasEmpty(), "and it is reported as new");
            assertTrue(c.warnings().isEmpty(), "with nothing to warn about");
            assertTrue(Files.exists(db.resolve("bonkdb.lock")), "the lock file is there");
            assertEquals(db, c.dir(), "dir()");
        }
    }

    static void tablesPersist() throws IOException {
        Path db = newDir("persist");
        try (Catalog c = Catalog.open(db)) {
            create(c, "CREATE TABLE people (id INT, name TEXT)");
            c.tables().get("people").insert(1.0, "Ann");
            c.tables().get("people").insert(2.0, "caf\u00e9 \u65e5\u672c");
            create(c, "CREATE TABLE empty (x INT)");
        }
        try (Catalog c = Catalog.open(db)) {
            assertTrue(!c.wasEmpty(), "no longer a new database");
            assertEquals(List.of("empty", "people"), names(c), "both tables came back");
            Table people = c.tables().get("people");
            assertEquals("people", people.name, "name");
            assertEquals(List.of("id", "name"), people.columns, "columns");
            assertEquals(List.of("DOUBLE", "STRING"), people.types, "types");
            assertEquals(List.of("[1.0, Ann]", "[2.0, caf\u00e9 \u65e5\u672c]"), rows(people), "rows");
            assertEquals(0, c.tables().get("empty").rowCount(), "an empty table is still a table");
            people.insert(3.0, "Zed"); // and it is usable
        }
        try (Catalog c = Catalog.open(db)) {
            assertEquals(3, c.tables().get("people").rowCount(), "changes made after reopening persist too");
        }
    }

    // Stored files depend on this naming scheme, so it is pinned.
    static void fileNames() throws IOException {
        Path db = newDir("filenames");
        try (Catalog c = Catalog.open(db)) {
            create(c, "CREATE TABLE users (a INT)");
            create(c, "CREATE TABLE CamelCase_1 (a INT)");
            // Names Windows reserves for devices, which could not be files without the prefix.
            create(c, "CREATE TABLE con (a INT)");
            create(c, "CREATE TABLE nul (a INT)");
            create(c, "CREATE TABLE com1 (a INT)");
            c.tables().get("con").insert(7.0);

            for (String name : new String[] { "users", "CamelCase_1", "con", "nul", "com1" }) {
                assertTrue(Files.exists(db.resolve("table_" + name + ".bonk")), "file for " + name);
            }
            assertEquals(db.resolve("table_users.bonk"), c.fileFor("users"), "fileFor");
        }
        try (Catalog c = Catalog.open(db)) {
            assertEquals(List.of("CamelCase_1", "com1", "con", "nul", "users"), names(c), "all five tables reopened");
            assertEquals(List.of("[7.0]"), rows(c.tables().get("con")), "including the one with a reserved name");
        }
    }

    static void validNames() throws IOException {
        for (String ok : new String[] { "a", "_x", "Users2", "a_b_C", "x".repeat(200) }) {
            assertTrue(Catalog.isValidName(ok), "'" + ok + "' should be allowed");
        }
        for (String bad : new String[] { "", "1a", "a-b", "a b", "a.b", "a/b", "a\\b", "caf\u00e9", "x".repeat(201) }) {
            assertTrue(!Catalog.isValidName(bad), "'" + bad + "' should be refused");
        }
        assertTrue(!Catalog.isValidName(null), "null");

        try (Catalog c = Catalog.open(newDir("names"))) {
            for (String bad : new String[] { "1a", "a-b", "" }) {
                try {
                    c.factory().create(bad, List.of("a"), List.of("DOUBLE"));
                    throw new AssertionError("'" + bad + "' should have been refused");
                } catch (RuntimeException e) {
                    assertTrue(e.getMessage().contains("Invalid table name"), "message: " + e.getMessage());
                }
            }
            assertTrue(c.tables().isEmpty(), "and nothing was created");
        }
    }

    static void caseClash() throws IOException {
        try (Catalog c = Catalog.open(newDir("caseclash"))) {
            create(c, "CREATE TABLE users (a INT)");
            for (String clash : new String[] { "Users", "USERS", "uSeRs" }) {
                try {
                    create(c, "CREATE TABLE " + clash + " (a INT)");
                    throw new AssertionError(clash + " should clash with users");
                } catch (RuntimeException e) {
                    assertTrue(e.getMessage().contains("Table '" + clash + "' would clash with the existing table 'users'"),
                        "message: " + e.getMessage());
                }
            }
            // The exact name is the executor's ordinary "already exists" error.
            try {
                create(c, "CREATE TABLE users (a INT)");
                throw new AssertionError("the same name should be refused");
            } catch (RuntimeException e) {
                assertEquals("Table already exists: users", e.getMessage(), "message");
            }
            // Different names are fine, even similar ones.
            create(c, "CREATE TABLE users2 (a INT)");
            create(c, "CREATE TABLE user (a INT)");
            assertEquals(List.of("user", "users", "users2"), names(c), "only the clashing ones were refused");
        }
    }

    // A database copied from a case-sensitive system could hold files that a
    // case-insensitive one can't tell apart. That's refused rather than guessed at.
    static void caseClashOnDisk() throws IOException {
        Path db = newDir("diskclash");
        try (Catalog c = Catalog.open(db)) {
            create(c, "CREATE TABLE a (x INT)");
        }
        Path lower = db.resolve("table_a.bonk");
        Path upper = db.resolve("table_A.bonk");
        if (Files.exists(upper)) {
            System.out.println("      (skipped: this file system doesn't tell upper and lower case apart)");
            return;
        }
        Files.copy(lower, upper);
        try {
            Catalog.open(db).close();
            throw new AssertionError("two files differing only in case should be refused");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("differ only in case"), "message: " + e.getMessage());
        }
        // The failed attempt didn't leave the folder locked.
        Files.delete(upper);
        try (Catalog c = Catalog.open(db)) {
            assertEquals(List.of("a"), names(c), "opens normally once the duplicate is gone");
        }
    }

    static void oneSessionAtATime() throws IOException {
        Path db = newDir("lock");
        try (Catalog first = Catalog.open(db)) {
            try {
                Catalog.open(db);
                throw new AssertionError("a second session should be refused");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("already open in another bonkDB session"), "message: " + e.getMessage());
            }
            create(first, "CREATE TABLE still_works (a INT)"); // the first session is unaffected
        }
        try (Catalog again = Catalog.open(db)) {
            assertEquals(List.of("still_works"), names(again), "once the first session is closed, the folder can be opened again");
        }
    }

    static void damagedTable() throws IOException {
        Path db = newDir("damaged");
        try (Catalog c = Catalog.open(db)) {
            create(c, "CREATE TABLE good (a INT)");
            create(c, "CREATE TABLE bad (a INT)");
            c.tables().get("good").insert(1.0);
        }
        Files.write(db.resolve("table_bad.bonk"), "this is not a table".getBytes());

        try (Catalog c = Catalog.open(db)) {
            assertEquals(List.of("good"), names(c), "the undamaged table is available");
            assertEquals(1, c.warnings().size(), "one warning");
            assertTrue(c.warnings().get(0).contains("Couldn't open table 'bad'") && c.warnings().get(0).contains("table_bad.bonk"),
                "warning: " + c.warnings().get(0));
            assertTrue(!c.wasEmpty(), "the folder still counts as having tables");
            assertEquals(List.of("[1.0]"), rows(c.tables().get("good")), "its data is fine");
            // The damaged file is not overwritten by a new table of the same name.
            try {
                create(c, "CREATE TABLE bad (a INT)");
                throw new AssertionError("creating a table over a damaged one should be refused");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("couldn't be opened at startup"), "message: " + e.getMessage());
            }
        }
    }

    static void otherFiles() throws IOException {
        Path db = newDir("other");
        Files.createDirectories(db);
        Files.writeString(db.resolve("notes.txt"), "hello");
        Files.writeString(db.resolve("table_x.txt"), "wrong extension");
        Files.writeString(db.resolve("mytable_y.bonk"), "wrong prefix");
        Files.writeString(db.resolve("table_bad name.bonk"), "invalid table name");
        Files.writeString(db.resolve("table_.bonk"), "empty table name");
        try (Catalog c = Catalog.open(db)) {
            assertTrue(c.tables().isEmpty(), "none of those is a table");
            assertTrue(c.wasEmpty(), "so the database counts as new");
            assertEquals(2, c.warnings().size(), "only the two that look like table files but aren't valid get a warning: " + c.warnings());
        }
        assertEquals("hello", Files.readString(db.resolve("notes.txt")), "other files are not touched");
    }

    // Capital letters sort before lowercase ones.
    static void sortedNames() throws IOException {
        try (Catalog c = Catalog.open(newDir("sorted"))) {
            create(c, "CREATE TABLE b (a INT)");
            create(c, "CREATE TABLE a (a INT)");
            create(c, "CREATE TABLE C (a INT)");
            assertEquals(List.of("C", "a", "b"), names(c), "name order");
        }
    }

    static void addFrom() throws IOException {
        MemoryTable source = new MemoryTable("whatever", List.of("n", "s"), List.of("DOUBLE", "STRING"));
        source.insert(1.0, "a");
        source.insert(2.0, "b");

        Path db = newDir("addfrom");
        try (Catalog c = Catalog.open(db)) {
            c.addFrom("copy", source);
            Table copy = c.tables().get("copy");
            assertEquals("copy", copy.name, "it takes the name it is given, not the source's");
            assertEquals(List.of("[1.0, a]", "[2.0, b]"), rows(copy), "rows");
            assertTrue(Files.exists(c.fileFor("copy")), "and has a file");

            expectThrows(RuntimeException.class, () -> c.addFrom("copy", source), "the name is taken");
            try {
                c.addFrom("COPY", source);
                throw new AssertionError("a name differing only in case should clash");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("would clash"), "message: " + e.getMessage());
            }
            expectThrows(IllegalArgumentException.class,
                () -> c.addFrom("u", new MemoryTable("u", List.of("a"))), "a source with no declared types");

            // A source with a row that doesn't fit: refused, and nothing is left behind.
            MemoryTable bad = new MemoryTable("bad", List.of("a", "b"), List.of("DOUBLE", "DOUBLE"));
            bad.append(new Object[] { 1.0, 2.0 });
            bad.append(new Object[] { 3.0 });
            try {
                c.addFrom("broken", bad);
                throw new AssertionError("a row with too few values should be refused");
            } catch (RuntimeException e) {
                assertTrue(e.getMessage().contains("Column count mismatch"), "message: " + e.getMessage());
            }
            assertTrue(!c.tables().containsKey("broken"), "no table was added");
            assertTrue(!Files.exists(c.fileFor("broken")), "and no file was left behind");
            c.addFrom("broken", source); // so the name is still free
        }
        try (Catalog c = Catalog.open(db)) {
            assertEquals(List.of("broken", "copy"), names(c), "both survive a restart");
        }
    }

    static void closing() throws IOException {
        Path db = newDir("closing");
        Catalog c = Catalog.open(db);
        create(c, "CREATE TABLE people (id INT)");
        Table people = c.tables().get("people");
        people.insert(1.0);
        c.close();
        expectThrows(IllegalStateException.class, () -> people.insert(2.0), "a table can't be used once the catalog is closed");
        c.close(); // closing twice is harmless
        try (Catalog again = Catalog.open(db)) {
            assertEquals(List.of("[1.0]"), rows(again.tables().get("people")), "what was written before closing is there");
        }
    }

    private static void deleteRecursively(Path path) {
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}