package Engine;

import static Engine.TestKit.*;

import java.util.List;

/**
 * Tests for BonkParser: what text is accepted, what tree it produces, and
 * how it complains about the rest. These check the grammar only; nothing is
 * executed. Run on its own with
 *   java Engine.ParserTests
 */
public class ParserTests {

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        run("parser: SELECT lists", ParserTests::selectLists);
        run("parser: keywords ignore case, identifiers keep it", ParserTests::caseRules);
        run("parser: whitespace", ParserTests::whitespace);
        run("parser: WHERE structure and precedence", ParserTests::whereStructure);
        run("parser: comparison operators and operands", ParserTests::operatorsAndOperands);
        run("parser: literals", ParserTests::literals);
        run("parser: JOIN and aliases", ParserTests::joinsAndAliases);
        run("parser: GROUP BY, ORDER BY, CHAOS, LIMIT", ParserTests::trailingClauses);
        run("parser: clauses must come in order", ParserTests::clauseOrder);
        run("parser: CREATE TABLE and type names", ParserTests::createTable);
        run("parser: INSERT", ParserTests::insert);
        run("parser: UPDATE", ParserTests::update);
        run("parser: DELETE", ParserTests::delete);
        run("parser: error messages", ParserTests::errorMessages);
        run("parser: LIMITATIONS (unsupported syntax is rejected)", ParserTests::limitations);
    }

    // ---------- Helpers ----------

    private static BonkParser.SelectStmt select(String sql) {
        BonkParser.Stmt stmt = BonkParser.parseSql(sql);
        assertTrue(stmt instanceof BonkParser.SelectStmt, "[" + sql + "] should parse as a SELECT but was " + stmt);
        return (BonkParser.SelectStmt) stmt;
    }

    private static BonkParser.Column column(Object expr) {
        assertTrue(expr instanceof BonkParser.Column, "expected a column but was " + expr);
        return (BonkParser.Column) expr;
    }

    private static BonkParser.BinaryOp binary(Object expr) {
        assertTrue(expr instanceof BonkParser.BinaryOp, "expected an operator node but was " + expr);
        return (BonkParser.BinaryOp) expr;
    }

    private static BonkParser.Literal literal(Object expr) {
        assertTrue(expr instanceof BonkParser.Literal, "expected a literal but was " + expr);
        return (BonkParser.Literal) expr;
    }

    private static void assertColumn(Object expr, String table, String name, String message) {
        BonkParser.Column c = column(expr);
        assertEquals(table, c.table, message + " (table qualifier)");
        assertEquals(name, c.name, message + " (name)");
    }

    // The text must be rejected, and the message must contain the fragment.
    private static void parseFails(String sql, String fragment) {
        try {
            BonkParser.parseSql(sql);
        } catch (RuntimeException e) {
            String message = String.valueOf(e.getMessage());
            assertTrue(message.contains(fragment),
                "[" + sql + "] was rejected, but the message should contain \"" + fragment + "\" and was: " + message);
            return;
        }
        throw new AssertionError("[" + sql + "] should have been rejected (\"" + fragment + "\") but parsed");
    }

    // ---------- SELECT ----------

    static void selectLists() {
        BonkParser.SelectStmt s = select("SELECT * FROM t");
        assertEquals(1, s.columns.size(), "star: one item");
        assertTrue(s.columns.get(0) instanceof BonkParser.Star, "star: it's the star");
        assertEquals("t", s.table, "table name");
        assertEquals(null, s.tableAlias, "no alias");
        assertTrue(s.joins.isEmpty(), "no joins");
        assertEquals(null, s.where, "no where");
        assertTrue(s.groupBy.isEmpty(), "no group by");
        assertTrue(s.orderBy.isEmpty(), "no order by");
        assertTrue(!s.chaos, "no chaos");
        assertEquals(null, s.limit, "no limit");

        s = select("SELECT a, t.b, c FROM t");
        assertEquals(3, s.columns.size(), "three columns");
        assertColumn(s.columns.get(0), null, "a", "first");
        assertColumn(s.columns.get(1), "t", "b", "qualified second");
        assertColumn(s.columns.get(2), null, "c", "third");

        s = select("SELECT COUNT(*), sum(price), Avg(t.x), MIN(y), max(z) FROM t");
        assertEquals(5, s.columns.size(), "five aggregates");
        BonkParser.AggregateCall count = (BonkParser.AggregateCall) s.columns.get(0);
        assertEquals("COUNT", count.function, "COUNT");
        assertTrue(count.star && count.argument == null, "COUNT(*) has no argument");
        BonkParser.AggregateCall sum = (BonkParser.AggregateCall) s.columns.get(1);
        assertEquals("SUM", sum.function, "function names are upper-cased");
        assertColumn(sum.argument, null, "price", "SUM argument");
        BonkParser.AggregateCall avg = (BonkParser.AggregateCall) s.columns.get(2);
        assertEquals("AVG", avg.function, "mixed-case function name");
        assertColumn(avg.argument, "t", "x", "qualified aggregate argument");
        assertEquals("MIN", ((BonkParser.AggregateCall) s.columns.get(3)).function, "MIN");
        assertEquals("MAX", ((BonkParser.AggregateCall) s.columns.get(4)).function, "MAX");

        s = select("SELECT name, COUNT(*) FROM t");
        assertTrue(s.columns.get(0) instanceof BonkParser.Column, "plain column next to an aggregate");
        assertTrue(s.columns.get(1) instanceof BonkParser.AggregateCall, "aggregate");
    }

    static void caseRules() {
        BonkParser.SelectStmt s = select("sElEcT Name FrOm Users wHeRe Age > 1 oRdEr bY Name dEsC lImIt 2");
        assertColumn(s.columns.get(0), null, "Name", "identifiers keep their case");
        assertEquals("Users", s.table, "table name keeps its case");
        assertTrue(s.orderBy.get(0).descending, "DESC in odd case");
        assertEquals(2, s.limit, "LIMIT in odd case");

        // Identifiers that merely contain keywords are ordinary identifiers.
        s = select("SELECT selected, fromage, order_total FROM wherever");
        assertColumn(s.columns.get(1), null, "fromage", "keyword prefix is not a keyword");
        assertEquals("wherever", s.table, "table named like a keyword prefix");
    }

    static void whitespace() {
        BonkParser.SelectStmt s = select("  SELECT\n\ta,\n  b\nFROM\t t \n WHERE a=1  ");
        assertEquals(2, s.columns.size(), "columns across lines and tabs");
        assertEquals("t", s.table, "table");
        binary(s.where);

        // No spaces needed around operators or punctuation.
        s = select("SELECT a,b FROM t WHERE a<=1 AND b>='x'");
        assertEquals("AND", binary(s.where).op, "operators without spaces");
    }

    static void whereStructure() {
        BonkParser.BinaryOp b = binary(select("SELECT a FROM t WHERE a = 1").where);
        assertEquals("=", b.op, "simple comparison");
        assertColumn(b.left, null, "a", "left");
        assertEquals(1.0, literal(b.right).value, "right");

        // AND binds tighter than OR: a = 1 OR (b = 2 AND c = 3)
        b = binary(select("SELECT a FROM t WHERE a = 1 OR b = 2 AND c = 3").where);
        assertEquals("OR", b.op, "OR is the top operator");
        assertEquals("=", binary(b.left).op, "left of OR is the plain comparison");
        assertEquals("AND", binary(b.right).op, "right of OR is the AND");

        // ...and the mirror image: (a = 1 AND b = 2) OR c = 3
        b = binary(select("SELECT a FROM t WHERE a = 1 AND b = 2 OR c = 3").where);
        assertEquals("OR", b.op, "OR on top again");
        assertEquals("AND", binary(b.left).op, "AND is on the left");

        // Same-level operators group to the left: (a AND b) AND c
        b = binary(select("SELECT a FROM t WHERE a = 1 AND b = 2 AND c = 3").where);
        assertEquals("AND", b.op, "top is AND");
        assertEquals("AND", binary(b.left).op, "grouped to the left");
        assertEquals("=", binary(b.right).op, "last comparison on the right");
    }

    static void operatorsAndOperands() {
        for (String op : new String[] { "=", "<>", "<", ">", "<=", ">=" }) {
            BonkParser.BinaryOp b = binary(select("SELECT a FROM t WHERE a " + op + " 1").where);
            assertEquals(op, b.op, "operator " + op);
        }

        // A comparison's two sides can each be a column or a literal.
        assertColumn(binary(select("SELECT a FROM t WHERE 1 = a").where).right, null, "a", "literal on the left");
        assertColumn(binary(select("SELECT a FROM t WHERE a = b").where).right, null, "b", "column on both sides");
        assertEquals("y", literal(binary(select("SELECT a FROM t WHERE 'x' = 'y'").where).right).value, "two literals");
        assertColumn(binary(select("SELECT a FROM t WHERE t.a = u.b").where).left, "t", "a", "qualified columns");
    }

    static void literals() {
        assertEquals(7.0, literal(binary(select("SELECT a FROM t WHERE a = 7").where).right).value, "integer is a Double");
        assertEquals(2.5, literal(binary(select("SELECT a FROM t WHERE a = 2.5").where).right).value, "decimal");
        assertEquals("hi", literal(binary(select("SELECT a FROM t WHERE a = 'hi'").where).right).value, "string");
        assertEquals("", literal(binary(select("SELECT a FROM t WHERE a = ''").where).right).value, "empty string");
        assertEquals("it's", literal(binary(select("SELECT a FROM t WHERE a = 'it''s'").where).right).value,
            "doubled quote is an escaped quote");
        assertEquals("a b;c", literal(binary(select("SELECT a FROM t WHERE a = 'a b;c'").where).right).value,
            "spaces and semicolons inside a string");
        assertEquals("caf\u00e9 \u65e5\u672c", literal(binary(
            select("SELECT a FROM t WHERE a = 'caf\u00e9 \u65e5\u672c'").where).right).value, "non-ASCII text");
        assertEquals("SELECT FROM", literal(binary(select("SELECT a FROM t WHERE a = 'SELECT FROM'").where).right).value,
            "keywords inside a string are just text");
    }

    static void joinsAndAliases() {
        BonkParser.SelectStmt s = select("SELECT * FROM a JOIN b ON a.id = b.a_id JOIN c ON b.id = c.b_id");
        assertEquals(2, s.joins.size(), "two joins");
        assertEquals("b", s.joins.get(0).table, "first join table");
        assertEquals("c", s.joins.get(1).table, "second join table");
        assertEquals(null, s.joins.get(0).alias, "no alias");
        assertEquals("=", binary(s.joins.get(1).on).op, "ON condition");

        s = select("SELECT x.id FROM a AS x JOIN b AS y ON x.id = y.a_id");
        assertEquals("a", s.table, "base table is the real name");
        assertEquals("x", s.tableAlias, "base alias");
        assertEquals("b", s.joins.get(0).table, "join table is the real name");
        assertEquals("y", s.joins.get(0).alias, "join alias");

        // An alias can be given without a join, and on only some tables.
        s = select("SELECT x.id FROM a AS x");
        assertEquals("x", s.tableAlias, "alias on a lone table");
        s = select("SELECT * FROM a JOIN b AS y ON a.id = y.a_id");
        assertEquals(null, s.tableAlias, "base table unaliased");
        assertEquals("y", s.joins.get(0).alias, "only the join aliased");

        // AS is required: a bare second word is not an alias.
        parseFails("SELECT * FROM a x", "Expected EOF");
    }

    static void trailingClauses() {
        BonkParser.SelectStmt s = select("SELECT a, COUNT(*) FROM t GROUP BY a, t.b");
        assertEquals(2, s.groupBy.size(), "two group-by columns");
        assertColumn(s.groupBy.get(1), "t", "b", "qualified group-by column");

        s = select("SELECT a FROM t ORDER BY a, b DESC, c ASC, d");
        assertEquals(4, s.orderBy.size(), "four sort keys");
        assertTrue(!s.orderBy.get(0).descending, "default is ascending");
        assertTrue(s.orderBy.get(1).descending, "DESC");
        assertTrue(!s.orderBy.get(2).descending, "explicit ASC");
        assertTrue(!s.chaos, "an ordinary ORDER BY isn't chaos");

        s = select("SELECT a FROM t ORDER BY t.b DESC");
        assertColumn(s.orderBy.get(0).column, "t", "b", "qualified sort column");

        s = select("SELECT a FROM t ORDER BY CHAOS LIMIT 3");
        assertTrue(s.chaos, "CHAOS sets the flag");
        assertTrue(s.orderBy.isEmpty(), "CHAOS replaces the sort keys");
        assertEquals(3, s.limit, "LIMIT after CHAOS");
        assertTrue(select("SELECT a FROM t ORDER BY chaos").chaos, "CHAOS in lower case");
        assertTrue(!select("SELECT a FROM t ORDER BY chaotic").chaos, "a column that merely starts with chaos");

        s = select("SELECT a FROM t WHERE a = 1 GROUP BY a ORDER BY a LIMIT 10");
        assertEquals(10, s.limit, "every clause together");
        assertEquals(0, select("SELECT a FROM t LIMIT 0").limit, "LIMIT 0 is allowed");
    }

    static void clauseOrder() {
        parseFails("SELECT a FROM t LIMIT 1 WHERE a = 1", "Expected EOF");
        parseFails("SELECT a FROM t ORDER BY a WHERE a = 1", "Expected EOF");
        parseFails("SELECT a FROM t ORDER BY a GROUP BY a", "Expected EOF");
        parseFails("SELECT a FROM t WHERE a = 1 JOIN u ON t.a = u.a", "Expected EOF");
        parseFails("SELECT a FROM t LIMIT 1 ORDER BY a", "Expected EOF");
    }

    // ---------- CREATE / INSERT / UPDATE / DELETE ----------

    static void createTable() {
        BonkParser.Stmt stmt = BonkParser.parseSql("CREATE TABLE people (id INT, name TEXT, score DOUBLE)");
        assertTrue(stmt instanceof BonkParser.CreateTableStmt, "parses as CREATE TABLE");
        BonkParser.CreateTableStmt c = (BonkParser.CreateTableStmt) stmt;
        assertEquals("people", c.table, "table name");
        assertEquals(3, c.columns.size(), "three columns");
        assertEquals("id", c.columns.get(0).name, "first column name");
        assertEquals("DOUBLE", c.columns.get(0).type, "INT is stored as DOUBLE");
        assertEquals("STRING", c.columns.get(1).type, "TEXT is stored as STRING");

        for (String name : new String[] { "INT", "INTEGER", "DOUBLE", "FLOAT", "NUMBER", "NUM", "int", "Double" }) {
            BonkParser.CreateTableStmt t = (BonkParser.CreateTableStmt) BonkParser.parseSql("CREATE TABLE t (a " + name + ")");
            assertEquals("DOUBLE", t.columns.get(0).type, "numeric type spelling " + name);
        }
        for (String name : new String[] { "TEXT", "VARCHAR", "STRING", "STR", "CHAR", "text", "Varchar" }) {
            BonkParser.CreateTableStmt t = (BonkParser.CreateTableStmt) BonkParser.parseSql("CREATE TABLE t (a " + name + ")");
            assertEquals("STRING", t.columns.get(0).type, "text type spelling " + name);
        }

        // Type names aren't reserved, so they work as column or table names.
        BonkParser.CreateTableStmt odd = (BonkParser.CreateTableStmt) BonkParser.parseSql("CREATE TABLE int (int INT, text TEXT)");
        assertEquals("int", odd.table, "a table named like a type");
        assertEquals("int", odd.columns.get(0).name, "a column named like a type");

        parseFails("CREATE TABLE t (a BOOLEAN)", "Unknown column type: BOOLEAN");
        parseFails("CREATE TABLE what (int)", "Expected IDENT but got RPAREN");
    }

    static void insert() {
        BonkParser.InsertStmt i = (BonkParser.InsertStmt) BonkParser.parseSql("INSERT INTO t VALUES (1, 'a', 2.5)");
        assertEquals("t", i.table, "table");
        assertTrue(i.columns.isEmpty(), "no column list");
        assertEquals(3, i.values.size(), "three values");
        assertEquals(1.0, i.values.get(0).value, "number");
        assertEquals("a", i.values.get(1).value, "string");

        i = (BonkParser.InsertStmt) BonkParser.parseSql("insert into t (b, a) values ('x', 1)");
        assertEquals(List.of("b", "a"), i.columns, "column list, in the order given");
        assertEquals("x", i.values.get(0).value, "values follow the same order");
    }

    static void update() {
        BonkParser.UpdateStmt u = (BonkParser.UpdateStmt) BonkParser.parseSql("UPDATE t SET a = 1, b = 'x' WHERE id = 5");
        assertEquals("t", u.table, "table");
        assertEquals(2, u.assignments.size(), "two assignments");
        assertEquals("a", u.assignments.get(0).column, "first column");
        assertEquals(1.0, u.assignments.get(0).value.value, "first value");
        assertEquals("x", u.assignments.get(1).value.value, "second value");
        assertEquals("=", binary(u.where).op, "WHERE");

        u = (BonkParser.UpdateStmt) BonkParser.parseSql("UPDATE t SET a = 1");
        assertEquals(null, u.where, "WHERE is optional");
    }

    static void delete() {
        BonkParser.DeleteStmt d = (BonkParser.DeleteStmt) BonkParser.parseSql("DELETE FROM t WHERE a > 1 AND b = 'x'");
        assertEquals("t", d.table, "table");
        assertEquals("AND", binary(d.where).op, "WHERE");
        assertEquals(null, ((BonkParser.DeleteStmt) BonkParser.parseSql("DELETE FROM t")).where, "WHERE is optional");
    }

    // ---------- Errors ----------

    static void errorMessages() {
        // Where the statement can't even start.
        parseFails("", "Expected SELECT, CREATE, INSERT, DELETE, or UPDATE, got EOF");
        parseFails("   ", "got EOF");
        parseFails("DROP TABLE t", "Expected SELECT, CREATE, INSERT, DELETE, or UPDATE, got IDENT");
        parseFails("42", "got NUMBER");

        // SELECT
        parseFails("SELECT", "Expected IDENT but got EOF");
        parseFails("SELECT a", "Expected FROM but got EOF");
        parseFails("SELECT a FROM", "Expected IDENT but got EOF");
        parseFails("SELECT FROM a", "Expected IDENT but got FROM");
        parseFails("SELECT a, FROM t", "Expected IDENT but got FROM");
        parseFails("SELECT a FROM t extra", "Expected EOF but got IDENT");
        parseFails("SELECT a FROM t WHERE", "Expected column or value, got EOF");
        parseFails("SELECT a FROM t WHERE a", "Expected OP but got EOF");
        parseFails("SELECT a FROM t WHERE a =", "Expected column or value, got EOF");
        parseFails("SELECT a FROM t WHERE a = 1 AND", "Expected column or value, got EOF");
        parseFails("SELECT a FROM t JOIN u", "Expected ON but got EOF");
        parseFails("SELECT a FROM t JOIN u ON", "Expected column or value, got EOF");
        parseFails("SELECT a FROM t LIMIT", "Expected NUMBER but got EOF");
        parseFails("SELECT a FROM t LIMIT x", "Expected NUMBER but got IDENT");
        parseFails("SELECT a FROM t ORDER a", "Expected BY but got IDENT");
        parseFails("SELECT a FROM t ORDER BY", "Expected IDENT but got EOF");
        parseFails("SELECT a FROM t GROUP a", "Expected BY but got IDENT");
        parseFails("SELECT a FROM t AS", "Expected IDENT but got EOF");
        parseFails("SELECT COUNT(a FROM t", "Expected RPAREN but got FROM");
        parseFails("SELECT COUNT( FROM t", "Expected IDENT but got FROM");
        parseFails("SELECT FOO(a) FROM t", "Unknown function: FOO");
        parseFails("SELECT SUM(*) FROM t", "only COUNT(*)");

        // CREATE TABLE
        parseFails("CREATE t (a INT)", "Expected TABLE but got IDENT");
        parseFails("CREATE TABLE", "Expected IDENT but got EOF");
        parseFails("CREATE TABLE t", "Expected LPAREN but got EOF");
        parseFails("CREATE TABLE t ()", "Expected IDENT but got RPAREN");
        parseFails("CREATE TABLE t (a INT,)", "Expected IDENT but got RPAREN");
        parseFails("CREATE TABLE t (a INT", "Expected RPAREN but got EOF");
        parseFails("CREATE TABLE t (a INT) extra", "Expected EOF but got IDENT");

        // INSERT
        parseFails("INSERT t VALUES (1)", "Expected INTO but got IDENT");
        parseFails("INSERT INTO VALUES (1)", "Expected IDENT but got VALUES");
        parseFails("INSERT INTO t (1)", "Expected IDENT but got NUMBER");
        parseFails("INSERT INTO t", "Expected VALUES but got EOF");
        parseFails("INSERT INTO t VALUES", "Expected LPAREN but got EOF");
        parseFails("INSERT INTO t VALUES ()", "Expected a literal value in VALUES, got RPAREN");
        parseFails("INSERT INTO t VALUES (a)", "Expected a literal value in VALUES, got IDENT");
        parseFails("INSERT INTO t VALUES (1", "Expected RPAREN but got EOF");
        parseFails("INSERT INTO t VALUES (1,)", "Expected a literal value in VALUES, got RPAREN");

        // UPDATE / DELETE
        parseFails("UPDATE t a = 1", "Expected SET but got IDENT");
        parseFails("UPDATE t SET", "Expected IDENT but got EOF");
        parseFails("UPDATE t SET a", "Expected OP but got EOF");
        parseFails("UPDATE t SET a > 1", "Expected '=' in SET clause");
        parseFails("UPDATE t SET a =", "Expected a literal value in VALUES, got EOF");
        parseFails("UPDATE t SET a = b", "Expected a literal value in VALUES, got IDENT");
        parseFails("UPDATE t SET a = 1,", "Expected IDENT but got EOF");
        parseFails("DELETE t", "Expected FROM but got IDENT");
        parseFails("DELETE FROM", "Expected IDENT but got EOF");

        // Characters the lexer doesn't know at all.
        parseFails("SELECT @ FROM t", "Unexpected character");
        parseFails("SELECT a FROM t WHERE a = 'unterminated", "Unexpected character");
        parseFails("SELECT a FROM t WHERE a != 1", "Unexpected character");
        parseFails("SELECT a FROM t;", "Unexpected character");
    }

    // Things a reader might expect to work, but which the grammar doesn't
    // support yet. Each one is rejected today. When one of these features is
    // added, its line here will start failing: that is the cue to delete the
    // line and write a real test for the new feature.
    static void limitations() {
        parseFails("SELECT a FROM t WHERE (a = 1)", "Expected column or value, got LPAREN");
        parseFails("SELECT a FROM t WHERE (a = 1 OR b = 2) AND c = 3", "got LPAREN");
        parseFails("SELECT a FROM t WHERE a = -1", "Expected column or value, got OP");
        parseFails("INSERT INTO t VALUES (-1)", "Expected a literal value in VALUES, got OP");
        parseFails("UPDATE t SET a = -1", "Expected a literal value in VALUES, got OP");
        parseFails("SELECT a FROM t -- a comment", "Expected EOF but got OP");
        parseFails("SELECT a /* inline */ FROM t", "Expected FROM but got OP");
        parseFails("SELECT a\r\nFROM t", "Unexpected character"); // the lexer doesn't treat a carriage return as whitespace
        parseFails("SELECT a FROM t GROUP BY a HAVING COUNT(*) > 1", "Expected EOF but got IDENT");
        parseFails("SELECT a FROM t LEFT JOIN u ON t.a = u.a", "Expected EOF but got IDENT");
        parseFails("SELECT a FROM t INNER JOIN u ON t.a = u.a", "Expected EOF but got IDENT");
        parseFails("SELECT 1", "Expected IDENT but got NUMBER");
        parseFails("SELECT DISTINCT a FROM t", "Expected FROM but got IDENT");
        parseFails("SELECT a AS b FROM t", "Expected FROM but got AS");
        parseFails("SELECT a FROM t WHERE NOT a = 1", "Expected column or value, got NOT");
        parseFails("SELECT a FROM t WHERE a IS NULL", "Expected OP but got IDENT");
        parseFails("SELECT a FROM t WHERE a IN (1, 2)", "Expected OP but got IDENT");
        parseFails("SELECT a FROM t WHERE a LIKE 'x%'", "Expected OP but got IDENT");
        parseFails("INSERT INTO t VALUES (1), (2)", "Expected EOF but got COMMA");
        parseFails("INSERT INTO t VALUES (NULL)", "Expected a literal value in VALUES, got IDENT");
        parseFails("UPDATE t SET a = a + 1", "Expected a literal value in VALUES, got IDENT");
        parseFails("SELECT a FROM t LIMIT 5 OFFSET 2", "Expected EOF but got IDENT");
        parseFails("SELECT a FROM t ORDER BY a NULLS FIRST", "Expected EOF but got IDENT");
        parseFails("DROP TABLE t", "Expected SELECT, CREATE, INSERT, DELETE, or UPDATE");
        parseFails("CREATE TABLE t (a INT PRIMARY KEY)", "Expected RPAREN but got IDENT");
        parseFails("CREATE TABLE IF NOT EXISTS t (a INT)", "Expected LPAREN but got NOT"); // "IF" is read as the table name
        parseFails("ALTER TABLE t ADD COLUMN b INT", "Expected SELECT, CREATE, INSERT, DELETE, or UPDATE");
        parseFails("SELECT a FROM t UNION SELECT a FROM u", "Expected EOF but got IDENT");
        parseFails("SELECT a FROM (SELECT a FROM t)", "Expected IDENT but got LPAREN");
    }
}
