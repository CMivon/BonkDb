import java.util.*;

/**
 * Minimal execution engine: takes a SelectStmt AST (from MiniParser) and
 * runs it against an in-memory Table. Tree-walking style -- no separate
 * query plan yet, the AST is evaluated directly.
 *
 * This is the seed you'll later refactor into an iterator/Volcano model
 * once you add JOINs and a real storage engine.
 */
public class ExecutionDemo {

//     // ---------- In-memory table representation ----------
//     // Stands in for your storage engine for now.

//     static class Table {
//         final String name;
//         final List<String> columns;
//         final List<Object[]> rows = new ArrayList<>();
//         final Map<String, Integer> columnIndex = new HashMap<>();

//         Table(String name, List<String> columns) {
//             this.name = name;
//             this.columns = columns;
//             for (int i = 0; i < columns.size(); i++) {
//                 columnIndex.put(columns.get(i), i);
//             }
//         }

//         void insert(Object... values) {
//             if (values.length != columns.size()) {
//                 throw new RuntimeException("Column count mismatch for table " + name);
//             }
//             rows.add(values);
//         }

//         int indexOf(String column) {
//             Integer idx = columnIndex.get(column);
//             if (idx == null) {
//                 throw new RuntimeException("Unknown column '" + column + "' in table " + name);
//             }
//             return idx;
//         }
//     }

    // ---------- Expression evaluator ----------
    // Walks a MiniParser.Expr against one row and produces a Java value.

    static Object evaluate(MiniParser.Expr expr, Table table, Object[] row) {
        if (expr instanceof MiniParser.Literal lit) {
            return lit.value;
        }
        if (expr instanceof MiniParser.Column col) {
            return row[table.indexOf(col.name)];
        }
        if (expr instanceof MiniParser.BinaryOp bin) {
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
        if (o instanceof Boolean b) return b;
        throw new RuntimeException("Expected boolean, got: " + o);
    }

    static boolean compare(String op, Object left, Object right) {
        // Numbers vs numbers
        if (left instanceof Double l && right instanceof Double r) {
            return switch (op) {
                case "=" -> l.doubleValue() == r.doubleValue();
                case "<>" -> l.doubleValue() != r.doubleValue();
                case "<" -> l < r;
                case ">" -> l > r;
                case "<=" -> l <= r;
                case ">=" -> l >= r;
                default -> throw new RuntimeException("Unknown operator: " + op);
            };
        }
        // Strings vs strings
        if (left instanceof String l && right instanceof String r) {
            int cmp = l.compareTo(r);
            return switch (op) {
                case "=" -> cmp == 0;
                case "<>" -> cmp != 0;
                case "<" -> cmp < 0;
                case ">" -> cmp > 0;
                case "<=" -> cmp <= 0;
                case ">=" -> cmp >= 0;
                default -> throw new RuntimeException("Unknown operator: " + op);
            };
        }
        throw new RuntimeException("Type mismatch comparing " + left + " and " + right);
    }

    // ---------- Select executor ----------

    static List<Object[]> executeSelect(MiniParser.SelectStmt stmt, Map<String, Table> catalog) {
        Table table = catalog.get(stmt.table);
        if (table == null) {
            throw new RuntimeException("No such table: " + stmt.table);
        }

        // 1. Resolve which columns to project (handle SELECT *)
        List<String> outputColumns;
        if (stmt.columns.size() == 1 && stmt.columns.get(0) instanceof MiniParser.Star) {
            outputColumns = table.columns;
        } else {
            outputColumns = new ArrayList<>();
            for (MiniParser.Expr e : stmt.columns) {
                outputColumns.add(((MiniParser.Column) e).name);
            }
        }

        // 2. Scan + filter + project
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : table.rows) {
            if (stmt.where == null || asBool(evaluate(stmt.where, table, row))) {
                Object[] projected = new Object[outputColumns.size()];
                for (int i = 0; i < outputColumns.size(); i++) {
                    projected[i] = row[table.indexOf(outputColumns.get(i))];
                }
                result.add(projected);
            }
        }
        return result;
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
 
        Map<String, Table> catalog = Map.of("users", users, "products", products);
 
        String[] queries = {
            "SELECT name, age FROM users WHERE age > 18",
            "SELECT * FROM products WHERE price <= 100 AND category = 'tools'",
        };
 
        for (String sql : queries) {
            System.out.println("\nSQL: " + sql);
            MiniParser.SelectStmt ast = MiniParser.parseSql(sql);
            List<Object[]> rows = executeSelect(ast, catalog);
            for (Object[] row : rows) {
                System.out.println("  " + Arrays.toString(row));
            }
        }
 
        // ---------- Persistence demo ----------
        System.out.println("\n--- Persistence ---");
 
        String path = "users.tbl";
        users.save(path);
        System.out.println("Saved users table to " + path);
 
        // Simulate a fresh process: load the table back from disk into a new object.
        Table reloadedUsers = Table.load(path);
        System.out.println("Reloaded table '" + reloadedUsers.name + "' with " + reloadedUsers.rows.size() + " rows");
 
        Map<String, Table> reloadedCatalog = Map.of("users", reloadedUsers);
        String sql = "SELECT name, age FROM users WHERE age > 18";
        System.out.println("\nSQL (on reloaded table): " + sql);
        List<Object[]> rows = executeSelect(MiniParser.parseSql(sql), reloadedCatalog);
        for (Object[] row : rows) {
            System.out.println("  " + Arrays.toString(row));
        }
    }
   
}