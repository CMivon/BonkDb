package Engine;

import java.util.*;

/**
 * Minimal execution engine: takes a SelectStmt AST (from MiniParser) and
 * runs it against a Table (see Table.java). Tree-walking style -- no
 * separate query plan yet, the AST is evaluated directly.
 *
 * Pure library code -- no main() here. See Test.java for a runnable
 * demo/test driver that exercises this class.
 *
 * This is the seed you'll later refactor into an iterator/Volcano model
 * once you add more JOIN strategies.
 */
public class ExecutionDemo {

    // A query's results, paired with the display names of its output columns
    // (e.g. "name", "users.id", "COUNT(*)") -- callers like the CLI need
    // headers, not just raw row data.
    static class QueryResult {
        final List<String> columns;
        final List<Object[]> rows;
        QueryResult(List<String> columns, List<Object[]> rows) {
            this.columns = columns; this.rows = rows;
        }
    }

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

    // "vibes = 'immaculate'" is a gimmick: if it appears *anywhere* in a
    // condition -- alone, ANDed/ORed with real conditions, nested arbitrarily
    // deep -- the whole condition is treated as unconditionally true, and is
    // never actually evaluated at all. This is checked once per statement
    // (not per row -- it doesn't depend on row data) via isUnconditionallyTrue,
    // which every WHERE/ON call site below goes through instead of calling
    // evaluate() directly.
    static boolean isUnconditionallyTrue(MiniParser.Expr condition) {
        return condition == null || containsImmaculateVibes(condition);
    }

    static boolean containsImmaculateVibes(MiniParser.Expr expr) {
        if (!(expr instanceof MiniParser.BinaryOp)) {
            return false; // Column/Literal/Star leaves can't contain it
        }
        MiniParser.BinaryOp bin = (MiniParser.BinaryOp) expr;
        if (bin.op.equals("=") && isVibesImmaculate(bin.left, bin.right)) {
            return true;
        }
        return containsImmaculateVibes(bin.left) || containsImmaculateVibes(bin.right);
    }

    static boolean isVibesImmaculate(MiniParser.Expr a, MiniParser.Expr b) {
        return (isVibesColumn(a) && isImmaculateLiteral(b)) || (isVibesColumn(b) && isImmaculateLiteral(a));
    }

    static boolean isVibesColumn(MiniParser.Expr e) {
        return e instanceof MiniParser.Column && ((MiniParser.Column) e).name.equalsIgnoreCase("vibes");
    }

    static boolean isImmaculateLiteral(MiniParser.Expr e) {
        return e instanceof MiniParser.Literal
            && "immaculate".equalsIgnoreCase(String.valueOf(((MiniParser.Literal) e).value));
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
    // leftLabel/rightLabel decide how each side's columns get qualified in
    // the joined result ("label.column") -- normally the table's own name,
    // but the caller passes an alias instead when one was given. This is
    // what makes a self-join (same table on both sides) actually work: with
    // two different labels, "a.num" and "b.num" are genuinely distinct
    // column names, instead of both collapsing to "what.num" and silently
    // colliding in the same lookup map.
    static Table join(Table left, String leftLabel, Table right, String rightLabel, MiniParser.Expr on) {
        List<String> combinedColumns = new ArrayList<>();
        // A null leftLabel means the left side is itself a join result, whose
        // columns are already qualified ("users.id"); prefixing them again would
        // hide them from any later reference to an earlier table.
        for (String c : left.columns) combinedColumns.add(leftLabel == null ? c : leftLabel + "." + c);
        for (String c : right.columns) combinedColumns.add(rightLabel + "." + c);

        MemoryTable joined = new MemoryTable((leftLabel == null ? left.name : leftLabel) + "_JOIN_" + rightLabel, combinedColumns);

        // Read the right-hand side once, up front, rather than once per left
        // row. For an in-memory table that makes no difference, but for a table
        // stored on disk it is the difference between one read and thousands.
        List<Object[]> rightRows = new ArrayList<>();
        for (Object[] rightRow : right.scan()) {
            rightRows.add(rightRow);
        }

        boolean alwaysMatches = isUnconditionallyTrue(on); // e.g. "ON vibes = 'imm' -> full cross join
        for (Object[] leftRow : left.scan()) {
            for (Object[] rightRow : rightRows) {
                Object[] combinedRow = new Object[leftRow.length + rightRow.length];
                System.arraycopy(leftRow, 0, combinedRow, 0, leftRow.length);
                System.arraycopy(rightRow, 0, combinedRow, leftRow.length, rightRow.length);

                if (alwaysMatches || asBool(evaluate(on, joined, combinedRow))) {
                    joined.append(combinedRow);
                }
            }
        }
        return joined;
    }

    // ---------- DDL executor ----------

    // Creates a new, empty Table from a CREATE TABLE statement and registers
    // it in the catalog. Unlike executeSelect, there's no result set to
    // return -- callers just check for a thrown exception.
    static void executeCreateTable(MiniParser.CreateTableStmt stmt, Map<String, Table> catalog) {
        executeCreateTable(stmt, catalog, (name, columns, types) -> new MemoryTable(name, columns, types));
    }

    // The same, but the caller chooses what kind of table gets made.
    static void executeCreateTable(MiniParser.CreateTableStmt stmt, Map<String, Table> catalog, TableFactory factory) {
        if (catalog.containsKey(stmt.table)) {
            throw new RuntimeException("Table already exists: " + stmt.table);
        }
        List<String> columnNames = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (MiniParser.ColumnDef cd : stmt.columns) {
            columnNames.add(cd.name);
            types.add(cd.type);
        }
        catalog.put(stmt.table, factory.create(stmt.table, columnNames, types));
    }

    // Inserts one row. Delegates the actual insert (and its type validation
    // against a CREATE TABLE'd schema) to Table.insert() -- this method's
    // only job is resolving which VALUES entry goes in which column position.
    static void executeInsert(MiniParser.InsertStmt stmt, Map<String, Table> catalog) {
        Table table = catalog.get(stmt.table);
        if (table == null) {
            throw new RuntimeException("No such table: " + stmt.table);
        }

        Object[] row;
        if (stmt.columns.isEmpty()) {
            // No column list: values must cover every column, in the
            // table's own declared order.
            if (stmt.values.size() != table.columns.size()) {
                throw new RuntimeException("Table '" + stmt.table + "' has " + table.columns.size()
                    + " columns but " + stmt.values.size() + " values were given");
            }
            row = new Object[stmt.values.size()];
            for (int i = 0; i < stmt.values.size(); i++) {
                row[i] = stmt.values.get(i).value;
            }
        } else {
            // Explicit column list: this engine has no NULL support, so every
            // table column must be named -- partial inserts aren't possible.
            if (stmt.columns.size() != table.columns.size()) {
                throw new RuntimeException("INSERT with a partial column list is not supported "
                    + "(no NULL support yet) -- name all " + table.columns.size() + " columns of '"
                    + stmt.table + "'");
            }
            if (stmt.columns.size() != stmt.values.size()) {
                throw new RuntimeException("Column count (" + stmt.columns.size()
                    + ") doesn't match value count (" + stmt.values.size() + ")");
            }
            row = new Object[table.columns.size()];
            for (int i = 0; i < stmt.columns.size(); i++) {
                int idx = table.indexOf(stmt.columns.get(i)); // throws on unknown column
                row[idx] = stmt.values.get(i).value;
            }
        }

        table.insert(row); // reuses Table's own type validation
    }

    // Deletes matching rows (or every row, if WHERE is absent) and returns
    // how many were removed, so callers (like the CLI) can report it.
    static int executeDelete(MiniParser.DeleteStmt stmt, Map<String, Table> catalog) {
        Table table = catalog.get(stmt.table);
        if (table == null) {
            throw new RuntimeException("No such table: " + stmt.table);
        }

        boolean alwaysMatches = isUnconditionallyTrue(stmt.where); // e.g. WHERE vibes = 'immaculate' -> deletes everything
        return table.deleteWhere(row -> alwaysMatches || asBool(evaluate(stmt.where, table, row)));
    }

    // Updates matching rows (or every row, if WHERE is absent) in place and
    // returns how many were changed. Column indices and type validation are
    // resolved once up front -- before touching any row -- so an unknown
    // column or a bad-type value fails the whole statement rather than
    // leaving some rows updated and others not.
    static int executeUpdate(MiniParser.UpdateStmt stmt, Map<String, Table> catalog) {
        Table table = catalog.get(stmt.table);
        if (table == null) {
            throw new RuntimeException("No such table: " + stmt.table);
        }

        List<Integer> indices = new ArrayList<>();
        List<Object> newValues = new ArrayList<>();
        for (MiniParser.Assignment a : stmt.assignments) {
            int idx = table.indexOf(a.column); // throws on unknown column
            Object value = a.value.value;
            if (table.types != null) {
                String expected = table.types.get(idx);
                boolean ok = expected.equals("DOUBLE") ? value instanceof Double : value instanceof String;
                if (!ok) {
                    throw new RuntimeException("Column '" + a.column + "' expects " + expected
                        + ", got " + (value == null ? "null" : value.getClass().getSimpleName())
                        + " (" + value + ")");
                }
            }
            indices.add(idx);
            newValues.add(value);
        }

        boolean alwaysMatches = isUnconditionallyTrue(stmt.where); // e.g. WHERE vibes = 'immaculate' -> updates everything
        return table.updateWhere(
            row -> alwaysMatches || asBool(evaluate(stmt.where, table, row)),
            row -> {
                Object[] changed = row.clone(); // never modify the stored row itself
                for (int i = 0; i < indices.size(); i++) {
                    changed[indices.get(i)] = newValues.get(i);
                }
                return changed;
            });
    }

    // ---------- Select executor ----------

    static QueryResult executeSelect(MiniParser.SelectStmt stmt, Map<String, Table> catalog) {
        Table base = catalog.get(stmt.table);
        if (base == null) {
            throw new RuntimeException("No such table: " + stmt.table);
        }

        // 1. Apply any JOINs left-to-right, folding each into a combined
        //    virtual table with qualified ("label.column") column names.
        //    "label" is an alias when one was given (AS), otherwise the
        //    real table name -- using the alias is what lets a self-join
        //    (same table joined to itself) tell its two sides apart.
        Table table = base;
        String leftLabel = stmt.tableAlias != null ? stmt.tableAlias : stmt.table;
        for (MiniParser.Join join : stmt.joins) {
            Table right = catalog.get(join.table);
            if (right == null) {
                throw new RuntimeException("No such table: " + join.table);
            }
            String rightLabel = join.alias != null ? join.alias : join.table;
            table = join(table, leftLabel, right, rightLabel, join.on);
            leftLabel = null; // the combined table's columns are already qualified; later joins keep them as they are
        }
        // "FROM users AS u" with no JOIN: run the query on a copy named after the
        // alias. Table.indexOfColumn accepts a qualifier equal to the table's own
        // name, so "u.name" then resolves and "users.name" no longer does, as in
        // standard SQL. Unqualified column names keep working.
        if (stmt.joins.isEmpty() && stmt.tableAlias != null) {
            MemoryTable aliased = new MemoryTable(stmt.tableAlias, base.columns);
            for (Object[] row : base.scan()) {
                aliased.append(row);
            }
            table = aliased;
        }

        // 2. Scan + filter (keep full rows -- both ORDER BY and GROUP BY may
        //    reference columns that aren't in the SELECT list, so we can't
        //    project yet)
        List<Object[]> filtered = new ArrayList<>();
        boolean alwaysMatches = isUnconditionallyTrue(stmt.where); // e.g. WHERE vibes = 'immaculate' -> every row matches
        for (Object[] row : table.scan()) {
            if (alwaysMatches || asBool(evaluate(stmt.where, table, row))) {
                filtered.add(row);
            }
        }

        // 3. GROUP BY / aggregates are a genuinely different shape of
        //    execution (many rows collapse into one per group), so they get
        //    their own path rather than being squeezed into the per-row one.
        boolean hasAggregates = false;
        for (MiniParser.Expr e : stmt.columns) {
            if (e instanceof MiniParser.AggregateCall) { hasAggregates = true; break; }
        }
        if (hasAggregates || !stmt.groupBy.isEmpty()) {
            return executeAggregateSelect(stmt, table, filtered);
        }

        // ---------- Plain (non-aggregate) path ----------

        // 4. Resolve which columns to project (handle SELECT *)
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

        // 5. Sort, if requested -- or shuffle, for ORDER BY CHAOS (bonkDB's
        // one legitimately silly feature: a "sort" that's actually chaos).
        // Combined with LIMIT, this doubles as a genuine "N random rows"
        // operator, since it slots into exactly the same pipeline position
        // a real sort would. Note: this means LIMIT can no longer short-
        // circuit the scan the way it used to -- we need every matching row
        // in hand before we know which ones sort (or land) at the top. (Real
        // engines avoid a full sort here with a top-k/heap strategy when the
        // limit is much smaller than the row count -- worth revisiting later.)
        if (stmt.chaos) {
            Collections.shuffle(filtered);
        } else if (!stmt.orderBy.isEmpty()) {
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

        // 6. Apply LIMIT
        if (stmt.limit != null && filtered.size() > stmt.limit) {
            filtered = filtered.subList(0, stmt.limit);
        }

        // 7. Project
        List<Object[]> result = new ArrayList<>();
        for (Object[] row : filtered) {
            Object[] projected = new Object[outputColumns.size()];
            for (int i = 0; i < outputColumns.size(); i++) {
                projected[i] = row[table.indexOfColumn(outputColumns.get(i))];
            }
            result.add(projected);
        }

        List<String> columnNames = new ArrayList<>();
        for (MiniParser.Column c : outputColumns) columnNames.add(c.display());
        return new QueryResult(columnNames, result);
    }

    // ---------- Aggregate execution path ----------
    // Groups the already-filtered rows by stmt.groupBy (or treats the whole
    // set as one group when GROUP BY is absent, matching SQL semantics --
    // "SELECT COUNT(*) FROM t WHERE ..." always returns exactly one row),
    // then computes one aggregate value per group per aggregate in the
    // SELECT list.
    static QueryResult executeAggregateSelect(MiniParser.SelectStmt stmt, Table table, List<Object[]> filtered) {
        for (MiniParser.Expr e : stmt.columns) {
            if (e instanceof MiniParser.Star) {
                throw new RuntimeException("SELECT * is not supported with GROUP BY / aggregates");
            }
        }
        if (!stmt.orderBy.isEmpty()) {
            // Sorting an aggregated result needs to sort by *output* columns
            // (e.g. COUNT(*)), not raw table columns -- a different enough
            // problem that it's left as a future extension.
            throw new RuntimeException("ORDER BY on GROUP BY / aggregate queries is not yet supported");
        }

        // Group rows by their GROUP BY column values. A List<Object> works as
        // a map key because Double/String already have sensible equals/hashCode,
        // and List's equals/hashCode combine its elements' automatically.
        Map<List<Object>, List<Object[]>> groups = new LinkedHashMap<>();
        if (stmt.groupBy.isEmpty()) {
            groups.put(new ArrayList<>(), filtered); // one group: the whole table
        } else {
            for (Object[] row : filtered) {
                List<Object> key = new ArrayList<>();
                for (MiniParser.Column gc : stmt.groupBy) {
                    key.add(row[table.indexOfColumn(gc)]);
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }

        List<Object[]> result = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Object[]>> entry : groups.entrySet()) {
            List<Object> groupKey = entry.getKey();
            List<Object[]> groupRows = entry.getValue();

            Object[] outRow = new Object[stmt.columns.size()];
            for (int i = 0; i < stmt.columns.size(); i++) {
                MiniParser.Expr e = stmt.columns.get(i);
                if (e instanceof MiniParser.AggregateCall) {
                    outRow[i] = computeAggregate((MiniParser.AggregateCall) e, table, groupRows);
                } else if (e instanceof MiniParser.Column) {
                    MiniParser.Column c = (MiniParser.Column) e;
                    int idx = groupByIndexOf(stmt.groupBy, c);
                    if (idx == -1) {
                        throw new RuntimeException(
                            "Column '" + c + "' must appear in GROUP BY or be used inside an aggregate function");
                    }
                    outRow[i] = groupKey.get(idx);
                } else {
                    throw new RuntimeException("Unsupported select item: " + e);
                }
            }
            result.add(outRow);
        }

        if (stmt.chaos) {
            Collections.shuffle(result);
        }

        if (stmt.limit != null && result.size() > stmt.limit) {
            result = result.subList(0, stmt.limit);
        }

        List<String> columnNames = new ArrayList<>();
        for (MiniParser.Expr e : stmt.columns) {
            if (e instanceof MiniParser.AggregateCall) {
                columnNames.add(e.toString()); // e.g. "COUNT(*)", "SUM(price)" -- already clean
            } else {
                columnNames.add(((MiniParser.Column) e).display());
            }
        }
        return new QueryResult(columnNames, result);
    }

    // Column doesn't override equals(), so matching a SELECT-list column
    // against the GROUP BY list is done by comparing table+name manually.
    static int groupByIndexOf(List<MiniParser.Column> groupBy, MiniParser.Column target) {
        for (int i = 0; i < groupBy.size(); i++) {
            MiniParser.Column gc = groupBy.get(i);
            if (Objects.equals(gc.table, target.table) && gc.name.equals(target.name)) {
                return i;
            }
        }
        return -1;
    }

    static Object computeAggregate(MiniParser.AggregateCall agg, Table table, List<Object[]> rows) {
        if (agg.function.equals("COUNT")) {
            if (agg.star) return (double) rows.size();
            int idx = table.indexOfColumn(agg.argument);
            return (double) rows.size(); // no NULLs in this engine yet, so COUNT(col) == COUNT(*)
        }

        int idx = table.indexOfColumn(agg.argument);

        if (agg.function.equals("SUM") || agg.function.equals("AVG")) {
            double sum = 0;
            for (Object[] row : rows) {
                if (!(row[idx] instanceof Double)) {
                    throw new RuntimeException(agg.function + " requires a numeric column, got: " + row[idx]);
                }
                sum += (Double) row[idx];
            }
            if (agg.function.equals("SUM")) return sum;
            return rows.isEmpty() ? null : sum / rows.size(); // AVG of empty group is NULL, per SQL
        }

        if (agg.function.equals("MIN") || agg.function.equals("MAX")) {
            if (rows.isEmpty()) return null;
            Object best = rows.get(0)[idx];
            for (int i = 1; i < rows.size(); i++) {
                Object candidate = rows.get(i)[idx];
                int cmp = compareValues(candidate, best);
                if ((agg.function.equals("MIN") && cmp < 0) || (agg.function.equals("MAX") && cmp > 0)) {
                    best = candidate;
                }
            }
            return best;
        }

        throw new RuntimeException("Unknown aggregate function: " + agg.function);
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
}
