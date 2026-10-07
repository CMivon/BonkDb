package Engine;

import java.util.List;

/**
 * Makes a new, empty table. CREATE TABLE is given one, so the same statement
 * can produce a table held in memory or one stored in a file.
 */
interface TableFactory {
    Table create(String name, List<String> columns, List<String> types);
}
