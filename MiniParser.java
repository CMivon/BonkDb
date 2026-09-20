
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
        SELECT, FROM, WHERE, AND, OR, NOT,
        IDENT, NUMBER, STRING, OP, COMMA, LPAREN, RPAREN, EOF
    }

    static final Set<String> KEYWORDS = Set.of("SELECT", "FROM", "WHERE", "AND", "OR", "NOT");

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
        for (String name : new String[]{"NUMBER", "STRING", "IDENT", "OP", "COMMA", "LPAREN", "RPAREN", "SKIP"}) {
            if (m.group(name) != null) return name;
        }
        throw new RuntimeException("No token type matched");
    }

    // ---------- AST node definitions ----------

    interface Expr {}

    static class Column implements Expr {
        final String name;
        Column(String name) { this.name = name; }
        public String toString() { return "Column(" + name + ")"; }
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

    static class SelectStmt {
        final List<Expr> columns;
        final String table;
        final Expr where; // nullable
        SelectStmt(List<Expr> columns, String table, Expr where) {
            this.columns = columns; this.table = table; this.where = where;
        }
        public String toString() {
            return "SelectStmt(columns=" + columns + ", table=" + table + ", where=" + where + ")";
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

        // select_stmt := SELECT select_list FROM IDENT [WHERE condition]
        SelectStmt parseSelect() {
            expect(TokenType.SELECT);
            List<Expr> columns = parseSelectList();
            expect(TokenType.FROM);
            String table = expect(TokenType.IDENT).value;
            Expr where = null;
            if (peek().type == TokenType.WHERE) {
                advance();
                where = parseOrExpr();
            }
            expect(TokenType.EOF);
            return new SelectStmt(columns, table, where);
        }

        List<Expr> parseSelectList() {
            if (peek().type == TokenType.OP && peek().value.equals("*")) {
                advance();
                return List.of(new Star());
            }
            List<Expr> columns = new ArrayList<>();
            columns.add(new Column(expect(TokenType.IDENT).value));
            while (peek().type == TokenType.COMMA) {
                advance();
                columns.add(new Column(expect(TokenType.IDENT).value));
            }
            return columns;
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

        // comparison := IDENT OP (NUMBER | STRING)
        Expr parseComparison() {
            Expr left = new Column(expect(TokenType.IDENT).value);
            Token opTok = expect(TokenType.OP);
            Token rightTok = advance();
            Expr right;
            if (rightTok.type == TokenType.NUMBER) {
                right = new Literal(Double.parseDouble(rightTok.value));
            } else if (rightTok.type == TokenType.STRING) {
                String s = rightTok.value;
                s = s.substring(1, s.length() - 1).replace("''", "'");
                right = new Literal(s);
            } else {
                throw new RuntimeException("Expected value, got " + rightTok.type);
            }
            return new BinaryOp(opTok.value, left, right);
        }
    }

    static SelectStmt parseSql(String sql) {
        List<Token> tokens = tokenize(sql);
        return new Parser(tokens).parseSelect();
    }

    // ---------- Demo ----------

    public static void main(String[] args) {
        String[] examples = {
            "SELECT name, age FROM users WHERE age > 18",
            "SELECT * FROM products WHERE price <= 100 AND category = 'tools'",
        };
        for (String sql : examples) {
            System.out.println("\nSQL:  " + sql);
            SelectStmt ast = parseSql(sql);
            System.out.println("AST:  " + ast);
        }
    }
}