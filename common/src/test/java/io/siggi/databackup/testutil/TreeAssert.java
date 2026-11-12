package io.siggi.databackup.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.content.FileContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deep comparison of two directory trees, whatever their implementation (memory, disk or merge).
 */
public final class TreeAssert {
    private TreeAssert() {
    }

    public static void assertTreeEquals(DirectoryEntryDirectory expected, DirectoryEntryDirectory actual) {
        assertEntryEquals(expected, actual, "/");
    }

    private static void assertEntryEquals(DirectoryEntry expected, DirectoryEntry actual, String path) {
        assertEquals(expected.getName(), actual.getName(), path + ": name");
        assertEquals(expected.getType(), actual.getType(), path + ": type");
        assertEquals(expected.getExtra(), actual.getExtra(), path + ": extra data");
        switch (expected.getType()) {
            case DIRECTORY -> {
                Map<String, DirectoryEntry> expectedEntries = expected.asDirectory().getEntries();
                Map<String, DirectoryEntry> actualEntries = actual.asDirectory().getEntries();
                assertEquals(sorted(expectedEntries.keySet()), sorted(actualEntries.keySet()), path + ": entries");
                for (String name : sorted(expectedEntries.keySet())) {
                    assertEntryEquals(expectedEntries.get(name), actualEntries.get(name), path + name
                        + (expectedEntries.get(name).isDirectory() ? "/" : ""));
                }
            }
            case FILE -> {
                DirectoryEntryFile e = expected.asFile();
                DirectoryEntryFile a = actual.asFile();
                assertEquals(e.getLastModified(), a.getLastModified(), path + ": last modified");
                assertEquals(e.getSize(), a.getSize(), path + ": size");
                assertEquals(new ArrayList<FileContent>(e.getFileContents()),
                    new ArrayList<FileContent>(a.getFileContents()), path + ": contents");
                assertEquals(e.hasFileContents(), a.hasFileContents(), path + ": hasFileContents");
            }
            case SYMLINK -> {
                DirectoryEntrySymlink e = expected.asSymlink();
                DirectoryEntrySymlink a = actual.asSymlink();
                assertEquals(e.getTarget(), a.getTarget(), path + ": symlink target");
            }
            case NULL -> {
            }
            default -> fail(path + ": unexpected type " + expected.getType());
        }
    }

    private static List<String> sorted(java.util.Collection<String> names) {
        return names.stream().sorted().toList();
    }
}
