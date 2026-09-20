import java.util.*;
import java.io.*;

//TODO: want a better representation. Don't want to load/save entire file at once
/**
 * In-memory table representation with simple text-file persistence.
 * Stands in for a real storage engine (pages, buffer pool, B+ trees) --
 * this is the layer everything else (parser, executor) talks to.
 */
public class Table {
    final String name;
    final List<String> columns;
    final List<Object[]> rows = new ArrayList<>();
    final Map<String, Integer> columnIndex = new HashMap<>();

    Table(String name, List<String> columns) {
        this.name = name;
        this.columns = columns;
        for (int i = 0; i < columns.size(); i++) {
            columnIndex.put(columns.get(i), i);
        }
    }

    void insert(Object... values) {
        if (values.length != columns.size()) {
            throw new RuntimeException("Column count mismatch for table " + name);
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

            Table table = new Table(tableName, new ArrayList<>(cols));
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

    private List<String> inferTypes() {
        List<String> types = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (!rows.isEmpty() && rows.get(0)[i] instanceof Double) {
                types.add("DOUBLE");
            } else {
                types.add("STRING");
            }
        }
        return types;
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