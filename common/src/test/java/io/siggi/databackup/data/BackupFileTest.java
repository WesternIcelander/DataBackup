package io.siggi.databackup.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.util.ObjectWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupFileTest {
    @TempDir
    File tempDir;

    File file;

    private static final int BIG_COUNT = 500;

    @BeforeEach
    void writeSampleFile() throws IOException {
        file = new File(tempDir, "manifest");
        BackupFile.writeToFile(file, sampleTree());
    }

    private static ContentIDFileContent content(int seed, long length) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed * 31 + i);
        }
        ContentIDFileContent content = new ContentIDFileContent();
        content.setOffset(0L);
        content.setLength(length);
        content.setHash(hash);
        return content;
    }

    private static DirectoryEntryFile memoryFile(String name, long lastModified, long size, FileContent... contents) {
        DirectoryEntryFile file = new DirectoryEntryFile(name, lastModified, size);
        file.getFileContents().addAll(List.of(contents));
        return file;
    }

    private static DirectoryEntryDirectoryMemory sampleTree() throws IOException {
        DirectoryEntryDirectoryMemory root = new DirectoryEntryDirectoryMemory("ROOT");
        root.getExtra().add(new ExtraDataFilePath("/source"));
        DirectoryEntryDirectoryMemory sub = new DirectoryEntryDirectoryMemory("sub");
        sub.getExtra().add(new ExtraDataPosixPermissions(0755, 501, 20));
        DirectoryEntryDirectoryMemory empty = new DirectoryEntryDirectoryMemory("empty");
        try (ObjectWriter<DirectoryEntry> writer = sub.updateEntries()) {
            writer.write(memoryFile("b.txt", 2000L, 20L, content(2, 20L)));
            writer.write(memoryFile("c.txt", 3000L, 30L, content(3, 10L), content(4, 20L)));
            writer.write(new DirectoryEntrySymlink("link", "../a.txt"));
        }
        // Large enough that the file is much bigger than one 8 KiB read buffer, so reads of the earlier entries leave
        // the shared file pointer in the middle of the file rather than at its end.
        DirectoryEntryDirectoryMemory big = new DirectoryEntryDirectoryMemory("zbig");
        try (ObjectWriter<DirectoryEntry> writer = big.updateEntries()) {
            for (int i = 0; i < BIG_COUNT; i++) {
                writer.write(memoryFile("file-with-a-fairly-long-name-" + i, i, i, content(100 + i, i)));
            }
        }
        DirectoryEntryFile a = memoryFile("a.txt", 1000L, 10L, content(1, 10L));
        a.getExtra().add(new ExtraDataPosixPermissions(0644, 501, 20));
        try (ObjectWriter<DirectoryEntry> writer = root.updateEntries()) {
            writer.write(a);
            writer.write(memoryFile("unhashed.txt", 4000L, 40L));
            writer.write(sub);
            writer.write(empty);
            writer.write(big);
        }
        return root;
    }

    private static void assertSampleTree(DirectoryEntryDirectory root) {
        assertEquals("ROOT", root.getName());
        assertEquals(new ExtraDataFilePath("/source"), root.getExtra(ExtraDataFilePath.class));
        Map<String, DirectoryEntry> entries = root.getEntries();
        assertEquals(List.of("a.txt", "empty", "sub", "unhashed.txt", "zbig"), entries.keySet().stream().sorted().toList());

        DirectoryEntryFile a = entries.get("a.txt").asFile();
        assertEquals(1000L, a.getLastModified());
        assertEquals(10L, a.getSize());
        assertEquals(List.of(content(1, 10L)), a.getFileContents());
        assertEquals(new ExtraDataPosixPermissions(0644, 501, 20), a.getExtra(ExtraDataPosixPermissions.class));

        DirectoryEntryFile unhashed = entries.get("unhashed.txt").asFile();
        assertFalse(unhashed.hasFileContents());
        assertTrue(unhashed.getFileContents().isEmpty());

        assertTrue(entries.get("empty").asDirectory().getEntries().isEmpty());

        DirectoryEntryDirectory sub = entries.get("sub").asDirectory();
        assertEquals(new ExtraDataPosixPermissions(0755, 501, 20), sub.getExtra(ExtraDataPosixPermissions.class));
        Map<String, DirectoryEntry> subEntries = sub.getEntries();
        assertEquals(3, subEntries.size());
        assertEquals(List.of(content(2, 20L)), subEntries.get("b.txt").asFile().getFileContents());
        assertEquals(List.of(content(3, 10L), content(4, 20L)), subEntries.get("c.txt").asFile().getFileContents());
        assertEquals("../a.txt", subEntries.get("link").asSymlink().getTarget());

        Map<String, DirectoryEntry> bigEntries = entries.get("zbig").asDirectory().getEntries();
        assertEquals(BIG_COUNT, bigEntries.size());
        for (int i = 0; i < BIG_COUNT; i++) {
            DirectoryEntryFile file = bigEntries.get("file-with-a-fairly-long-name-" + i).asFile();
            assertEquals(i, file.getSize());
            assertEquals(List.of(content(100 + i, i)), file.getFileContents());
        }
    }

    @Test
    void opensReadOnly() throws IOException {
        try (BackupFile backupFile = BackupFile.open(file)) {
            assertSampleTree(backupFile.getRootDirectory());
        }
    }

    @Test
    void opensWritable() throws IOException {
        try (BackupFile backupFile = BackupFile.open(file, true)) {
            assertSampleTree(backupFile.getRootDirectory());
        }
    }

    @Test
    void readOnlyRefusesWrites() throws IOException {
        long length = file.length();
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            DirectoryEntryFile a = root.getEntry("a.txt").asFile();
            assertThrows(IOException.class, a::updateFileContents);
            assertThrows(IOException.class, root::updateEntries);
            assertSampleTree(root);
        }
        assertEquals(length, file.length());
    }

    @Test
    void notABackupFile() throws IOException {
        File other = new File(tempDir, "other");
        Files.write(other.toPath(), new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(IllegalArgumentException.class, () -> BackupFile.open(other).close());
    }

    @Test
    void updatesInPlace() throws IOException {
        try (BackupFile backupFile = BackupFile.open(file, true)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            DirectoryEntryFile unhashed = root.getEntry("unhashed.txt").asFile();
            try (ObjectWriter<FileContent> writer = unhashed.updateFileContents()) {
                writer.write(content(10, 15L));
                writer.write(content(11, 25L));
            }
            DirectoryEntryFile a = root.getEntry("a.txt").asFile();
            try (ObjectWriter<FileContent> writer = a.updateFileContents()) {
                writer.write(content(12, 10L));
            }
            DirectoryEntryDirectory empty = root.getEntry("empty").asDirectory();
            try (ObjectWriter<DirectoryEntry> writer = empty.updateEntries()) {
                writer.write(memoryFile("new.txt", 5000L, 50L));
                writer.write(new DirectoryEntrySymlink("newlink", "target"));
            }
            assertEquals(List.of(content(10, 15L), content(11, 25L)), unhashed.getFileContents());
            assertEquals(List.of(content(12, 10L)), a.getFileContents());
            assertEquals(2, empty.getEntries().size());
        }
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertEquals(List.of(content(10, 15L), content(11, 25L)),
                root.getEntry("unhashed.txt").asFile().getFileContents());
            assertEquals(List.of(content(12, 10L)), root.getEntry("a.txt").asFile().getFileContents());
            Map<String, DirectoryEntry> emptyEntries = root.getEntry("empty").asDirectory().getEntries();
            assertEquals(2, emptyEntries.size());
            DirectoryEntryFile newFile = emptyEntries.get("new.txt").asFile();
            assertEquals(5000L, newFile.getLastModified());
            assertEquals(50L, newFile.getSize());
            assertFalse(newFile.hasFileContents());
            assertEquals("target", emptyEntries.get("newlink").asSymlink().getTarget());
            // untouched entries are unchanged
            Map<String, DirectoryEntry> subEntries = root.getEntry("sub").asDirectory().getEntries();
            assertEquals(List.of(content(3, 10L), content(4, 20L)), subEntries.get("c.txt").asFile().getFileContents());
        }
    }

    @Test
    void contentsOfEntryWrittenInPlaceCanBeUpdated() throws IOException {
        try (BackupFile backupFile = BackupFile.open(file, true)) {
            DirectoryEntryDirectory empty = backupFile.getRootDirectory().getEntry("empty").asDirectory();
            try (ObjectWriter<DirectoryEntry> writer = empty.updateEntries()) {
                writer.write(memoryFile("new.txt", 5000L, 50L));
            }
            DirectoryEntryFile newFile = empty.getEntry("new.txt").asFile();
            try (ObjectWriter<FileContent> writer = newFile.updateFileContents()) {
                writer.write(content(20, 50L));
            }
        }
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory empty = backupFile.getRootDirectory().getEntry("empty").asDirectory();
            assertEquals(List.of(content(20, 50L)), empty.getEntry("new.txt").asFile().getFileContents());
        }
    }

    @Test
    void interleavedReadsDoNotMoveWrites() throws IOException {
        try (BackupFile backupFile = BackupFile.open(file, true)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            DirectoryEntryDirectory sub = root.getEntry("sub").asDirectory();
            DirectoryEntryFile c = sub.getEntry("c.txt").asFile();
            DirectoryEntryFile unhashed = root.getEntry("unhashed.txt").asFile();

            // Like FileMetadataScanner.copyHashes: copy contents lazily from one file of the same BackupFile while
            // writing another, re-reading other parts of the file between writes.
            try (ObjectWriter<FileContent> writer = unhashed.updateFileContents()) {
                Iterator<FileContent> source = c.iterator();
                while (source.hasNext()) {
                    FileContent next = source.next();
                    assertEquals(List.of(content(2, 20L)), readContents(sub, "b.txt"));
                    writer.write(next);
                    assertEquals(List.of(content(1, 10L)), readContents(root, "a.txt"));
                }
            }

            DirectoryEntryDirectory empty = root.getEntry("empty").asDirectory();
            try (ObjectWriter<DirectoryEntry> writer = empty.updateEntries()) {
                writer.write(memoryFile("x.txt", 1L, 1L));
                assertEquals(List.of(content(3, 10L), content(4, 20L)), readContents(sub, "c.txt"));
                writer.write(new DirectoryEntrySymlink("y", "x.txt"));
                assertEquals("../a.txt", readEntry(sub, "link").asSymlink().getTarget());
                writer.write(memoryFile("z.txt", 2L, 2L));
                assertEquals(5, readEntryNames(root).size());
            }
        }
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertEquals(List.of(content(3, 10L), content(4, 20L)), readContents(root, "unhashed.txt"));
            DirectoryEntryDirectory empty = root.getEntry("empty").asDirectory();
            assertEquals(List.of("x.txt", "y", "z.txt"), readEntryNames(empty).stream().sorted().toList());
            assertEquals(2L, empty.getEntry("z.txt").asFile().getSize());
            assertEquals("x.txt", empty.getEntry("y").asSymlink().getTarget());

            // the rest of the tree is intact
            Map<String, DirectoryEntry> subEntries = root.getEntry("sub").asDirectory().getEntries();
            assertEquals(List.of(content(2, 20L)), subEntries.get("b.txt").asFile().getFileContents());
            assertEquals(List.of(content(1, 10L)), root.getEntry("a.txt").asFile().getFileContents());
            assertEquals(BIG_COUNT, root.getEntry("zbig").asDirectory().getEntries().size());
        }
    }

    @Test
    void writingAnOpenedTreeLeavesItUnchanged() throws IOException {
        File copy = new File(tempDir, "copy");
        long originalLength = file.length();
        try (BackupFile backupFile = BackupFile.open(file, true)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            // Hold on to entries from the opened file, so the same objects are used before and after the write.
            DirectoryEntryFile a = root.getEntry("a.txt").asFile();
            DirectoryEntryFile unhashed = root.getEntry("unhashed.txt").asFile();
            DirectoryEntryDirectory sub = root.getEntry("sub").asDirectory();
            DirectoryEntryDirectory empty = root.getEntry("empty").asDirectory();
            DirectoryEntryFile c = sub.getEntry("c.txt").asFile();
            long aOffset = a.getFileContentOffset();
            long aOffsetOffset = a.getFileContentOffsetOffset();
            long unhashedOffsetOffset = unhashed.getFileContentOffsetOffset();
            long cOffset = c.getFileContentOffset();
            long cOffsetOffset = c.getFileContentOffsetOffset();
            long rootOffsetOffset = root.getDirectoryOffsetOffset();
            long subOffsetOffset = sub.getDirectoryOffsetOffset();
            long emptyOffsetOffset = empty.getDirectoryOffsetOffset();

            // Nest the tree one level down so that every entry lands at a different offset in the copy than in the
            // original.
            DirectoryEntryDirectoryMemory wrapper = new DirectoryEntryDirectoryMemory("WRAPPER");
            try (ObjectWriter<DirectoryEntry> writer = wrapper.updateEntries()) {
                writer.write(root);
            }
            BackupFile.writeToFile(copy, wrapper);

            assertEquals(originalLength, file.length());
            assertEquals(aOffset, a.getFileContentOffset());
            assertEquals(aOffsetOffset, a.getFileContentOffsetOffset());
            assertEquals(unhashedOffsetOffset, unhashed.getFileContentOffsetOffset());
            assertEquals(cOffset, c.getFileContentOffset());
            assertEquals(cOffsetOffset, c.getFileContentOffsetOffset());
            assertEquals(rootOffsetOffset, root.getDirectoryOffsetOffset());
            assertEquals(subOffsetOffset, sub.getDirectoryOffsetOffset());
            assertEquals(emptyOffsetOffset, empty.getDirectoryOffsetOffset());

            // The held entries still read from, and update, the original file.
            assertEquals(List.of(content(3, 10L), content(4, 20L)), readContents(sub, "c.txt"));
            try (ObjectWriter<FileContent> writer = a.updateFileContents()) {
                writer.write(content(30, 10L));
            }
            try (ObjectWriter<FileContent> writer = unhashed.updateFileContents()) {
                writer.write(content(31, 40L));
            }
            try (ObjectWriter<FileContent> writer = c.updateFileContents()) {
                writer.write(content(32, 30L));
            }
            try (ObjectWriter<DirectoryEntry> writer = empty.updateEntries()) {
                writer.write(memoryFile("new.txt", 5000L, 50L));
            }
            assertEquals(List.of(content(30, 10L)), readContents(root, "a.txt"));
            assertEquals(List.of(content(32, 30L)), readContents(sub, "c.txt"));
        }

        // The copy is a faithful copy of the tree as it was when written.
        try (BackupFile backupFile = BackupFile.open(copy)) {
            DirectoryEntryDirectory wrapper = backupFile.getRootDirectory();
            assertEquals(List.of("ROOT"), readEntryNames(wrapper));
            assertSampleTree(wrapper.getEntry("ROOT").asDirectory());
        }

        // The original got the in-place updates, and nothing else changed.
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertEquals(List.of(content(30, 10L)), readContents(root, "a.txt"));
            assertEquals(List.of(content(31, 40L)), readContents(root, "unhashed.txt"));
            DirectoryEntryDirectory sub = root.getEntry("sub").asDirectory();
            assertEquals(List.of(content(32, 30L)), readContents(sub, "c.txt"));
            assertEquals(List.of(content(2, 20L)), readContents(sub, "b.txt"));
            assertEquals("../a.txt", sub.getEntry("link").asSymlink().getTarget());
            DirectoryEntryDirectory empty = root.getEntry("empty").asDirectory();
            assertEquals(List.of("new.txt"), readEntryNames(empty));
            assertEquals(50L, empty.getEntry("new.txt").asFile().getSize());
            assertEquals(BIG_COUNT, root.getEntry("zbig").asDirectory().getEntries().size());
        }
    }

    @Test
    void writingAMergeOfOpenedFilesLeavesThemUnchanged() throws IOException {
        File diffFile = new File(tempDir, "diff");
        DirectoryEntryDirectoryMemory diffRoot = new DirectoryEntryDirectoryMemory("ROOT");
        DirectoryEntryDirectoryMemory diffSub = new DirectoryEntryDirectoryMemory("sub");
        try (ObjectWriter<DirectoryEntry> writer = diffSub.updateEntries()) {
            writer.write(memoryFile("b.txt", 2001L, 21L, content(40, 21L)));
            writer.write(new DirectoryEntryNull("link"));
        }
        try (ObjectWriter<DirectoryEntry> writer = diffRoot.updateEntries()) {
            writer.write(memoryFile("d.txt", 6000L, 60L, content(41, 60L)));
            writer.write(diffSub);
        }
        BackupFile.writeToFile(diffFile, diffRoot);

        File merged = new File(tempDir, "merged");
        try (BackupFile base = BackupFile.open(file, true); BackupFile diff = BackupFile.open(diffFile, true)) {
            DirectoryEntryDirectory baseRoot = base.getRootDirectory();
            DirectoryEntryDirectory diffOpened = diff.getRootDirectory();
            DirectoryEntryFile a = baseRoot.getEntry("a.txt").asFile();
            DirectoryEntryFile d = diffOpened.getEntry("d.txt").asFile();
            DirectoryEntryFile b = diffOpened.getEntry("sub").asDirectory().getEntry("b.txt").asFile();

            BackupFile.writeToFile(merged, new DirectoryEntryDirectoryMerge(baseRoot, diffOpened));

            try (ObjectWriter<FileContent> writer = a.updateFileContents()) {
                writer.write(content(50, 10L));
            }
            try (ObjectWriter<FileContent> writer = d.updateFileContents()) {
                writer.write(content(51, 60L));
            }
            try (ObjectWriter<FileContent> writer = b.updateFileContents()) {
                writer.write(content(52, 21L));
            }
        }

        try (BackupFile backupFile = BackupFile.open(merged)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertEquals(List.of("a.txt", "d.txt", "empty", "sub", "unhashed.txt", "zbig"),
                readEntryNames(root).stream().sorted().toList());
            assertEquals(List.of(content(1, 10L)), readContents(root, "a.txt"));
            assertEquals(List.of(content(41, 60L)), readContents(root, "d.txt"));
            DirectoryEntryDirectory sub = root.getEntry("sub").asDirectory();
            assertEquals(List.of("b.txt", "c.txt"), readEntryNames(sub).stream().sorted().toList());
            assertEquals(List.of(content(40, 21L)), readContents(sub, "b.txt"));
            assertEquals(List.of(content(3, 10L), content(4, 20L)), readContents(sub, "c.txt"));
            assertEquals(BIG_COUNT, root.getEntry("zbig").asDirectory().getEntries().size());
        }
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertEquals(List.of(content(50, 10L)), readContents(root, "a.txt"));
            DirectoryEntryDirectory sub = root.getEntry("sub").asDirectory();
            assertEquals(List.of(content(2, 20L)), readContents(sub, "b.txt"));
            assertEquals(List.of(content(3, 10L), content(4, 20L)), readContents(sub, "c.txt"));
        }
        try (BackupFile backupFile = BackupFile.open(diffFile)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertEquals(List.of(content(51, 60L)), readContents(root, "d.txt"));
            DirectoryEntryDirectory sub = root.getEntry("sub").asDirectory();
            assertEquals(List.of(content(52, 21L)), readContents(sub, "b.txt"));
            assertTrue(sub.getEntry("link").isNull());
        }
    }

    /**
     * Reads a file's contents through freshly opened streams, bypassing any cached entries.
     */
    private static List<FileContent> readContents(DirectoryEntryDirectory directory, String name) {
        DirectoryEntry entry = readEntry(directory, name);
        assertNotNull(entry, name);
        List<FileContent> contents = new ArrayList<>();
        for (FileContent content : entry.asFile()) {
            contents.add(content);
        }
        return contents;
    }

    private static DirectoryEntry readEntry(DirectoryEntryDirectory directory, String name) {
        for (DirectoryEntry entry : directory) {
            if (entry.getName().equals(name)) return entry;
        }
        return null;
    }

    private static List<String> readEntryNames(DirectoryEntryDirectory directory) {
        List<String> names = new ArrayList<>();
        for (DirectoryEntry entry : directory) {
            names.add(entry.getName());
        }
        return names;
    }
}
