package Engine;

import static Engine.TestKit.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * End-to-end tests of the command-line program: text goes in as if typed, and
 * the exact text that comes out is checked. This is the layer users actually
 * see, and the one least affected by changes to how data is stored.
 *
 * Run on its own with
 *   java Engine.CliTests
 *
 * The .revenge command is deliberately NOT tested: it permanently deletes
 * folders from disk, and a test run must never be able to do that.
 *
 * Tests named KNOWN BUG or QUIRK pin behavior that is wrong or surprising
 * today; see QueryTests for what that means.
 */
public class CliTests {

    private static final String BANNER = "Mini SQL engine -- type .help for commands, .exit to quit.";

    private static Path dir;
    private static int cliRuns = 0;

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        try {
            dir = Files.createTempDirectory("bonk-cli-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            run("cli: starting and quitting", CliTests::startAndQuit);
            run("cli: the sample tables", CliTests::sampleTables);
            run("cli: how query results are printed", CliTests::resultFormatting);
            run("cli: feedback after each kind of statement", CliTests::statementFeedback);
            run("cli: errors are reported and the session continues", CliTests::errorsContinue);
            run("cli: statements can span lines; case and blank lines", CliTests::inputHandling);
            run("cli: a whole session", CliTests::wholeSession);
            run("cli: .help and command errors", CliTests::helpAndCommandErrors);
            run("cli: .save and .load", CliTests::saveAndLoad);
            run("cli: gimmick commands", CliTests::gimmickCommands);
            run("QUIRK: the sample tables have no declared types", CliTests::quirkSampleTablesUntyped);
            run("QUIRK: a statement with no final ; is silently dropped", CliTests::quirkNoFinalSemicolon);
            run("QUIRK: commands can't interrupt an unfinished statement", CliTests::quirkCommandInsideStatement);
            run("LIMITATION: .save and .load paths can't contain spaces", CliTests::limitationSpacesInPaths);
            run("KNOWN BUG: a ; inside a string ends the statement", CliTests::bugSemicolonInString);
            run("KNOWN BUG: a second statement on one line is silently dropped", CliTests::bugTwoStatementsOnALine);
            run("KNOWN BUG: .load of a malformed file crashes the program", CliTests::bugLoadMalformedCrashes);
            run("KNOWN BUG: a table loaded under a new name keeps its old name", CliTests::bugLoadedTableKeepsOldName);
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------- Helpers ----------

    // Runs the CLI with the given text as its input and returns everything it
    // printed. Each call opens a brand-new database folder inside the temp
    // folder, so the sample tables are re-created fresh each time.
    private static String cli(String input) throws Exception {
        String database = dir.resolve("db" + (++cliRuns)).toString();
        InputStream originalIn = System.in;
        System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
        try {
            return captureOutput(() -> Cli.main(new String[] { database }));
        } finally {
            System.setIn(originalIn);
        }
    }

    private static void assertContains(String output, String fragment, String message) {
        assertTrue(output.contains(fragment), message + ": expected the output to contain\n---\n" + fragment
            + "\n---\nbut the output was\n---\n" + output + "---");
    }

    private static void assertNotContains(String output, String fragment, String message) {
        assertTrue(!output.contains(fragment), message + ": the output should not contain\n---\n" + fragment
            + "\n---\nbut the output was\n---\n" + output + "---");
    }

    // The fragments must all appear, one after another, in this order.
    private static void assertInOrder(String output, String... fragments) {
        int from = 0;
        for (String fragment : fragments) {
            int at = output.indexOf(fragment, from);
            assertTrue(at >= 0, "expected to find (after the previous fragments)\n---\n" + fragment
                + "\n---\nin the output\n---\n" + output + "---");
            from = at + fragment.length();
        }
    }

    // ---------- Normal behavior ----------

    static void startAndQuit() throws Exception {
        String quit = BANNER + "\nsql> Bye.\n";
        assertEquals(quit, cli(".exit\n"), ".exit");
        assertEquals(quit, cli(".quit\n"), ".quit");
        assertEquals(quit, cli(""), "end of input with nothing typed");
        assertEquals(quit, cli(".exit\nSELECT * FROM users;\n.help\n"), "anything after .exit is ignored");
        assertEquals(quit, cli(".exit"), ".exit without a trailing newline");
    }

    static void sampleTables() throws Exception {
        String out = cli(".tables\n.exit\n");
        assertContains(out, "  users [id, name, age]\n", "users listed with its columns");
        assertContains(out, "  products [name, price, category]\n", "products listed");
        assertContains(out, "  orders [id, user_id, total]\n", "orders listed");

        // The same tables, with their data, are there to query straight away.
        out = cli("SELECT name FROM users ORDER BY name;\n.exit\n");
        assertContains(out, "| name  |\n+-------+\n| Alice |\n| Bob   |\n| Carol |\n(3 rows)\n", "sample users");
        assertContains(cli("SELECT COUNT(*) FROM products;\n.exit\n"), "| 3.0      |", "sample products");
        assertContains(cli("SELECT COUNT(*) FROM orders;\n.exit\n"), "| 3.0      |", "sample orders");
    }

    static void resultFormatting() throws Exception {
        // Columns are padded to the widest value or header, with a ruled header line.
        assertContains(cli("SELECT name, age FROM users WHERE age > 18 ORDER BY name;\n.exit\n"),
            "| name  | age  |\n+-------+------+\n| Alice | 30.0 |\n| Carol | 45.0 |\n(2 rows)\n", "two rows");
        assertContains(cli("SELECT * FROM products ORDER BY price DESC;\n.exit\n"),
            "| name   | price | category |\n+--------+-------+----------+\n"
            + "| Wrench | 150.0 | tools    |\n| Paint  | 40.0  | supplies |\n| Hammer | 25.0  | tools    |\n(3 rows)\n", "three columns");

        // A header wider than its values sets the column width.
        assertContains(cli("SELECT COUNT(*) FROM users;\n.exit\n"),
            "| COUNT(*) |\n+----------+\n| 3.0      |\n(1 row)\n", "a single row says \"1 row\"");

        // No rows: no table, just the column names.
        assertContains(cli("SELECT name, age FROM users WHERE age > 100;\n.exit\n"),
            "(0 rows) -- columns: [name, age]\n", "empty result");
        // An empty value (AVG of nothing) prints as null.
        assertContains(cli("SELECT AVG(age) FROM users WHERE age > 100;\n.exit\n"),
            "| AVG(age) |\n+----------+\n| null     |\n(1 row)\n", "null value");
        // Joined columns are labelled with their table.
        assertContains(cli("SELECT users.name, orders.total FROM users JOIN orders ON users.id = orders.user_id "
            + "ORDER BY orders.total DESC;\n.exit\n"),
            "| users.name | orders.total |\n+------------+--------------+\n| Alice      | 75.0         |\n"
            + "| Carol      | 60.0         |\n| Alice      | 20.0         |\n(3 rows)\n", "join headers");
    }

    static void statementFeedback() throws Exception {
        String out = cli("CREATE TABLE t (n INT);\nINSERT INTO t VALUES (1);\nINSERT INTO t VALUES (2);\nINSERT INTO t VALUES (3);\n"
            // Rows are 1, 2, 3. After the first UPDATE: 9, 2, 3. After the second: 9, 8, 8.
            + "UPDATE t SET n = 9 WHERE n = 1;\nUPDATE t SET n = 8 WHERE n < 5;\nUPDATE t SET n = 7 WHERE n > 100;\n"
            + "DELETE FROM t WHERE n = 7;\nDELETE FROM t WHERE n = 9;\nDELETE FROM t;\nDELETE FROM t;\n.exit\n");
        assertInOrder(out,
            "Table 't' created.",
            "1 row inserted into 't'.", "1 row inserted into 't'.", "1 row inserted into 't'.",
            "1 row bonked into shape in 't'.",
            "2 rows bonked into shape in 't'.",
            "0 rows bonked into shape in 't'.",
            "0 rows bonked out of 't'.",
            "1 row bonked out of 't'.",
            "2 rows bonked out of 't'.",
            "0 rows bonked out of 't'.");
    }

    static void errorsContinue() throws Exception {
        // Ten statements, most of them wrong. Each error is printed, and the
        // session keeps going: the good queries before, between and after still answer.
        String out = cli("SELECT FROM users;\n"
            + "SELECT COUNT(*) FROM users;\n"
            + "SELECT * FROM nowhere;\n"
            + "CREATE TABLE users (a INT);\n"
            + "SELECT nope FROM users;\n"
            + "SELECT name FROM users WHERE age > 'x';\n"
            + "INSERT INTO users VALUES (1);\n"
            + "DROP TABLE users;\n"
            + "SELECT 1;\n"
            + "SELECT COUNT(*) FROM orders;\n"
            + ".exit\n");
        assertInOrder(out,
            "Error: Expected IDENT but got FROM",
            "| 3.0      |",
            "Error: No such table: nowhere",
            "Error: Table already exists: users",
            "Error: Unknown column 'nope'",
            "Error: Type mismatch comparing 30.0 and x",
            "Error: Table 'users' has 3 columns but 1 values were given",
            "Error: Expected SELECT, CREATE, INSERT, DELETE, or UPDATE, got IDENT",
            "Error: Expected IDENT but got NUMBER",
            "| 3.0      |",
            "Bye.");
    }

    static void inputHandling() throws Exception {
        // A statement can be split over several lines; each continuation line is prompted with "  -> ".
        String out = cli("SELECT\n  name\nFROM users\nWHERE age > 40;\n.exit\n");
        assertContains(out, "sql>   ->   ->   -> | name  |\n+-------+\n| Carol |\n(1 row)\n", "four lines, one statement");

        // Keywords can be any case; blank lines and extra spaces are ignored.
        out = cli("\n   \nselect COUNT(*) from users where age > 20;\n\n.exit\n");
        assertContains(out, "| 2.0      |", "lower-case SQL and blank lines");

        // Windows line endings are fine.
        out = cli("SELECT COUNT(*) FROM users;\r\nSELECT name\r\nFROM users\r\nWHERE id = 2;\r\n.exit\r\n");
        assertContains(out, "| 3.0      |", "first statement with CRLF input");
        assertContains(out, "| Bob  |", "second statement spanning lines with CRLF input");

        // A line holding only a semicolon is an empty statement, and is ignored.
        out = cli(";\n;\nSELECT COUNT(*) FROM users;\n.exit\n");
        assertContains(out, "| 3.0      |", "stray semicolons");
        assertNotContains(out, "Error", "stray semicolons aren't errors");
    }

    static void wholeSession() throws Exception {
        String out = cli("CREATE TABLE pets (id INT, name TEXT, age INT);\n"
            + "INSERT INTO pets VALUES (1, 'Rex', 3);\n"
            + "INSERT INTO pets VALUES (2, 'Milo', 5);\n"
            + "SELECT * FROM pets ORDER BY id;\n"
            + "UPDATE pets SET age = 4 WHERE name = 'Rex';\n"
            + "DELETE FROM pets WHERE age > 4;\n"
            + "SELECT * FROM pets;\n"
            + "DELETE FROM pets;\n"
            + "SELECT * FROM pets;\n"
            + ".exit\n");
        assertInOrder(out,
            "Table 'pets' created.",
            "1 row inserted into 'pets'.",
            "1 row inserted into 'pets'.",
            "| id  | name | age |\n+-----+------+-----+\n| 1.0 | Rex  | 3.0 |\n| 2.0 | Milo | 5.0 |\n(2 rows)\n",
            "1 row bonked into shape in 'pets'.",
            "1 row bonked out of 'pets'.",
            "| id  | name | age |\n+-----+------+-----+\n| 1.0 | Rex  | 4.0 |\n(1 row)\n",
            "1 row bonked out of 'pets'.",
            "(0 rows) -- columns: [id, name, age]\n",
            "Bye.");
    }

    // ---------- Commands ----------

    static void helpAndCommandErrors() throws Exception {
        String help = cli(".help\n.exit\n");
        for (String command : new String[] { ".tables", ".load", ".save", ".art", ".panic", ".revenge", ".help", ".exit / .quit" }) {
            assertContains(help, command, ".help lists " + command);
        }

        assertContains(cli(".bogus\n.exit\n"), "Unknown command: .bogus (try .help)\n", "unknown command");
        assertContains(cli(".load\n.exit\n"), "Usage: .load <name> [path]  (path defaults to <name>.tbl)\n", ".load with no arguments");
        assertContains(cli(".load a b c\n.exit\n"), "Usage: .load", ".load with too many arguments");
        assertContains(cli(".save\n.exit\n"), "Usage: .save <name> [path]  (path defaults to <name>.tbl)\n", ".save with no arguments");
        assertContains(cli(".save a b c\n.exit\n"), "Usage: .save", ".save with too many arguments");
        assertContains(cli(".save nosuchtable\n.exit\n"), "No such table: nosuchtable\n", ".save of a table that doesn't exist");
        assertContains(cli(".TABLES\n.exit\n"), "Unknown command: .TABLES", "commands are case-sensitive");
    }

    static void saveAndLoad() throws Exception {
        String file = dir.resolve("users.tbl").toString();
        if (file.contains(" ")) {
            System.out.println("      (skipped: the temp folder's path contains a space, which .save/.load can't handle)");
            return;
        }

        String out = cli(".save users " + file + "\n.load copy " + file + "\nSELECT name FROM copy ORDER BY name;\n.exit\n");
        assertInOrder(out,
            "Saved 'users' to " + file,
            "Loaded 'copy' (3 rows) from " + file,
            "| name  |\n+-------+\n| Alice |\n| Bob   |\n| Carol |\n(3 rows)\n");

        // The loaded copy and the original are separate tables.
        out = cli(".save users " + file + "\n.load copy " + file + "\nDELETE FROM copy;\nSELECT COUNT(*) FROM users;\n.exit\n");
        assertContains(out, "3 rows bonked out of 'copy'.", "deleting from the copy");
        assertContains(out, "| 3.0      |", "the original is untouched");

        // Loading over an existing name replaces that table.
        out = cli(".save users " + file + "\nDELETE FROM users;\n.load users " + file + "\nSELECT COUNT(*) FROM users;\n.exit\n");
        assertContains(out, "| 3.0      |", "reloading restores the rows");

        // A file that doesn't exist is reported and the session carries on.
        String missing = dir.resolve("missing.tbl").toString();
        out = cli(".load x " + missing + "\nSELECT COUNT(*) FROM users;\n.exit\n");
        assertContains(out, "Error loading " + missing + ":", "missing file");
        assertContains(out, "| 3.0      |", "the session continued");
        assertContains(cli(".save users " + dir.resolve("no-such-dir").resolve("x.tbl") + "\n.exit\n"), "Error saving to", "unwritable path");

        // Saving gives the table types, even though the sample tables had none.
        out = cli(".save users " + file + "\n.load typed " + file + "\nINSERT INTO typed VALUES ('x', 'y', 'z');\n.exit\n");
        assertContains(out, "Error: Column 'id' expects DOUBLE, got String (x)", "a reloaded table checks its types");
    }

    static void gimmickCommands() throws Exception {
        // ORDER BY CHAOS announces itself.
        String out = cli("SELECT name FROM products ORDER BY CHAOS;\n.exit\n");
        assertContains(out, "bonk! shuffling results into chaos...\n", "chaos message");
        assertContains(out, "(3 rows)\n", "and still returns the rows");
        assertNotContains(cli("SELECT name FROM products ORDER BY name;\n.exit\n"), "chaos", "no message for an ordinary sort");

        // vibes = 'immaculate'
        assertContains(cli("SELECT COUNT(*) FROM users WHERE age > 1000 AND vibes = 'immaculate';\n.exit\n"), "| 3.0      |", "vibes");

        // .art prints the mascot.
        assertContains(cli(".art\n.exit\n"), "BONK!", ".art");

	//TODO: corrupt doesn't work anymore. It isn't up to date with the new HeapTable
        // .panic corrupts one cell and says which table.
        // out = cli(".panic\n.exit\n");
        // assertContains(out, "PANIC! Table '", ".panic announces itself");
        // assertContains(out, "' has been bonked into chaos.", ".panic names a table");
        // assertContains(out, "TEST", "and shows what it did");

        // With nothing to corrupt, it says so.
        // out = cli("DELETE FROM users;\nDELETE FROM products;\nDELETE FROM orders;\n.panic\n.exit\n");
        // assertContains(out, "Nothing to panic about -- every table is empty.\n", ".panic with nothing to corrupt");
    }

    // ---------- Quirks, bugs, and limitations ----------

    // The CLI creates its sample tables without declared column types, so
    // they accept any values (CREATE TABLE tables don't).
    static void quirkSampleTablesUntyped() throws Exception {
        String out = cli("INSERT INTO users VALUES ('x', 'y', 'z');\n.exit\n");
        assertContains(out, "1 row inserted into 'users'.", "text in the numeric id and age columns is accepted");
        assertNotContains(out, "Error", "no complaint");
        out = cli("CREATE TABLE typed (id INT);\nINSERT INTO typed VALUES ('x');\n.exit\n");
        assertContains(out, "Error: Column 'id' expects DOUBLE", "a CREATE TABLE table is checked");
    }

    // The CLI only runs a statement once it sees a ';'. At the end of the
    // input, an unfinished statement is thrown away without a word.
    static void quirkNoFinalSemicolon() throws Exception {
        String out = cli("SELECT COUNT(*) FROM users");
        assertEquals(BANNER + "\nsql>   -> Bye.\n", out, "no result and no error");
        out = cli("DELETE FROM users");
        assertEquals(BANNER + "\nsql>   -> Bye.\n", out, "a DELETE without ; isn't run");
        // A following .exit doesn't rescue it: it's swallowed into the unfinished
        // statement (see the next test), and the input simply runs out.
        out = cli("DELETE FROM users\n.exit\n");
        assertEquals(BANNER + "\nsql>   ->   -> Bye.\n", out, ".exit is absorbed, and still nothing runs");
    }

    // Commands are only recognized at the start of a statement. Typed in the
    // middle of one, they're read as SQL, so there's no way to abandon a
    // half-typed statement except to finish it.
    static void quirkCommandInsideStatement() throws Exception {
        String out = cli("SELECT *\n.exit\nFROM users;\n.exit\n");
        assertContains(out, "Error: Expected FROM but got DOT (.)", ".exit was read as part of the SQL");
        assertContains(out, "Bye.", "the program ended at the second .exit");
    }

    // Commands are split on whitespace, so a path containing a space is
    // taken as several arguments.
    static void limitationSpacesInPaths() throws Exception {
        assertContains(cli(".save users my folder/users.tbl\n.exit\n"), "Usage: .save", ".save");
        assertContains(cli(".load users my folder/users.tbl\n.exit\n"), "Usage: .load", ".load");
    }

    // KNOWN BUG: the CLI cuts a statement off at the first ';' on a line, even
    // one inside a quoted string. The INSERT below is mangled and fails, and
    // the table stays empty. When this is fixed, the INSERT should succeed and
    // the SELECT should return the row  a;b.
    static void bugSemicolonInString() throws Exception {
        String out = cli("CREATE TABLE t (s TEXT);\nINSERT INTO t VALUES ('a;b');\nSELECT * FROM t;\n.exit\n");
        assertContains(out, "Error: Unexpected character", "the INSERT is cut off at the ; inside the string");
        assertNotContains(out, "1 row inserted", "so nothing was inserted");
        assertContains(out, "(0 rows) -- columns: [s]\n", "and the table is empty");
    }

    // KNOWN BUG: everything after the first ';' on a line is discarded without
    // any message. Here the INSERT never runs. When this is fixed, both
    // statements should run.
    static void bugTwoStatementsOnALine() throws Exception {
        String out = cli("CREATE TABLE t (n INT); INSERT INTO t VALUES (1);\nSELECT COUNT(*) FROM t;\n.exit\n");
        assertContains(out, "Table 't' created.", "the first statement ran");
        assertNotContains(out, "1 row inserted", "the second statement did not");
        assertContains(out, "| 0.0      |", "so the table is empty");
        assertNotContains(out, "Error", "and nothing was reported");
    }

    // KNOWN BUG: .load only catches IOException, but Table.load fails with
    // other exceptions on a malformed file (see TableTests), so the exception
    // escapes the program's main loop and ends the session. When this is
    // fixed, .load should print an error and the session should continue.
    static void bugLoadMalformedCrashes() throws Exception {
        String garbage = dir.resolve("garbage.tbl").toString();
        if (garbage.contains(" ")) {
            System.out.println("      (skipped: the temp folder's path contains a space)");
            return;
        }
        Files.writeString(dir.resolve("garbage.tbl"), "this is not a table file\n");
        expectThrows(RuntimeException.class, () -> cli(".load g " + garbage + "\nSELECT 1;\n.exit\n"),
            ".load of a malformed file throws out of the program");
    }

    // KNOWN BUG: .load registers the table under the name you give it, but the
    // table keeps the name stored in the file. A reference like copy.name then
    // fails (the table still thinks it's called users), while users.name
    // works even though no table named users is being queried.
    // When this is fixed, copy.name should work and users.name should not.
    static void bugLoadedTableKeepsOldName() throws Exception {
        String file = dir.resolve("renamed.tbl").toString();
        if (file.contains(" ")) {
            System.out.println("      (skipped: the temp folder's path contains a space)");
            return;
        }
        String out = cli(".save users " + file + "\n.load copy " + file + "\nSELECT copy.name FROM copy;\n.exit\n");
        assertContains(out, "Error: Unknown column 'copy.name' in table users", "the table still calls itself users");
        out = cli(".save users " + file + "\n.load copy " + file + "\nSELECT users.name FROM copy ORDER BY users.name;\n.exit\n");
        assertContains(out, "| users.name |", "the old name works");
        assertContains(out, "| Alice      |", "and returns the data");
        // Unqualified names are unaffected, and so are joins (which label by the name used in the query).
        out = cli(".save users " + file + "\n.load copy " + file + "\nSELECT name FROM copy ORDER BY name;\n.exit\n");
        assertContains(out, "| Alice |", "unqualified column names work");
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
