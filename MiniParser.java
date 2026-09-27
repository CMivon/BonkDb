
import java.util.*;
import java.util.regex.*;

/**
 * Minimal SQL parser example: lexer + recursive descent parser for
 *     SELECT <col, col, *> FROM <table> [WHERE <condition>]
 *
 * Teaching skeleton, not production code -- shows the *shape* of a real
 * parser so you can extend it (INSERT, JOIN, etc).
 */
public class MiniParser {

    // ---------- Stage 1: Lexer ----------

    enum TokenType {
        SELECT, FROM, WHERE, AND, OR, NOT, JOIN, ON, LIMIT, ORDER, BY, ASC, DESC, GROUP,
        CREATE, TABLE,
        IDENT, NUMBER, STRING, OP, COMMA, DOT, LPAREN, RPAREN, EOF
    }

    static final Set<String> KEYWORDS = Set.of(
        "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "JOIN", "ON", "LIMIT",
        "ORDER", "BY", "ASC", "DESC", "GROUP", "CREATE", "TABLE"
    );

    // Aggregate function names are NOT reserved keywords -- like real SQL,
    // they're ordinary identifiers that happen to be followed by '('.
    static final Set<String> AGGREGATE_FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");

    // Column type names aren't reserved keywords either -- recognized
    // positionally in CREATE TABLE, same idea as aggregate function names.
    // The storage layer only really has two types under the hood, so every
    // common SQL spelling collapses down to one of "DOUBLE" or "STRING".
    static final Set<String> NUMERIC_TYPE_NAMES = Set.of("INT", "INTEGER", "DOUBLE", "FLOAT", "NUMBER", "NUM");
    static final Set<String> TEXT_TYPE_NAMES = Set.of("TEXT", "VARCHAR", "STRING", "STR", "CHAR");

    static String normalizeTypeName(String raw) {
        String upper = raw.toUpperCase();
        if (NUMERIC_TYPE_NAMES.contains(upper)) return "DOUBLE";
        if (TEXT_TYPE_NAMES.contains(upper)) return "STRING";
        throw new RuntimeException("Unknown column type: " + raw
            + " (expected one of " + NUMERIC_TYPE_NAMES + " or " + TEXT_TYPE_NAMES + ")");
    }

    static class Token {
        final TokenType type;
        final String value;
        Token(TokenType type, String value) { this.type = type; this.value = value; }
        public String toString() { return type + "(" + value + ")"; }
    }

    // Order matters: longer operators before shorter ones, STRING before IDENT, etc.
    static final Pattern TOKEN_PATTERN = Pattern.compile(
        "(?<NUMBER>\\d+(\\.\\d+)?)" +
        "|(?<STRING>'([^']|'')*')" +
        "|(?<IDENT>[A-Za-z_][A-Za-z0-9_]*)" +
        "|(?<OP><>|<=|>=|=|<|>|\\+|-|\\*|/)" +
        "|(?<COMMA>,)" +
        "|(?<DOT>\\.)" +
        "|(?<LPAREN>\\()" +
        "|(?<RPAREN>\\))" +
        "|(?<SKIP>[ \\t\\n]+)"
    );

    static List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(sql);
        int pos = 0;
        while (pos < sql.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new RuntimeException("Unexpected character at position " + pos + ": " + sql.charAt(pos));
            }
            String kind = kindOf(m);
            String value = m.group();
            pos = m.end();
            if (kind.equals("SKIP")) continue;

            TokenType type;
            if (kind.equals("IDENT") && KEYWORDS.contains(value.toUpperCase())) {
                type = TokenType.valueOf(value.toUpperCase()); // promote to keyword
            } else {
                type = TokenType.valueOf(kind);
            }
            tokens.add(new Token(type, value));
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return tokens;
    }

    // Helper: find which named group matched
    static String kindOf(Matcher m) {
        for (String name : new String[]{"NUMBER", "STRING", "IDENT", "OP", "COMMA", "DOT", "LPAREN", "RPAREN", "SKIP"}) {
            if (m.group(name) != null) return name;
        }
        throw new RuntimeException("No token type matched");
    }

    // ---------- AST node definitions ----------

    // Marker for any top-level statement (SELECT, CREATE TABLE, ...) so
    // parseSql() can return whichever kind it parsed, and callers dispatch
    // on the concrete type to decide what to do with it.
    interface Stmt {}

    interface Expr {}

    static class Column implements Expr {
        final String table; // nullable -- e.g. "users" in "users.id", null for unqualified "id"
        final String name;
        Column(String name) { this(null, name); }
        Column(String table, String name) { this.table = table; this.name = name; }
        public String toString() { return "Column(" + (table != null ? table + "." : "") + name + ")"; }
        // Clean "table.name" / "name" form for user-facing output (e.g. CLI headers),
        // as opposed to toString()'s "Column(...)" debug form.
        String display() { return (table != null ? table + "." : "") + name; }
    }

    static class Star implements Expr {
        public String toString() { return "Star()"; }
    }

    static class Literal implements Expr {
        final Object value; // Double or String
        Literal(Object value) { this.value = value; }
        public String toString() { return "Literal(" + value + ")"; }
    }

    static class BinaryOp implements Expr {
        final String op;
        final Expr left, right;
        BinaryOp(String op, Expr left, Expr right) { this.op = op; this.left = left; this.right = right; }
        public String toString() { return "BinaryOp(" + op + ", " + left + ", " + right + ")"; }
    }

    static class AggregateCall implements Expr {
        final String function; // "COUNT", "SUM", "AVG", "MIN", "MAX"
        final Column argument;  // null when star is true (COUNT(*))
        final boolean star;
        AggregateCall(String function, Column argument, boolean star) {
            this.function = function; this.argument = argument; this.star = star;
        }
        public String toString() {
            return function + "(" + (star ? "*" : argument.display()) + ")";
        }
    }

    static class Join {
        final String table;
        final Expr on;
        Join(String table, Expr on) { this.table = table; this.on = on; }
        public String toString() { return "Join(table=" + table + ", on=" + on + ")"; }
    }

    static class OrderByItem {
        final Column column;
        final boolean descending;
        OrderByItem(Column column, boolean descending) { this.column = column; this.descending = descending; }
        public String toString() { return column + (descending ? " DESC" : " ASC"); }
    }

    static class SelectStmt implements Stmt {
        final List<Expr> columns;
        final String table;
        final List<Join> joins;
        final Expr where; // nullable
        final List<Column> groupBy; // empty if no GROUP BY
        final List<OrderByItem> orderBy; // empty if no ORDER BY (and always empty when chaos is true)
        final boolean chaos; // true for "ORDER BY CHAOS" -- shuffle instead of sort
        final Integer limit; // nullable
        SelectStmt(List<Expr> columns, String table, List<Join> joins, Expr where,
                   List<Column> groupBy, List<OrderByItem> orderBy, boolean chaos, Integer limit) {
            this.columns = columns; this.table = table; this.joins = joins; this.where = where;
            this.groupBy = groupBy; this.orderBy = orderBy; this.chaos = chaos; this.limit = limit;
        }
        public String toString() {
            return "SelectStmt(columns=" + columns + ", table=" + table + ", joins=" + joins
                    + ", where=" + where + ", groupBy=" + groupBy + ", orderBy=" + orderBy
                    + ", chaos=" + chaos + ", limit=" + limit + ")";
        }
    }

    static class ColumnDef {
        final String name;
        final String type; // normalized internal type: "DOUBLE" or "STRING"
        ColumnDef(String name, String type) { this.name = name; this.type = type; }
        public String toString() { return name + " " + type; }
    }

    static class CreateTableStmt implements Stmt {
        final String table;
        final List<ColumnDef> columns;
        CreateTableStmt(String table, List<ColumnDef> columns) {
            this.table = table; this.columns = columns;
        }
        public String toString() {
            return "CreateTableStmt(table=" + table + ", columns=" + columns + ")";
        }
    }

    // ---------- Stage 2/3: Recursive descent parser ----------

    static class Parser {
        final List<Token> tokens;
        int pos = 0;

        Parser(List<Token> tokens) { this.tokens = tokens; }

        Token peek() { return tokens.get(pos); }
        Token advance() { return tokens.get(pos++); }

        Token expect(TokenType type) {
            Token tok = peek();
            if (tok.type != type) {
                throw new RuntimeException("Expected " + type + " but got " + tok.type + " (" + tok.value + ")");
            }
            return advance();
        }

        // select_stmt := SELECT select_list FROM IDENT (JOIN IDENT ON condition)*
        //                [WHERE condition] [LIMIT NUMBER]
        SelectStmt parseSelect() {
            expect(TokenType.SELECT);
            List<Expr> columns = parseSelectList();
            expect(TokenType.FROM);
            String table = expect(TokenType.IDENT).value;

            List<Join> joins = new ArrayList<>();
            while (peek().type == TokenType.JOIN) {
                advance();
                String joinTable = expect(TokenType.IDENT).value;
                expect(TokenType.ON);
                Expr on = parseOrExpr();
                joins.add(new Join(joinTable, on));
            }

            Expr where = null;
            if (peek().type == TokenType.WHERE) {
                advance();
                where = parseOrExpr();
            }

            List<Column> groupBy = new ArrayList<>();
            if (peek().type == TokenType.GROUP) {
                advance();
                expect(TokenType.BY);
                groupBy.add(parseQualifiedColumn());
                while (peek().type == TokenType.COMMA) {
                    advance();
                    groupBy.add(parseQualifiedColumn());
                }
            }

            List<OrderByItem> orderBy = new ArrayList<>();
            boolean chaos = false;
            if (peek().type == TokenType.ORDER) {
                advance();
                expect(TokenType.BY);
                // "CHAOS" isn't a reserved keyword -- it's recognized only in
                // this position, so a real column named "chaos" still works
                // everywhere else (WHERE, a normal ORDER BY list, etc).
                if (peek().type == TokenType.IDENT && peek().value.equalsIgnoreCase("CHAOS")) {
                    advance();
                    chaos = true;
                } else {
                    orderBy.add(parseOrderByItem());
                    while (peek().type == TokenType.COMMA) {
                        advance();
                        orderBy.add(parseOrderByItem());
                    }
                }
            }

            Integer limit = null;
            if (peek().type == TokenType.LIMIT) {
                advance();
                Token n = expect(TokenType.NUMBER);
                limit = (int) Double.parseDouble(n.value);
                if (limit < 0) {
                    throw new RuntimeException("LIMIT must be non-negative, got " + limit);
                }
            }

            expect(TokenType.EOF);
            return new SelectStmt(columns, table, joins, where, groupBy, orderBy, chaos, limit);
        }

        // create_table_stmt := CREATE TABLE IDENT '(' column_def (',' column_def)* ')'
        CreateTableStmt parseCreateTable() {
            expect(TokenType.CREATE);
            expect(TokenType.TABLE);
            String table = expect(TokenType.IDENT).value;
            expect(TokenType.LPAREN);

            List<ColumnDef> columns = new ArrayList<>();
            columns.add(parseColumnDef());
            while (peek().type == TokenType.COMMA) {
                advance();
                columns.add(parseColumnDef());
            }

            expect(TokenType.RPAREN);
            expect(TokenType.EOF);
            return new CreateTableStmt(table, columns);
        }

        // column_def := IDENT IDENT   -- e.g. "age INT" ; the second IDENT is
        // validated against known type spellings by normalizeTypeName().
        ColumnDef parseColumnDef() {
            String name = expect(TokenType.IDENT).value;
            String rawType = expect(TokenType.IDENT).value;
            return new ColumnDef(name, normalizeTypeName(rawType));
        }

        // order_by_item := qualified_column [ASC | DESC]
        OrderByItem parseOrderByItem() {
            Column col = parseQualifiedColumn();
            boolean descending = false;
            if (peek().type == TokenType.ASC) {
                advance();
            } else if (peek().type == TokenType.DESC) {
                advance();
                descending = true;
            }
            return new OrderByItem(col, descending);
        }

        List<Expr> parseSelectList() {
            if (peek().type == TokenType.OP && peek().value.equals("*")) {
                advance();
                return List.of(new Star());
            }
            List<Expr> columns = new ArrayList<>();
            columns.add(parseSelectItem());
            while (peek().type == TokenType.COMMA) {
                advance();
                columns.add(parseSelectItem());
            }
            return columns;
        }

        // select_item := qualified_column | AGG_FUNC '(' ('*' | qualified_column) ')'
        // Function calls are recognized by "IDENT followed by '('" -- the function
        // name itself isn't a reserved keyword, same as in real SQL.
        Expr parseSelectItem() {
            String first = expect(TokenType.IDENT).value;

            if (peek().type == TokenType.LPAREN) {
                String func = first.toUpperCase();
                if (!AGGREGATE_FUNCTIONS.contains(func)) {
                    throw new RuntimeException("Unknown function: " + first);
                }
                advance(); // consume '('
                boolean star = false;
                Column arg = null;
                if (peek().type == TokenType.OP && peek().value.equals("*")) {
                    if (!func.equals("COUNT")) {
                        throw new RuntimeException(func + "(*) is not supported -- only COUNT(*) is");
                    }
                    advance();
                    star = true;
                } else {
                    arg = parseQualifiedColumnFrom(expect(TokenType.IDENT).value);
                }
                expect(TokenType.RPAREN);
                return new AggregateCall(func, arg, star);
            }

            return parseQualifiedColumnFrom(first);
        }

        // qualified_column := IDENT ('.' IDENT)?
        Column parseQualifiedColumn() {
            return parseQualifiedColumnFrom(expect(TokenType.IDENT).value);
        }

        // Same as parseQualifiedColumn, but the first IDENT has already been consumed
        // (needed because parseSelectItem must consume an IDENT before it knows
        // whether it's about to see a function call's '(' or a plain column).
        Column parseQualifiedColumnFrom(String first) {
            if (peek().type == TokenType.DOT) {
                advance();
                String second = expect(TokenType.IDENT).value;
                return new Column(first, second); // first is the table qualifier
            }
            return new Column(first); // unqualified
        }

        // condition := and_expr (OR and_expr)*
        Expr parseOrExpr() {
            Expr left = parseAndExpr();
            while (peek().type == TokenType.OR) {
                advance();
                Expr right = parseAndExpr();
                left = new BinaryOp("OR", left, right);
            }
            return left;
        }

        // and_expr := comparison (AND comparison)*
        Expr parseAndExpr() {
            Expr left = parseComparison();
            while (peek().type == TokenType.AND) {
                advance();
                Expr right = parseComparison();
                left = new BinaryOp("AND", left, right);
            }
            return left;
        }

        // comparison := operand OP operand
        // operand := qualified_column | NUMBER | STRING
        Expr parseComparison() {
            Expr left = parseOperand();
            Token opTok = expect(TokenType.OP);
            Expr right = parseOperand();
            return new BinaryOp(opTok.value, left, right);
        }

        Expr parseOperand() {
            Token tok = peek();
            if (tok.type == TokenType.NUMBER) {
                advance();
                return new Literal(Double.parseDouble(tok.value));
            }
            if (tok.type == TokenType.STRING) {
                advance();
                String s = tok.value;
                s = s.substring(1, s.length() - 1).replace("''", "'");
                return new Literal(s);
            }
            if (tok.type == TokenType.IDENT) {
                return parseQualifiedColumn();
            }
            throw new RuntimeException("Expected column or value, got " + tok.type);
        }
    }

    static Stmt parseSql(String sql) {
        List<Token> tokens = tokenize(sql);
        Parser parser = new Parser(tokens);
        Token first = parser.peek();
        if (first.type == TokenType.SELECT) {
            return parser.parseSelect();
        }
        if (first.type == TokenType.CREATE) {
            return parser.parseCreateTable();
        }
        throw new RuntimeException("Expected SELECT or CREATE, got " + first.type);
    }

    // ---------- Demo ----------

    public static void main(String[] args) {
        String[] examples = {
            "SELECT name, age FROM users WHERE age > 18",
            "SELECT * FROM products WHERE price <= 100 AND category = 'tools'",
            "SELECT users.name, orders.total FROM users JOIN orders ON users.id = orders.user_id WHERE orders.total > 50",
            "SELECT * FROM products LIMIT 2",
            "SELECT * FROM products ORDER BY price DESC LIMIT 2",
            "SELECT category, COUNT(*), SUM(price) FROM products GROUP BY category",
            "SELECT COUNT(*) FROM users WHERE age > 18",
            "CREATE TABLE employees (id INT, name TEXT, salary DOUBLE)",
            "SELECT * FROM products ORDER BY CHAOS LIMIT 2",
        };
        for (String sql : examples) {
            System.out.println("\nSQL:  " + sql);
            Stmt ast = parseSql(sql);
            System.out.println("AST:  " + ast);
        }
    }
}