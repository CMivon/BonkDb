package Storage;

import java.util.Arrays;

/**
 * A fixed-size page holding variable-length rows, using the slotted-page layout:
 *
 *   [ header | slot array -->   ...free space...   <-- row data ]
 *
 *   header      4 bytes: slot count (u16), start of row data (u16)
 *   slot        4 bytes each: row offset (u16), row length (u16)
 *   row data    packed from the END of the page toward the front
 *
 * A row is addressed by its slot number, not its byte offset. That lets the
 * page move rows around internally (compaction, growing an update) without
 * changing any address that something else, like an index, might hold.
 *
 * A deleted slot is marked with offset 0 (no real row can start at offset 0,
 * because the header lives there). Deleted slots are reused by later inserts.
 * That means a slot number is only a stable address for as long as its row is
 * alive -- once anything else stores row addresses, deleting a row has to
 * remove those references too.
 *
 * All multi-byte values are big-endian. Page sizes are limited to 32768
 * because offsets and lengths are stored as unsigned 16-bit values.
 *
 * This class works purely in memory; reading and writing pages to a file is
 * a separate job.
 */
public class Page {

    public static final int DEFAULT_SIZE = 4096;
    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 32768;

    private static final int HEADER_SIZE = 4;
    private static final int SLOT_SIZE = 4;
    private static final int HEADER_SLOT_COUNT = 0;
    private static final int HEADER_FREE_END = 2;

    private final byte[] data;

    // Diagnostic only: how many times compact() has run on this instance.
    private int compactions = 0;

    public Page() {
        this(DEFAULT_SIZE);
    }

    public Page(int pageSize) {
        checkPageSize(pageSize);
        this.data = new byte[pageSize];
        setSlotCount(0);
        setFreeEnd(pageSize);
    }

    private Page(byte[] data) {
        this.data = data;
    }

    // Rebuilds a page from bytes previously produced by toBytes() (e.g. read
    // from disk). Copies the array and checks it is structurally sound, so a
    // damaged page is rejected here instead of causing confusing errors later.
    public static Page fromBytes(byte[] bytes) {
        checkPageSize(bytes.length);
        Page page = new Page(bytes.clone());
        page.checkInvariants();
        return page;
    }

    // A copy of the page's raw bytes, ready to be written to disk.
    public byte[] toBytes() {
        return data.clone();
    }

    // ---------- Basic info ----------

    public int pageSize() {
        return data.length;
    }

    // Number of slots, including deleted ones.
    public int slotCount() {
        return getU16(HEADER_SLOT_COUNT);
    }

    public int liveRowCount() {
        int count = 0;
        for (int slot = 0; slot < slotCount(); slot++) {
            if (slotOffset(slot) != 0) count++;
        }
        return count;
    }

    // The largest single row this page could ever hold (an empty page, one slot).
    public int maxRowSize() {
        return data.length - HEADER_SIZE - SLOT_SIZE;
    }

    // Total bytes available for row data, counting space that deleted or
    // shrunk rows left behind (compaction reclaims it). A brand-new row also
    // needs SLOT_SIZE more bytes unless it can reuse a deleted slot.
    public int freeSpace() {
        return data.length - HEADER_SIZE - slotCount() * SLOT_SIZE - liveBytes();
    }

    public boolean isLive(int slot) {
        checkSlot(slot);
        return slotOffset(slot) != 0;
    }

    // Slot numbers of every live row, in ascending order.
    public int[] liveSlots() {
        int[] result = new int[liveRowCount()];
        int n = 0;
        for (int slot = 0; slot < slotCount(); slot++) {
            if (slotOffset(slot) != 0) result[n++] = slot;
        }
        return result;
    }

    // ---------- Operations ----------

    // Stores a row and returns its slot number, or -1 if the page doesn't have
    // room (the caller should try another page). Throws if the row could never
    // fit in any page of this size.
    public int insert(byte[] row) {
        checkRow(row);

        int reusable = firstDeletedSlot();
        int slotCost = reusable == -1 ? SLOT_SIZE : 0;
        if (freeSpace() < row.length + slotCost) {
            return -1;
        }
        if (contiguousFree() < row.length + slotCost) {
            compact(); // enough total space, but scattered in holes
        }

        int slot;
        if (reusable == -1) {
            slot = slotCount();
            setSlotCount(slot + 1);
        } else {
            slot = reusable;
        }

        int offset = freeEnd() - row.length;
        System.arraycopy(row, 0, data, offset, row.length);
        setFreeEnd(offset);
        setSlot(slot, offset, row.length);
        return slot;
    }

    // Returns a copy of the row's bytes, or null if the slot has been deleted.
    public byte[] read(int slot) {
        checkSlot(slot);
        int offset = slotOffset(slot);
        if (offset == 0) return null;
        return Arrays.copyOfRange(data, offset, offset + slotLength(slot));
    }

    // Returns true if a row was deleted, false if the slot was already empty.
    public boolean delete(int slot) {
        checkSlot(slot);
        if (slotOffset(slot) == 0) return false;
        setSlot(slot, 0, 0);
        return true;
    }

    // Replaces a row, keeping its slot number. Returns false (and changes
    // nothing) if the new version doesn't fit in this page, in which case the
    // caller should delete the row here and insert it into another page.
    public boolean update(int slot, byte[] newRow) {
        checkSlot(slot);
        checkRow(newRow);
        int offset = slotOffset(slot);
        if (offset == 0) {
            throw new IllegalArgumentException("Slot " + slot + " is deleted; use insert");
        }
        int oldLength = slotLength(slot);

        if (newRow.length <= oldLength) {
            // Fits in the old space; any leftover bytes become reclaimable.
            System.arraycopy(newRow, 0, data, offset, newRow.length);
            setSlot(slot, offset, newRow.length);
            return true;
        }

        if (freeSpace() + oldLength < newRow.length) {
            return false;
        }
        setSlot(slot, 0, 0); // release the old copy so its space can count
        if (contiguousFree() < newRow.length) {
            compact();
        }
        int newOffset = freeEnd() - newRow.length;
        System.arraycopy(newRow, 0, data, newOffset, newRow.length);
        setFreeEnd(newOffset);
        setSlot(slot, newOffset, newRow.length);
        return true;
    }

    // ---------- Integrity ----------

    // Throws IllegalArgumentException if the page's structure is inconsistent:
    // header values out of range, rows outside the data area, or rows overlapping.
    public void checkInvariants() {
        int count = slotCount();
        int slotArrayEnd = HEADER_SIZE + count * SLOT_SIZE;
        int freeEnd = freeEnd();
        if (slotArrayEnd > data.length) {
            throw corrupt("slot array (" + count + " slots) runs past the end of the page");
        }
        if (freeEnd < slotArrayEnd || freeEnd > data.length) {
            throw corrupt("row data start " + freeEnd + " is outside " + slotArrayEnd + ".." + data.length);
        }

        int[][] spans = new int[count][];
        int live = 0;
        for (int slot = 0; slot < count; slot++) {
            int offset = slotOffset(slot);
            if (offset == 0) continue;
            int length = slotLength(slot);
            if (length < 1) {
                throw corrupt("slot " + slot + " has length " + length);
            }
            if (offset < freeEnd || offset + length > data.length) {
                throw corrupt("slot " + slot + " (offset " + offset + ", length " + length
                    + ") lies outside the row data area");
            }
            spans[live++] = new int[] { offset, offset + length };
        }

        int[][] liveSpans = Arrays.copyOf(spans, live);
        Arrays.sort(liveSpans, (a, b) -> Integer.compare(a[0], b[0]));
        for (int i = 1; i < liveSpans.length; i++) {
            if (liveSpans[i][0] < liveSpans[i - 1][1]) {
                throw corrupt("two rows overlap near offset " + liveSpans[i][0]);
            }
        }
    }

    // For tests: confirms the compaction path really ran.
    int compactionCount() {
        return compactions;
    }

    @Override
    public String toString() {
        return "Page(size=" + data.length + ", slots=" + slotCount() + ", live=" + liveRowCount()
            + ", freeSpace=" + freeSpace() + ")";
    }

    // ---------- Internals ----------

    // Slides all live rows to the end of the page, squeezing out holes left
    // by deletes and shrunk updates. Slot numbers don't change.
    private void compact() {
        compactions++;
        int count = slotCount();
        Integer[] live = new Integer[liveRowCount()];
        int n = 0;
        for (int slot = 0; slot < count; slot++) {
            if (slotOffset(slot) != 0) live[n++] = slot;
        }
        // Highest offset first: each row only ever moves toward the end of the
        // page, so rows closer to the end must be moved before the ones behind them.
        Arrays.sort(live, (a, b) -> Integer.compare(slotOffset(b), slotOffset(a)));

        int end = data.length;
        for (int slot : live) {
            int length = slotLength(slot);
            int newOffset = end - length;
            System.arraycopy(data, slotOffset(slot), data, newOffset, length); // overlap-safe
            setSlot(slot, newOffset, length);
            end = newOffset;
        }
        setFreeEnd(end);
    }

    private int liveBytes() {
        int total = 0;
        for (int slot = 0; slot < slotCount(); slot++) {
            if (slotOffset(slot) != 0) total += slotLength(slot);
        }
        return total;
    }

    // The gap between the end of the slot array and the start of the row data.
    private int contiguousFree() {
        return freeEnd() - (HEADER_SIZE + slotCount() * SLOT_SIZE);
    }

    private int firstDeletedSlot() {
        for (int slot = 0; slot < slotCount(); slot++) {
            if (slotOffset(slot) == 0) return slot;
        }
        return -1;
    }

    private void checkRow(byte[] row) {
        if (row.length < 1) {
            throw new IllegalArgumentException("Row is empty");
        }
        if (row.length > maxRowSize()) {
            throw new IllegalArgumentException("Row is " + row.length + " bytes; a " + data.length
                + "-byte page can hold at most " + maxRowSize());
        }
    }

    private void checkSlot(int slot) {
        if (slot < 0 || slot >= slotCount()) {
            throw new IndexOutOfBoundsException("Slot " + slot + " out of range (page has " + slotCount() + " slots)");
        }
    }

    private static void checkPageSize(int size) {
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new IllegalArgumentException("Page size " + size + " must be between " + MIN_SIZE
                + " and " + MAX_SIZE);
        }
    }

    private static IllegalArgumentException corrupt(String detail) {
        return new IllegalArgumentException("Corrupt page: " + detail);
    }

    private int slotPos(int slot) {
        return HEADER_SIZE + slot * SLOT_SIZE;
    }

    private int slotOffset(int slot) {
        return getU16(slotPos(slot));
    }

    private int slotLength(int slot) {
        return getU16(slotPos(slot) + 2);
    }

    private void setSlot(int slot, int offset, int length) {
        putU16(slotPos(slot), offset);
        putU16(slotPos(slot) + 2, length);
    }

    private int freeEnd() {
        return getU16(HEADER_FREE_END);
    }

    private void setFreeEnd(int value) {
        putU16(HEADER_FREE_END, value);
    }

    private void setSlotCount(int value) {
        putU16(HEADER_SLOT_COUNT, value);
    }

    private int getU16(int pos) {
        return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    }

    private void putU16(int pos, int value) {
        data[pos] = (byte) (value >>> 8);
        data[pos + 1] = (byte) value;
    }
}