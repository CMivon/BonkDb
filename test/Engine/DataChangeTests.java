package Engine;

import static Engine.TestDb.r;
import static Engine.TestKit.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for the statements that create and change data: CREATE TABLE,
 * INSERT, UPDATE and DELETE, plus the CorruptData gimmick. Results are
 * always read back with SELECT, never by looking inside a table. Run on
 * its own with
 *   java Engine.DataChangeTests
 *
 * Tests named KNOWN BUG pin behavior that is wrong today; see QueryTests
 * for what that means.
 */
public class DataChangeTests {

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        run("create: a new table starts empty with its columns", DataChangeTests::createTable);
        run("create: types decide what a column accepts", DataChangeTests::createTypes);
        run("create: names", DataChangeTests::createNames);
        run("insert: values land in the right columns", DataChangeTests::insertBasics);
        run("insert: column lists", DataChangeTests::insertColumnLists);
        run("insert: values of every kind", DataChangeTests::insertValueKinds);
        run("insert: a refused insert changes nothing", DataChangeTests::insertErrors);
        run("update: basics", DataChangeTests::updateBasics);
        run("update: which rows, and how many", DataChangeTests::updateRows);
        run("update: a refused update changes nothing", DataChangeTests::updateErrors);
        run("delete: basics", DataChangeTests::deleteBasics);
        run("delete: a refused delete changes nothing", DataChangeTests::deleteErrors);
        run("changes: tables are independent of each other", DataChangeTests::tablesAreIndependent);
        run("changes: a long sequence of mixed statements", DataChangeTests::lifecycle);
        run("changes: a WHERE that fails partway changes nothing", DataChangeTests::changesAreAllOrNothing);
        run("vibes: also overrides UPDATE and DELETE conditions", DataChangeTests::vibesInChanges);
        // run("gimmick: CorruptData", DataChangeTests::corruptData); // TODO: corrupt doesn't work any with the HeapTable
        run("KNOWN BUG: duplicate column names are accepted", DataChangeTests::bugDuplicateColumns);
    }

    // ---------- CREATE TABLE ----------

    static void createTable() {
        TestDb db = new TestDb();
        assertEquals(0, db.exec("CREATE TABLE people (id INT, name TEXT, score DOUBLE)"), "CREATE affects no rows");
        db.assertColumns("SELECT * FROM people", "id", "name", "score");
        db.assertRows("SELECT * FROM people");
        assertEquals(0, db.count("people"), "a new table has no rows");

        // Guard against the test pass quietly using the wrong kind of table.
        if (TestDb.usingFileTables()) {
            assertTrue(db.catalog.get("people") instanceof HeapTable, "in file mode, CREATE TABLE makes a file-backed table");
        } else {
            assertTrue(db.catalog.get("people") instanceof MemoryTable, "normally, CREATE TABLE makes an in-memory table");
        }

        db.expectError("CREATE TABLE people (x INT)", "Table already exists: people");
        // The failed CREATE didn't disturb the original.
        db.assertColumns("SELECT * FROM people", "id", "name", "score");

        // A one-column table, and a wide one.
        db.exec("CREATE TABLE one (only TEXT)");
        db.assertColumns("SELECT * FROM one", "only");
        db.exec("CREATE TABLE wide (c1 INT, c2 INT, c3 INT, c4 INT, c5 INT, c6 INT, c7 INT, c8 INT)");
        db.exec("INSERT INTO wide VALUES (1, 2, 3, 4, 5, 6, 7, 8)");
        db.assertRows("SELECT c8, c1 FROM wide", r(8.0, 1.0));
    }

    static void createTypes() {
        TestDb db = new TestDb();
        // Every numeric spelling means the same thing, and so does every text spelling.
        for (String type : new String[] { "INT", "INTEGER", "DOUBLE", "FLOAT", "NUMBER", "NUM" }) {
            db.exec("CREATE TABLE n_" + type + " (v " + type + ")");
            db.exec("INSERT INTO n_" + type + " VALUES (1.5)");
            db.expectError("INSERT INTO n_" + type + " VALUES ('text')", "Column 'v' expects DOUBLE, got String (text)");
        }
        for (String type : new String[] { "TEXT", "VARCHAR", "STRING", "STR", "CHAR" }) {
            db.exec("CREATE TABLE t_" + type + " (v " + type + ")");
            db.exec("INSERT INTO t_" + type + " VALUES ('hello')");
            db.expectError("INSERT INTO t_" + type + " VALUES (5)", "Column 'v' expects STRING, got Double (5.0)");
        }
        // INT is not a whole-number type: decimals are accepted and kept.
        db.assertRows("SELECT v FROM n_INT", r(1.5));
        db.expectError("CREATE TABLE bad (v BOOLEAN)", "Unknown column type: BOOLEAN");
        db.expectError("SELECT * FROM bad", "No such table: bad");
    }

    static void createNames() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE Users (a INT)");
        db.exec("CREATE TABLE users (b INT)");
        db.exec("CREATE TABLE USERS (c INT)");
        // Table names are case-sensitive, so these are three different tables.
        db.assertColumns("SELECT * FROM Users", "a");
        db.assertColumns("SELECT * FROM users", "b");
        db.assertColumns("SELECT * FROM USERS", "c");
        db.expectError("CREATE TABLE users (z INT)", "Table already exists: users");

        // Names containing digits and underscores are fine; type words aren't reserved.
        db.exec("CREATE TABLE t_2024 (col_1 INT, int INT, text TEXT)");
        db.assertColumns("SELECT * FROM t_2024", "col_1", "int", "text");
        // Column names are case-sensitive too.
        db.exec("CREATE TABLE mixed (Name TEXT, name TEXT)");
        db.exec("INSERT INTO mixed VALUES ('upper', 'lower')");
        db.assertRows("SELECT Name, name FROM mixed", r("upper", "lower"));
    }

    // ---------- INSERT ----------

    static void insertBasics() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE people (id INT, name TEXT, score DOUBLE)");
        assertEquals(1, db.exec("INSERT INTO people VALUES (1, 'Ann', 9.5)"), "INSERT affects one row");
        db.exec("INSERT INTO people VALUES (2, 'Bo', 7)");
        db.assertRows("SELECT * FROM people", r(1.0, "Ann", 9.5), r(2.0, "Bo", 7.0));
        assertEquals(2, db.count("people"), "two rows");

        // Identical rows are allowed: there are no keys or uniqueness rules.
        db.exec("INSERT INTO people VALUES (2, 'Bo', 7)");
        assertEquals(3, db.count("people"), "duplicates are kept");
        db.assertRows("SELECT name FROM people WHERE id = 2", r("Bo"), r("Bo"));
    }

    static void insertColumnLists() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE p (name TEXT, qty INT, tag TEXT)");
        // With a column list, the values go to the named columns, whatever their order.
        db.exec("INSERT INTO p (tag, name, qty) VALUES ('t1', 'n1', 5)");
        db.exec("INSERT INTO p (name, qty, tag) VALUES ('n2', 6, 't2')");
        db.exec("INSERT INTO p (qty, tag, name) VALUES (7, 't3', 'n3')");
        db.assertRows("SELECT name, qty, tag FROM p", r("n1", 5.0, "t1"), r("n2", 6.0, "t2"), r("n3", 7.0, "t3"));

        // The list must name every column, exactly once.
        db.expectError("INSERT INTO p (name, qty) VALUES ('x', 1)", "partial column list is not supported");
        db.expectError("INSERT INTO p (name) VALUES ('x')", "partial column list is not supported");
        db.expectError("INSERT INTO p (name, qty, tag, name) VALUES ('x', 1, 't', 'y')", "partial column list is not supported");
        db.expectError("INSERT INTO p (name, qty, nope) VALUES ('x', 1, 't')", "Unknown column 'nope' in table p");
        db.expectError("INSERT INTO p (name, name, qty) VALUES ('x', 'y', 1)", "expects STRING, got null");
        db.expectError("INSERT INTO p (name, qty, tag) VALUES ('x', 1)", "doesn't match value count");
        db.expectError("INSERT INTO p (name, qty, tag) VALUES ('x', 1, 't', 'extra')", "doesn't match value count");
        assertEquals(3, db.count("p"), "none of the refused inserts added a row");
    }

    static void insertValueKinds() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE v (s TEXT, n DOUBLE)");
        db.exec("INSERT INTO v VALUES ('it''s', 1)");
        db.exec("INSERT INTO v VALUES ('', 2)");
        db.exec("INSERT INTO v VALUES ('a;b,c', 3)");
        db.exec("INSERT INTO v VALUES ('  padded  ', 4)");
        db.exec("INSERT INTO v VALUES ('caf\u00e9 \u65e5\u672c \uD83D\uDE00', 5)");
        db.exec("INSERT INTO v VALUES ('SELECT * FROM v', 6)");
        db.exec("INSERT INTO v VALUES ('x', 007)");
        db.exec("INSERT INTO v VALUES ('x', 0.25)");
        db.exec("INSERT INTO v VALUES ('x', 123456789.5)");
        db.assertRows("SELECT s, n FROM v WHERE n >= 1 AND n < 7",
            r("it's", 1.0), r("", 2.0), r("a;b,c", 3.0), r("  padded  ", 4.0),
            r("caf\u00e9 \u65e5\u672c \uD83D\uDE00", 5.0), r("SELECT * FROM v", 6.0));
        db.assertRows("SELECT n FROM v WHERE s = 'x'", r(7.0), r(0.25), r(123456789.5));
        // Strings keep their case, and an empty string is a real value.
        db.assertRows("SELECT n FROM v WHERE s = ''", r(2.0));
        db.assertRows("SELECT n FROM v WHERE s = 'IT''S'");
    }

    static void insertErrors() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE p (name TEXT, qty INT)");
        db.exec("INSERT INTO p VALUES ('keep', 1)");

        db.expectError("INSERT INTO p VALUES ('x')", "Table 'p' has 2 columns but 1 values were given");
        db.expectError("INSERT INTO p VALUES ('x', 1, 2)", "Table 'p' has 2 columns but 3 values were given");
        db.expectError("INSERT INTO p VALUES (5, 1)", "Column 'name' expects STRING, got Double (5.0)");
        db.expectError("INSERT INTO p VALUES ('x', 'y')", "Column 'qty' expects DOUBLE, got String (y)");
        db.expectError("INSERT INTO nowhere VALUES (1)", "No such table: nowhere");
        db.expectError("INSERT INTO P VALUES ('x', 1)", "No such table: P");

        assertEquals(1, db.count("p"), "refused inserts left the table alone");
        db.assertRows("SELECT * FROM p", r("keep", 1.0));
    }

    // ---------- UPDATE ----------

    static void updateBasics() {
        TestDb db = TestDb.withSampleData();
        assertEquals(1, db.exec("UPDATE users SET age = 31 WHERE name = 'Alice'"), "one row updated");
        db.assertRows("SELECT name, age FROM users", r("Alice", 31.0), r("Bob", 17.0), r("Carol", 45.0));

        // Several assignments at once, and strings as well as numbers.
        assertEquals(1, db.exec("UPDATE users SET name = 'Robert', age = 18 WHERE id = 2"), "two columns at once");
        db.assertRows("SELECT id, name, age FROM users",
            r(1.0, "Alice", 31.0), r(2.0, "Robert", 18.0), r(3.0, "Carol", 45.0));

        // Setting a column to its own value, and to an empty string.
        assertEquals(1, db.exec("UPDATE users SET age = 45 WHERE id = 3"), "no-op update still counts the row");
        db.exec("UPDATE users SET name = '' WHERE id = 3");
        db.assertRows("SELECT name FROM users WHERE id = 3", r(""));

        // If a column is assigned twice, the later assignment wins.
        db.exec("UPDATE users SET age = 1, age = 2 WHERE id = 1");
        db.assertRows("SELECT age FROM users WHERE id = 1", r(2.0));
    }

    static void updateRows() {
        TestDb db = TestDb.withSampleData();
        // No WHERE means every row.
        assertEquals(3, db.exec("UPDATE users SET age = 50"), "all rows");
        db.assertRows("SELECT age FROM users", r(50.0), r(50.0), r(50.0));

        // A WHERE that matches several rows, or none.
        assertEquals(2, db.exec("UPDATE products SET category = 'misc' WHERE category = 'tools'"), "two tools");
        db.assertRows("SELECT name, category FROM products",
            r("Hammer", "misc"), r("Wrench", "misc"), r("Paint", "supplies"));
        assertEquals(0, db.exec("UPDATE products SET category = 'x' WHERE price > 1000"), "no matches");
        db.assertRows("SELECT category FROM products WHERE name = 'Paint'", r("supplies"));

        // The WHERE sees each row as it was before this statement changed anything.
        assertEquals(1, db.exec("UPDATE orders SET total = 0 WHERE total = 75"), "update the column the WHERE tests");
        db.assertRows("SELECT total FROM orders WHERE id = 100", r(0.0));
        // Compound conditions work in UPDATE just as in SELECT.
        assertEquals(2, db.exec("UPDATE orders SET user_id = 9 WHERE total = 20 OR total = 60 AND id = 102"), "AND/OR");
        // Other rows and columns are untouched.
        db.assertRows("SELECT id, user_id, total FROM orders",
            r(100.0, 1.0, 0.0), r(101.0, 9.0, 20.0), r(102.0, 9.0, 60.0));
    }

    static void updateErrors() {
        TestDb db = TestDb.withSampleData();
        db.expectError("UPDATE users SET age = 'old'", "Column 'age' expects DOUBLE, got String (old)");
        db.expectError("UPDATE users SET name = 5", "Column 'name' expects STRING, got Double (5.0)");
        db.expectError("UPDATE users SET nope = 1", "Unknown column 'nope' in table users");
        db.expectError("UPDATE users SET age = 1, nope = 2", "Unknown column 'nope'");
        db.expectError("UPDATE users SET age = 99, name = 5", "Column 'name' expects STRING");
        db.expectError("UPDATE nowhere SET a = 1", "No such table: nowhere");
        db.expectError("UPDATE users SET age = 1 WHERE name > 5", "Type mismatch");
        db.expectError("UPDATE users SET age = 1 WHERE nope = 1", "Unknown column 'nope'");
        // Every one of those failed before changing anything: even
        // "age = 99" in the statement with the bad second assignment didn't happen.
        db.assertRows("SELECT id, name, age FROM users", r(1.0, "Alice", 30.0), r(2.0, "Bob", 17.0), r(3.0, "Carol", 45.0));
    }

    // ---------- DELETE ----------

    static void deleteBasics() {
        TestDb db = TestDb.withSampleData();
        assertEquals(1, db.exec("DELETE FROM users WHERE name = 'Bob'"), "one row deleted");
        db.assertRows("SELECT name FROM users", r("Alice"), r("Carol"));
        assertEquals(0, db.exec("DELETE FROM users WHERE name = 'Bob'"), "deleting it again finds nothing");
        assertEquals(0, db.exec("DELETE FROM users WHERE age > 1000"), "nothing matches");
        assertEquals(2, db.count("users"), "count after");

        assertEquals(2, db.exec("DELETE FROM products WHERE category = 'tools'"), "several rows");
        db.assertRows("SELECT name FROM products", r("Paint"));
        assertEquals(2, db.exec("DELETE FROM orders WHERE total < 30 OR id = 102"), "OR condition");
        db.assertRows("SELECT id FROM orders", r(100.0));

        // No WHERE deletes everything, but the table itself stays.
        assertEquals(2, db.exec("DELETE FROM users"), "all rows");
        assertEquals(0, db.count("users"), "empty table");
        db.assertColumns("SELECT * FROM users", "id", "name", "age");
        assertEquals(0, db.exec("DELETE FROM users"), "deleting from an empty table");

        // The table can be filled again afterwards.
        db.exec("INSERT INTO users VALUES (9, 'Zed', 99)");
        db.assertRows("SELECT * FROM users", r(9.0, "Zed", 99.0));
    }

    static void deleteErrors() {
        TestDb db = TestDb.withSampleData();
        db.expectError("DELETE FROM nowhere", "No such table: nowhere");
        db.expectError("DELETE FROM users WHERE name > 5", "Type mismatch");
        db.expectError("DELETE FROM users WHERE nope = 1", "Unknown column 'nope'");
        db.expectError("DELETE FROM users WHERE age > 1 AND nope = 1", "Unknown column 'nope'");
        assertEquals(3, db.count("users"), "a refused DELETE removes nothing");
        db.assertRows("SELECT name FROM users", r("Alice"), r("Bob"), r("Carol"));
    }

    // ---------- Combined behavior ----------

    static void tablesAreIndependent() {
        TestDb db = TestDb.withSampleData();
        db.exec("DELETE FROM users");
        db.exec("UPDATE products SET price = 1");
        assertEquals(0, db.count("users"), "users emptied");
        assertEquals(3, db.count("orders"), "orders untouched by changes to others");
        db.assertRows("SELECT price FROM products", r(1.0), r(1.0), r(1.0));
        db.assertRows("SELECT total FROM orders", r(75.0), r(20.0), r(60.0));
        // Two tables can share a column name without interfering.
        db.exec("UPDATE orders SET id = 0");
        db.assertRows("SELECT id FROM orders", r(0.0), r(0.0), r(0.0));
    }

    // Many statements in a row, checking the table after each phase against
    // what a plain Java list says it should contain.
    static void lifecycle() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE log (seq INT, label TEXT, weight DOUBLE)");
        List<Object[]> expected = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            String label = "item" + (i % 7);
            db.exec("INSERT INTO log VALUES (" + i + ", '" + label + "', " + (i % 10) + ")");
            expected.add(new Object[] { (double) i, label, (double) (i % 10) });
        }
        assertEquals(300, db.count("log"), "after 300 inserts");
        db.assertRows("SELECT COUNT(*), SUM(weight), MIN(seq), MAX(seq) FROM log", r(300.0, 1350.0, 0.0, 299.0));

        // Update a slice, delete another, and mirror both on the Java list.
        db.exec("UPDATE log SET label = 'changed' WHERE weight >= 8");
        for (Object[] row : expected) if ((Double) row[2] >= 8) row[1] = "changed";
        db.exec("DELETE FROM log WHERE weight = 0 OR seq < 20");
        expected.removeIf(row -> (Double) row[2] == 0 || (Double) row[0] < 20);

        Object[][] want = expected.toArray(new Object[0][]);
        db.assertRows("SELECT seq, label, weight FROM log", want);
        assertEquals(expected.size(), db.count("log"), "count after updates and deletes");

        // Grouped totals agree with the mirror too.
        Map<String, Integer> byLabel = new HashMap<>();
        for (Object[] row : expected) byLabel.merge((String) row[1], 1, Integer::sum);
        List<Object[]> groups = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byLabel.entrySet()) groups.add(r(e.getKey(), (double) e.getValue()));
        db.assertRows("SELECT label, COUNT(*) FROM log GROUP BY label", groups.toArray(new Object[0][]));

        // Inserting after deleting works, and new rows are found alongside old ones.
        db.exec("INSERT INTO log VALUES (1000, 'late', 5)");
        assertEquals(expected.size() + 1, db.count("log"), "count after one more insert");
        db.assertRows("SELECT label FROM log WHERE seq = 1000", r("late"));
        assertEquals(expected.size() + 1, db.exec("DELETE FROM log"), "delete everything");
        assertEquals(0, db.count("log"), "empty at the end");
    }

    // A table with no declared types can hold mixed values, so a WHERE can work
    // on the first rows and then fail on a later one. The statement must then
    // change nothing at all. (UPDATE used to leave the earlier rows changed.)
    static void changesAreAllOrNothing() {
        TestDb db = new TestDb();
        MemoryTable mixed = new MemoryTable("mixed", List.of("n"));
        mixed.insert(1.0);
        mixed.insert(2.0);
        mixed.insert("text");
        mixed.insert(4.0);
        db.catalog.put("mixed", mixed);

        // n > 0 is fine for the numbers but fails on "text".
        db.expectError("UPDATE mixed SET n = 0 WHERE n > 0", "Type mismatch");
        db.assertRows("SELECT n FROM mixed", r(1.0), r(2.0), r("text"), r(4.0));
        db.expectError("DELETE FROM mixed WHERE n > 0", "Type mismatch");
        db.assertRows("SELECT n FROM mixed", r(1.0), r(2.0), r("text"), r(4.0));

        // A WHERE that doesn't depend on the mixed column can be evaluated for every row,
        // so the change goes through. (Any comparison on the column itself fails on
        // either the numbers or the text.)
        assertEquals(4, db.exec("UPDATE mixed SET n = 0 WHERE 1 = 1"), "update every row");
        db.assertRows("SELECT n FROM mixed", r(0.0), r(0.0), r(0.0), r(0.0));
    }

    static void vibesInChanges() {
        TestDb db = TestDb.withSampleData();
        // The whole condition counts as true, even alongside one that matches nothing.
        assertEquals(3, db.exec("UPDATE users SET age = 1 WHERE age > 1000 AND vibes = 'immaculate'"), "UPDATE");
        db.assertRows("SELECT age FROM users", r(1.0), r(1.0), r(1.0));
        assertEquals(3, db.exec("UPDATE users SET name = 'x' WHERE nope = 5 AND vibes = 'immaculate'"),
            "a broken condition is never evaluated");
        assertEquals(3, db.exec("DELETE FROM users WHERE name = 'nobody' AND vibes = 'immaculate'"), "DELETE");
        assertEquals(0, db.count("users"), "everything is gone");
        // Without the magic words, the same condition is an ordinary one.
        db.expectError("DELETE FROM orders WHERE vibes = 'terrible'", "Unknown column 'vibes'");
        assertEquals(3, db.count("orders"), "so nothing was deleted");
    }

    // ---------- CorruptData ----------

    // CorruptData picks a random non-empty table, a random cell, and appends
    // "TEST" to it, whatever the cell's type. This test pins that behavior.
//     static void corruptData() throws Exception {
//         if (TestDb.usingFileTables()) {
//             // CorruptData appends text to a cell whatever its type, which a file-backed
//             // table refuses (see HeapTableTests). Its behavior is pinned on in-memory tables.
//             System.out.println("      (skipped: CorruptData only works on in-memory tables)");
//             return;
//         }
//         // Nothing to corrupt: no tables at all, or only empty ones.
//         TestDb none = new TestDb();
//         assertEquals(null, CorruptData.corrupt(none.catalog), "empty catalog");
//         none.exec("CREATE TABLE empty (a INT)");
//         assertEquals(null, CorruptData.corrupt(none.catalog), "only empty tables");

//         // On real data it returns the table's name and changes exactly one cell.
//         Set<String> tablesHit = new HashSet<>();
//         for (int run = 0; run < 40; run++) {
//             TestDb db = TestDb.withSampleData();
//             Map<String, List<List<Object>>> before = snapshot(db);
//             final String[] hit = new String[1];
//             String printed = captureOutput(() -> hit[0] = CorruptData.corrupt(db.catalog));
//             Map<String, List<List<Object>>> after = snapshot(db);

//             assertTrue(hit[0] != null, "something was corrupted");
//             assertTrue(printed.contains("bonked '" + hit[0] + "'"), "it reports what it did: " + printed);
//             tablesHit.add(hit[0]);

//             int changedCells = 0;
//             for (String table : before.keySet()) {
//                 List<List<Object>> was = before.get(table);
//                 List<List<Object>> now = after.get(table);
//                 assertEquals(was.size(), now.size(), "row count of " + table + " is unchanged");
//                 for (int i = 0; i < was.size(); i++) {
//                     for (int j = 0; j < was.get(i).size(); j++) {
//                         Object oldValue = was.get(i).get(j);
//                         Object newValue = now.get(i).get(j);
//                         if (!oldValue.equals(newValue)) {
//                             changedCells++;
//                             assertEquals(hit[0], table, "the changed cell is in the table that was reported");
//                             assertEquals(String.valueOf(oldValue) + "TEST", newValue, "TEST is appended to the old value");
//                         }
//                     }
//                 }
//             }
//             assertEquals(1, changedCells, "exactly one cell changes");
//         }
//         assertTrue(tablesHit.size() > 1, "over 40 runs it should hit more than one table, but only hit " + tablesHit);

//         // Only non-empty tables are candidates.
//         for (int run = 0; run < 20; run++) {
//             TestDb db = new TestDb();
//             db.exec("CREATE TABLE empty (a INT)");
//             db.exec("CREATE TABLE full (a INT)");
//             db.exec("INSERT INTO full VALUES (1)");
//             final String[] hit = new String[1];
//             captureOutput(() -> hit[0] = CorruptData.corrupt(db.catalog));
//             assertEquals("full", hit[0], "an empty table is never chosen");
//         }

//         // The corruption is real: a number becomes text, which breaks queries on it.
//         TestDb db = new TestDb();
//         db.exec("CREATE TABLE one (n INT)");
//         db.exec("INSERT INTO one VALUES (5)");
//         captureOutput(() -> CorruptData.corrupt(db.catalog));
//         db.assertRows("SELECT n FROM one", r("5.0TEST"));
//         db.expectError("SELECT SUM(n) FROM one", "SUM requires a numeric column");
//         db.expectError("SELECT n FROM one WHERE n > 1", "Type mismatch");
//     }

    // Every table's rows as lists of values, read with SELECT *.
    private static Map<String, List<List<Object>>> snapshot(TestDb db) {
        Map<String, List<List<Object>>> out = new HashMap<>();
        for (String table : db.catalog.keySet()) {
            out.put(table, db.rows("SELECT * FROM " + table));
        }
        return out;
    }

    // ---------- Known bug ----------

    // KNOWN BUG: CREATE TABLE accepts the same column name twice. Both names
    // then point at the second column, so the first column's data is hidden
    // (here the 1.0 disappears and 'x' shows up twice). A table like this
    // should be refused. When that's fixed, expect an error from CREATE.
    static void bugDuplicateColumns() {
        TestDb db = new TestDb();
        if (TestDb.usingFileTables()) {
            // A file can't describe a repeated column name, so a file-backed table
            // refuses what an in-memory table silently accepts.
            db.expectError("CREATE TABLE dup (a INT, a TEXT)", "Duplicate column name: a");
            return;
        }
        assertEquals(0, db.exec("CREATE TABLE dup (a INT, a TEXT)"), "the duplicate name is accepted");
        db.exec("INSERT INTO dup VALUES (1, 'x')");
        db.assertRows("SELECT * FROM dup", r("x", "x"));
        db.assertColumns("SELECT * FROM dup", "a", "a");
    }
}
