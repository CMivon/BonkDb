
package Engine;

import java.io.*;
import java.util.*;
import Art.Art;

/**
 * Basic interactive command-line REPL for the engine.
 *
 * Type a SELECT, CREATE TABLE, INSERT, UPDATE, or DELETE statement ending
 * in ';' and press Enter (multi-line statements are fine -- it keeps
 * reading until it sees the ';'). Meta commands (not SQL) start with '.':
 *
 *   .tables            list loaded tables and their columns
 *   .load <name> <path> load a table from a saved .tbl file (see Table.save)
 *   .save <name> [path] save a table to a .tbl file (defaults to <name>.tbl)
 *   .help               show this message
 *   .exit / .quit       quit
 *
 * Starts with the same sample data as Test.java (users, products, orders)
 * so there's something to query right away, but you can now CREATE TABLE,
 * INSERT, and DELETE your own data through SQL too.
 */
public class Cli {

    static Map<String, Table> catalog = new HashMap<>();

    public static void main(String[] args) throws IOException {
        seedSampleData();

        System.out.println(Art.bonkMascot());
        System.out.println("Mini SQL engine -- type .help for commands, .exit to quit.");
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
                handleMetaCommand(line);
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
                runStatement(statement);
            }
        }

        System.out.println("Bye.");
    }

    static void runStatement(String sql) {
        try {
            MiniParser.Stmt stmt = MiniParser.parseSql(sql);
            if (stmt instanceof MiniParser.SelectStmt) {
                MiniParser.SelectStmt selectStmt = (MiniParser.SelectStmt) stmt;
                if (selectStmt.chaos) {
                    System.out.println("bonk! shuffling results into chaos...");
                }
                ExecutionDemo.QueryResult result = ExecutionDemo.executeSelect(selectStmt, catalog);
                printTable(result);
            } else if (stmt instanceof MiniParser.CreateTableStmt) {
                MiniParser.CreateTableStmt createStmt = (MiniParser.CreateTableStmt) stmt;
                ExecutionDemo.executeCreateTable(createStmt, catalog);
                System.out.println("Table '" + createStmt.table + "' created.");
            } else if (stmt instanceof MiniParser.InsertStmt) {
                MiniParser.InsertStmt insertStmt = (MiniParser.InsertStmt) stmt;
                ExecutionDemo.executeInsert(insertStmt, catalog);
                System.out.println("1 row inserted into '" + insertStmt.table + "'.");
            } else if (stmt instanceof MiniParser.DeleteStmt) {
                MiniParser.DeleteStmt deleteStmt = (MiniParser.DeleteStmt) stmt;
                int deleted = ExecutionDemo.executeDelete(deleteStmt, catalog);
                System.out.println(deleted + " row" + (deleted == 1 ? "" : "s") + " bonked out of '"
                    + deleteStmt.table + "'.");
            } else if (stmt instanceof MiniParser.UpdateStmt) {
                MiniParser.UpdateStmt updateStmt = (MiniParser.UpdateStmt) stmt;
                int updated = ExecutionDemo.executeUpdate(updateStmt, catalog);
                System.out.println(updated + " row" + (updated == 1 ? "" : "s") + " bonked into shape in '"
                    + updateStmt.table + "'.");
            } else {
                System.out.println("Error: don't know how to run statement: " + stmt);
            }
        } catch (RuntimeException e) {
            System.out.println("Error: " + e.getMessage());
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

    static void handleMetaCommand(String line) {
        String[] parts = line.split("\\s+");
        switch (parts[0]) {
            case ".help":
                System.out.println(
                    ".tables              list loaded tables and their columns\n" +
                    ".load <name> <path>  load a table from a saved .tbl file\n" +
                    ".save <name> [path]  save a table to a .tbl file (defaults to <name>.tbl)\n" +
                    ".panic               corrupt a random cell in a random table -- chaos mode\n" +
                    ".help                show this message\n" +
                    ".exit / .quit        quit"
                );
                break;
            case ".panic":
                String corrupted = CorruptData.corrupt(catalog);
                System.out.println(corrupted != null
                    ? "\uD83D\uDCA5 PANIC! Goodbye Table '" + corrupted + "'"
                    : "Nothing to panic about -- every table is empty.");
                break;
            case ".tables":
                if (catalog.isEmpty()) {
                    System.out.println("(no tables loaded)");
                } else {
                    for (Map.Entry<String, Table> e : catalog.entrySet()) {
                        System.out.println("  " + e.getKey() + " " + e.getValue().columns);
                    }
                }
                break;
            case ".load":
                if (parts.length != 3) {
                    System.out.println("Usage: .load <name> <path>");
                    break;
                }
                try {
                    Table t = Table.load(parts[2]);
                    catalog.put(parts[1], t);
                    System.out.println("Loaded '" + parts[1] + "' (" + t.rows.size() + " rows) from " + parts[2]);
                } catch (IOException e) {
                    System.out.println("Error loading " + parts[2] + ": " + e.getMessage());
                }
                break;
            case ".save":
                if (parts.length != 2 && parts.length != 3) {
                    System.out.println("Usage: .save <name> [path]  (path defaults to <name>.tbl)");
                    break;
                }
                Table t = catalog.get(parts[1]);
                if (t == null) {
                    System.out.println("No such table: " + parts[1]);
                    break;
                }
                String savePath = parts.length == 3 ? parts[2] : parts[1] + ".tbl";
                try {
                    t.save(savePath);
                    System.out.println("Saved '" + parts[1] + "' to " + savePath);
                } catch (IOException e) {
                    System.out.println("Error saving to " + savePath + ": " + e.getMessage());
                }
                break;
            default:
                System.out.println("Unknown command: " + parts[0] + " (try .help)");
        }
    }

    static void seedSampleData() {
        Table users = new Table("users", List.of("id", "name", "age"));
        users.insert(1.0, "Alice", 30.0);
        users.insert(2.0, "Bob", 17.0);
        users.insert(3.0, "Carol", 45.0);
        catalog.put("users", users);

        Table products = new Table("products", List.of("name", "price", "category"));
        products.insert("Hammer", 25.0, "tools");
        products.insert("Wrench", 150.0, "tools");
        products.insert("Paint", 40.0, "supplies");
        catalog.put("products", products);

        Table orders = new Table("orders", List.of("id", "user_id", "total"));
        orders.insert(100.0, 1.0, 75.0);
        orders.insert(101.0, 1.0, 20.0);
        orders.insert(102.0, 3.0, 60.0);
        catalog.put("orders", orders);
    }
}