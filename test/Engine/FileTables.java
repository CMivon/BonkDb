package Engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Test helper: a temporary folder of table files. Its factory() makes
 * file-backed tables in that folder, and close() closes every table it made
 * and deletes the folder. Use it in a try-with-resources.
 */
final class FileTables implements AutoCloseable {

    private final Path dir;
    private final List<HeapTable> created = new ArrayList<>();
    private int counter = 0;

    FileTables() {
        try {
            dir = Files.createTempDirectory("bonk-file-tables");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path dir() {
        return dir;
    }

    // Each table made gets its own new file (t0.bonk, t1.bonk, ...).
    TableFactory factory() {
        return (name, columns, types) -> {
            try {
                HeapTable table = HeapTable.create(dir.resolve("t" + (counter++) + ".bonk"), name, columns, types);
                created.add(table);
                return table;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    @Override
    public void close() {
        for (HeapTable table : created) {
            try {
                table.close();
            } catch (IOException ignored) {
                // best effort cleanup
            }
        }
        try (Stream<Path> walk = Files.walk(dir)) {
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
