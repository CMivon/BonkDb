package Engine;

import java.util.*;
import java.io.*;

/**
 * In-memory table representation with simple text-file persistence.
 * Stands in for a real storage engine (pages, buffer pool, B+ trees) --
 * this is the layer everything else (parser, executor) talks to.
 */
public class Table {
    final String name;
    final List<String> columns;
    // Declared column types ("DOUBLE"/"STRING"), parallel to columns -- null
    // when a table was built without an explicit schema (the old behavior:
    // types get *inferred* from the first row instead, see inferTypes()).
    // CREATE TABLE always supplies this; ad-hoc Tables built directly in Java
    // (as in Test.java) generally don't.
    final List<String> types;
    final List<Object[]> rows = new ArrayList<>();
    final Map<String, Integer> columnIndex = new HashMap<>();

    Table(String name, List<String> columns) {
        this(name, columns, null);
    }

    Table(String name, List<String> columns, List<String> types) {
        if (types != null && types.size() != columns.size()) {
            throw new RuntimeException("Column/type count mismatch for table " + name);
        }
        this.name = name;
        this.columns = columns;
        this.types = types;
        for (int i = 0; i < columns.size(); i++) {
            columnIndex.put(columns.get(i), i);
        }
    }

    void insert(Object... values) {
        if (values.length != columns.size()) {
            throw new RuntimeException("Column count mismatch for table " + name);
        }
        // Only tables with a declared schema (from CREATE TABLE) get validated --
        // ad-hoc Tables built directly in Java have no schema to check against.
        if (types != null) {
            for (int i = 0; i < values.length; i++) {
                boolean ok = types.get(i).equals("DOUBLE")
                    ? values[i] instanceof Double
                    : values[i] instanceof String;
                if (!ok) {
                    throw new RuntimeException("Column '" + columns.get(i) + "' expects " + types.get(i)
                        + ", got " + (values[i] == null ? "null" : values[i].getClass().getSimpleName())
                        + " (" + values[i] + ")");
                }
            }
        }
        rows.add(values);
    }

    int indexOf(String column) {
        Integer idx = columnIndex.get(column);
        if (idx == null) {
            throw new RuntimeException("Unknown column '" + column + "' in table " + name);
        }
        return idx;
    }

    // Resolves a possibly-qualified column reference (e.g. "orders.total" from
    // a JOIN's virtual table, or a plain "age" from a single table). Falls back
    // to the unqualified name when the qualifier is just this table's own name,
    // so "users.age" still works against a table whose columns aren't prefixed.
    int indexOfColumn(MiniParser.Column col) {
        if (col.table != null) {
            String qualified = col.table + "." + col.name;
            if (columnIndex.containsKey(qualified)) {
                return columnIndex.get(qualified);
            }
            if (col.table.equals(this.name)) {
                return indexOf(col.name);
            }
            throw new RuntimeException("Unknown column '" + qualified + "' in table " + name);
        }
        return indexOf(col.name);
    }

    // ---------- Persistence: simple text file format ----------
    //
    // TABLE <name>
    // COLUMNS col1,col2,...
    // TYPES type1,type2,...
    // ROWS
    // val1,val2,...
    // val1,val2,...

    void save(String path) throws IOException {
        List<String> types = inferTypes();
        try (BufferedWriter w = new BufferedWriter(new FileWriter(path))) {
            w.write("TABLE " + name);
            w.newLine();
            w.write("COLUMNS " + String.join(",", columns));
            w.newLine();
            w.write("TYPES " + String.join(",", types));
            w.newLine();
            w.write("ROWS");
            w.newLine();
            for (Object[] row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) line.append(",");
                    line.append(escape(String.valueOf(row[i])));
                }
                w.write(line.toString());
                w.newLine();
            }
        }
    }

    static Table load(String path) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String tableLine = r.readLine(); // "TABLE <name>"
            String tableName = tableLine.substring("TABLE ".length()).trim();

            String columnsLine = r.readLine(); // "COLUMNS a,b,c"
            List<String> cols = Arrays.asList(columnsLine.substring("COLUMNS ".length()).split(",", -1));

            String typesLine = r.readLine(); // "TYPES STRING,DOUBLE"
            List<String> types = Arrays.asList(typesLine.substring("TYPES ".length()).split(",", -1));

            r.readLine(); // "ROWS" marker, discard

            Table table = new Table(tableName, new ArrayList<>(cols), new ArrayList<>(types));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] fields = splitEscaped(line);
                Object[] values = new Object[fields.length];
                for (int i = 0; i < fields.length; i++) {
                    String raw = unescape(fields[i]);
                    values[i] = types.get(i).equals("DOUBLE") ? (Object) Double.parseDouble(raw) : (Object) raw;
                }
                table.rows.add(values);
            }
            return table;
        }
    }

	//TODO: I don't really like infering Types. Maybe throw an error wherever this is needed instead?
    private List<String> inferTypes() {
        if (types != null) {
            return types; // declared schema (from CREATE TABLE) always wins
        }
        // No declared schema (an ad-hoc Table): fall back to guessing from
        // the first row, as before. Note this guesses "STRING" for a table
        // that's still empty -- another reason CREATE TABLE tables carry
        // their real types explicitly instead of relying on this.
        List<String> inferred = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (!rows.isEmpty() && rows.get(0)[i] instanceof Double) {
                inferred.add("DOUBLE");
            } else {
                inferred.add("STRING");
            }
        }
        return inferred;
    }

    // Minimal escaping so commas/newlines inside string values don't break parsing.
    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace(",", "\\,").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                if (next == 'n') out.append('\n');
                else out.append(next); // handles \\ and \,
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // Splits on unescaped commas only (so "\," doesn't count as a separator).
    private static String[] splitEscaped(String line) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < line.length()) {
                current.append(c).append(line.charAt(++i));
            } else if (c == ',') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts.toArray(new String[0]);
    }
}