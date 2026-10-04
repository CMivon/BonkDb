package Engine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A tiny test framework for the Engine tests, so there's no need for JUnit.
 * Each test is a method that throws if something is wrong.
 *
 * (The Storage package has its own copy of this idea. They are kept separate
 * so neither package has to depend on the other just for testing.)
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
                if (frame.getClassName().startsWith("Engine.") && !frame.getClassName().equals("Engine.TestKit")) {
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
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected " + expected + " but got " + actual);
        }
    }

    // Passes only if check throws a Throwable of the given type.
    static void expectThrows(Class<? extends Throwable> type, Check check, String message) {
        try {
            check.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return;
            throw new AssertionError(message + ": expected " + type.getSimpleName() + " but got " + t);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    // Runs check with System.out captured, and returns what it printed. Line
    // endings are normalized to \n so expectations read the same on Windows.
    static String captureOutput(Check check) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        System.setOut(capture);
        try {
            check.run();
        } finally {
            System.setOut(original);
            capture.flush();
        }
        return buffer.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
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
