
package Engine;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import Art.Art;
import DeleteFiles.DeleteFiles;

/**
 * Basic interactive command-line REPL for the engine.
 *
 * Tables are stored in a database folder, so they are still there next time.
 * The folder is bonkdb-data in the current directory, or the one named on the
 * command line:   java Main [folder]
 * A brand-new database starts with some sample tables (users, products,
 * orders) so there is something to query right away.
 *
 * Type a SELECT, CREATE TABLE, INSERT, UPDATE, or DELETE statement ending
 * in ';' and press Enter (multi-line statements are fine -- it keeps
 * reading until it sees the ';'). Meta commands (not SQL) start with '.':
 *
 *   .tables                list the tables and their columns
 *   .export <name> [path]  write a table to a text file (defaults to <name>.tbl)
 *   .import <name> [path]  create a table from a text file (defaults to <name>.tbl)
 *   .help                  show this message
 *   .exit / .quit          quit
 *
 * Each change is written to disk as soon as its statement finishes, so
 * closing the window without .exit doesn't lose anything.
 */
public class Cli {

    static final String DEFAULT_DIRECTORY = "bonkdb-data";

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : DEFAULT_DIRECTORY);

        Catalog catalog;
        try {
            catalog = Catalog.open(dir);
        } catch (IOException e) {
            System.out.println("Couldn't open the database folder " + dir + ": " + e.getMessage());
            return;
        }
        try (catalog) {
            repl(catalog);
        }
    }

    private static void repl(Catalog catalog) throws IOException {
        System.out.println("Mini SQL engine -- type .help for commands, .exit to quit.");
        System.out.println("Database: " + catalog.dir().toAbsolutePath().normalize());
        for (String warning : catalog.warnings()) {
            System.out.println("Warning: " + warning);
        }
        if (catalog.wasEmpty()) {
            seedSampleData(catalog);
            System.out.println("New database: created the sample tables users, products, orders.");
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder buffer = new StringBuilder();

        while (true) {
            System.out.print(buffer.length() == 0 ? "sql> " : "  -> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) {
                break; // EOF (e.g. Ctrl+D)
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Meta-commands only make sense at the start of a fresh statement.
            if (buffer.length() == 0 && line.startsWith(".")) {
                if (line.equals(".exit") || line.equals(".quit")) {
                    break;
                }
                handleMetaCommand(line, catalog);
                continue;
            }

            int semi = line.indexOf(';');
            if (semi == -1) {
                buffer.append(line).append(' ');
                continue; // keep reading until we see a ';'
            }

            buffer.append(line, 0, semi);
            String statement = buffer.toString().trim();
            buffer.setLength(0);

            if (!statement.isEmpty()) {
                runStatement(statement, catalog);
            }
        }

        System.out.println("Bye.");
    }

    static void runStatement(String sql, Catalog catalog) {
        Map<String, Table> tables = catalog.tables();
        try {
            BonkParser.Stmt stmt = BonkParser.parseSql(sql);
            if (stmt instanceof BonkParser.SelectStmt) {
                BonkParser.SelectStmt selectStmt = (BonkParser.SelectStmt) stmt;
                if (selectStmt.chaos) {
                    System.out.println("bonk! shuffling results into chaos...");
                }
                ExecutionDemo.QueryResult result = ExecutionDemo.executeSelect(selectStmt, tables);
                printTable(result);
            } else if (stmt instanceof BonkParser.CreateTableStmt) {
                BonkParser.CreateTableStmt createStmt = (BonkParser.CreateTableStmt) stmt;
                ExecutionDemo.executeCreateTable(createStmt, tables, catalog.factory());
                flush(tables.get(createStmt.table));
                System.out.println("Table '" + createStmt.table + "' created.");
            } else if (stmt instanceof BonkParser.InsertStmt) {
                BonkParser.InsertStmt insertStmt = (BonkParser.InsertStmt) stmt;
                ExecutionDemo.executeInsert(insertStmt, tables);
                flush(tables.get(insertStmt.table));
                System.out.println("1 row inserted into '" + insertStmt.table + "'.");
            } else if (stmt instanceof BonkParser.DeleteStmt) {
                BonkParser.DeleteStmt deleteStmt = (BonkParser.DeleteStmt) stmt;
                int deleted = ExecutionDemo.executeDelete(deleteStmt, tables);
                flush(tables.get(deleteStmt.table));
                System.out.println(deleted + " row" + (deleted == 1 ? "" : "s") + " bonked out of '"
                    + deleteStmt.table + "'.");
            } else if (stmt instanceof BonkParser.UpdateStmt) {
                BonkParser.UpdateStmt updateStmt = (BonkParser.UpdateStmt) stmt;
                int updated = ExecutionDemo.executeUpdate(updateStmt, tables);
                flush(tables.get(updateStmt.table));
                System.out.println(updated + " row" + (updated == 1 ? "" : "s") + " bonked into shape in '"
                    + updateStmt.table + "'.");
            } else {
                System.out.println("Error: don't know how to run statement: " + stmt);
            }
        } catch (RuntimeException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    // Makes sure a table's latest change is on disk before the statement is reported as done.
    private static void flush(Table table) {
        try {
            table.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void printTable(ExecutionDemo.QueryResult result) {
        if (result.rows.isEmpty()) {
            System.out.println("(0 rows) -- columns: " + result.columns);
            return;
        }

        int[] widths = new int[result.columns.size()];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = result.columns.get(i).length();
        }
        for (Object[] row : result.rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], String.valueOf(row[i]).length());
            }
        }

        printRow(result.columns.toArray(), widths);
        printSeparator(widths);
        for (Object[] row : result.rows) {
            printRow(row, widths);
        }
        System.out.println("(" + result.rows.size() + " row" + (result.rows.size() == 1 ? "" : "s") + ")");
    }

    static void printRow(Object[] values, int[] widths) {
        StringBuilder line = new StringBuilder("| ");
        for (int i = 0; i < values.length; i++) {
            String s = String.valueOf(values[i]);
            line.append(s);
            for (int pad = widths[i] - s.length(); pad > 0; pad--) line.append(' ');
            line.append(" | ");
        }
        System.out.println(line.toString().stripTrailing());
    }

    static void printSeparator(int[] widths) {
        StringBuilder line = new StringBuilder("+-");
        for (int i = 0; i < widths.length; i++) {
            for (int j = 0; j < widths[i]; j++) line.append('-');
            line.append("-+-");
        }
        System.out.println(line.substring(0, line.length() - 1));
    }

    static void handleMetaCommand(String line, Catalog catalog) {
        Map<String, Table> tables = catalog.tables();
        String[] parts = line.split("\\s+");
        switch (parts[0]) {
            case ".help":
                System.out.println(
                    ".tables                list the tables and their columns\n" +
                    ".export <name> [path]  write a table to a text file (defaults to <name>.tbl)\n" +
                    ".import <name> [path]  create a table from a text file (defaults to <name>.tbl)\n" +
                    ".panic                 (future upgrade -- not available yet)\n" +
                    ".revenge               permanently delete a random folder from DeleteFiles/Sandbox/\n" +
                    ".help                  show this message\n" +
                    ".exit / .quit          quit"
                );
                break;
            case ".panic":
                // Parked for now. CorruptData still exists, but it can't corrupt a
                // table whose columns have fixed types, so what .panic should do
                // with tables stored in files is still to be decided.
                System.out.println("Future upgrade");
                break;
            case ".revenge":
                var destroyed = DeleteFiles.deleteRandomFolder();
                System.out.println(destroyed != null
                    ? "\uD83D\uDDE1\uFE0F REVENGE. '" + destroyed + "' is gone."
                    : "Nothing to take revenge on -- Sandbox/ is empty or doesn't exist.");
                break;
            case ".tables":
                if (tables.isEmpty()) {
                    System.out.println("(no tables)");
                } else {
                    for (Map.Entry<String, Table> e : tables.entrySet()) {
                        System.out.println("  " + e.getKey() + " " + e.getValue().columns);
                    }
                }
                break;
            case ".import": {
                if (parts.length != 2 && parts.length != 3) {
                    System.out.println("Usage: .import <name> [path]  (path defaults to <name>.tbl)");
                    break;
                }
                String name = parts[1];
                String path = parts.length == 3 ? parts[2] : name + ".tbl";
                if (!Catalog.isValidName(name)) {
                    System.out.println("Error: '" + name + "' isn't a valid table name (letters, digits and underscores, "
                        + "not starting with a digit)");
                    break;
                }
                if (tables.containsKey(name)) {
                    System.out.println("Error: Table already exists: " + name);
                    break;
                }
                MemoryTable source;
                try {
                    source = Table.load(path);
                } catch (IOException e) {
                    System.out.println("Error importing " + path + ": " + e.getMessage());
                    break;
                } catch (RuntimeException e) {
                    System.out.println("Error importing " + path + ": the file isn't a valid table export");
                    break;
                }
                try {
                    catalog.addFrom(name, source);
                    System.out.println("Imported '" + name + "' (" + source.rowCount() + " rows) from " + path);
                } catch (RuntimeException e) {
                    System.out.println("Error importing " + path + ": " + e.getMessage());
                }
                break;
            }
            case ".export": {
                if (parts.length != 2 && parts.length != 3) {
                    System.out.println("Usage: .export <name> [path]  (path defaults to <name>.tbl)");
                    break;
                }
                Table t = tables.get(parts[1]);
                if (t == null) {
                    System.out.println("No such table: " + parts[1]);
                    break;
                }
                String path = parts.length == 3 ? parts[2] : parts[1] + ".tbl";
                try {
                    t.save(path);
                    System.out.println("Exported '" + parts[1] + "' to " + path);
                } catch (IOException e) {
                    System.out.println("Error exporting to " + path + ": " + e.getMessage());
                }
                break;
            }
            default:
                System.out.println("Unknown command: " + parts[0] + " (try .help)");
        }
    }

    // The sample tables, created in a new database. They have real column types,
    // like any table made with CREATE TABLE.
    static void seedSampleData(Catalog catalog) throws IOException {
        TableFactory factory = catalog.factory();
        Table users = factory.create("users", List.of("id", "name", "age"), List.of("DOUBLE", "STRING", "DOUBLE"));
        users.insert(1.0, "Alice", 30.0);
        users.insert(2.0, "Bob", 17.0);
        users.insert(3.0, "Carol", 45.0);
        users.flush();
        catalog.tables().put("users", users);

        Table products = factory.create("products", List.of("name", "price", "category"), List.of("STRING", "DOUBLE", "STRING"));
        products.insert("Hammer", 25.0, "tools");
        products.insert("Wrench", 150.0, "tools");
        products.insert("Paint", 40.0, "supplies");
        products.flush();
        catalog.tables().put("products", products);

        Table orders = factory.create("orders", List.of("id", "user_id", "total"), List.of("DOUBLE", "DOUBLE", "DOUBLE"));
        orders.insert(100.0, 1.0, 75.0);
        orders.insert(101.0, 1.0, 20.0);
        orders.insert(102.0, 3.0, 60.0);
        orders.flush();
        catalog.tables().put("orders", orders);
    }
}