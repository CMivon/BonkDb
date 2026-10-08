package Engine;

import static Engine.TestDb.r;
import static Engine.TestKit.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tests for what SELECT statements return. Everything runs through TestDb
 * with real SQL text. Queries without ORDER BY are compared ignoring row
 * order, since SQL doesn't promise one. Run on its own with
 *   java Engine.QueryTests
 *
 * Tests whose names start with KNOWN BUG or QUIRK pin behavior that is wrong
 * or surprising today. They pass now; when the behavior is fixed they will
 * fail, which is the cue to update them to the corrected expectation.
 */
public class QueryTests {

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        run("select: projection and column headers", QueryTests::projection);
        run("select: WHERE comparisons", QueryTests::whereComparisons);
        run("select: WHERE with AND/OR", QueryTests::whereLogic);
        run("select: WHERE errors", QueryTests::whereErrors);
        run("select: names are case-sensitive, keywords aren't", QueryTests::caseSensitivity);
        run("select: unknown tables and columns", QueryTests::unknownNames);
        run("order by: direction, keys, and types", QueryTests::orderBy);
        run("order by: ties keep their original order", QueryTests::orderByStability);
        run("limit", QueryTests::limit);
        run("aggregates: whole-table", QueryTests::aggregates);
        run("aggregates: GROUP BY", QueryTests::groupBy);
        run("aggregates: empty input", QueryTests::aggregatesOnEmpty);
        run("aggregates: errors", QueryTests::aggregateErrors);
        run("join: basics", QueryTests::joinBasics);
        run("join: with WHERE, ORDER BY, and GROUP BY", QueryTests::joinWithClauses);
        run("join: non-equality and empty results", QueryTests::joinConditions);
        run("join: aliases and self-joins", QueryTests::aliasesAndSelfJoins);
        run("join: name resolution rules", QueryTests::joinNameRules);
        run("gimmick: vibes = 'immaculate'", QueryTests::vibes);
        run("gimmick: ORDER BY CHAOS", QueryTests::chaos);
        run("QUIRK: errors only surface when a row is examined", QueryTests::quirkErrorsNeedRows);
        run("QUIRK: arithmetic parses but fails at run time", QueryTests::quirkArithmetic);
        run("join: three tables", QueryTests::threeTableJoin);
        run("KNOWN BUG: an alias on a lone table can't be used", QueryTests::bugAliasWithoutJoin);
        run("LIMITATION: unqualified columns after a join", QueryTests::limitationUnqualifiedAfterJoin);
    }

    // ---------- Filtering and projection ----------

    static void projection() {
        TestDb db = TestDb.withSampleData();
        db.assertRows("SELECT name, age FROM users", r("Alice", 30.0), r("Bob", 17.0), r("Carol", 45.0));
        db.assertColumns("SELECT name, age FROM users", "name", "age");
        db.assertColumns("SELECT age, name FROM users", "age", "name");
        db.assertRows("SELECT age, name FROM users WHERE id = 1", r(30.0, "Alice"));
        db.assertRows("SELECT name, name FROM users WHERE id = 2", r("Bob", "Bob"));

        db.assertColumns("SELECT * FROM users", "id", "name", "age");
        db.assertRows("SELECT * FROM products WHERE name = 'Paint'", r("Paint", 40.0, "supplies"));

        // A table name before a column is allowed on a single table too.
        db.assertRows("SELECT users.name FROM users WHERE users.age > 40", r("Carol"));
        db.assertColumns("SELECT users.name FROM users", "users.name");

        // Numbers always come back as Doubles, even ones declared INT.
        assertEquals(30.0, db.rows("SELECT age FROM users WHERE id = 1").get(0).get(0), "INT column values are Doubles");
    }

    static void whereComparisons() {
        TestDb db = TestDb.withSampleData();
        db.assertRows("SELECT name FROM users WHERE age = 17", r("Bob"));
        db.assertRows("SELECT name FROM users WHERE age <> 17", r("Alice"), r("Carol"));
        db.assertRows("SELECT name FROM users WHERE age < 30", r("Bob"));
        db.assertRows("SELECT name FROM users WHERE age <= 30", r("Alice"), r("Bob"));
        db.assertRows("SELECT name FROM users WHERE age > 30", r("Carol"));
        db.assertRows("SELECT name FROM users WHERE age >= 30", r("Alice"), r("Carol"));

        // Strings compare with =, <>, and ordering (by character code).
        db.assertRows("SELECT name FROM users WHERE name = 'Bob'", r("Bob"));
        db.assertRows("SELECT name FROM users WHERE name <> 'Bob'", r("Alice"), r("Carol"));
        db.assertRows("SELECT name FROM users WHERE name > 'Alice'", r("Bob"), r("Carol"));
        db.assertRows("SELECT name FROM users WHERE name <= 'Bob'", r("Alice"), r("Bob"));
        db.assertRows("SELECT name FROM users WHERE name < 'Bob'", r("Alice"));
        db.assertRows("SELECT name FROM users WHERE name >= 'Bob'", r("Bob"), r("Carol"));
        db.assertRows("SELECT name FROM users WHERE name >= 'Bob' AND name <= 'Bob'", r("Bob"));
        db.assertRows("SELECT name FROM users WHERE name = 'bob'"); // matching is case-sensitive

        // Decimals, and either side being the literal.
        db.assertRows("SELECT name FROM products WHERE price > 25.5", r("Wrench"), r("Paint"));
        db.assertRows("SELECT name FROM users WHERE 17 = age", r("Bob"));
        db.assertRows("SELECT name FROM users WHERE 30 < age", r("Carol"));

        // Two columns compared with each other, and two literals.
        db.assertRows("SELECT id FROM orders WHERE total > user_id", r(100.0), r(101.0), r(102.0));
        db.assertRows("SELECT id FROM orders WHERE user_id > 1", r(102.0));
        db.assertRows("SELECT id FROM orders WHERE 1 = 1", r(100.0), r(101.0), r(102.0));
        db.assertRows("SELECT id FROM orders WHERE 1 = 2");

        // No rows matching is an empty result, not an error, and keeps the headers.
        db.assertRows("SELECT name FROM users WHERE age > 1000");
        db.assertColumns("SELECT name, age FROM users WHERE age > 1000", "name", "age");
    }

    static void whereLogic() {
        TestDb db = TestDb.withSampleData();
        db.assertRows("SELECT name FROM products WHERE price <= 100 AND category = 'tools'", r("Hammer"));
        db.assertRows("SELECT name FROM products WHERE price > 100 OR category = 'supplies'", r("Wrench"), r("Paint"));
        db.assertRows("SELECT name FROM products WHERE price > 10 AND price < 100 AND category = 'tools'", r("Hammer"));

        // AND binds tighter than OR: this is  supplies OR (tools AND price > 100)
        db.assertRows("SELECT name FROM products WHERE category = 'supplies' OR category = 'tools' AND price > 100",
            r("Wrench"), r("Paint"));
        // ...which differs from reading it left to right, (supplies OR tools) AND price > 100
        // would give only Wrench. Paint appearing proves the precedence.
        db.assertRows("SELECT name FROM products WHERE category = 'tools' AND price > 100 OR category = 'supplies'",
            r("Wrench"), r("Paint"));
        db.assertRows("SELECT name FROM products WHERE price < 30 OR price > 100 AND category = 'supplies'", r("Hammer"));
    }

    static void whereErrors() {
        TestDb db = TestDb.withSampleData();
        db.expectError("SELECT name FROM users WHERE age > 'old'", "Type mismatch comparing 30.0 and old");
        db.expectError("SELECT name FROM users WHERE name = 5", "Type mismatch");
        db.expectError("SELECT name FROM users WHERE name < age", "Type mismatch");
        db.expectError("SELECT name FROM users WHERE nope = 1", "Unknown column 'nope' in table users");
        db.expectError("SELECT name FROM users WHERE users.nope = 1", "Unknown column 'nope' in table users");
        db.expectError("SELECT name FROM users WHERE other.age = 1", "Unknown column 'other.age'");
    }

    static void caseSensitivity() {
        TestDb db = TestDb.withSampleData();
        // Keywords may be any case...
        db.assertRows("select name from users where age = 17 order by name limit 5", r("Bob"));
        // ...but table and column names must match exactly.
        db.expectError("SELECT name FROM Users", "No such table: Users");
        db.expectError("SELECT NAME FROM users", "Unknown column 'NAME'");
        db.expectError("SELECT name FROM users WHERE AGE = 1", "Unknown column 'AGE'");
        db.expectError("SELECT name FROM users ORDER BY Name", "Unknown column 'Name'");
    }

    static void unknownNames() {
        TestDb db = TestDb.withSampleData();
        db.expectError("SELECT name FROM nowhere", "No such table: nowhere");
        db.expectError("SELECT nope FROM users", "Unknown column 'nope' in table users");
        db.expectError("SELECT name, nope FROM users", "Unknown column 'nope'");
        db.expectError("SELECT users.nope FROM users", "Unknown column 'nope' in table users");
        db.expectError("SELECT a.name FROM users", "Unknown column 'a.name'");
        db.expectError("SELECT name FROM users ORDER BY nope", "Unknown column 'nope'");
        db.expectError("SELECT nope, COUNT(*) FROM users GROUP BY nope", "Unknown column 'nope'");
    }

    // ---------- ORDER BY and LIMIT ----------

    static void orderBy() {
        TestDb db = TestDb.withSampleData();
        db.assertRowsInOrder("SELECT name FROM users ORDER BY age", r("Bob"), r("Alice"), r("Carol"));
        db.assertRowsInOrder("SELECT name FROM users ORDER BY age ASC", r("Bob"), r("Alice"), r("Carol"));
        db.assertRowsInOrder("SELECT name FROM users ORDER BY age DESC", r("Carol"), r("Alice"), r("Bob"));
        db.assertRowsInOrder("SELECT name FROM users ORDER BY name DESC", r("Carol"), r("Bob"), r("Alice"));

        // Sort keys can be columns that aren't in the output.
        db.assertRowsInOrder("SELECT name FROM products ORDER BY price DESC", r("Wrench"), r("Paint"), r("Hammer"));
        db.assertColumns("SELECT name FROM products ORDER BY price", "name");

        // Several keys: later ones break ties in earlier ones.
        db.assertRowsInOrder("SELECT category, name FROM products ORDER BY category, price DESC",
            r("supplies", "Paint"), r("tools", "Wrench"), r("tools", "Hammer"));
        db.assertRowsInOrder("SELECT category, name FROM products ORDER BY category DESC, name",
            r("tools", "Hammer"), r("tools", "Wrench"), r("supplies", "Paint"));

        // Sorting happens after WHERE, and works together with it.
        db.assertRowsInOrder("SELECT name FROM users WHERE age > 20 ORDER BY age DESC", r("Carol"), r("Alice"));

        // Text sorts by character code: capitals before lowercase.
        TestDb words = new TestDb();
        words.exec("CREATE TABLE w (word TEXT)");
        for (String word : new String[] { "pear", "Apple", "apple", "banana", "Zed" }) {
            words.exec("INSERT INTO w VALUES ('" + word + "')");
        }
        words.assertRowsInOrder("SELECT word FROM w ORDER BY word",
            r("Apple"), r("Zed"), r("apple"), r("banana"), r("pear"));

        db.expectError("SELECT name FROM users ORDER BY nope", "Unknown column");
    }

    static void orderByStability() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE t (grp TEXT, seq INT)");
        String[] groups = { "b", "a", "b", "a", "b", "a" };
        for (int i = 0; i < groups.length; i++) {
            db.exec("INSERT INTO t VALUES ('" + groups[i] + "', " + i + ")");
        }
        // Rows with equal sort keys stay in the order they were inserted.
        db.assertRowsInOrder("SELECT grp, seq FROM t ORDER BY grp",
            r("a", 1.0), r("a", 3.0), r("a", 5.0), r("b", 0.0), r("b", 2.0), r("b", 4.0));
        db.assertRowsInOrder("SELECT grp, seq FROM t ORDER BY grp DESC",
            r("b", 0.0), r("b", 2.0), r("b", 4.0), r("a", 1.0), r("a", 3.0), r("a", 5.0));
    }

    static void limit() {
        TestDb db = TestDb.withSampleData();
        assertEquals(2, db.rows("SELECT * FROM users LIMIT 2").size(), "LIMIT 2");
        assertEquals(0, db.rows("SELECT * FROM users LIMIT 0").size(), "LIMIT 0");
        assertEquals(3, db.rows("SELECT * FROM users LIMIT 3").size(), "LIMIT equal to the row count");
        assertEquals(3, db.rows("SELECT * FROM users LIMIT 100").size(), "LIMIT larger than the table");
        db.assertColumns("SELECT * FROM users LIMIT 0", "id", "name", "age");

        // LIMIT applies after sorting, so it picks the top rows.
        db.assertRowsInOrder("SELECT name FROM users ORDER BY age DESC LIMIT 2", r("Carol"), r("Alice"));
        db.assertRowsInOrder("SELECT name FROM users ORDER BY age LIMIT 1", r("Bob"));
        // ...and after filtering.
        db.assertRowsInOrder("SELECT name FROM users WHERE age > 20 ORDER BY age LIMIT 1", r("Alice"));
        assertEquals(1, db.rows("SELECT * FROM users WHERE age > 20 LIMIT 1").size(), "LIMIT after WHERE");
    }

    // ---------- Aggregates ----------

    static void aggregates() {
        TestDb db = TestDb.withSampleData();
        db.assertRows("SELECT COUNT(*) FROM users", r(3.0));
        db.assertRows("SELECT COUNT(*), SUM(age), AVG(age), MIN(age), MAX(age) FROM users",
            r(3.0, 92.0, 92.0 / 3, 17.0, 45.0));
        db.assertRows("SELECT MIN(name), MAX(name) FROM users", r("Alice", "Carol"));
        db.assertRows("SELECT COUNT(age), COUNT(name) FROM users", r(3.0, 3.0));

        // WHERE is applied before aggregating.
        db.assertRows("SELECT COUNT(*), SUM(age) FROM users WHERE age > 20", r(2.0, 75.0));
        db.assertRows("SELECT SUM(price), MAX(price) FROM products WHERE category = 'tools'", r(175.0, 150.0));

        // Output columns are named after the call, as written.
        db.assertColumns("SELECT COUNT(*), SUM(price), avg(price), Min(price), max(name) FROM products",
            "COUNT(*)", "SUM(price)", "AVG(price)", "MIN(price)", "MAX(name)");
        // A qualifier is kept in the name.
        db.assertColumns("SELECT MIN(users.age) FROM users", "MIN(users.age)");
        db.assertColumns("SELECT COUNT(*), MIN(users.age) FROM users JOIN orders ON vibes = 'immaculate'",
            "COUNT(*)", "MIN(users.age)");
    }

    static void groupBy() {
        TestDb db = TestDb.withSampleData();
        db.assertRows("SELECT category, COUNT(*), SUM(price) FROM products GROUP BY category",
            r("tools", 2.0, 175.0), r("supplies", 1.0, 40.0));
        db.assertColumns("SELECT category, COUNT(*), SUM(price) FROM products GROUP BY category",
            "category", "COUNT(*)", "SUM(price)");

        // The grouping column doesn't have to be in the output.
        db.assertRows("SELECT COUNT(*) FROM products GROUP BY category", r(2.0), r(1.0));
        // Several grouping columns, and the output in any column order.
        TestDb sales = new TestDb();
        sales.exec("CREATE TABLE sales (region TEXT, product TEXT, units INT)");
        sales.exec("INSERT INTO sales VALUES ('east', 'pen', 5)");
        sales.exec("INSERT INTO sales VALUES ('east', 'pen', 7)");
        sales.exec("INSERT INTO sales VALUES ('east', 'ink', 1)");
        sales.exec("INSERT INTO sales VALUES ('west', 'pen', 4)");
        sales.assertRows("SELECT region, product, SUM(units) FROM sales GROUP BY region, product",
            r("east", "pen", 12.0), r("east", "ink", 1.0), r("west", "pen", 4.0));
        sales.assertRows("SELECT SUM(units), product, region FROM sales GROUP BY region, product",
            r(12.0, "pen", "east"), r(1.0, "ink", "east"), r(4.0, "pen", "west"));
        // Same values in different columns are different groups.
        sales.assertRows("SELECT region, COUNT(*) FROM sales GROUP BY region", r("east", 3.0), r("west", 1.0));

        // WHERE filters before grouping; LIMIT trims after.
        db.assertRows("SELECT category, COUNT(*) FROM products WHERE price > 30 GROUP BY category",
            r("tools", 1.0), r("supplies", 1.0));
        assertEquals(1, db.rows("SELECT category, COUNT(*) FROM products GROUP BY category LIMIT 1").size(),
            "LIMIT after GROUP BY");
        assertEquals(0, db.rows("SELECT category, COUNT(*) FROM products GROUP BY category LIMIT 0").size(),
            "LIMIT 0 after GROUP BY");
        db.assertRows("SELECT MIN(name), MAX(name) FROM products GROUP BY category",
            r("Hammer", "Wrench"), r("Paint", "Paint"));
    }

    static void aggregatesOnEmpty() {
        TestDb db = TestDb.withSampleData();
        // With no GROUP BY, an aggregate query always produces exactly one row...
        db.assertRows("SELECT COUNT(*), SUM(age) FROM users WHERE age > 100", r(0.0, 0.0));
        // ...in which AVG, MIN and MAX of nothing are null.
        db.assertRows("SELECT COUNT(*), SUM(age), AVG(age), MIN(age), MAX(name) FROM users WHERE age > 100",
            r(0.0, 0.0, null, null, null));

        TestDb empty = new TestDb();
        empty.exec("CREATE TABLE e (n INT, s TEXT)");
        empty.assertRows("SELECT COUNT(*), SUM(n), AVG(n), MIN(n), MAX(s) FROM e", r(0.0, 0.0, null, null, null));
        // With GROUP BY there are no groups, so no rows at all.
        empty.assertRows("SELECT s, COUNT(*) FROM e GROUP BY s");
        empty.assertColumns("SELECT s, COUNT(*) FROM e GROUP BY s", "s", "COUNT(*)");
        db.assertRows("SELECT category, COUNT(*) FROM products WHERE price > 1000 GROUP BY category");
    }

    static void aggregateErrors() {
        TestDb db = TestDb.withSampleData();
        db.expectError("SELECT name, COUNT(*) FROM users", "must appear in GROUP BY");
        db.expectError("SELECT category, name, COUNT(*) FROM products GROUP BY category", "must appear in GROUP BY");
        db.expectError("SELECT SUM(name) FROM users", "SUM requires a numeric column");
        db.expectError("SELECT AVG(name) FROM users", "AVG requires a numeric column");
        db.expectError("SELECT * FROM users GROUP BY name", "SELECT * is not supported with GROUP BY");
        db.expectError("SELECT COUNT(*) FROM users ORDER BY age", "ORDER BY on GROUP BY / aggregate queries is not yet supported");
        db.expectError("SELECT name, COUNT(*) FROM users GROUP BY name ORDER BY name", "not yet supported");
        db.expectError("SELECT COUNT(nope) FROM users", "Unknown column 'nope'");
        db.expectError("SELECT SUM(nope) FROM users", "Unknown column 'nope'");
        db.expectError("SELECT FOO(age) FROM users", "Unknown function: FOO");
        db.expectError("SELECT SUM(*) FROM users", "only COUNT(*)");
    }

    // ---------- Joins ----------

    static void joinBasics() {
        TestDb db = TestDb.withSampleData();
        db.assertRows("SELECT users.name, orders.total FROM users JOIN orders ON users.id = orders.user_id",
            r("Alice", 75.0), r("Alice", 20.0), r("Carol", 60.0));
        db.assertColumns("SELECT users.name, orders.total FROM users JOIN orders ON users.id = orders.user_id",
            "users.name", "orders.total");

        // SELECT * gives every column of both tables, qualified, left table first.
        db.assertColumns("SELECT * FROM users JOIN orders ON users.id = orders.user_id",
            "users.id", "users.name", "users.age", "orders.id", "orders.user_id", "orders.total");
        db.assertRows("SELECT * FROM users JOIN orders ON users.id = orders.user_id",
            r(1.0, "Alice", 30.0, 100.0, 1.0, 75.0),
            r(1.0, "Alice", 30.0, 101.0, 1.0, 20.0),
            r(3.0, "Carol", 45.0, 102.0, 3.0, 60.0));

        // Both tables have an "id" column, and the qualifier tells them apart.
        db.assertRows("SELECT users.id, orders.id FROM users JOIN orders ON users.id = orders.user_id",
            r(1.0, 100.0), r(1.0, 101.0), r(3.0, 102.0));
        // The column order in the output is whatever the SELECT list says.
        db.assertRows("SELECT orders.total, users.name FROM users JOIN orders ON users.id = orders.user_id",
            r(75.0, "Alice"), r(20.0, "Alice"), r(60.0, "Carol"));
        // The right-hand table can be the one on the left of the ON.
        db.assertRows("SELECT users.name FROM users JOIN orders ON orders.user_id = users.id WHERE orders.total = 60",
            r("Carol"));
        db.expectError("SELECT * FROM users JOIN nowhere ON users.id = nowhere.id", "No such table: nowhere");
    }

    static void joinWithClauses() {
        TestDb db = TestDb.withSampleData();
        String join = " FROM users JOIN orders ON users.id = orders.user_id";

        db.assertRows("SELECT users.name, orders.total" + join + " WHERE orders.total > 50",
            r("Alice", 75.0), r("Carol", 60.0));
        db.assertRows("SELECT users.name" + join + " WHERE users.age > 40 AND orders.total < 100", r("Carol"));
        db.assertRowsInOrder("SELECT users.name, orders.total" + join + " ORDER BY orders.total DESC",
            r("Alice", 75.0), r("Carol", 60.0), r("Alice", 20.0));
        db.assertRowsInOrder("SELECT orders.id" + join + " ORDER BY users.name DESC, orders.total",
            r(102.0), r(101.0), r(100.0));
        assertEquals(2, db.rows("SELECT users.name" + join + " ORDER BY orders.total DESC LIMIT 2").size(), "LIMIT on a join");

        db.assertRows("SELECT users.name, COUNT(*), SUM(orders.total)" + join + " GROUP BY users.name",
            r("Alice", 2.0, 95.0), r("Carol", 1.0, 60.0));
        db.assertRows("SELECT COUNT(*), MAX(orders.total)" + join, r(3.0, 75.0));
        db.assertRows("SELECT COUNT(*)" + join + " WHERE users.age > 100", r(0.0));
    }

    static void joinConditions() {
        TestDb db = TestDb.withSampleData();
        // The ON condition can be anything that compares two things, not just equality.
        db.assertRows("SELECT users.name, orders.id FROM users JOIN orders ON users.id < orders.user_id",
            r("Alice", 102.0), r("Bob", 102.0));
        db.assertRows("SELECT users.name FROM users JOIN orders ON users.id = orders.user_id AND orders.total > 50",
            r("Alice"), r("Carol"));
        db.assertRows("SELECT users.name FROM users JOIN orders ON users.id = 1 AND orders.id = 101", r("Alice"));

        // No matches: no rows, but the headers are still right.
        db.assertRows("SELECT users.name FROM users JOIN orders ON users.id = orders.total");
        db.assertColumns("SELECT users.name, orders.id FROM users JOIN orders ON users.id = orders.total",
            "users.name", "orders.id");
        db.assertRows("SELECT COUNT(*) FROM users JOIN orders ON users.id = orders.total", r(0.0));

        // An empty table on either side gives an empty join.
        db.exec("CREATE TABLE empty (id INT)");
        db.assertRows("SELECT users.name FROM users JOIN empty ON users.id = empty.id");
        db.assertRows("SELECT users.name FROM empty JOIN users ON users.id = empty.id");

        db.expectError("SELECT users.name FROM users JOIN orders ON users.name = orders.total", "Type mismatch");
    }

    static void aliasesAndSelfJoins() {
        TestDb db = TestDb.withSampleData();

        // Aliases on both sides of an ordinary join.
        db.assertRows("SELECT u.name, o.total FROM users AS u JOIN orders AS o ON u.id = o.user_id",
            r("Alice", 75.0), r("Alice", 20.0), r("Carol", 60.0));
        db.assertColumns("SELECT u.name, o.total FROM users AS u JOIN orders AS o ON u.id = o.user_id", "u.name", "o.total");
        db.assertColumns("SELECT * FROM users AS u JOIN orders AS o ON u.id = o.user_id",
            "u.id", "u.name", "u.age", "o.id", "o.user_id", "o.total");
        // Only one side aliased.
        db.assertRows("SELECT u.name FROM users AS u JOIN orders ON u.id = orders.user_id WHERE orders.total = 60", r("Carol"));
        db.assertRows("SELECT users.name FROM users JOIN orders AS o ON users.id = o.user_id WHERE o.total = 60", r("Carol"));

        // Once a table is aliased, its real name no longer works.
        db.expectError("SELECT users.name FROM users AS u JOIN orders AS o ON u.id = o.user_id", "Unknown column 'users.name'");
        db.expectError("SELECT u.name FROM users AS u JOIN orders AS o ON users.id = o.user_id", "Unknown column 'users.id'");

        // Self-joins: the same table twice, told apart by their aliases.
        TestDb t = new TestDb();
        t.exec("CREATE TABLE what (num INT)");
        t.exec("INSERT INTO what VALUES (2)");
        t.exec("INSERT INTO what VALUES (3)");
        t.assertRows("SELECT a.num, b.num FROM what AS a JOIN what AS b ON a.num = b.num", r(2.0, 2.0), r(3.0, 3.0));
        t.assertRows("SELECT a.num, b.num FROM what AS a JOIN what AS b ON a.num < b.num", r(2.0, 3.0));
        t.assertRows("SELECT a.num, b.num FROM what AS a JOIN what AS b ON a.num > b.num", r(3.0, 2.0));
        t.assertColumns("SELECT a.num, b.num FROM what AS a JOIN what AS b ON a.num = b.num", "a.num", "b.num");
        assertEquals(4, t.rows("SELECT * FROM what AS a JOIN what AS b ON 1 = 1").size(), "an always-true ON gives every pairing");

        // A self-join on a table with several columns: pair each person with their manager.
        TestDb staff = new TestDb();
        staff.exec("CREATE TABLE staff (id INT, name TEXT, boss_id INT)");
        staff.exec("INSERT INTO staff VALUES (1, 'Ada', 0)");
        staff.exec("INSERT INTO staff VALUES (2, 'Ben', 1)");
        staff.exec("INSERT INTO staff VALUES (3, 'Cy', 1)");
        staff.exec("INSERT INTO staff VALUES (4, 'Di', 2)");
        staff.assertRows("SELECT e.name, m.name FROM staff AS e JOIN staff AS m ON e.boss_id = m.id",
            r("Ben", "Ada"), r("Cy", "Ada"), r("Di", "Ben"));
        staff.assertRows("SELECT m.name, COUNT(*) FROM staff AS e JOIN staff AS m ON e.boss_id = m.id GROUP BY m.name",
            r("Ada", 2.0), r("Ben", 1.0));

        // Without aliases a self-join can't tell its two sides apart: both
        // columns get the same name, so the ON compares a value with itself.
        // It "works" only by matching every pairing (the bug that motivated aliases).
        assertEquals(4, t.rows("SELECT * FROM what JOIN what ON what.num = what.num").size(),
            "an unaliased self-join degenerates into a full cross product");
    }

    static void joinNameRules() {
        TestDb db = TestDb.withSampleData();
        // After a join, columns are only known by their qualified names.
        db.expectError("SELECT users.name FROM users JOIN orders ON users.id = orders.user_id WHERE total > 1",
            "Unknown column 'total'");
        // A qualifier that matches neither table is an error.
        db.expectError("SELECT other.name FROM users JOIN orders ON users.id = orders.user_id", "Unknown column 'other.name'");
        db.expectError("SELECT users.nope FROM users JOIN orders ON users.id = orders.user_id", "Unknown column 'users.nope'");
        db.expectError("SELECT users.name FROM users JOIN orders ON users.id = orders.nope", "Unknown column 'orders.nope'");
    }

    // ---------- Gimmicks ----------

    static void vibes() {
        TestDb db = TestDb.withSampleData();
        Object[][] allUsers = { r("Alice"), r("Bob"), r("Carol") };

        // vibes = 'immaculate' makes the whole condition true...
        db.assertRows("SELECT name FROM users WHERE vibes = 'immaculate'", allUsers);
        db.assertRows("SELECT name FROM users WHERE 'immaculate' = vibes", allUsers);
        db.assertRows("SELECT name FROM users WHERE VIBES = 'IMMACULATE'", allUsers);
        db.assertRows("SELECT name FROM users WHERE Vibes = 'Immaculate'", allUsers);
        // ...even when ANDed with conditions that would exclude every row...
        db.assertRows("SELECT name FROM users WHERE age > 1000 AND vibes = 'immaculate'", allUsers);
        db.assertRows("SELECT name FROM users WHERE vibes = 'immaculate' AND age > 1000", allUsers);
        // ...or nested anywhere inside a larger condition.
        db.assertRows("SELECT name FROM users WHERE age < 0 OR age > 1000 AND name = 'x' OR vibes = 'immaculate'", allUsers);
        db.assertRows("SELECT name FROM users WHERE age = 1 AND name = 'nobody' AND id = 99 AND vibes = 'immaculate'", allUsers);
        // The rest of the condition is never evaluated, so even a broken one is ignored.
        db.assertRows("SELECT name FROM users WHERE nope = 1 AND vibes = 'immaculate'", allUsers);
        db.assertRows("SELECT name FROM users WHERE age > 'text' OR vibes = 'immaculate'", allUsers);
        // Any table qualifier is accepted on vibes.
        db.assertRows("SELECT name FROM users WHERE users.vibes = 'immaculate'", allUsers);
        db.assertRows("SELECT name FROM users WHERE vibes = 'immaculate' ORDER BY name LIMIT 2", r("Alice"), r("Bob"));

        // Only the exact pairing "vibes" = "immaculate" is special. Anything else
        // is an ordinary comparison against a column that doesn't exist.
        db.expectError("SELECT name FROM users WHERE vibes = 'terrible'", "Unknown column 'vibes'");
        db.expectError("SELECT name FROM users WHERE vibes = 'immaculate!'", "Unknown column 'vibes'");
        db.expectError("SELECT name FROM users WHERE vibes <> 'immaculate'", "Unknown column 'vibes'");
        db.expectError("SELECT name FROM users WHERE vibes > 'immaculate'", "Unknown column 'vibes'");
        db.expectError("SELECT name FROM users WHERE vibes = 1", "Unknown column 'vibes'");
        db.expectError("SELECT name FROM users WHERE mood = 'immaculate'", "Unknown column 'mood'");

        // It also overrides a join's ON condition, turning the join into every pairing.
        assertEquals(9, db.rows("SELECT * FROM users JOIN orders ON vibes = 'immaculate'").size(), "3 users x 3 orders");
        assertEquals(9, db.rows("SELECT * FROM users JOIN orders ON users.id = 99 AND vibes = 'immaculate'").size(),
            "an impossible ON is overridden");
        db.assertRows("SELECT COUNT(*) FROM users JOIN orders ON vibes = 'immaculate'", r(9.0));
        // And it applies in aggregate queries' WHERE.
        db.assertRows("SELECT COUNT(*) FROM users WHERE age > 1000 AND vibes = 'immaculate'", r(3.0));
    }

    static void chaos() {
        TestDb db = TestDb.withSampleData();

        // The result is always the same set of rows, just shuffled.
        Set<String> orderings = new HashSet<>();
        for (int run = 0; run < 60; run++) {
            List<List<Object>> rows = db.rows("SELECT name FROM products ORDER BY CHAOS");
            db.assertRows("SELECT name FROM products ORDER BY CHAOS", r("Hammer"), r("Wrench"), r("Paint"));
            orderings.add(rows.toString());
        }
        // 3 rows can be arranged 6 ways; 60 independent shuffles landing on a
        // single arrangement has a probability around 1 in 10^46.
        assertTrue(orderings.size() > 1, "CHAOS should produce different orders across runs, but saw only " + orderings);

        // With LIMIT it's a random sample: the right number of distinct rows from the table.
        Set<String> seen = new HashSet<>();
        for (int run = 0; run < 60; run++) {
            List<List<Object>> sample = db.rows("SELECT name FROM products ORDER BY CHAOS LIMIT 2");
            assertEquals(2, sample.size(), "CHAOS LIMIT 2 returns two rows");
            assertTrue(!sample.get(0).equals(sample.get(1)), "the two sampled rows are different rows");
            for (List<Object> row : sample) {
                assertTrue(List.of("Hammer", "Wrench", "Paint").contains(row.get(0)), "sampled row comes from the table: " + row);
            }
            seen.add(sample.toString());
        }
        assertTrue(seen.size() > 1, "random samples should vary, but saw only " + seen);

        // Filtering, headers and the rest still apply.
        db.assertRows("SELECT name, price FROM products WHERE price > 30 ORDER BY CHAOS", r("Wrench", 150.0), r("Paint", 40.0));
        db.assertColumns("SELECT name, price FROM products ORDER BY CHAOS", "name", "price");
        assertEquals(0, db.rows("SELECT name FROM products ORDER BY CHAOS LIMIT 0").size(), "CHAOS LIMIT 0");
        assertEquals(3, db.rows("SELECT name FROM products ORDER BY CHAOS LIMIT 50").size(), "CHAOS LIMIT beyond the row count");
        db.assertRows("SELECT name FROM products WHERE price > 1000 ORDER BY CHAOS");

        // Unlike a real ORDER BY, CHAOS is allowed on grouped and aggregate queries.
        db.assertRows("SELECT category, COUNT(*) FROM products GROUP BY category ORDER BY CHAOS",
            r("tools", 2.0), r("supplies", 1.0));
        db.assertRows("SELECT COUNT(*) FROM products ORDER BY CHAOS", r(3.0));
        assertEquals(1, db.rows("SELECT category, COUNT(*) FROM products GROUP BY category ORDER BY CHAOS LIMIT 1").size(),
            "CHAOS with GROUP BY and LIMIT");
        // ...and it really does shuffle the groups: with two groups, 60 tries
        // that all came out in one order would be a 1 in 2^59 coincidence.
        Set<String> groupOrders = new HashSet<>();
        for (int run = 0; run < 60; run++) {
            groupOrders.add(db.rows("SELECT category, COUNT(*) FROM products GROUP BY category ORDER BY CHAOS").toString());
        }
        assertEquals(2, groupOrders.size(), "both orders of the two groups should appear");

        // And on joins.
        db.assertRows("SELECT users.name, orders.total FROM users JOIN orders ON users.id = orders.user_id ORDER BY CHAOS",
            r("Alice", 75.0), r("Alice", 20.0), r("Carol", 60.0));
    }

    // ---------- Quirks, bugs, and limitations ----------

    // A mistake in a WHERE clause is only noticed when a row is actually
    // examined. On an empty table (or one the WHERE never reaches) nothing is
    // checked, so a misspelled column goes unreported.
    static void quirkErrorsNeedRows() {
        TestDb db = new TestDb();
        db.exec("CREATE TABLE e (n INT, s TEXT)");
        db.assertRows("SELECT * FROM e WHERE nonexistent = 1");
        db.assertRows("SELECT * FROM e WHERE n > 'text'");
        db.assertRows("SELECT n FROM e ORDER BY nonexistent");
        assertEquals(0, db.exec("UPDATE e SET n = 1 WHERE nonexistent = 1"), "UPDATE with a bad WHERE on an empty table");
        assertEquals(0, db.exec("DELETE FROM e WHERE nonexistent = 1"), "DELETE with a bad WHERE on an empty table");
        // The same mistakes are reported as soon as there is a row to examine.
        db.exec("INSERT INTO e VALUES (1, 'x')");
        db.expectError("SELECT * FROM e WHERE nonexistent = 1", "Unknown column 'nonexistent'");
        db.expectError("SELECT * FROM e WHERE n > 'text'", "Type mismatch");
        // The SELECT list is no different: columns are looked up while rows are
        // being projected, so with no rows a misspelled name goes unnoticed too.
        TestDb empty = new TestDb();
        empty.exec("CREATE TABLE e (n INT)");
        empty.assertRows("SELECT nonexistent FROM e");
        empty.assertColumns("SELECT nonexistent FROM e", "nonexistent");
        empty.exec("INSERT INTO e VALUES (1)");
        empty.expectError("SELECT nonexistent FROM e", "Unknown column 'nonexistent'");
    }

    // The tokenizer treats + - * / as operators and the comparison code
    // accepts any operator, so arithmetic is read as a comparison and then
    // fails when evaluated.
    static void quirkArithmetic() {
        TestDb db = TestDb.withSampleData();
        db.expectError("SELECT name FROM users WHERE age + 1", "Unknown operator: +");
        db.expectError("SELECT name FROM users WHERE age * 2", "Unknown operator: *");
        db.expectError("SELECT name FROM users WHERE age - 1", "Unknown operator: -");
        db.expectError("SELECT name FROM users WHERE age / 2", "Unknown operator: /");
        // Followed by a comparison, it doesn't parse at all.
        db.expectError("SELECT name FROM users WHERE age + 1 > 30", "Expected EOF");
    }

    // Joining three tables. Each join's result keeps its columns qualified by
    // the original table names, so a later ON, WHERE or SELECT can still name a
    // table from an EARLIER join. (This used to fail: the second join
    // re-labelled columns the first join had already labelled, so "orders.id"
    // could no longer be found.)
    static void threeTableJoin() {
        TestDb db = TestDb.withSampleData();
        db.exec("CREATE TABLE items (order_id INT, sku TEXT)");
        db.exec("INSERT INTO items VALUES (100, 'nail')");
        db.exec("INSERT INTO items VALUES (102, 'screw')");

        // A single join.
        db.assertRows("SELECT orders.id, items.sku FROM orders JOIN items ON orders.id = items.order_id",
            r(100.0, "nail"), r(102.0, "screw"));
        // Chaining a second one, with the ON naming a table from the first join.
        db.assertRows("SELECT users.name, items.sku FROM users JOIN orders ON users.id = orders.user_id "
            + "JOIN items ON orders.id = items.order_id", r("Alice", "nail"), r("Carol", "screw"));
        // A column from the middle table can be selected too.
        db.assertRows("SELECT users.name, orders.total, items.sku FROM users JOIN orders ON users.id = orders.user_id "
            + "JOIN items ON orders.id = items.order_id", r("Alice", 75.0, "nail"), r("Carol", 60.0, "screw"));
        // SELECT * keeps every column of every table, in join order.
        db.assertColumns("SELECT * FROM users JOIN orders ON users.id = orders.user_id JOIN items ON orders.id = items.order_id",
            "users.id", "users.name", "users.age", "orders.id", "orders.user_id", "orders.total",
            "items.order_id", "items.sku");
        // WHERE can name any of the three tables.
        db.assertRows("SELECT users.name, items.sku FROM users JOIN orders ON users.id = orders.user_id "
            + "JOIN items ON orders.id = items.order_id WHERE orders.total > 70", r("Alice", "nail"));
        // A second join whose ON only names the newest table keeps working,
        db.assertRows("SELECT items.sku FROM users JOIN orders ON users.id = orders.user_id JOIN items ON items.order_id = 100",
            r("nail"), r("nail"), r("nail"));
        // and so does naming an earlier table in the SELECT list.
        db.assertRows("SELECT users.name FROM users JOIN orders ON users.id = orders.user_id JOIN items ON items.order_id = 100",
            r("Alice"), r("Alice"), r("Carol"));
        // A misspelled column is still reported.
        db.expectError("SELECT users.name FROM users JOIN orders ON users.id = orders.user_id "
            + "JOIN items ON orders.nope = items.order_id", "Unknown column 'orders.nope'");
    }

    // KNOWN BUG: the parser accepts "FROM users AS u" with no JOIN, and
    // "SELECT u.name" is the natural thing to write after it, but the alias is
    // only wired up for joins. Referring to the alias fails, and referring to
    // the real table name (which standard SQL would reject) is what works.
    // When this is fixed, "u.name" should work and "users.name" should fail.
    static void bugAliasWithoutJoin() {
        TestDb db = TestDb.withSampleData();
        db.expectError("SELECT u.name FROM users AS u", "Unknown column 'u.name' in table users");
        db.expectError("SELECT name FROM users AS u WHERE u.age > 20", "Unknown column 'u.age'");
        // Unqualified names, and the real table name, are what work today.
        db.assertRows("SELECT name FROM users AS u WHERE age > 20", r("Alice"), r("Carol"));
        db.assertRows("SELECT users.name FROM users AS u WHERE users.age > 40", r("Carol"));
    }

    // LIMITATION: in real SQL an unambiguous column name needs no qualifier
    // even in a join ("SELECT name FROM users JOIN orders ..."). Here, joined
    // columns are only known by their qualified names.
    static void limitationUnqualifiedAfterJoin() {
        TestDb db = TestDb.withSampleData();
        String join = " FROM users JOIN orders ON users.id = orders.user_id";
        db.expectError("SELECT name" + join, "Unknown column 'name' in table users_JOIN_orders");
        db.expectError("SELECT users.name" + join + " WHERE total > 1", "Unknown column 'total'");
        db.expectError("SELECT users.name" + join + " ORDER BY total", "Unknown column 'total'");
        db.expectError("SELECT total, COUNT(*)" + join + " GROUP BY total", "Unknown column 'total'");
        // Qualified names always work.
        db.assertRows("SELECT users.name" + join + " WHERE orders.total > 70", r("Alice"));
    }
}
