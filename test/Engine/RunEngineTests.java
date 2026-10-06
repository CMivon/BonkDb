
package Engine;

/**
 * Runs every Engine test class and prints one combined total. The
 * SQL-level tests run twice: once with tables in memory, and once with every
 * table stored in a file.
 *   java Engine.RunEngineTests
 * Exits with a failing status if any test fails.
 */
public class RunEngineTests {
    public static void main(String[] args) {
        ParserTests.runAll();
        QueryTests.runAll();
        DataChangeTests.runAll();
        TableTests.runAll();
        TableContractTests.runAll();
        HeapTableTests.runAll();
        CatalogTests.runAll();
        CliTests.runAll();

        // The SQL-level tests again, with every CREATE TABLE making a table stored
        // in a file. Same queries, same expected answers.
        System.out.println();
        System.out.println("--- the same SQL-level tests, with tables stored in files ---");
        TestDb.withFileTables(() -> {
            QueryTests.runAll();
            DataChangeTests.runAll();
        });
        TestKit.finish();
    }
}