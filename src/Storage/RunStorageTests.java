package Storage;

/**
 * Runs every Storage test class and prints one combined total.
 *   java Storage.RunStorageTests
 */
public class RunStorageTests {
    public static void main(String[] args) {
        StorageTest.runAll();
        PageFileTest.runAll();
        TestKit.finish();
    }
}
