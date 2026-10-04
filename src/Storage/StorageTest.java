
package Storage;

import static Storage.TestKit.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Tests for RowSerializer and Page. No JUnit needed: run with
 *   java Storage.StorageTest
 * Exits with a non-zero status if anything fails.
 */
public class StorageTest {

    public static void main(String[] args) {
        runAll();
        TestKit.finish();
    }

    static void runAll() {
        run("serializer: round trip", StorageTest::serializerRoundTrip);
        run("serializer: special doubles and unicode", StorageTest::serializerSpecialValues);
        run("serializer: rejects bad input", StorageTest::serializerRejectsBadInput);
        run("serializer: NULL values round trip and take no space", StorageTest::serializerNulls);
        run("serializer: every NULL pattern, across bitmap byte boundaries", StorageTest::serializerNullPatterns);
        run("serializer: null bitmap layout", StorageTest::serializerBitmapLayout);
        run("serializer: NOT NULL columns", StorageTest::serializerNotNull);
        run("serializer: rejects damaged null bitmaps", StorageTest::serializerRejectsBadBitmaps);
        run("serializer: rejects bad bytes", StorageTest::serializerRejectsBadBytes);
        run("page: insert, read, delete, slot reuse", StorageTest::pageBasics);
        run("page: reports full", StorageTest::pageFillsUp);
        run("page: row size limits", StorageTest::pageRowSizeLimits);
        run("page: page size limits", StorageTest::pageSizeLimits);
        run("page: compaction reclaims scattered space", StorageTest::pageCompaction);
        run("page: update shrink, grow, and too big", StorageTest::pageUpdate);
        run("page: toBytes/fromBytes round trip", StorageTest::pageRoundTrip);
        run("page: detects corruption", StorageTest::pageDetectsCorruption);
        run("page: randomized stress vs a model", StorageTest::randomizedStress);
        run("rows serialized into pages and read back", StorageTest::rowsInPages);
    }

    // ---------- Serializer tests ----------

    static void serializerRoundTrip() {
        List<String> types = List.of("DOUBLE", "STRING", "DOUBLE");
        Object[] row = { 1.5, "hello", -2.25 };
        byte[] bytes = RowSerializer.serialize(row, types);
        assertEquals(1 + 8 + 2 + 5 + 8, bytes.length, "serialized size (1-byte null bitmap, then the values)");
        assertTrue(Arrays.equals(row, RowSerializer.deserialize(bytes, types)), "round trip");

        List<String> onlyText = List.of("STRING", "STRING");
        Object[] textRow = { "", "x" };
        assertTrue(Arrays.equals(textRow,
            RowSerializer.deserialize(RowSerializer.serialize(textRow, onlyText), onlyText)), "empty string");
    }

    static void serializerSpecialValues() {
        List<String> types = List.of("DOUBLE", "STRING");
        double[] doubles = { 0.0, -0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.MIN_VALUE, Double.MAX_VALUE, -123456.789 };
        for (double d : doubles) {
            Object[] row = { d, "x" };
            Object[] back = RowSerializer.deserialize(RowSerializer.serialize(row, types), types);
            assertTrue(Arrays.equals(row, back), "double " + d);
        }

        // Unicode escapes (not literal characters) so this file compiles the
        // same way regardless of the compiler's source-file encoding.
        String unicode = "h\u00e9llo \u65e5\u672c\u8a9e \uD83D\uDE00";
        Object[] row = { 1.0, unicode };
        byte[] bytes = RowSerializer.serialize(row, types);
        assertEquals(unicode, RowSerializer.deserialize(bytes, types)[1], "unicode string");
        // The length prefix counts UTF-8 bytes, not characters.
        assertTrue(bytes.length > 8 + 2 + unicode.length(), "length is in bytes");

        String longest = "a".repeat(RowSerializer.MAX_STRING_BYTES);
        Object[] big = { 1.0, longest };
        assertEquals(longest, RowSerializer.deserialize(RowSerializer.serialize(big, types), types)[1],
            "string of maximum length");
    }

    static void serializerRejectsBadInput() {
        List<String> types = List.of("DOUBLE", "STRING");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { 1.0 }, types), "too few values");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { 1.0, "a", "b" }, types), "too many values");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { "oops", "a" }, types), "string in DOUBLE column");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { 1.0, 2.0 }, types), "number in STRING column");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { 1.0 }, List.of("BOOLEAN")), "unknown type");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { 1.0, "a".repeat(RowSerializer.MAX_STRING_BYTES + 1) },
                types), "string over the length limit");
    }

    static void serializerRejectsBadBytes() {
        List<String> types = List.of("DOUBLE", "STRING");
        byte[] good = RowSerializer.serialize(new Object[] { 1.0, "abc" }, types);

        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.deserialize(Arrays.copyOf(good, good.length - 1), types),
            "string cut short");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.deserialize(Arrays.copyOf(good, 4), types), "double cut short");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.deserialize(Arrays.copyOf(good, good.length + 3), types), "trailing bytes");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.deserialize(new byte[0], types), "empty input");
    }

    // ---------- NULL tests ----------

    static void serializerNulls() {
        List<String> types = List.of("DOUBLE", "STRING", "DOUBLE");
        Object[][] rows = {
            { null, "x", 1.0 }, { 1.0, null, 2.0 }, { 1.0, "x", null },
            { null, null, 2.0 }, { null, "x", null }, { 1.0, null, null }, { null, null, null },
        };
        for (Object[] row : rows) {
            byte[] bytes = RowSerializer.serialize(row, types);
            assertTrue(Arrays.equals(row, RowSerializer.deserialize(bytes, types)), "round trip of " + Arrays.toString(row));
        }

        // A NULL costs nothing beyond its bit in the bitmap.
        int full = RowSerializer.serialize(new Object[] { 1.0, "hello", 2.0 }, types).length;
        assertEquals(1 + 8 + 2 + 5 + 8, full, "a row with no NULLs");
        assertEquals(full - 8, RowSerializer.serialize(new Object[] { null, "hello", 2.0 }, types).length, "NULL number saves 8 bytes");
        assertEquals(full - 7, RowSerializer.serialize(new Object[] { 1.0, null, 2.0 }, types).length, "NULL text saves its length and contents");
        assertEquals(1, RowSerializer.serialize(new Object[] { null, null, null }, types).length, "an all-NULL row is just the bitmap");

        // NULL is its own value: it is not zero, not an empty string, and not NaN.
        Object[] zeros = { 0.0, "", 0.0 };
        Object[] allNull = { null, null, null };
        assertTrue(Arrays.equals(zeros, RowSerializer.deserialize(RowSerializer.serialize(zeros, types), types)), "zeros stay zeros");
        assertTrue(Arrays.equals(allNull, RowSerializer.deserialize(RowSerializer.serialize(allNull, types), types)), "NULLs stay NULL");
        assertTrue(!Arrays.equals(RowSerializer.serialize(zeros, types), RowSerializer.serialize(allNull, types)), "and they are stored differently");
        Object[] nan = RowSerializer.deserialize(RowSerializer.serialize(new Object[] { Double.NaN, "", null }, types), types);
        assertTrue(nan[0] instanceof Double && ((Double) nan[0]).isNaN(), "NaN is a number, not NULL");
        assertEquals("", nan[1], "an empty string is a value, not NULL");
        assertTrue(nan[2] == null, "and the third is NULL");

        // A one-column row of NULL is still one byte long, never zero.
        assertEquals(1, RowSerializer.serialize(new Object[] { null }, List.of("STRING")).length, "single NULL column");
    }

    // Every combination of NULL and non-NULL for 1 to 10 columns (covering the
    // step from one bitmap byte to two), then random patterns on a wide table.
    static void serializerNullPatterns() {
        for (int n = 1; n <= 10; n++) {
            List<String> types = new ArrayList<>();
            for (int i = 0; i < n; i++) types.add(i % 2 == 0 ? "DOUBLE" : "STRING");
            for (int mask = 0; mask < (1 << n); mask++) {
                Object[] row = new Object[n];
                int expectedSize = (n + 7) / 8;
                for (int i = 0; i < n; i++) {
                    if (((mask >> i) & 1) == 1) {
                        row[i] = null;
                    } else if (types.get(i).equals("DOUBLE")) {
                        row[i] = (double) i;
                        expectedSize += 8;
                    } else {
                        row[i] = "s" + i;
                        expectedSize += 2 + ("s" + i).length();
                    }
                }
                byte[] bytes = RowSerializer.serialize(row, types);
                assertEquals(expectedSize, bytes.length, n + " columns, mask " + mask + ": size");
                assertTrue(Arrays.equals(row, RowSerializer.deserialize(bytes, types)), n + " columns, mask " + mask + ": round trip");
            }
        }

        Random rnd = new Random(77);
        List<String> wide = new ArrayList<>();
        for (int i = 0; i < 41; i++) wide.add(i % 3 == 0 ? "STRING" : "DOUBLE");
        for (int round = 0; round < 300; round++) {
            Object[] row = new Object[41];
            for (int i = 0; i < 41; i++) {
                if (rnd.nextInt(3) == 0) continue; // NULL
                row[i] = wide.get(i).equals("DOUBLE") ? (Object) rnd.nextDouble() : (Object) ("v" + rnd.nextInt(1000));
            }
            assertTrue(Arrays.equals(row, RowSerializer.deserialize(RowSerializer.serialize(row, wide), wide)), "wide row " + round);
        }
    }

    // Pins the exact bitmap layout: bit i of the bitmap is column i, counting
    // from the least significant bit of byte 0. Stored files depend on this.
    static void serializerBitmapLayout() {
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add("DOUBLE");

        Object[] row = new Object[10];
        Arrays.fill(row, 1.0);
        row[0] = null;
        row[9] = null;
        byte[] bytes = RowSerializer.serialize(row, ten);
        assertEquals(2 + 8 * 8, bytes.length, "two bitmap bytes, then eight numbers");
        assertEquals((byte) 0x01, bytes[0], "column 0 is bit 0 of byte 0");
        assertEquals((byte) 0x02, bytes[1], "column 9 is bit 1 of byte 1");

        row = new Object[10];
        Arrays.fill(row, 1.0);
        row[7] = null;
        assertEquals((byte) 0x80, RowSerializer.serialize(row, ten)[0], "column 7 is the top bit of byte 0");
        row[7] = 1.0;
        row[8] = null;
        byte[] eight = RowSerializer.serialize(row, ten);
        assertEquals((byte) 0x00, eight[0], "column 8 doesn't touch byte 0");
        assertEquals((byte) 0x01, eight[1], "column 8 is bit 0 of byte 1");
    }

    static void serializerNotNull() {
        Schema schema = new Schema(List.of("id", "name", "score"), List.of("DOUBLE", "STRING", "DOUBLE"),
            List.of(false, true, true));

        Object[] ok = { 1.0, null, null };
        assertTrue(Arrays.equals(ok, RowSerializer.deserialize(RowSerializer.serialize(ok, schema), schema.columnTypes())),
            "NULL is fine in nullable columns");
        try {
            RowSerializer.serialize(new Object[] { null, "x", 1.0 }, schema);
            throw new AssertionError("NULL in a NOT NULL column should be refused");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("'id'") && e.getMessage().contains("NOT NULL"),
                "message names the column: " + e.getMessage());
        }

        // The list-of-types form knows nothing about NOT NULL: every column may be NULL.
        RowSerializer.serialize(new Object[] { null, null, null }, schema.columnTypes());
        // And a plain Schema (no flags given) is all-nullable.
        RowSerializer.serialize(new Object[] { null, null }, new Schema(List.of("a", "b"), List.of("DOUBLE", "STRING")));
        // Type checks still apply to the values that are present.
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { "oops", null, null }, schema), "wrong type alongside NULLs");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.serialize(new Object[] { null, "x" }, List.of("DOUBLE", "BOOLEAN")), "unknown type, even over a NULL");
    }

    static void serializerRejectsBadBitmaps() {
        List<String> types = List.of("DOUBLE", "STRING", "DOUBLE");
        byte[] good = RowSerializer.serialize(new Object[] { 1.0, "ab", null }, types);
        assertEquals((byte) 0x04, good[0], "column 2 is NULL");

        byte[] strayBit = good.clone();
        strayBit[0] |= 0x08; // a bit for a fourth column that doesn't exist
        expectThrows(IllegalArgumentException.class, () -> RowSerializer.deserialize(strayBit, types), "bit beyond the last column");

        byte[] clearedBit = good.clone();
        clearedBit[0] = 0; // claims column 2 holds a value, but there are no bytes for it
        expectThrows(IllegalArgumentException.class, () -> RowSerializer.deserialize(clearedBit, types), "NULL bit cleared, value missing");

        byte[] extraBit = good.clone();
        extraBit[0] |= 0x01; // claims column 0 is NULL, leaving its 8 bytes unread
        expectThrows(IllegalArgumentException.class, () -> RowSerializer.deserialize(extraBit, types), "NULL bit set over real data");

        List<String> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) nine.add("DOUBLE");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.deserialize(new byte[] { (byte) 0xFF }, nine), "bitmap cut short (9 columns need 2 bytes)");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.deserialize(new byte[0], types), "no bytes at all");
        expectThrows(IllegalArgumentException.class,
            () -> RowSerializer.deserialize(new byte[] { 0x07, 0 }, types), "all NULL, but a stray byte follows");
    }

    // ---------- Page tests ----------

    static void pageBasics() {
        Page page = new Page(256);
        byte[] a = { 1, 2, 3 };
        byte[] b = { 4, 5 };

        assertEquals(0, page.insert(a), "first slot");
        assertEquals(1, page.insert(b), "second slot");
        assertEquals(2, page.slotCount(), "slot count");
        assertEquals(2, page.liveRowCount(), "live count");
        assertTrue(Arrays.equals(a, page.read(0)), "read slot 0");
        assertTrue(Arrays.equals(b, page.read(1)), "read slot 1");

        a[0] = 99; // read returns copies, so callers can't alias page memory
        assertEquals((byte) 1, page.read(0)[0], "page unaffected by caller mutating its array");
        page.read(0)[0] = 77;
        assertEquals((byte) 1, page.read(0)[0], "page unaffected by mutating a returned array");

        assertTrue(page.delete(0), "delete live row");
        assertTrue(page.read(0) == null, "deleted slot reads as null");
        assertTrue(!page.isLive(0), "deleted slot not live");
        assertTrue(!page.delete(0), "second delete returns false");
        assertEquals(1, page.liveRowCount(), "live count after delete");
        assertEquals(2, page.slotCount(), "slot count unchanged by delete");

        assertEquals(0, page.insert(new byte[] { 9, 9, 9, 9 }), "deleted slot is reused");
        assertEquals(2, page.slotCount(), "reuse doesn't grow the slot array");

        expectThrows(IndexOutOfBoundsException.class, () -> page.read(2), "slot past the end");
        expectThrows(IndexOutOfBoundsException.class, () -> page.read(-1), "negative slot");
        page.checkInvariants();
    }

    static void pageFillsUp() {
        // 128-byte page: 4 header, so 124 usable; each 20-byte row costs 24 with its slot -> 5 rows.
        Page page = new Page(128);
        int inserted = 0;
        for (int i = 0; i < 20; i++) {
            if (page.insert(new byte[20]) == -1) break;
            inserted++;
        }
        assertEquals(5, inserted, "rows that fit");
        assertEquals(-1, page.insert(new byte[20]), "full page reports -1");
        assertEquals(-1, page.insert(new byte[1]), "even a tiny row doesn't fit with no slot room");
        assertEquals(0, page.maxInsertSize(), "a full page can accept nothing");
        page.checkInvariants();
    }

    static void pageRowSizeLimits() {
        Page page = new Page(128);
        assertEquals(120, page.maxRowSize(), "max row size");
        assertEquals(120, page.maxInsertSize(), "an empty page can accept a max-size row");
        assertEquals(0, page.insert(new byte[120]), "a row of exactly the max size fits an empty page");
        Page other = new Page(128);
        expectThrows(IllegalArgumentException.class, () -> other.insert(new byte[121]), "row can never fit");
        expectThrows(IllegalArgumentException.class, () -> other.insert(new byte[0]), "empty row");
    }

    static void pageSizeLimits() {
        expectThrows(IllegalArgumentException.class, () -> new Page(Page.MIN_SIZE - 1), "too small");
        expectThrows(IllegalArgumentException.class, () -> new Page(Page.MAX_SIZE + 1), "too large");
        new Page(Page.MIN_SIZE).checkInvariants();

        Page big = new Page(Page.MAX_SIZE);
        byte[] row = new byte[big.maxRowSize()];
        Arrays.fill(row, (byte) 0x5A);
        assertEquals(0, big.insert(row), "a row filling the largest page");
        assertTrue(Arrays.equals(row, big.read(0)), "read it back");
        big.checkInvariants();
    }

    static void pageCompaction() {
        Page page = new Page(256);
        byte[][] rows = new byte[5][];
        for (int i = 0; i < 5; i++) {
            rows[i] = new byte[40];
            Arrays.fill(rows[i], (byte) (i + 1));
            assertEquals(i, page.insert(rows[i]), "slot " + i);
        }
        assertEquals(-1, page.insert(new byte[40]), "page is full");

        // Free two non-adjacent rows: lots of total space, but split into holes.
        page.delete(1);
        page.delete(3);
        assertEquals(0, page.compactionCount(), "no compaction yet");

        byte[] big = new byte[70];
        Arrays.fill(big, (byte) 0x7F);
        int slot = page.insert(big);
        assertEquals(1, slot, "reuses the first deleted slot");
        assertEquals(1, page.compactionCount(), "compaction was needed");

        assertTrue(Arrays.equals(rows[0], page.read(0)), "row 0 intact after compaction");
        assertTrue(Arrays.equals(big, page.read(1)), "new row stored");
        assertTrue(Arrays.equals(rows[2], page.read(2)), "row 2 intact after compaction");
        assertTrue(page.read(3) == null, "slot 3 still deleted");
        assertTrue(Arrays.equals(rows[4], page.read(4)), "row 4 intact after compaction");
        page.checkInvariants();
    }

    static void pageUpdate() {
        Page page = new Page(256);
        byte[] original = new byte[30];
        Arrays.fill(original, (byte) 1);
        int slot = page.insert(original);

        byte[] shorter = new byte[20];
        Arrays.fill(shorter, (byte) 2);
        assertTrue(page.update(slot, shorter), "shrink in place");
        assertTrue(Arrays.equals(shorter, page.read(slot)), "shrunk content");

        byte[] longer = new byte[50];
        Arrays.fill(longer, (byte) 3);
        assertTrue(page.update(slot, longer), "grow");
        assertTrue(Arrays.equals(longer, page.read(slot)), "grown content");
        assertEquals(1, page.slotCount(), "update never changes the slot count");
        page.checkInvariants();

        // Two 100-byte rows leave only 44 bytes free in a 256-byte page.
        Page tight = new Page(256);
        byte[] a = new byte[100];
        byte[] b = new byte[100];
        Arrays.fill(a, (byte) 4);
        Arrays.fill(b, (byte) 5);
        int sa = tight.insert(a);
        int sb = tight.insert(b);
        assertTrue(!tight.update(sa, new byte[150]), "growth that can't fit is refused");
        assertTrue(Arrays.equals(a, tight.read(sa)), "refused update leaves the row unchanged");
        assertTrue(Arrays.equals(b, tight.read(sb)), "neighbour unchanged");
        assertTrue(tight.update(sa, new byte[140]), "growth that exactly fits is allowed");
        tight.checkInvariants();

        tight.delete(sb);
        expectThrows(IllegalArgumentException.class, () -> tight.update(sb, new byte[5]), "update of a deleted slot");
    }

    static void pageRoundTrip() {
        Page page = new Page(512);
        List<byte[]> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            byte[] row = new byte[10 + i * 7];
            Arrays.fill(row, (byte) (i + 10));
            rows.add(row);
            page.insert(row);
        }
        page.delete(2);

        byte[] raw = page.toBytes();
        assertEquals(512, raw.length, "raw page size");
        Page copy = Page.fromBytes(raw);
        assertEquals(page.slotCount(), copy.slotCount(), "slot count");
        for (int slot = 0; slot < page.slotCount(); slot++) {
            assertTrue(Arrays.equals(page.read(slot), copy.read(slot)), "slot " + slot);
        }

        // The copy is independent of both the page and the array it came from.
        page.insert(new byte[] { 1 });
        raw[10] ^= 0x55;
        assertEquals(5, copy.liveRowCount(), "copy unaffected by later changes");
        copy.checkInvariants();
    }

    static void pageDetectsCorruption() {
        Page page = new Page(256);
        page.insert(new byte[20]);
        page.insert(new byte[20]);
        byte[] good = page.toBytes();
        Page.fromBytes(good); // must be accepted

        byte[] tampered = good.clone();
        setU16(tampered, 0, 5000); // slot count far too large
        expectThrows(IllegalArgumentException.class, () -> Page.fromBytes(tampered), "huge slot count");

        byte[] badFreeEnd = good.clone();
        setU16(badFreeEnd, 2, 0); // row data "starts" inside the header
        expectThrows(IllegalArgumentException.class, () -> Page.fromBytes(badFreeEnd), "bad row-data start");

        byte[] outOfBounds = good.clone();
        setU16(outOfBounds, 4, 250); // slot 0 offset
        setU16(outOfBounds, 6, 50);  // slot 0 length: runs past the end
        expectThrows(IllegalArgumentException.class, () -> Page.fromBytes(outOfBounds), "row past the page end");

        byte[] overlap = good.clone();
        setU16(overlap, 8, getU16(good, 4)); // slot 1 offset = slot 0 offset
        expectThrows(IllegalArgumentException.class, () -> Page.fromBytes(overlap), "overlapping rows");

        byte[] zeroLength = good.clone();
        setU16(zeroLength, 6, 0); // slot 0 live (offset != 0) but length 0
        expectThrows(IllegalArgumentException.class, () -> Page.fromBytes(zeroLength), "zero-length live row");

        expectThrows(IllegalArgumentException.class, () -> Page.fromBytes(new byte[10]), "array too small");
        expectThrows(IllegalArgumentException.class, () -> Page.fromBytes(new byte[40000]), "array too large");
    }

    // Applies thousands of random inserts, deletes, and updates to a small
    // page and checks it against a plain HashMap after every operation,
    // including that a refusal ("full") only happens when it genuinely must.
    static void randomizedStress() {
        final int pageSize = 512;
        Random rnd = new Random(12345);
        Page page = new Page(pageSize);
        Map<Integer, byte[]> model = new HashMap<>();

        int insertsOk = 0, insertsFull = 0, deletes = 0, updatesOk = 0, updatesRefused = 0;

        for (int step = 0; step < 30000; step++) {
            int slots = page.slotCount();
            int available = pageSize - 4 - slots * 4 - liveBytes(model);
            int op = rnd.nextInt(10);

            if (op < 4 || model.isEmpty()) {
                byte[] row = randomBytes(rnd, 1 + rnd.nextInt(100));
                boolean hasDeletedSlot = slots > model.size();
                int required = row.length + (hasDeletedSlot ? 0 : 4);
                int slot = page.insert(row);
                if (available < required) {
                    assertEquals(-1, slot, "step " + step + ": insert must be refused when it can't fit");
                    insertsFull++;
                } else {
                    assertTrue(slot >= 0, "step " + step + ": insert must succeed when it fits");
                    assertTrue(!model.containsKey(slot), "step " + step + ": insert returned a live slot");
                    if (!hasDeletedSlot) {
                        assertEquals(slots, slot, "step " + step + ": new slot appended at the end");
                    }
                    model.put(slot, row);
                    insertsOk++;
                }
            } else if (op < 7) {
                int slot = randomKey(model, rnd);
                assertTrue(page.delete(slot), "step " + step + ": delete of a live row");
                model.remove(slot);
                assertTrue(!page.delete(slot), "step " + step + ": repeat delete");
                deletes++;
            } else {
                int slot = randomKey(model, rnd);
                byte[] old = model.get(slot);
                byte[] row = randomBytes(rnd, 1 + rnd.nextInt(100));
                boolean expectOk = row.length <= old.length || available + old.length >= row.length;
                boolean ok = page.update(slot, row);
                assertEquals(expectOk, ok, "step " + step + ": update acceptance");
                if (ok) {
                    model.put(slot, row);
                    updatesOk++;
                } else {
                    updatesRefused++;
                }
            }

            page.checkInvariants();
            assertMaxInsertSizeIsExact(page, "step " + step);
            assertEquals(model.size(), page.liveRowCount(), "step " + step + ": live row count");
            assertEquals(pageSize - 4 - page.slotCount() * 4 - liveBytes(model), page.freeSpace(),
                "step " + step + ": free space");
            for (int slot = 0; slot < page.slotCount(); slot++) {
                byte[] expected = model.get(slot);
                byte[] actual = page.read(slot);
                if (expected == null) {
                    assertTrue(actual == null, "step " + step + ": slot " + slot + " should be empty");
                } else {
                    assertTrue(Arrays.equals(expected, actual), "step " + step + ": slot " + slot + " content");
                }
            }
        }

        System.out.println("      " + insertsOk + " inserts ok, " + insertsFull + " refused as full, "
            + deletes + " deletes, " + updatesOk + " updates ok, " + updatesRefused + " refused, "
            + page.compactionCount() + " compactions");
        assertTrue(insertsFull > 0, "the test should have hit full pages");
        assertTrue(updatesRefused > 0, "the test should have refused some updates");
        assertTrue(page.compactionCount() > 0, "the test should have exercised compaction");
    }

    // The whole point: real rows -> bytes -> pages -> "disk" -> pages -> bytes -> rows.
    static void rowsInPages() {
        List<String> types = List.of("DOUBLE", "STRING", "DOUBLE");
        String[] pieces = { "a", "b", "c", "X", " ", "0", "\u00e9", "\u65e5", "\uD83D\uDE00" };
        Random rnd = new Random(7);

        List<Page> pages = new ArrayList<>();
        pages.add(new Page(1024));
        List<Object[]> rows = new ArrayList<>();
        List<int[]> addresses = new ArrayList<>(); // {page index, slot}

        for (int i = 0; i < 300; i++) {
            StringBuilder text = new StringBuilder();
            int pieceCount = rnd.nextInt(40);
            for (int p = 0; p < pieceCount; p++) {
                text.append(pieces[rnd.nextInt(pieces.length)]);
            }
            Object[] row = { (double) i, text.toString(), rnd.nextDouble() * 1000 };
            byte[] bytes = RowSerializer.serialize(row, types);

            Page current = pages.get(pages.size() - 1);
            int slot = current.insert(bytes);
            if (slot == -1) {
                current = new Page(1024);
                pages.add(current);
                slot = current.insert(bytes);
            }
            assertTrue(slot >= 0, "row " + i + " fits an empty page");
            rows.add(row);
            addresses.add(new int[] { pages.size() - 1, slot });
        }
        assertTrue(pages.size() > 1, "300 rows should need more than one 1 KB page");

        // Round-trip every page through raw bytes, as a file would.
        List<Page> reloaded = new ArrayList<>();
        for (Page p : pages) {
            reloaded.add(Page.fromBytes(p.toBytes()));
        }
        for (int i = 0; i < rows.size(); i++) {
            int[] addr = addresses.get(i);
            byte[] bytes = reloaded.get(addr[0]).read(addr[1]);
            assertTrue(Arrays.equals(rows.get(i), RowSerializer.deserialize(bytes, types)), "row " + i);
        }
        System.out.println("      300 rows across " + pages.size() + " pages");
    }

    // maxInsertSize() must be exact: a row of that size is accepted, one byte
    // more is refused. Checked on copies so the page under test isn't changed.
    private static void assertMaxInsertSizeIsExact(Page page, String where) {
        int cap = page.maxInsertSize();
        assertTrue(cap >= 0 && cap <= page.maxRowSize(), where + ": maxInsertSize " + cap + " out of range");
        if (cap >= 1) {
            Page fits = Page.fromBytes(page.toBytes());
            assertTrue(fits.insert(new byte[cap]) >= 0, where + ": a row of maxInsertSize (" + cap + ") should fit");
        }
        if (cap + 1 <= page.maxRowSize()) {
            Page tooBig = Page.fromBytes(page.toBytes());
            assertEquals(-1, tooBig.insert(new byte[cap + 1]), where + ": a row one byte over maxInsertSize must not fit");
        }
    }

    // ---------- Helpers ----------

    private static int liveBytes(Map<Integer, byte[]> model) {
        int total = 0;
        for (byte[] row : model.values()) total += row.length;
        return total;
    }

    private static int randomKey(Map<Integer, byte[]> model, Random rnd) {
        Integer[] keys = model.keySet().toArray(new Integer[0]);
        return keys[rnd.nextInt(keys.length)];
    }

    private static byte[] randomBytes(Random rnd, int length) {
        byte[] bytes = new byte[length];
        rnd.nextBytes(bytes);
        return bytes;
    }

    private static int getU16(byte[] bytes, int pos) {
        return ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
    }

    private static void setU16(byte[] bytes, int pos, int value) {
        bytes[pos] = (byte) (value >>> 8);
        bytes[pos + 1] = (byte) value;
    }
}