
package DeleteFiles;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.Comparator;

/**
 * Deletes one specific folder -- named exactly "DeleteThis", found right
 * next to this class's own location on disk -- along with everything
 * inside it.
 *
 * Deliberately NOT configurable with an arbitrary path: the whole point is
 * that it only ever touches a folder with this one, unmistakable name, so
 * there's no way to accidentally point it at something important. Looking
 * relative to this class's own folder (rather than the current working
 * directory) means it finds "DeleteThis" consistently regardless of where
 * you launched `java` from.
 */
public class DeleteFiles {

    private static final String TARGET_FOLDER_NAME = "DeleteThis";

    public static void main(String[] args) {
        deleteFolder();
    }

    // Finds the folder this class itself lives in (its compiled .class
    // file's folder, which is also its .java source's folder in this
    // project's simple javac-based build -- see the class doc comment).
    private static File ownFolder() {
        try {
            File classpathRoot = new File(
                DeleteFiles.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String packagePath = DeleteFiles.class.getPackage().getName().replace('.', '/');
            return new File(classpathRoot, packagePath);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Couldn't determine this class's own location", e);
        }
    }

    // Recursively deletes the "DeleteThis" folder (files first, then
    // directories, deepest first) -- plain File.delete() only removes empty
    // directories, so a populated folder needs this walk-then-delete approach.
    public static void deleteFolder() {
        Path target = new File(ownFolder(), TARGET_FOLDER_NAME).toPath();
System.out.println("AAAA");
        if (!Files.exists(target)) {
            System.out.println("No folder found at '" + target + "' -- nothing to delete.");
            return;
        }
        if (!Files.isDirectory(target)) {
            System.out.println("'" + target + "' exists but isn't a folder -- refusing to touch it.");
            return;
        }

        try (var walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()) // deepest entries first, so files go before their parent dirs
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        System.out.println("Failed to delete " + path + ": " + e.getMessage());
                    }
                });
            System.out.println("Deleted folder '" + TARGET_FOLDER_NAME + "' and everything inside it.");
        } catch (IOException e) {
            System.out.println("Error walking '" + TARGET_FOLDER_NAME + "': " + e.getMessage());
        }
    }
}