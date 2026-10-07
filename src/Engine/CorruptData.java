// package Engine;

// import java.util.*;

// //TODO: This doesn't work anymore since the HeapTable implementation. I want to do this in a future update
// //TODO: We need a more creative way to corrupt data instead of just adding words to it.
// // Maybe Swap data around? Maybe make affect the whole structure of Table so it crashes?
// /**
//  * Chaos-mode gimmick: picks a random table, a random row, and a random
//  * column within it, then appends "TEST" onto whatever value is already
//  * there -- regardless of the column's declared type. That's the point:
//  * this can genuinely corrupt a CREATE TABLE'd column's type invariant
//  * (a DOUBLE column ending up holding a String), which downstream code
//  * (SUM, comparisons, etc.) isn't guaranteed to handle gracefully.
//  *
//  * Not part of the real engine -- purely a bonkDB flourish. Use it on
//  * data you don't mind breaking.
//  */
// public class CorruptData {

//     static final Random random = new Random();

//     // Picks one random table out of the catalog, restricted to tables that
//     // actually have rows (an empty table has nothing to corrupt). Returns
//     // null if the catalog is empty or every table in it is empty.
//     //
//     // Private -- an implementation detail of corrupt()/corruptHelper(),
//     // not part of this class's public surface.
//     private static Table findRandomTable(Map<String, Table> catalog) {
//         List<Table> candidates = new ArrayList<>();
//         for (Table t : catalog.values()) {
//             if (!t.rows.isEmpty()) candidates.add(t);
//         }
//         if (candidates.isEmpty()) {
//             return null;
//         }
//         return candidates.get(random.nextInt(candidates.size()));
//     }

//     // The public entry point: corrupts one random cell in one random table
//     // from the catalog. Callers just pass the catalog -- no need to know
//     // about Table internals, row/column indices, or how the target gets
//     // picked; all of that is corruptHelper()'s problem. Returns the name of
//     // the table that got corrupted, or null if there was nothing to corrupt
//     // (empty catalog, or every table is empty).
//     public static String corrupt(Map<String, Table> catalog) {
//         return corruptHelper(catalog);
//     }

//     // Does the actual work: finds a table, picks a row/column within it,
//     // and mutates that cell. Kept private -- this is corrupt()'s
//     // implementation detail, not something callers should reach for directly.
//     // Prints what happened (for anyone watching the console), but only
//     // returns the table's name -- that's the one piece of info worth
//     // handing back to a caller that shouldn't need to know about rows,
//     // columns, or Table internals.
//     private static String corruptHelper(Map<String, Table> catalog) {
//         Table table = findRandomTable(catalog);
//         if (table == null) {
//             return null;
//         }

//         int rowIdx = random.nextInt(table.rows.size());
//         int colIdx = random.nextInt(table.columns.size());

//         Object[] row = table.rows.get(rowIdx);
//         Object oldValue = row[colIdx];
//         Object newValue = "BONK_" + String.valueOf(oldValue) + "_BONK";
//         row[colIdx] = newValue;

//         System.out.println("bonked '" + table.name + "' row " + rowIdx + ", column '"
//             + table.columns.get(colIdx) + "': " + oldValue + " -> " + newValue);

//         return table.name;
//     }

//     public static void main(String[] args) {
//         // Quick standalone demo against a couple of throwaway tables.
//         Table users = new Table("users", List.of("name", "age"));
//         users.insert("Alice", 30.0);
//         users.insert("Bob", 17.0);

//         Table products = new Table("products", List.of("name", "price"));
//         products.insert("Hammer", 25.0);

//         Map<String, Table> catalog = new HashMap<>();
//         catalog.put("users", users);
//         catalog.put("products", products);

//         for (int i = 0; i < 3; i++) {
//             String corruptedTable = corrupt(catalog);
//             System.out.println(corruptedTable != null
//                 ? "-> corrupted table: " + corruptedTable
//                 : "(nothing to corrupt)");
//         }
//     }
// }