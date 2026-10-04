package Engine;

/**
 * Runs every Engine test class and prints one combined total.
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
        CliTests.runAll();
        TestKit.finish();
    }
}
