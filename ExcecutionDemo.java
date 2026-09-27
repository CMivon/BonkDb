
import java.util.*;

/**
 * Minimal execution engine: takes a SelectStmt AST (from MiniParser) and
 * runs it against a Table (see Table.java). Tree-walking style -- no
 * separate query plan yet, the AST is evaluated directly.
 *
 * This is the seed you'll later refactor into an iterator/Volcano model
 * once you add JOINs.
 */
public class ExecutionDemo {

    // ---------- Expression evaluator ----------
    // Walks a MiniParser.Expr against one row and produces a Java value.
    // Table stays a pure storage/row container; SQL semantics (how to
    // interpret an Expr, how to join two tables) live here instead.

    static Object evaluate(MiniParser.Expr expr, Table table, Object[] row) {
        if (expr instanceof MiniParser.Literal) {
            MiniParser.Literal lit = (MiniParser.Literal) expr;
            return lit.value;
        }
        if (expr instanceof MiniParser.Column) {
            MiniParser.Column col = (MiniParser.Column) expr;
            return row[table.indexOfColumn(col)];
        }
        if (expr instanceof MiniParser.BinaryOp) {
            MiniParser.BinaryOp bin = (MiniParser.BinaryOp) expr;
            // AND / OR short-circuit and expect boolean operands
            if (bin.op.equals("AND")) {
                return asBool(evaluate(bin.left, table, row)) && asBool(evaluate(bin.right, table, row));
            }
            if (bin.op.equals("OR")) {
                return asBool(evaluate(bin.left, table, row)) || asBool(evaluate(bin.right, table, row));
            }
            // Otherwise it's a comparison: =, <, >, <=, >=, <>
            Object left = evaluate(bin.left, table, row);
            Object right = evaluate(bin.right, table, row);
            return compare(bin.op, left, right);
        }
        throw new RuntimeException("Cannot evaluate expression: " + expr);
    }

    static boolean asBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        throw new RuntimeException("Expected boolean, got: " + o);
    }

    static boolean compare(String op, Object left, Object right) {
        // Numbers vs numbers
        if (left instanceof Double && right instanceof Double) {
            double l = (Double) left;
            double r = (Double) right;
            if (op.equals("=")) return l == r;
            if (op.equals("<>")) return l != r;
            if (op.equals("<")) return l < r;
            if (op.equals(">")) return l > r;
            if (op.equals("<=")) return l <= r;
            if (op.equals(">=")) return l >= r;
            throw new RuntimeException("Unknown operator: " + op);
        }
        // Strings vs strings
        if (left instanceof String && right instanceof String) {
            int cmp = ((String) left).compareTo((String) right);
            if (op.equals("=")) return cmp == 0;
            if (op.equals("<>")) return cmp != 0;
            if (op.equals("<")) return cmp < 0;
            if (op.equals(">")) return cmp > 0;
            if (op.equals("<=")) return cmp <= 0;
            if (op.equals(">=")) return cmp >= 0;
            throw new RuntimeException("Unknown operator: " + op);
        }
        throw new RuntimeException("Type mismatch comparing " + left + " and " + right);
    }

    // ---------- Join ----------
    // Nested-loop join: for every (leftRow, rightRow) pair, evaluate the ON
    // condition; keep the combination if it's true. The result is itself a
    // Table, with columns qualified as "tableName.column" so downstream WHERE
    // filtering and projection can resolve names unambiguously even when both
    // sides share a column name (e.g. both have an "id").
    static Table join(Table left, Table right, MiniParser.Expr on) {
        List<String> combinedColumns = new ArrayList<>();
        for (String c : left.columns) combinedColumns.add(left.name + "." + c);
        for (String c : right.columns) combinedColumns.add(right.name + "." + c);

        Table joined = new Table(left.name + "_JOIN_" + right.name, combinedColumns);

        for (Object[] leftRow : left.rows) {
            for (Object[] rightRow : right.rows) {
                Object[] combinedRow = new Object[leftRow.length + rightRow.length];
                System.arraycopy(leftRow, 0, combinedRow, 0, leftRow.length);
                System.arraycopy(rightRow, 0, combinedRow, leftRow.length, rightRow.length);

                if (on == null || asBool(evaluate(on, joined, combinedRow))) {
                    joined.rows.add(combinedRow);
                }
            }
        }
        return joined;
    }

    // ---------- Select executor ----------

    static List<Object[]> executeSelect(MiniParser.SelectStmt stmt, Map<String, Table> catalog) {
        Table base = catalog.get(stmt.table);
        if (base == null) {
            throw new RuntimeException("No such table: " + stmt.table);
        }

        // 1. Apply any JOINs left-to-right, folding each into a combined
        //    virtual table with qualified ("table.column") column names.
        Table table = base;
        for (MiniParser.Join join : stmt.joins) {
            Table right = catalog.get(join.table);
            if (right == null) {
                throw new RuntimeException("No such table: " + join.table);
            }
            table = join(table, right, join.on);
        }

        // 2. Resolve which columns to project (handle SELECT *)
        List<MiniParser.Column> outputColumns;
        if (stmt.columns.size() == 1 && stmt.columns.get(0) instanceof MiniParser.Star) {
            outputColumns = new ArrayList<>();
            for (String c : table.columns) outputColumns.add(new MiniParser.Column(c));
        } else {
            outputColumns = new ArrayList<>();
            for (MiniParser.Expr e : stmt.columns) {
                outputColumns.add((MiniParser.Column) e);
            }
        }

        // 3. Scan + filter (keep full rows -- ORDER BY may reference columns
        //    that aren't in the SELECT list, so we can't project yet)
        List<Object[]> filtered = new ArrayList<>();
        for (Object[] row : table.rows) {
            if (stmt.where == null || asBool(evaluate(stmt.where, table, row))) {
                filtered.add(row);
            }
        }

        // 4. Sort, if requested. Note: this means LIMIT can no longer short-
        // circuit the scan the way it used to -- we need every matching row
        // in hand before we know which ones sort to the top. (Real engines
        // avoid a full sort here with a top-k/heap strategy when the limit
        // is much smaller than the row count -- worth revisiting later.)
        if (!stmt.orderBy.isEmpty()) {
            final Table sortTable = table; // lambdas need an effectively-final capture
            Comparator<Object[]> comparator = (a, b) -> {
                for (MiniParser.OrderByItem item : stmt.orderBy) {
                    int idx = sortTable.indexOfColumn(item.column);
                    int cmp = compareValues(a[idx], b[idx]);
                    if (item.descending) cmp = -cmp;
                    if (cmp != 0) return cmp;
                }
                return 0;
            };
            filtered.sort(comparator);
        }

        // 5. Apply LIMIT
        if (stmt.limit != null && filtered.size() > stmt.limit) {
            filtered = filtered.subList(0, stmt.limit);
        }

        // 6. Project
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : filtered) {
            Object[] projected = new Object[outputColumns.size()];
            for (int i = 0; i < outputColumns.size(); i++) {
                projected[i] = row[table.indexOfColumn(outputColumns.get(i))];
            }
            result.add(projected);
        }
        return result;
    }

    // Ordering comparator for sorting (distinct from compare(), which
    // evaluates a specific operator like "=" or ">" for WHERE/ON clauses).
    static int compareValues(Object left, Object right) {
        if (left instanceof Double && right instanceof Double) {
            return Double.compare((Double) left, (Double) right);
        }
        if (left instanceof String && right instanceof String) {
            return ((String) left).compareTo((String) right);
        }
        throw new RuntimeException("Cannot compare " + left + " and " + right);
    }

    // ---------- Demo ----------

    public static void main(String[] args) throws java.io.IOException {
        // Build a sample "users" table
        Table users = new Table("users", List.of("name", "age"));
        users.insert("Alice", 30.0);
        users.insert("Bob", 17.0);
        users.insert("Carol", 45.0);

        Table products = new Table("products", List.of("name", "price", "category"));
        products.insert("Hammer", 25.0, "tools");
        products.insert("Wrench", 150.0, "tools");
        products.insert("Paint", 40.0, "supplies");

        // users need an id column for orders to reference -- rebuild with one
        Table usersWithId = new Table("users", List.of("id", "name", "age"));
        usersWithId.insert(1.0, "Alice", 30.0);
        usersWithId.insert(2.0, "Bob", 17.0);
        usersWithId.insert(3.0, "Carol", 45.0);

        Table orders = new Table("orders", List.of("id", "user_id", "total"));
        orders.insert(100.0, 1.0, 75.0);
        orders.insert(101.0, 1.0, 20.0);
        orders.insert(102.0, 3.0, 60.0);

        Map<String, Table> catalog = Map.of("users", users, "products", products, "orders", orders);
        Map<String, Table> joinCatalog = Map.of("users", usersWithId, "orders", orders);

        // String[] queries = {
        //     "SELECT name, age FROM users WHERE age > 18",
        //     "SELECT * FROM products WHERE price <= 100 AND category = 'tools'",
        // };

        // for (String sql : queries) {
        //     System.out.println("\nSQL: " + sql);
        //     MiniParser.SelectStmt ast = MiniParser.parseSql(sql);
        //     List<Object[]> rows = executeSelect(ast, catalog);
        //     for (Object[] row : rows) {
        //         System.out.println("  " + Arrays.toString(row));
        //     }
        // }

        // ---------- LIMIT demo ----------
        String limitSql = "SELECT * FROM products LIMIT 2";
        System.out.println("\nSQL: " + limitSql);
        List<Object[]> limitRows = executeSelect(MiniParser.parseSql(limitSql), catalog);
        for (Object[] row : limitRows) {
            System.out.println("  " + Arrays.toString(row));
        }

        // ---------- ORDER BY demo ----------
        String orderSql = "SELECT * FROM products ORDER BY price DESC LIMIT 2";
        System.out.println("\nSQL: " + orderSql);
        List<Object[]> orderRows = executeSelect(MiniParser.parseSql(orderSql), catalog);
        for (Object[] row : orderRows) {
            System.out.println("  " + Arrays.toString(row));
        }

        // ---------- Join demo ----------
        String joinSql = "SELECT users.name, orders.total FROM users JOIN orders ON users.id = orders.user_id WHERE orders.total > 50";
        System.out.println("\nSQL: " + joinSql);
        List<Object[]> joinRows = executeSelect(MiniParser.parseSql(joinSql), joinCatalog);
        for (Object[] row : joinRows) {
            System.out.println("  " + Arrays.toString(row));
        }

        // ---------- Persistence demo ----------
        // System.out.println("\n--- Persistence ---");

        // String path = "users.tbl";
        // users.save(path);
        // System.out.println("Saved users table to " + path);

        // // Simulate a fresh process: load the table back from disk into a new object.
        // Table reloadedUsers = Table.load(path);
        // System.out.println("Reloaded table '" + reloadedUsers.name + "' with " + reloadedUsers.rows.size() + " rows");

        // Map<String, Table> reloadedCatalog = Map.of("users", reloadedUsers);
        // String sql = "SELECT name, age FROM users WHERE age > 18";
        // System.out.println("\nSQL (on reloaded table): " + sql);
        // List<Object[]> rows = executeSelect(MiniParser.parseSql(sql), reloadedCatalog);
        // for (Object[] row : rows) {
        //     System.out.println("  " + Arrays.toString(row));
        // }
    }
}