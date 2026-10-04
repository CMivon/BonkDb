package Engine;

import java.io.*;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A table: a name, an ordered list of columns (optionally with declared
 * types), and rows. This class holds what every kind of table has in common;
 * where the rows actually live is up to the subclass. Today that is
 * MemoryTable. A table backed by a file can be added beside it without the
 * executor or the CLI having to change, because they only use what is
 * declared here.
 *
 * Nothing outside a table should reach into its rows. Everything the engine
 * does to rows goes through the five operations below:
 *
 *   scan()         read every row
 *   insert(...)    add a row (validated against the declared types, if any)
 *   deleteWhere    remove the rows a predicate selects
 *   updateWhere    replace the rows a predicate selects
 *   rowCount()     how many rows there are
 *
 * Rows are Object[] values in column order (Doubles and Strings).
 *
 * The text-file format (save/load) lives here too, since it is written in
 * terms of those operations and works for any kind of table.
 */
public abstract class Table {
    final String name;
    final List<String> columns;
    // Declared column types ("DOUBLE"/"STRING"), parallel to columns -- null
    // when a table was built without an explicit schema (the old behavior:
    // types get *inferred* from the first row instead, see inferTypes()).
    // CREATE TABLE always supplies this; ad-hoc tables built directly in Java
    // (as in Test.java) generally don't.
    final List<String> types;
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

    // ---------- Row operations: each kind of table provides these ----------

    // The number of rows.
    abstract int rowCount();

    boolean isEmpty() {
        return rowCount() == 0;
    }

    // Every row, in the table's own order. The arrays that come back belong to
    // the table: callers must treat them as read-only (copy one before
    // changing it). Changing the table while a scan is in progress is not
    // allowed; DELETE and UPDATE use deleteWhere/updateWhere for that reason.
    abstract Iterable<Object[]> scan();

    // Deletes every row for which matches returns true, and returns how many
    // that was. The predicate is applied to every row before anything is
    // deleted, so if it throws, the table is left exactly as it was.
    abstract int deleteWhere(Predicate<Object[]> matches);

    // Replaces every row for which matches returns true with change.apply(row),
    // and returns how many were replaced. change must return a new array (it is
    // given a row it must not modify). Both functions are applied to every
    // selected row before any row is replaced, so if either throws, the table
    // is left exactly as it was. A table in memory keeps each row's position;
    // a table stored in a file does too unless a grown row no longer fits where
    // it was, in which case it moves. Whether a new row's values are checked
    // against the column types depends on the kind of table: a MemoryTable
    // doesn't check (the caller does, see executeUpdate); a HeapTable refuses
    // a row its file can't hold, and in that case changes nothing.
    abstract int updateWhere(Predicate<Object[]> matches, UnaryOperator<Object[]> change);

    // Stores a row that insert() has already validated. Implementations must
    // not keep the caller's array.
    abstract void store(Object[] values);

    // Releases whatever the table holds open (a file, for a stored table).
    // Does nothing for a table in memory.
    void close() throws IOException {
    }

    final void insert(Object... values) {
        if (values.length != columns.size()) {
            throw new RuntimeException("Column count mismatch for table " + name);
        }
        // Only tables with a declared schema (from CREATE TABLE) get validated --
        // ad-hoc tables built directly in Java have no schema to check against.
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
        store(values);
    }

    // ---------- Finding columns ----------

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
            for (Object[] row : scan()) {
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

    // Reads a table from the text format. The result is always an in-memory table.
    static MemoryTable load(String path) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String tableLine = r.readLine(); // "TABLE <name>"
            String tableName = tableLine.substring("TABLE ".length()).trim();

            String columnsLine = r.readLine(); // "COLUMNS a,b,c"
            List<String> cols = Arrays.asList(columnsLine.substring("COLUMNS ".length()).split(",", -1));

            String typesLine = r.readLine(); // "TYPES STRING,DOUBLE"
            List<String> types = Arrays.asList(typesLine.substring("TYPES ".length()).split(",", -1));

            r.readLine(); // "ROWS" marker, discard

            MemoryTable table = new MemoryTable(tableName, new ArrayList<>(cols), new ArrayList<>(types));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] fields = splitEscaped(line);
                Object[] values = new Object[fields.length];
                for (int i = 0; i < fields.length; i++) {
                    String raw = unescape(fields[i]);
                    values[i] = types.get(i).equals("DOUBLE") ? (Object) Double.parseDouble(raw) : (Object) raw;
                }
                table.append(values);
            }
            return table;
        }
    }

    private List<String> inferTypes() {
        if (types != null) {
            return types; // declared schema (from CREATE TABLE) always wins
        }
        // No declared schema (an ad-hoc table): fall back to guessing from
        // the first row, as before. Note this guesses "STRING" for a table
        // that's still empty -- another reason CREATE TABLE tables carry
        // their real types explicitly instead of relying on this.
        Object[] first = null;
        for (Object[] row : scan()) {
            first = row;
            break;
        }
        List<String> inferred = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (first != null && first[i] instanceof Double) {
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
