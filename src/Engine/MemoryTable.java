package Engine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A table whose rows live in a list in memory. They last only as long as
 * the program runs, unless saved with Table.save.
 *
 * This is also what joins build their temporary result tables from.
 */
public class MemoryTable extends Table {

    // The stored rows. Package-visible so tests can inspect them; the engine
    // itself goes through the Table operations instead.
    final List<Object[]> rows = new ArrayList<>();

    MemoryTable(String name, List<String> columns) {
        super(name, columns);
    }

    MemoryTable(String name, List<String> columns, List<String> types) {
        super(name, columns, types);
    }

    @Override
    int rowCount() {
        return rows.size();
    }

    // The stored arrays themselves, not copies: cheap, but read-only to the caller.
    @Override
    Iterable<Object[]> scan() {
        return rows;
    }

    @Override
    void store(Object[] values) {
        rows.add(values.clone());
    }

    // Adds a row without checking it and without copying it: the table takes
    // ownership of the array. For building a table whose rows are already
    // known to be right (a join's result, or a file being loaded).
    void append(Object[] row) {
        rows.add(row);
    }

    @Override
    int deleteWhere(Predicate<Object[]> matches) {
        List<Object[]> kept = new ArrayList<>();
        int deleted = 0;
        for (Object[] row : rows) {
            if (matches.test(row)) {
                deleted++;
            } else {
                kept.add(row);
            }
        }
        // Only now, with every row decided, is anything changed.
        rows.clear();
        rows.addAll(kept);
        return deleted;
    }

    @Override
    int updateWhere(Predicate<Object[]> matches, UnaryOperator<Object[]> change) {
        // Phase 1: decide which rows are affected, and work out their new values.
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (matches.test(rows.get(i))) {
                hits.add(i);
            }
        }
        List<Object[]> replacements = new ArrayList<>(hits.size());
        for (int i : hits) {
            replacements.add(change.apply(rows.get(i)));
        }
        // Phase 2: nothing above can fail any more, so apply it all.
        for (int k = 0; k < hits.size(); k++) {
            rows.set(hits.get(k), replacements.get(k));
        }
        return hits.size();
    }
}
