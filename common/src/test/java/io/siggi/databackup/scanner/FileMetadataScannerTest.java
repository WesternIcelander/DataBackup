package io.siggi.databackup.scanner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.util.Util;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileMetadataScannerTest {
    @TempDir
    Path tempDir;

    Path source;

    /**
     * Relative path to file contents, for every file that should end up in the backup.
     */
    final Map<String, byte[]> expectedFiles = new TreeMap<>();

    private void writeFile(String path, byte[] data) throws IOException {
        Path file = source.resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, data);
    }

    private void addFile(String path, String text) throws IOException {
        addFile(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private void addFile(String path, byte[] data) throws IOException {
        writeFile(path, data);
        expectedFiles.put(path, data);
    }

    @BeforeEach
    void createSource() throws IOException {
        source = tempDir.resolve("source");
        Files.createDirectories(source);
        addFile("a.txt", "hello");
        addFile("sub/b.txt", "the b file");
        byte[] big = new byte[200_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 7 + i / 251);
        addFile("sub/deep/c.bin", big);
        addFile("sub/deep/empty-file", new byte[0]);
        Files.createDirectories(source.resolve("empty"));
        Files.createSymbolicLink(source.resolve("link"), Path.of("a.txt"));
        Files.createSymbolicLink(source.resolve("sub/dir-link"), Path.of("deep"));

        // excluded by .nobackup
        writeFile("excluded/secret.txt", "secret".getBytes(StandardCharsets.UTF_8));
        writeFile("excluded/.nobackup", new byte[0]);
        writeFile("sub/deep/also-excluded/x.txt", "x".getBytes(StandardCharsets.UTF_8));
        writeFile("sub/deep/also-excluded/.nobackup", new byte[0]);

        // excluded by the path predicate below
        writeFile("skip.log", "log".getBytes(StandardCharsets.UTF_8));
        writeFile("sub/deep/skip.log", "log".getBytes(StandardCharsets.UTF_8));
        writeFile("cache/big-cache-file", "cache".getBytes(StandardCharsets.UTF_8));

        Files.setPosixFilePermissions(source.resolve("a.txt"), PosixFilePermissions.fromString("rw-r-----"));
    }

    private static boolean include(String path) {
        return !path.endsWith(".log") && !path.equals("cache");
    }

    private FileMetadataScanner scan() throws IOException, InterruptedException {
        return new FileMetadataScanner(source.toFile()).setPathPredicate(FileMetadataScannerTest::include).grabFiles();
    }

    private static byte[] sha256(byte[] data) {
        return Util.sha256().digest(data);
    }

    private static Map<String, DirectoryEntryFile> collectFiles(DirectoryEntryDirectory directory) {
        Map<String, DirectoryEntryFile> files = new TreeMap<>();
        collectFiles(directory, "", files);
        return files;
    }

    private static void collectFiles(DirectoryEntryDirectory directory, String path, Map<String, DirectoryEntryFile> files) {
        for (DirectoryEntry entry : directory) {
            if (entry.isFile()) {
                files.put(path + entry.getName(), entry.asFile());
            } else if (entry.isDirectory()) {
                collectFiles(entry.asDirectory(), path + entry.getName() + "/", files);
            }
        }
    }

    private void assertHashes(DirectoryEntryDirectory root) {
        Map<String, DirectoryEntryFile> files = collectFiles(root);
        assertEquals(expectedFiles.keySet(), files.keySet());
        for (Map.Entry<String, byte[]> expected : expectedFiles.entrySet()) {
            DirectoryEntryFile file = files.get(expected.getKey());
            byte[] data = expected.getValue();
            assertEquals(data.length, file.getSize(), expected.getKey());
            List<FileContent> contents = file.getFileContents();
            assertEquals(1, contents.size(), expected.getKey());
            ContentIDFileContent content = assertInstanceOf(ContentIDFileContent.class, contents.get(0));
            assertEquals(0L, content.getOffset());
            assertEquals(data.length, content.getLength());
            assertArrayEquals(sha256(data), content.getHash(), expected.getKey());
        }
    }

    @Test
    void scansTreeWithCountsAndExclusions() throws Exception {
        FileMetadataScanner scanner = scan();
        DirectoryEntryDirectory root = scanner.rootDirectory;

        assertEquals(4L, scanner.fileCount);
        assertEquals(4L, scanner.unhashedFiles);
        long totalSize = expectedFiles.values().stream().mapToLong(d -> d.length).sum();
        assertEquals(totalSize, scanner.totalFileSize);
        assertEquals(totalSize, scanner.unhashedSize);
        assertEquals(3L, scanner.directoryCount); // sub, sub/deep, empty
        assertEquals(2L, scanner.symlinkCount);
        assertEquals(List.of("cache", "excluded", "skip.log", "sub/deep/also-excluded", "sub/deep/skip.log"),
            scanner.ignoredItems.stream().sorted().toList());
        assertTrue(scanner.failedItems.isEmpty());

        assertEquals(List.of("a.txt", "empty", "link", "sub"), root.getEntries().keySet().stream().sorted().toList());
        assertEquals(List.of("b.txt", "deep", "dir-link"),
            root.getEntry("sub").asDirectory().getEntries().keySet().stream().sorted().toList());
        assertTrue(root.getEntry("empty").asDirectory().getEntries().isEmpty());
        assertEquals("a.txt", root.getEntry("link").asSymlink().getTarget());
        assertEquals("deep", root.getEntry("sub").asDirectory().getEntry("dir-link").asSymlink().getTarget());

        String absolute = source.toFile().getAbsolutePath().replace(File.separatorChar, '/');
        assertEquals(new ExtraDataFilePath(absolute), root.getExtra(ExtraDataFilePath.class));

        DirectoryEntryFile a = root.getEntry("a.txt").asFile();
        assertEquals(Files.getLastModifiedTime(source.resolve("a.txt")).toMillis(), a.getLastModified());
        ExtraDataPosixPermissions permissions = a.getExtra(ExtraDataPosixPermissions.class);
        assertNotNull(permissions);
        assertEquals("rw-r-----", permissions.getPermissionsAsString());

        // nothing is hashed yet
        for (DirectoryEntryFile file : collectFiles(root).values()) {
            assertFalse(file.hasFileContents());
        }
    }

    @Test
    void hashesFiles() throws Exception {
        FileMetadataScanner scanner = scan();
        List<String> hashed = new ArrayList<>();
        scanner.fillInHashes(hashed::add);
        assertEquals(expectedFiles.keySet().stream().toList(), hashed.stream().sorted().toList());
        assertEquals(0L, scanner.unhashedFiles);
        assertEquals(0L, scanner.unhashedSize);
        assertTrue(scanner.failedItems.isEmpty());
        assertHashes(scanner.rootDirectory);
    }

    @Test
    void hashesSurviveBackupFileRoundTrip() throws Exception {
        FileMetadataScanner scanner = scan();
        scanner.fillInHashes(null);
        File manifest = tempDir.resolve("manifest").toFile();
        BackupFile.writeToFile(manifest, scanner.rootDirectory);
        try (BackupFile backupFile = BackupFile.open(manifest)) {
            assertHashes(backupFile.getRootDirectory());
            assertEquals("a.txt", backupFile.getRootDirectory().getEntry("link").asSymlink().getTarget());
        }
    }

    @Test
    void copyHashesSkipsUnchangedFiles() throws Exception {
        FileMetadataScanner first = scan();
        first.fillInHashes(null);
        File manifest = tempDir.resolve("manifest").toFile();
        BackupFile.writeToFile(manifest, first.rootDirectory);

        // changed size and contents
        addFile("sub/b.txt", "the b file, changed");
        // same size, different contents and modified time
        Path cPath = source.resolve("sub/deep/c.bin");
        byte[] c = expectedFiles.get("sub/deep/c.bin").clone();
        c[1000] ^= 0x55;
        addFile("sub/deep/c.bin", c);
        Files.setLastModifiedTime(cPath, FileTime.fromMillis(Files.getLastModifiedTime(cPath).toMillis() + 5000L));
        // a new file
        addFile("empty/new.txt", "new");

        try (BackupFile previous = BackupFile.open(manifest)) {
            FileMetadataScanner second = scan();
            assertEquals(5L, second.unhashedFiles);
            second.copyHashes(previous.getRootDirectory());
            assertEquals(3L, second.unhashedFiles);
            long changedSize = expectedFiles.get("sub/b.txt").length + c.length + 3L;
            assertEquals(changedSize, second.unhashedSize);

            List<String> hashed = new ArrayList<>();
            second.fillInHashes(hashed::add);
            assertEquals(List.of("empty/new.txt", "sub/b.txt", "sub/deep/c.bin"), hashed.stream().sorted().toList());
            assertEquals(0L, second.unhashedFiles);
            assertEquals(0L, second.unhashedSize);
            assertHashes(second.rootDirectory);
        }
    }

    @Test
    void copyHashesIntoBackupFileInPlace() throws Exception {
        // the scanner can also write into an opened BackupFile, as the server-side copy would
        FileMetadataScanner first = scan();
        first.fillInHashes(null);
        File previousManifest = tempDir.resolve("previous").toFile();
        BackupFile.writeToFile(previousManifest, first.rootDirectory);

        addFile("sub/b.txt", "changed again!");
        File newManifest = tempDir.resolve("new").toFile();
        BackupFile.writeToFile(newManifest, scan().rootDirectory);

        try (BackupFile previous = BackupFile.open(previousManifest);
             BackupFile current = BackupFile.open(newManifest, true)) {
            FileMetadataScanner scanner = new FileMetadataScanner(current.getRootDirectory(), source.toFile());
            scanner.copyHashes(previous.getRootDirectory());
            List<String> hashed = new ArrayList<>();
            scanner.fillInHashes(hashed::add);
            assertEquals(List.of("sub/b.txt"), hashed);
        }
        try (BackupFile current = BackupFile.open(newManifest)) {
            assertHashes(current.getRootDirectory());
        }
    }

    @Test
    void listsRootFromAnotherDirectory() throws Exception {
        // Only entries listed in the live root are scanned: "only-live" is missing from the scanned root, so it is
        // left out, and "empty", "link" and the excluded entries aren't listed at all.
        Path live = tempDir.resolve("live");
        Files.createDirectories(live.resolve("sub"));
        Files.createDirectories(live.resolve("only-live"));
        Files.writeString(live.resolve("a.txt"), "a different, longer live file");
        FileMetadataScanner scanner = new FileMetadataScanner(source.toFile())
            .setPathPredicate(FileMetadataScannerTest::include)
            .setRootListingDirectory(live.toFile())
            .grabFiles();
        DirectoryEntryDirectory root = scanner.rootDirectory;
        assertEquals(List.of("a.txt", "sub"), root.getEntries().keySet().stream().sorted().toList());
        // metadata and subdirectories come from the scanned root, not the listing directory
        assertEquals(5L, root.getEntry("a.txt").asFile().getSize());
        assertEquals(List.of("b.txt", "deep", "dir-link"),
            root.getEntry("sub").asDirectory().getEntries().keySet().stream().sorted().toList());
        scanner.fillInHashes(null);
        assertArrayEquals(sha256("hello".getBytes(StandardCharsets.UTF_8)),
            ((ContentIDFileContent) root.getEntry("a.txt").asFile().getFileContents().get(0)).getHash());
    }

    @Test
    void unlistableRootFails() {
        FileMetadataScanner scanner = new FileMetadataScanner(tempDir.resolve("missing").toFile());
        IOException e = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, scanner::grabFiles);
        assertTrue(e.getMessage().contains("missing"), e.getMessage());
    }

    @Test
    void unlistableSubdirectoryIsReportedAsFailed() throws Exception {
        Path locked = source.resolve("sub/locked");
        Files.createDirectories(locked);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(locked), "running as root");
            FileMetadataScanner scanner = scan();
            assertEquals(List.of("sub/locked"), scanner.failedItems);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void wholeSecondTimesAreRecordedForDirectoriesAndSymlinks() throws Exception {
        FileTime wholeSecond = FileTime.from(Instant.parse("2020-01-02T03:04:05Z"));
        Files.setLastModifiedTime(source.resolve("a.txt"), wholeSecond);
        Files.setLastModifiedTime(source.resolve("empty"), wholeSecond);
        DirectoryEntryDirectory root = scan().rootDirectory;

        // A file's own millisecond time already holds a whole-second time exactly.
        DirectoryEntryFile a = root.getEntry("a.txt").asFile();
        assertEquals(wholeSecond.toMillis(), a.getLastModified());
        assertNull(a.getExtra(ExtraDataNanosecondModifiedDate.class));
        // Directories and symlinks have no other time, so they always get the nanosecond one. (Java can't set a
        // symlink's own time on every system, so the symlink's is whatever it was created with.)
        ExtraDataNanosecondModifiedDate time = root.getEntry("empty").getExtra(ExtraDataNanosecondModifiedDate.class);
        assertNotNull(time);
        assertEquals(wholeSecond.toInstant().getEpochSecond(), time.seconds);
        assertEquals(0, time.nanos);
        assertNotNull(root.getEntry("link").getExtra(ExtraDataNanosecondModifiedDate.class));
    }
}
