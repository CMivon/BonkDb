
import java.util.*;


//TODO: Eventually this should be automated. So have expect and compare the results that we get
/**
 * Test/demo driver for the engine: builds sample tables, runs queries
 * through MiniParser + ExecutionDemo, and prints the results.
 *
 * Each SQL feature area gets its own method so you can read (or run) them
 * independently rather than wading through one long main().
 *
 * ExecutionDemo itself has no main() -- it's pure library code (evaluate,
 * join, executeSelect, ...). This file is where you exercise it.
 */
public class Test {

    public static void main(String[] args) throws java.io.IOException {
        testBasicSelectAndWhere();
        testLimit();
        testOrderBy();
        testGroupByAndAggregates();
        testJoin();
        testPersistence();
    }

    // ---------- Shared sample data ----------

    static Table sampleUsers() {
        Table users = new Table("users", List.of("name", "age"));
        users.insert("Alice", 30.0);
        users.insert("Bob", 17.0);
        users.insert("Carol", 45.0);
        return users;
    }

    static Table sampleProducts() {
        Table products = new Table("products", List.of("name", "price", "category"));
        products.insert("Hammer", 25.0, "tools");
        products.insert("Wrench", 150.0, "tools");
        products.insert("Paint", 40.0, "supplies");
        return products;
    }

    // users need an id column for orders to reference -- a separate builder
    // from sampleUsers() since most tests don't need the id column at all.
    static Table sampleUsersWithId() {
        Table users = new Table("users", List.of("id", "name", "age"));
        users.insert(1.0, "Alice", 30.0);
        users.insert(2.0, "Bob", 17.0);
        users.insert(3.0, "Carol", 45.0);
        return users;
    }

    static Table sampleOrders() {
        Table orders = new Table("orders", List.of("id", "user_id", "total"));
        orders.insert(100.0, 1.0, 75.0);
        orders.insert(101.0, 1.0, 20.0);
        orders.insert(102.0, 3.0, 60.0);
        return orders;
    }

    // ---------- Helper ----------

    static void run(String sql, Map<String, Table> catalog) {
        System.out.println("\nSQL: " + sql);
        MiniParser.SelectStmt ast = MiniParser.parseSql(sql);
        List<Object[]> rows = ExecutionDemo.executeSelect(ast, catalog);
        for (Object[] row : rows) {
            System.out.println("  " + Arrays.toString(row));
        }
    }

    // ---------- Tests ----------

    static void testBasicSelectAndWhere() {
        Map<String, Table> catalog = Map.of("users", sampleUsers(), "products", sampleProducts());

        run("SELECT name, age FROM users WHERE age > 18", catalog);
        run("SELECT * FROM products WHERE price <= 100 AND category = 'tools'", catalog);
    }

    static void testLimit() {
        Map<String, Table> catalog = Map.of("products", sampleProducts());

        run("SELECT * FROM products LIMIT 2", catalog);
    }

    static void testOrderBy() {
        Map<String, Table> catalog = Map.of("products", sampleProducts());

        run("SELECT * FROM products ORDER BY price DESC LIMIT 2", catalog);
    }

    static void testGroupByAndAggregates() {
        Map<String, Table> catalog = Map.of("users", sampleUsers(), "products", sampleProducts());

        run("SELECT category, COUNT(*), SUM(price) FROM products GROUP BY category", catalog);
        run("SELECT COUNT(*) FROM users WHERE age > 18", catalog);
    }

    static void testJoin() {
        Map<String, Table> catalog = Map.of("users", sampleUsersWithId(), "orders", sampleOrders());

        run("SELECT users.name, orders.total FROM users JOIN orders ON users.id = orders.user_id "
                + "WHERE orders.total > 50", catalog);
    }

    static void testPersistence() throws java.io.IOException {
        System.out.println("\n--- Persistence ---");

        Table users = sampleUsers();
        String path = "users.tbl";
        users.save(path);
        System.out.println("Saved users table to " + path);

        // Simulate a fresh process: load the table back from disk into a new object.
        Table reloadedUsers = Table.load(path);
        System.out.println("Reloaded table '" + reloadedUsers.name + "' with " + reloadedUsers.rows.size() + " rows");

        Map<String, Table> catalog = Map.of("users", reloadedUsers);
        run("SELECT name, age FROM users WHERE age > 18", catalog);
    }
}