package Storage;

/**
 * The address of a row in a HeapFile: which data page it is on and which
 * slot within that page. Immutable.
 *
 * An address is only meaningful while its row is alive. Once a row is deleted,
 * a later insert may reuse the same slot, so a stored RowId must be dropped
 * when its row goes away. Updating a row can also change its address (see
 * HeapFile.update), which is why update returns the current one.
 */
public final class RowId implements Comparable<RowId> {

    public final int pageNo; // data pages are numbered from 1; 0 is the file header
    public final int slot;

    public RowId(int pageNo, int slot) {
        if (pageNo < 1) {
            throw new IllegalArgumentException("Data pages are numbered from 1, got " + pageNo);
        }
        if (slot < 0) {
            throw new IllegalArgumentException("Slot numbers start at 0, got " + slot);
        }
        this.pageNo = pageNo;
        this.slot = slot;
    }

    @Override
    public int compareTo(RowId other) {
        int byPage = Integer.compare(pageNo, other.pageNo);
        return byPage != 0 ? byPage : Integer.compare(slot, other.slot);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RowId)) return false;
        RowId that = (RowId) other;
        return pageNo == that.pageNo && slot == that.slot;
    }

    @Override
    public int hashCode() {
        return 31 * pageNo + slot;
    }

    @Override
    public String toString() {
        return "(page " + pageNo + ", slot " + slot + ")";
    }
}
