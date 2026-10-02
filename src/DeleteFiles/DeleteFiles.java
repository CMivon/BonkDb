
package DeleteFiles;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

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

    // The ONLY place findRandomFolder() will ever look. It will never search
    // the current working directory, the user's home directory, or anywhere
    // else -- only inside this one folder, which you create yourself, on
    // purpose, specifically to hand this function safe things to find.
    // Searching anywhere broader would risk returning a folder you never
    // meant to put at risk, especially if the result ever got fed into
    // something destructive later.
    private static final String SANDBOX_FOLDER_NAME = "Sandbox";

    private static final Random random = new Random();

    public static void main(String[] args) {
        // deleteFolder();
	System.out.println(findRandomFolder());
    }

    // Picks one random folder from anywhere inside SANDBOX_FOLDER_NAME
    // (searched recursively, any depth). Returns null if the sandbox folder
    // doesn't exist yet, or exists but contains no subfolders at all.
    public static Path findRandomFolder() {
        File sandboxRoot = new File(ownFolder(), SANDBOX_FOLDER_NAME);

        if (!sandboxRoot.isDirectory()) {
            System.out.println("No sandbox folder found at '" + sandboxRoot
                + "' -- create it yourself first if you want findRandomFolder() to have anything to find.");
            return null;
        }

        List<File> candidates = new ArrayList<>();
        collectFolders(sandboxRoot, candidates);

        if (candidates.isEmpty()) {
            System.out.println("'" + sandboxRoot + "' has no subfolders to pick from.");
            return null;
        }

        return candidates.get(random.nextInt(candidates.size())).toPath();
    }

    // Recursively collects every subfolder under dir (at any depth) into out.
    // Never leaves the subtree rooted at the original sandbox folder, since
    // it only ever recurses into dir's own children.
    private static void collectFolders(File dir, List<File> out) {
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null) {
            return;
        }
        for (File child : children) {
            out.add(child);
            collectFolders(child, out);
        }
    }

    // Finds the folder this class itself lives in (its compiled .class
    // file's folder, which is also its .java source's folder in this
    // project's simple javac-based build -- see the class doc comment).
    //
    // Handles two different launch modes, since getCodeSource().getLocation()
    // reports something different depending on which one was used:
    //  - Traditional (javac, then `java ClassName`): location is the
    //    classpath ROOT -- append the package path to reach this class's folder.
    //  - Single-file source launcher (`java DeleteFiles.java` directly,
    //    skipping a separate javac step): location IS the .java file itself,
    //    which already sits in this class's own folder -- no package path
    //    to append, just take its parent directory.
    private static File ownFolder() {
        try {
            File location = new File(
                DeleteFiles.class.getProtectionDomain().getCodeSource().getLocation().toURI());

            if (location.isFile()) {
                return location.getParentFile();
            }
            String packagePath = DeleteFiles.class.getPackage().getName().replace('.', '/');
            return new File(location, packagePath);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Couldn't determine this class's own location", e);
        }
    }

    // Recursively deletes the "DeleteThis" folder (files first, then
    // directories, deepest first) -- plain File.delete() only removes empty
    // directories, so a populated folder needs this walk-then-delete approach.
    public static void deleteFolder() {
        Path target = new File(ownFolder(), TARGET_FOLDER_NAME).toPath();

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