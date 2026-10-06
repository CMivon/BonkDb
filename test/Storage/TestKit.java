package Storage;

//TODO: I dont really like having a testing framework rewritten. Use
// Junit or another testing suite in the future. This will be fine for now
/**
 * A tiny test framework shared by the Storage test classes, so there's no
 * need for JUnit. Each test is a method that throws if something is wrong.
 */
final class TestKit {

    interface Check {
        void run() throws Exception;
    }

    private static int passed = 0;
    private static int failed = 0;

    private TestKit() {}

    static void run(String name, Check check) {
        try {
            check.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable t) {
            failed++;
            System.out.println("FAIL  " + name + "  ->  " + t);
            for (StackTraceElement frame : t.getStackTrace()) {
                if (frame.getClassName().startsWith("Storage.") && !frame.getClassName().equals("Storage.TestKit")) {
                    System.out.println("        at " + frame);
                    break;
                }
            }
        }
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }

    static void expectThrows(Class<? extends Throwable> type, Check check, String message) {
        try {
            check.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(message + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    // Prints the totals and exits with a failing status if any test failed.
    static void finish() {
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
