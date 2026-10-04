package Storage;

import java.io.EOFException;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Stores fixed-size pages in a file. Knows nothing about rows: it only moves
 * whole Page objects between memory and disk.
 *
 * File layout (page N lives at byte offset N * pageSize):
 *
 *   page 0        header page: magic "BONK" (4 bytes), format version (u16),
 *                 page size (4 bytes), then the table's Schema; the rest of
 *                 the page is zero padding
 *   pages 1..N    data pages
 *
 * Page number 0 is never a data page, so 0 can safely mean "no page".
 *
 * The number of data pages is NOT stored in the header. It is always
 * derived from the file's length, so there is no second copy that could
 * disagree with the file after a crash. A length that isn't a whole number
 * of pages is reported as damage when the file is opened.
 *
 * Durability: writes may sit in the operating system's cache until flush()
 * or close() forces them to disk. There is no write-ahead log yet, so a
 * crash in the middle of a page write can leave that page torn;
 * readPage() will reject a page whose structure is damaged, but not one
 * with silently altered row bytes (there are no checksums).
 *
 * Opening a file that this process already has open is refused, because
 * two PageFile objects for one file would each cache their own view of it.
 * This does not protect against other processes.
 *
 * Not thread-safe.
 */
public class PageFile implements AutoCloseable {

    static final int MAGIC = 0x424F4E4B; // "BONK"
    static final int VERSION = 1;
    private static final int FIXED_HEADER_BYTES = 4 + 2 + 4; // magic + version + page size

    // Files currently open in this process, by real path.
    private static final Set<Path> openFiles = new HashSet<>();

    private final Path path;
    private final FileChannel channel;
    private final int pageSize;
    private final Schema schema;
    private int dataPageCount;
    private boolean closed = false;

    private PageFile(Path path, FileChannel channel, int pageSize, Schema schema, int dataPageCount) {
        this.path = path;
        this.channel = channel;
        this.pageSize = pageSize;
        this.schema = schema;
        this.dataPageCount = dataPageCount;
    }

    // ---------- Creating and opening ----------

    // Creates a new, empty table file. Fails (FileAlreadyExistsException)
    // rather than overwrite a file that is already there.
    public static PageFile create(Path path, int pageSize, Schema schema) throws IOException {
        Page.checkPageSize(pageSize);
        byte[] header = encodeHeader(pageSize, schema); // validated before touching the disk

        FileChannel channel = FileChannel.open(path,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        Path key = null;
        boolean registered = false;
        try {
            key = path.toRealPath();
            if (!register(key)) {
                throw new IOException(path + " is already open in this process");
            }
            registered = true;
            writeFully(channel, ByteBuffer.wrap(header), 0);
            channel.force(true);
            return new PageFile(key, channel, pageSize, schema, 0);
        } catch (IOException | RuntimeException e) {
            // We created this file moments ago, so don't leave a half-made one behind.
            if (registered) unregister(key);
            closeQuietly(channel);
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    // Opens an existing table file, checking that it really is one.
    public static PageFile open(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            Path key = path.toRealPath();
            long size = channel.size();
            if (size < FIXED_HEADER_BYTES) {
                throw new IOException(path + " is too small to be a bonkDB table file");
            }

            ByteBuffer fixed = ByteBuffer.allocate(FIXED_HEADER_BYTES);
            readFully(channel, fixed, 0);
            fixed.flip();
            if (fixed.getInt() != MAGIC) {
                throw new IOException(path + " is not a bonkDB table file (bad magic number)");
            }
            int version = fixed.getShort() & 0xFFFF;
            if (version != VERSION) {
                throw new IOException(path + " uses unsupported format version " + version
                    + " (this build reads version " + VERSION + ")");
            }
            int pageSize = fixed.getInt();
            if (pageSize < Page.MIN_SIZE || pageSize > Page.MAX_SIZE) {
                throw new IOException(path + " has an invalid page size (" + pageSize + ") in its header");
            }
            if (size % pageSize != 0) {
                throw new IOException(path + " is " + size + " bytes, which is not a multiple of its page size ("
                    + pageSize + "); the file is truncated or was damaged mid-write");
            }
            long totalPages = size / pageSize;
            if (totalPages - 1 > Integer.MAX_VALUE) {
                throw new IOException(path + " has too many pages");
            }

            ByteBuffer headerPage = ByteBuffer.allocate(pageSize);
            readFully(channel, headerPage, 0);
            headerPage.position(FIXED_HEADER_BYTES);
            Schema schema;
            try {
                schema = Schema.decode(headerPage);
            } catch (IllegalArgumentException | BufferUnderflowException e) {
                throw new IOException(path + " has a damaged schema in its header: " + e.getMessage(), e);
            }

            if (!register(key)) {
                throw new IOException(path + " is already open in this process");
            }
            return new PageFile(key, channel, pageSize, schema, (int) (totalPages - 1));
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    // ---------- Information ----------

    public Path path() {
        return path;
    }

    public int pageSize() {
        return pageSize;
    }

    public Schema schema() {
        return schema;
    }

    // Number of data pages. Valid page numbers are 1 through this value.
    public int dataPageCount() {
        return dataPageCount;
    }

    // ---------- Page operations ----------

    // Reads data page pageNo. A page whose structure is damaged is reported
    // as an IOException naming the page.
    public Page readPage(int pageNo) throws IOException {
        checkOpen();
        checkPageNumber(pageNo);
        ByteBuffer buf = ByteBuffer.allocate(pageSize);
        readFully(channel, buf, (long) pageNo * pageSize);
        try {
            return Page.fromBytes(buf.array());
        } catch (IllegalArgumentException e) {
            throw new IOException("Page " + pageNo + " of " + path + " is damaged: " + e.getMessage(), e);
        }
    }

    // Overwrites data page pageNo with the given page's contents.
    public void writePage(int pageNo, Page page) throws IOException {
        checkOpen();
        checkPageNumber(pageNo);
        if (page.pageSize() != pageSize) {
            throw new IllegalArgumentException("Page is " + page.pageSize() + " bytes but this file uses "
                + pageSize + "-byte pages");
        }
        writeFully(channel, ByteBuffer.wrap(page.toBytes()), (long) pageNo * pageSize);
    }

    // Appends a new empty page to the end of the file and returns its number.
    public int allocatePage() throws IOException {
        checkOpen();
        int pageNo = dataPageCount + 1;
        long position = (long) pageNo * pageSize;
        try {
            writeFully(channel, ByteBuffer.wrap(new Page(pageSize).toBytes()), position);
        } catch (IOException e) {
            try {
                channel.truncate(position); // don't leave a partial page at the end
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
        dataPageCount = pageNo;
        return pageNo;
    }

    // Forces everything written so far out to the disk.
    public void flush() throws IOException {
        checkOpen();
        channel.force(true);
    }

    // Flushes and closes the file. Safe to call more than once.
    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            channel.force(true);
        } finally {
            try {
                channel.close();
            } finally {
                unregister(path);
            }
        }
    }

    // ---------- Internals ----------

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("PageFile for " + path + " is closed");
        }
    }

    private void checkPageNumber(int pageNo) {
        if (pageNo < 1 || pageNo > dataPageCount) {
            throw new IndexOutOfBoundsException("Page " + pageNo + " is out of range (valid data pages are 1 to "
                + dataPageCount + "; page 0 is the header)");
        }
    }

    private static byte[] encodeHeader(int pageSize, Schema schema) {
        byte[] schemaBytes = schema.encode();
        if (FIXED_HEADER_BYTES + schemaBytes.length > pageSize) {
            throw new IllegalArgumentException("The schema needs " + (FIXED_HEADER_BYTES + schemaBytes.length)
                + " bytes of header but a page is only " + pageSize + " bytes");
        }
        ByteBuffer buf = ByteBuffer.allocate(pageSize); // zero-filled, so the padding is already in place
        buf.putInt(MAGIC);
        buf.putShort((short) VERSION);
        buf.putInt(pageSize);
        buf.put(schemaBytes);
        return buf.array();
    }

    // FileChannel reads and writes may transfer fewer bytes than asked for,
    // so loop until the whole buffer has been handled.
    private static void readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, pos);
            if (n < 0) {
                throw new EOFException("Unexpected end of file at byte " + pos);
            }
            pos += n;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long pos = position;
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static synchronized boolean register(Path key) {
        return openFiles.add(key);
    }

    private static synchronized void unregister(Path key) {
        openFiles.remove(key);
    }
}
