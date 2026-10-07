
package Engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * A database: a folder holding one file per table. The catalog opens every
 * table in the folder when it starts, hands them to the executor as a map of
 * name to Table, and creates the files for new tables.
 *
 * Nothing about the tables is stored apart from their files: each file
 * records its own columns and column types, and its name is part of its
 * file name. So the folder IS the catalog; deleting a file removes the table.
 *
 * File names:   table_<name>.bonk   (e.g. table_users.bonk)
 * The fixed prefix matters: Windows reserves names like CON, NUL, PRN and
 * COM1 for devices, whatever the extension, so a table called "con" could
 * never be a file named con.bonk there.
 *
 * Table names are case-sensitive in SQL, but Windows and macOS file systems
 * are not, so "Users" and "users" would be the same file there. To keep a
 * database usable on any system, a table whose name differs from an existing
 * one only in case is refused.
 *
 * A lock file (bonkdb.lock) stops two sessions opening the same folder.
 * Closing the catalog closes every table and releases the lock.
 */
final class Catalog implements AutoCloseable {

    static final String FILE_PREFIX = "table_";
    static final String FILE_SUFFIX = ".bonk";
    static final String LOCK_FILE = "bonkdb.lock";
    static final int MAX_NAME_LENGTH = 200;

    private final Path dir;
    private final FileChannel lockChannel;
    private final FileLock lock;
    // Sorted by name, so listings are in a steady order.
    private final Map<String, Table> tables = new TreeMap<>();
    private final List<String> warnings = new ArrayList<>();
    private boolean foundTableFiles = false;

    private Catalog(Path dir, FileChannel lockChannel, FileLock lock) {
        this.dir = dir;
        this.lockChannel = lockChannel;
        this.lock = lock;
    }

    // Opens the database in dir, creating the folder if it doesn't exist yet.
    // Throws if another session already has it open, or if two table files
    // differ only in case. A table file that can't be opened (damaged, say)
    // doesn't stop the rest: it is skipped, with a warning.
    static Catalog open(Path dir) throws IOException {
        Files.createDirectories(dir);
        FileChannel channel = FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null; // held by this very program
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new IOException("it is already open in another bonkDB session");
        }

        Catalog catalog = new Catalog(dir, channel, lock);
        try {
            catalog.loadTables();
        } catch (IOException | RuntimeException e) {
            catalog.close();
            throw e;
        }
        return catalog;
    }

    private void loadTables() throws IOException {
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(Files::isRegularFile).sorted().toList();
        }
        Set<String> seen = new TreeSet<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) {
                continue; // not a table file: the lock file, someone's notes, ...
            }
            String name = fileName.substring(FILE_PREFIX.length(), fileName.length() - FILE_SUFFIX.length());
            if (!isValidName(name)) {
                warnings.add("Ignoring " + fileName + ": that isn't a valid table name");
                continue;
            }
            foundTableFiles = true;
            for (String other : seen) {
                if (other.equalsIgnoreCase(name)) {
                    throw new IOException("two table files differ only in case ('" + other + "' and '" + name
                        + "'); rename one so the database can be opened on any system");
                }
            }
            seen.add(name);
            try {
                tables.put(name, HeapTable.open(file, name));
            } catch (IOException | RuntimeException e) {
                warnings.add("Couldn't open table '" + name + "' (" + fileName + "): " + e.getMessage()
                    + ". It won't be available in this session.");
            }
        }
    }

    // Letters, digits and underscores, not starting with a digit: the same rule the SQL
    // lexer uses for names, which also makes every name safe to use in a file name.
    static boolean isValidName(String name) {
        return name != null && name.length() <= MAX_NAME_LENGTH && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    Path dir() {
        return dir;
    }

    // The tables, by name. This is the live map the executor reads and changes.
    Map<String, Table> tables() {
        return tables;
    }

    // Problems found while opening that didn't stop the database opening.
    List<String> warnings() {
        return warnings;
    }

    // True if the folder held no table files when it was opened (a new database).
    boolean wasEmpty() {
        return !foundTableFiles;
    }

    Path fileFor(String name) {
        return dir.resolve(FILE_PREFIX + name + FILE_SUFFIX);
    }

    // Makes new, empty, file-backed tables in this database's folder. It only
    // creates the table: whoever calls it adds the result to tables().
    TableFactory factory() {
        return (name, columns, types) -> {
            checkCanCreate(name);
            try {
                return HeapTable.create(fileFor(name), name, columns, types);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    private void checkCanCreate(String name) {
        if (!isValidName(name)) {
            throw new RuntimeException("Invalid table name: " + name);
        }
        for (String existing : tables.keySet()) {
            if (existing.equalsIgnoreCase(name) && !existing.equals(name)) {
                throw new RuntimeException("Table '" + name + "' would clash with the existing table '" + existing
                    + "' (names that differ only in case would share a file on some systems)");
            }
        }
        if (Files.exists(fileFor(name))) {
            throw new RuntimeException("A file for table '" + name + "' is already in the database folder but couldn't be "
                + "opened at startup, so the table can't be created");
        }
    }

    // Creates a table called name holding a copy of source's columns, types and
    // rows. If anything goes wrong part way, the new table's file is removed and
    // the database is left as it was.
    void addFrom(String name, Table source) {
        if (tables.containsKey(name)) {
            throw new RuntimeException("Table already exists: " + name);
        }
        if (source.types == null) {
            throw new IllegalArgumentException("A table stored in a file needs declared column types");
        }
        Table created = factory().create(name, source.columns, source.types);
        try {
            for (Object[] row : source.scan()) {
                created.insert(row);
            }
            created.flush();
        } catch (RuntimeException e) {
            discard(created, name);
            throw e;
        } catch (IOException e) {
            discard(created, name);
            throw new UncheckedIOException(e);
        }
        tables.put(name, created);
    }

    private void discard(Table table, String name) {
        try {
            table.close();
        } catch (IOException ignored) {
            // best effort cleanup
        }
        try {
            Files.deleteIfExists(fileFor(name));
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    // Closes every table and releases the lock on the folder.
    @Override
    public void close() {
        IOException failure = null;
        for (Table table : tables.values()) {
            try {
                table.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
            }
        }
        try {
            lock.release();
        } catch (IOException ignored) {
            // closing the channel releases it anyway
        }
        try {
            lockChannel.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
        }
        if (failure != null) {
            throw new UncheckedIOException(failure);
        }
    }
}