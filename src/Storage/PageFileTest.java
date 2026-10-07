package Storage;

import static Storage.TestKit.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * Tests for Schema and PageFile. Runs in a temporary directory that is
 * deleted afterwards. Run on its own with
 *   java Storage.PageFileTest
 */
public class PageFileTest {

    private static Path dir;

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        try {
            dir = Files.createTempDirectory("bonk-pagefile-test");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            run("schema: accessors, equality, and encoding round trip", PageFileTest::schemaBasics);
            run("schema: NOT NULL flags and their encoding", PageFileTest::schemaNullability);
            run("schema: rejects invalid definitions", PageFileTest::schemaRejectsInvalid);
            run("schema: rejects malformed bytes", PageFileTest::schemaRejectsBadBytes);
            run("file: create, close, reopen", PageFileTest::createAndReopen);
            run("file: NOT NULL flags survive close and reopen", PageFileTest::nullabilitySurvivesReopen);
            run("file: allocate pages", PageFileTest::allocatePages);
            run("file: written pages survive close and reopen", PageFileTest::writeAndReadBack);
            run("file: create never overwrites an existing file", PageFileTest::createRefusesToOverwrite);
            run("file: failed create leaves no file behind", PageFileTest::failedCreateLeavesNothing);
            run("file: failed create cleans up after itself", PageFileTest::failedCreateCleansUp);
            run("file: page number and size checks", PageFileTest::pageNumberChecks);
            run("file: open rejects files that aren't valid", PageFileTest::openRejectsBadFiles);
            run("file: a damaged data page is reported, others still readable", PageFileTest::damagedDataPage);
            run("file: can't open the same file twice", PageFileTest::cannotOpenTwice);
            run("file: close rules", PageFileTest::closeRules);
            run("file: rows -> pages -> file -> reopen -> rows", PageFileTest::rowsThroughAFile);
            run("file: randomized page writes with reopens", PageFileTest::randomizedPageWrites);
        } finally {
            deleteRecursively(dir);
        }
    }

    // ---------- Schema ----------

    static void schemaBasics() {
        Schema s = new Schema(List.of("id", "name"), List.of("DOUBLE", "STRING"));
        assertEquals(2, s.columnCount(), "column count");
        assertEquals(List.of("id", "name"), s.columnNames(), "names");
        assertEquals(List.of("DOUBLE", "STRING"), s.columnTypes(), "types");

        // Unicode escapes, not literal characters, so the file compiles identically everywhere.
        Schema unicode = new Schema(List.of("caf\u00e9", "\u65e5\u672c\u8a9e", "x"),
            List.of("STRING", "STRING", "DOUBLE"));
        Schema back = Schema.decode(ByteBuffer.wrap(unicode.encode()));
        assertEquals(unicode, back, "encode/decode round trip");
        assertEquals(unicode.hashCode(), back.hashCode(), "hash codes agree");
        assertTrue(!s.equals(unicode), "different schemas aren't equal");

        // A Schema isn't affected by later changes to the lists it was built from.
        List<String> names = new ArrayList<>(List.of("a", "b"));
        Schema copy = new Schema(names, List.of("DOUBLE", "DOUBLE"));
        names.set(0, "changed");
        assertEquals("a", copy.columnNames().get(0), "defensive copy");
        expectThrows(UnsupportedOperationException.class, () -> s.columnNames().add("z"), "names are read-only");
        expectThrows(UnsupportedOperationException.class, () -> s.columnTypes().add("DOUBLE"), "types are read-only");
    }

    static void schemaRejectsInvalid() {
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of(), List.of()), "no columns");
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of("a", "b"), List.of("DOUBLE")), "names/types length mismatch");
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of("a", "a"), List.of("DOUBLE", "STRING")), "duplicate names");
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of(""), List.of("DOUBLE")), "empty name");
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of("a"), List.of("BOOLEAN")), "unknown type");
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of("a"), List.of("DOUBLE"), List.of(true, true)), "more nullability flags than columns");
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of("a", "b"), List.of("DOUBLE", "DOUBLE"), List.of(true)), "fewer nullability flags than columns");
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(List.of("a", "b"), List.of("DOUBLE", "DOUBLE"), Arrays.asList(true, null)), "a missing flag");
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        expectThrows(IllegalArgumentException.class,
            () -> new Schema(withNull, List.of("DOUBLE")), "null name");
    }

    static void schemaRejectsBadBytes() {
        byte[] good = new Schema(List.of("id", "name"), List.of("DOUBLE", "STRING")).encode();

        expectThrows(IllegalArgumentException.class,
            () -> Schema.decode(ByteBuffer.wrap(Arrays.copyOf(good, good.length - 1))), "truncated");
        expectThrows(IllegalArgumentException.class,
            () -> Schema.decode(ByteBuffer.wrap(new byte[] { 0, 0 })), "zero columns");
        expectThrows(IllegalArgumentException.class,
            () -> Schema.decode(ByteBuffer.wrap(new byte[] { (byte) 0xFF, (byte) 0xFF, 0, 0, 1, 'a' })),
            "huge column count with little data");
        byte[] badType = good.clone();
        badType[2] = 9; // first column's type code
        expectThrows(IllegalArgumentException.class,
            () -> Schema.decode(ByteBuffer.wrap(badType)), "unknown type code");
        byte[] unknownBit = good.clone();
        unknownBit[2] = 0x40; // a flag bit that doesn't exist
        expectThrows(IllegalArgumentException.class,
            () -> Schema.decode(ByteBuffer.wrap(unknownBit)), "unknown flag bit in the type byte");
        byte[] badTypeWithFlag = good.clone();
        badTypeWithFlag[2] = (byte) 0x82; // NOT NULL, but type code 2 doesn't exist
        expectThrows(IllegalArgumentException.class,
            () -> Schema.decode(ByteBuffer.wrap(badTypeWithFlag)), "unknown type code with the NOT NULL bit set");
        byte[] badLength = good.clone();
        badLength[3] = 0x7F; // first column's name length, far beyond the data
        expectThrows(IllegalArgumentException.class,
            () -> Schema.decode(ByteBuffer.wrap(badLength)), "name length past the end");
    }

    // ---------- PageFile ----------

    static Schema testSchema() {
        return new Schema(List.of("id", "text"), List.of("DOUBLE", "STRING"));
    }

    static void schemaNullability() {
        Schema all = new Schema(List.of("a", "b"), List.of("DOUBLE", "STRING"));
        assertTrue(all.isNullable(0) && all.isNullable(1), "columns are nullable unless said otherwise");
        assertEquals(List.of(true, true), all.columnNullability(), "default flags");

        Schema mixed = new Schema(List.of("a", "b", "c"), List.of("DOUBLE", "STRING", "DOUBLE"), List.of(false, true, false));
        assertTrue(!mixed.isNullable(0) && mixed.isNullable(1) && !mixed.isNullable(2), "per-column flags");
        assertEquals(List.of(false, true, false), mixed.columnNullability(), "flags as a list");
        expectThrows(UnsupportedOperationException.class, () -> mixed.columnNullability().add(true), "flags are read-only");

        // Survives encoding, and takes part in equality.
        assertEquals(mixed, Schema.decode(ByteBuffer.wrap(mixed.encode())), "encode/decode round trip");
        assertEquals(mixed.hashCode(), Schema.decode(ByteBuffer.wrap(mixed.encode())).hashCode(), "hash code");
        Schema sameButNullable = new Schema(List.of("a", "b", "c"), List.of("DOUBLE", "STRING", "DOUBLE"));
        assertTrue(!mixed.equals(sameButNullable), "NOT NULL makes a different schema");
        assertTrue(mixed.toString().contains("a DOUBLE NOT NULL") && !mixed.toString().contains("b STRING NOT NULL"),
            "toString shows NOT NULL: " + mixed);

        // The flag is the top bit of each column's type byte: 0x80 = NOT NULL.
        // Layout: count (2 bytes), then per column type (1), name length (2), name.
        // For columns named "a", "b", "c" those type bytes sit at offsets 2, 6 and 10.
        byte[] encoded = mixed.encode();
        assertEquals(0x80, encoded[2] & 0xFF, "a: DOUBLE, NOT NULL");
        assertEquals(0x01, encoded[6] & 0xFF, "b: STRING, nullable");
        assertEquals(0x80, encoded[10] & 0xFF, "c: DOUBLE, NOT NULL");
        byte[] plain = sameButNullable.encode();
        assertEquals(0x00, plain[2] & 0xFF, "nullable columns have no flag bit, same as before NULL support");
        assertEquals(sameButNullable, Schema.decode(ByteBuffer.wrap(plain)), "so such bytes read back as all-nullable");

        // Defensive copy.
        List<Boolean> flags = new ArrayList<>(List.of(false, true));
        Schema copy = new Schema(List.of("a", "b"), List.of("DOUBLE", "DOUBLE"), flags);
        flags.set(0, true);
        assertTrue(!copy.isNullable(0), "later changes to the flags list don't affect the schema");
    }

    static void nullabilitySurvivesReopen() throws IOException {
        Schema schema = new Schema(List.of("id", "note"), List.of("DOUBLE", "STRING"), List.of(false, true));
        Path p = dir.resolve("nullability.bonk");
        try (PageFile f = PageFile.create(p, 512, schema)) {
            assertEquals(schema, f.schema(), "schema as created");
        }
        try (PageFile f = PageFile.open(p)) {
            assertEquals(schema, f.schema(), "schema after reopen");
            assertTrue(!f.schema().isNullable(0) && f.schema().isNullable(1), "which column is NOT NULL survives");
        }
    }

    static void createAndReopen() throws IOException {
        Path p = dir.resolve("create.bonk");
        try (PageFile f = PageFile.create(p, 1024, testSchema())) {
            assertEquals(0, f.dataPageCount(), "new file has no data pages");
            assertEquals(1024, f.pageSize(), "page size");
            assertEquals(testSchema(), f.schema(), "schema");
            f.flush();
        }
        assertEquals(1024L, Files.size(p), "a new file is exactly the header page");

        try (PageFile f = PageFile.open(p)) {
            assertEquals(0, f.dataPageCount(), "count after reopen");
            assertEquals(1024, f.pageSize(), "page size after reopen");
            assertEquals(testSchema(), f.schema(), "schema after reopen");
        }
    }

    static void allocatePages() throws IOException {
        Path p = dir.resolve("allocate.bonk");
        try (PageFile f = PageFile.create(p, 512, testSchema())) {
            assertEquals(1, f.allocatePage(), "first data page is number 1");
            assertEquals(2, f.allocatePage(), "second");
            assertEquals(3, f.allocatePage(), "third");
            assertEquals(3, f.dataPageCount(), "count");
            assertEquals(4 * 512L, Files.size(p), "header + 3 pages");
            for (int n = 1; n <= 3; n++) {
                Page page = f.readPage(n);
                assertEquals(0, page.slotCount(), "page " + n + " starts empty");
                assertEquals(512, page.pageSize(), "page " + n + " size");
            }
        }
        try (PageFile f = PageFile.open(p)) {
            assertEquals(3, f.dataPageCount(), "count after reopen");
        }
    }

    static void writeAndReadBack() throws IOException {
        Path p = dir.resolve("readback.bonk");
        byte[] a = { 1, 2, 3, 4, 5 };
        byte[] b = { 9, 8, 7 };
        try (PageFile f = PageFile.create(p, 512, testSchema())) {
            f.allocatePage();
            f.allocatePage();
            Page page = f.readPage(2);
            assertEquals(0, page.insert(a), "slot in page 2");
            assertEquals(1, page.insert(b), "second slot in page 2");
            f.writePage(2, page);

            assertEquals(0, f.readPage(1).slotCount(), "page 1 is unaffected by writing page 2");
            assertTrue(Arrays.equals(a, f.readPage(2).read(0)), "read back immediately");
        }
        try (PageFile f = PageFile.open(p)) {
            Page page = f.readPage(2);
            assertTrue(Arrays.equals(a, page.read(0)), "row a after reopen");
            assertTrue(Arrays.equals(b, page.read(1)), "row b after reopen");
            assertEquals(0, f.readPage(1).slotCount(), "page 1 still empty");
        }
    }

    static void createRefusesToOverwrite() throws IOException {
        Path p = dir.resolve("noclobber.bonk");
        try (PageFile f = PageFile.create(p, 512, testSchema())) {
            f.allocatePage();
        }
        byte[] before = Files.readAllBytes(p);
        expectThrows(FileAlreadyExistsException.class,
            () -> PageFile.create(p, 1024, testSchema()), "create over an existing file");
        assertTrue(Arrays.equals(before, Files.readAllBytes(p)), "existing file untouched");
    }

    static void failedCreateLeavesNothing() {
        Path badSize = dir.resolve("badsize.bonk");
        expectThrows(IllegalArgumentException.class,
            () -> PageFile.create(badSize, 10, testSchema()), "page size too small");
        assertTrue(!Files.exists(badSize), "no file after a bad page size");

        List<String> names = new ArrayList<>();
        List<String> types = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            names.add("column_number_" + i);
            types.add("DOUBLE");
        }
        Path tooBig = dir.resolve("toobig.bonk");
        expectThrows(IllegalArgumentException.class,
            () -> PageFile.create(tooBig, 64, new Schema(names, types)), "schema too big for the header page");
        assertTrue(!Files.exists(tooBig), "no file after an oversized schema");
    }

    // Exercises the failure path that happens AFTER the new file exists, which
    // the test above can't reach. To get there without faking an I/O error:
    // delete a file while it is still open (possible on Linux/macOS), so its
    // path is free again but still registered as open, then try to create a
    // file at that path. create() makes the file, then discovers the "already
    // open" conflict and must remove the file it just made -- without
    // disturbing the registration that belongs to the first PageFile.
    static void failedCreateCleansUp() throws IOException {
        Path p = dir.resolve("stale.bonk");
        PageFile first = PageFile.create(p, 512, testSchema());
        try {
            try {
                Files.delete(p);
            } catch (IOException e) {
                System.out.println("      (skipped: this OS won't delete a file that is still open)");
                return;
            }
            assertTrue(!Files.exists(p), "path is free again");

            for (int attempt = 1; attempt <= 2; attempt++) {
                try (PageFile second = PageFile.create(p, 512, testSchema())) {
                    throw new AssertionError("attempt " + attempt + ": create should have been refused");
                } catch (IOException e) {
                    assertTrue(e.getMessage().contains("already open"),
                        "attempt " + attempt + ": message was: " + e.getMessage());
                }
                assertTrue(!Files.exists(p), "attempt " + attempt + ": the half-made file was removed");
            }
            // Attempt 2 being refused too proves attempt 1 didn't unregister the first file.
        } finally {
            first.close();
        }
    }

    static void pageNumberChecks() throws IOException {
        Path p = dir.resolve("numbers.bonk");
        try (PageFile f = PageFile.create(p, 1024, testSchema())) {
            f.allocatePage();
            f.allocatePage();
            Page ok = new Page(1024);

            expectThrows(IndexOutOfBoundsException.class, () -> f.readPage(0), "reading the header page");
            expectThrows(IndexOutOfBoundsException.class, () -> f.readPage(-1), "negative page");
            expectThrows(IndexOutOfBoundsException.class, () -> f.readPage(3), "page past the end");
            expectThrows(IndexOutOfBoundsException.class, () -> f.writePage(0, ok), "overwriting the header page");
            expectThrows(IndexOutOfBoundsException.class, () -> f.writePage(3, ok), "writing past the end");
            expectThrows(IllegalArgumentException.class, () -> f.writePage(1, new Page(512)), "wrong page size");
            assertEquals(2, f.dataPageCount(), "failed calls didn't change the file");
        }
    }

    static void openRejectsBadFiles() throws IOException {
        Path missing = dir.resolve("does-not-exist.bonk");
        expectThrows(NoSuchFileException.class, () -> PageFile.open(missing), "missing file");

        Path empty = dir.resolve("empty.bonk");
        Files.write(empty, new byte[0]);
        expectOpenFails(empty, "too small", "empty file");

        Path tiny = dir.resolve("tiny.bonk");
        Files.write(tiny, new byte[] { 1, 2, 3, 4, 5 });
        expectOpenFails(tiny, "too small", "5-byte file");

        byte[] garbage = new byte[3000];
        new Random(1).nextBytes(garbage);
        Path junk = dir.resolve("junk.bonk");
        Files.write(junk, garbage);
        expectOpenFails(junk, "not a bonkDB", "random bytes");

        // Start from a valid 3-page file, then damage one thing at a time.
        Path p = dir.resolve("tamper.bonk");
        try (PageFile f = PageFile.create(p, 1024, testSchema())) {
            f.allocatePage();
            f.allocatePage();
        }
        byte[] valid = Files.readAllBytes(p);

        byte[] badVersion = valid.clone();
        badVersion[5] = 99;
        Files.write(p, badVersion);
        expectOpenFails(p, "version", "unsupported version");

        byte[] oldFormat = valid.clone();
        ByteBuffer.wrap(oldFormat).putShort(4, (short) 1);
        Files.write(p, oldFormat);
        expectOpenFails(p, "unsupported format version 1", "a version 1 file (rows had no null bitmap)");

        byte[] tinyPageSize = valid.clone();
        ByteBuffer.wrap(tinyPageSize).putInt(6, 10);
        Files.write(p, tinyPageSize);
        expectOpenFails(p, "page size", "page size below the minimum");

        byte[] hugePageSize = valid.clone();
        ByteBuffer.wrap(hugePageSize).putInt(6, 1_000_000);
        Files.write(p, hugePageSize);
        expectOpenFails(p, "page size", "page size above the maximum");

        byte[] extraBytes = Arrays.copyOf(valid, valid.length + 5);
        Files.write(p, extraBytes);
        expectOpenFails(p, "multiple", "trailing partial page");

        Files.write(p, Arrays.copyOf(valid, valid.length - 1));
        expectOpenFails(p, "multiple", "truncated by one byte");

        byte[] badSchema = valid.clone();
        ByteBuffer.wrap(badSchema).putShort(10, (short) 0xFFFF); // column count
        Files.write(p, badSchema);
        expectOpenFails(p, "schema", "damaged schema");

        // None of those failed opens should have leaked a file handle or a
        // registration: restoring the original bytes must make it open cleanly.
        Files.write(p, valid);
        try (PageFile f = PageFile.open(p)) {
            assertEquals(2, f.dataPageCount(), "valid file opens after all the failed attempts");
        }
    }

    static void damagedDataPage() throws IOException {
        Path p = dir.resolve("damaged.bonk");
        try (PageFile f = PageFile.create(p, 1024, testSchema())) {
            for (int i = 0; i < 3; i++) {
                f.allocatePage();
            }
            Page page = f.readPage(2);
            page.insert(new byte[] { 1, 2, 3 });
            f.writePage(2, page);
        }

        byte[] bytes = Files.readAllBytes(p);
        ByteBuffer.wrap(bytes).putShort(2 * 1024, (short) 5000); // page 2's slot count
        Files.write(p, bytes);

        try (PageFile f = PageFile.open(p)) {
            try {
                f.readPage(2);
                throw new AssertionError("expected the damaged page to be rejected");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("Page 2"), "message names the page: " + e.getMessage());
            }
            assertEquals(0, f.readPage(1).slotCount(), "page 1 still readable");
            assertEquals(0, f.readPage(3).slotCount(), "page 3 still readable");
        }
    }

    static void cannotOpenTwice() throws IOException {
        Path p = dir.resolve("twice.bonk");
        PageFile first = PageFile.create(p, 512, testSchema());
        try {
            expectOpenFails(p, "already open", "second open");
            // The same file reached through a different spelling of its path.
            Path alias = dir.resolve(".").resolve("twice.bonk");
            expectOpenFails(alias, "already open", "second open via another path spelling");
        } finally {
            first.close();
        }
        try (PageFile again = PageFile.open(p)) {
            assertEquals(0, again.dataPageCount(), "reopens fine once the first is closed");
        }
    }

    static void closeRules() throws IOException {
        Path p = dir.resolve("close.bonk");
        PageFile f = PageFile.create(p, 512, testSchema());
        f.allocatePage();
        f.close();
        f.close(); // closing twice is harmless

        expectThrows(IllegalStateException.class, () -> f.readPage(1), "read after close");
        expectThrows(IllegalStateException.class, () -> f.writePage(1, new Page(512)), "write after close");
        expectThrows(IllegalStateException.class, f::allocatePage, "allocate after close");
        expectThrows(IllegalStateException.class, f::flush, "flush after close");
        assertEquals(512, f.pageSize(), "plain getters still work after close");
    }

    // The whole chain: real rows -> bytes -> pages -> a file -> reopened -> real rows.
    static void rowsThroughAFile() throws IOException {
        Schema schema = new Schema(List.of("id", "text", "score"), List.of("DOUBLE", "STRING", "DOUBLE"));
        String[] pieces = { "a", "b", "c", "X", " ", "0", "\u00e9", "\u65e5", "\uD83D\uDE00" };
        Random rnd = new Random(99);
        Path p = dir.resolve("rows.bonk");

        List<Object[]> rows = new ArrayList<>();
        List<int[]> addresses = new ArrayList<>(); // {page number, slot}

        try (PageFile f = PageFile.create(p, 1024, schema)) {
            int current = f.allocatePage();
            Page page = f.readPage(current);
            for (int i = 0; i < 500; i++) {
                StringBuilder text = new StringBuilder();
                int count = rnd.nextInt(40);
                for (int k = 0; k < count; k++) {
                    text.append(pieces[rnd.nextInt(pieces.length)]);
                }
                Object[] row = { (double) i, text.toString(), rnd.nextDouble() * 100 };
                byte[] bytes = RowSerializer.serialize(row, schema.columnTypes());

                int slot = page.insert(bytes);
                if (slot == -1) {
                    f.writePage(current, page);
                    current = f.allocatePage();
                    page = f.readPage(current);
                    slot = page.insert(bytes);
                }
                assertTrue(slot >= 0, "row " + i + " fits an empty page");
                rows.add(row);
                addresses.add(new int[] { current, slot });
            }
            f.writePage(current, page);
        }

        try (PageFile f = PageFile.open(p)) {
            assertEquals(schema, f.schema(), "schema survived");
            assertTrue(f.dataPageCount() > 1, "500 rows should span several pages");
            for (int i = 0; i < rows.size(); i++) {
                int[] addr = addresses.get(i);
                byte[] bytes = f.readPage(addr[0]).read(addr[1]);
                Object[] back = RowSerializer.deserialize(bytes, f.schema().columnTypes());
                assertTrue(Arrays.equals(rows.get(i), back), "row " + i);
            }
            System.out.println("      500 rows across " + f.dataPageCount() + " pages, all read back after reopen");
        }
    }

    // Random page edits written through a file, with the file closed and
    // reopened along the way, checked against an in-memory copy of every page.
    static void randomizedPageWrites() throws IOException {
        final int pageSize = 512;
        final int pages = 30;
        Random rnd = new Random(2024);
        Path p = dir.resolve("random.bonk");

        byte[][] model = new byte[pages + 1][];
        PageFile f = PageFile.create(p, pageSize, testSchema());
        try {
            for (int n = 1; n <= pages; n++) {
                assertEquals(n, f.allocatePage(), "allocate " + n);
                model[n] = new Page(pageSize).toBytes();
            }

            int reopens = 0;
            for (int step = 0; step < 3000; step++) {
                int n = 1 + rnd.nextInt(pages);
                Page page = f.readPage(n);
                assertTrue(Arrays.equals(model[n], page.toBytes()), "step " + step + ": page " + n + " matches the model");

                int ops = 1 + rnd.nextInt(3);
                for (int k = 0; k < ops; k++) {
                    int[] live = page.liveSlots();
                    int op = rnd.nextInt(3);
                    if (op == 0 || live.length == 0) {
                        byte[] row = new byte[1 + rnd.nextInt(80)];
                        rnd.nextBytes(row);
                        page.insert(row);
                    } else if (op == 1) {
                        page.delete(live[rnd.nextInt(live.length)]);
                    } else {
                        byte[] row = new byte[1 + rnd.nextInt(80)];
                        rnd.nextBytes(row);
                        page.update(live[rnd.nextInt(live.length)], row);
                    }
                }
                f.writePage(n, page);
                model[n] = page.toBytes();

                if (step % 500 == 499) {
                    f.close();
                    f = PageFile.open(p);
                    reopens++;
                    assertEquals(pages, f.dataPageCount(), "page count after reopen");
                }
            }

            f.close();
            f = PageFile.open(p);
            for (int n = 1; n <= pages; n++) {
                assertTrue(Arrays.equals(model[n], f.readPage(n).toBytes()), "final check of page " + n);
            }
            System.out.println("      3000 random page writes, " + (reopens + 1) + " reopens, all pages match");
        } finally {
            f.close();
        }
    }

    // ---------- Helpers ----------

    // Asserts that opening the file fails with an IOException mentioning messagePart.
    private static void expectOpenFails(Path path, String messagePart, String label) {
        try (PageFile f = PageFile.open(path)) {
            throw new AssertionError(label + ": expected opening to fail");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains(messagePart),
                label + ": message should mention \"" + messagePart + "\" but was: " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
