package Engine;

import static Engine.TestKit.assertEquals;
import static Engine.TestKit.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A small throwaway database for tests: a catalog plus helpers that run SQL
 * text and compare the results.
 *
 * This is deliberately the ONLY test class that calls the parser and executor
 * directly. Every other test goes through it (or through the CLI), so when the
 * engine's internals change, this one file is what needs updating.
 */
final class TestDb {

    // What CREATE TABLE makes. In-memory tables unless withFileTables() is running.
    private static TableFactory defaultFactory = (name, columns, types) -> new MemoryTable(name, columns, types);
    private static boolean fileTables = false;

    final Map<String, Table> catalog = new HashMap<>();
    private final TableFactory factory = defaultFactory;

    // True while withFileTables() is running.
    static boolean usingFileTables() {
        return fileTables;
    }

    // Runs body with every TestDb making file-backed tables, in a temporary
    // folder that is cleaned up afterwards. Test names are labelled "[files]".
    static void withFileTables(Runnable body) {
        try (FileTables files = new FileTables()) {
            TableFactory previous = defaultFactory;
            defaultFactory = files.factory();
            fileTables = true;
            TestKit.prefix = "[files] ";
            try {
                body.run();
            } finally {
                defaultFactory = previous;
                fileTables = false;
                TestKit.prefix = "";
            }
        }
    }

    // The same three tables the CLI seeds, but built with real CREATE TABLE
    // and INSERT statements so they have declared column types.
    static TestDb withSampleData() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE users (id INT, name TEXT, age INT)");
        db.exec("INSERT INTO users VALUES (1, 'Alice', 30)");
        db.exec("INSERT INTO users VALUES (2, 'Bob', 17)");
        db.exec("INSERT INTO users VALUES (3, 'Carol', 45)");

        db.exec("CREATE TABLE products (name TEXT, price DOUBLE, category TEXT)");
        db.exec("INSERT INTO products VALUES ('Hammer', 25, 'tools')");
        db.exec("INSERT INTO products VALUES ('Wrench', 150, 'tools')");
        db.exec("INSERT INTO products VALUES ('Paint', 40, 'supplies')");

        db.exec("CREATE TABLE orders (id INT, user_id INT, total DOUBLE)");
        db.exec("INSERT INTO orders VALUES (100, 1, 75)");
        db.exec("INSERT INTO orders VALUES (101, 1, 20)");
        db.exec("INSERT INTO orders VALUES (102, 3, 60)");
        return db;
    }

    // Shorthand for building an expected row: r("Alice", 30.0).
    static Object[] r(Object... values) {
        return values;
    }

    // ---------- Running statements ----------

    // Runs any statement. Returns a QueryResult for SELECT, or the number of
    // rows affected for everything else (CREATE counts as 0, INSERT as 1).
    private Object run(String sql) {
        BonkParser.Stmt stmt = BonkParser.parseSql(sql);
        if (stmt instanceof BonkParser.SelectStmt) {
            return ExecutionDemo.executeSelect((BonkParser.SelectStmt) stmt, catalog);
        }
        if (stmt instanceof BonkParser.CreateTableStmt) {
            ExecutionDemo.executeCreateTable((BonkParser.CreateTableStmt) stmt, catalog, factory);
            return 0;
        }
        if (stmt instanceof BonkParser.InsertStmt) {
            ExecutionDemo.executeInsert((BonkParser.InsertStmt) stmt, catalog);
            return 1;
        }
        if (stmt instanceof BonkParser.DeleteStmt) {
            return ExecutionDemo.executeDelete((BonkParser.DeleteStmt) stmt, catalog);
        }
        if (stmt instanceof BonkParser.UpdateStmt) {
            return ExecutionDemo.executeUpdate((BonkParser.UpdateStmt) stmt, catalog);
        }
        throw new IllegalStateException("Test helper doesn't know how to run " + stmt);
    }

    // Runs a non-SELECT statement and returns how many rows it affected.
    int exec(String sql) {
        Object result = run(sql);
        if (result instanceof ExecutionDemo.QueryResult) {
            throw new IllegalArgumentException("exec() is for statements that change data; use query()/rows(): " + sql);
        }
        return (Integer) result;
    }

    ExecutionDemo.QueryResult query(String sql) {
        Object result = run(sql);
        if (!(result instanceof ExecutionDemo.QueryResult)) {
            throw new IllegalArgumentException("query() is for SELECT statements: " + sql);
        }
        return (ExecutionDemo.QueryResult) result;
    }

    // The rows of a SELECT, each as a list of values (numbers are Doubles).
    List<List<Object>> rows(String sql) {
        List<List<Object>> out = new ArrayList<>();
        for (Object[] row : query(sql).rows) {
            out.add(Arrays.asList(row.clone()));
        }
        return out;
    }

    List<String> columns(String sql) {
        return query(sql).columns;
    }

    // How many rows a table has (queried through SQL, not by peeking inside).
    int count(String table) {
        return (int) Math.round((Double) rows("SELECT COUNT(*) FROM " + table).get(0).get(0));
    }

    // ---------- Assertions ----------

    // The SELECT must return exactly these rows, in any order (duplicates count).
    void assertRows(String sql, Object[]... expected) {
        List<List<Object>> actual = rows(sql);
        List<List<Object>> wanted = new ArrayList<>();
        for (Object[] row : expected) {
            wanted.add(Arrays.asList(row));
        }
        assertEquals(canonical(wanted), canonical(actual),
            "rows for [" + sql + "]\n   expected " + wanted + "\n   actual   " + actual + "\n  (compared ignoring order)");
    }

    // The SELECT must return exactly these rows, in exactly this order.
    void assertRowsInOrder(String sql, Object[]... expected) {
        List<List<Object>> actual = rows(sql);
        List<List<Object>> wanted = new ArrayList<>();
        for (Object[] row : expected) {
            wanted.add(Arrays.asList(row));
        }
        assertEquals(wanted, actual, "rows (in order) for [" + sql + "]");
    }

    void assertColumns(String sql, String... expected) {
        assertEquals(Arrays.asList(expected), columns(sql), "column names for [" + sql + "]");
    }

    // The statement must fail with a RuntimeException whose message contains
    // the fragment. Fails the test if the statement succeeds.
    void expectError(String sql, String fragment) {
        try {
            run(sql);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            assertTrue(message.contains(fragment),
                "[" + sql + "] failed, but the message should contain \"" + fragment + "\" and was: " + message);
            return;
        }
        throw new AssertionError("[" + sql + "] should have failed (with \"" + fragment + "\") but succeeded");
    }

    // Turns rows into sorted strings, with each value tagged by its type so a
    // number and a string that print the same don't compare as equal.
    private static List<String> canonical(List<List<Object>> rows) {
        List<String> out = new ArrayList<>();
        for (List<Object> row : rows) {
            StringBuilder sb = new StringBuilder();
            for (Object value : row) {
                sb.append(value == null ? "null" : value.getClass().getSimpleName() + ":" + value).append(" | ");
            }
            out.add(sb.toString());
        }
        Collections.sort(out);
        return out;
    }
}
