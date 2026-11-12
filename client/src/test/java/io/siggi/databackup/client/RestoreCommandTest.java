package io.siggi.databackup.client;

import io.siggi.databackup.client.BackupCommand.Outcome;
import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.client.ManifestBuilder.ScanSource;
import io.siggi.databackup.client.RestoreCommand.RestoreFailedException;
import io.siggi.databackup.client.api.ManifestUploadResult;
import io.siggi.databackup.client.api.RetryPolicy;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.content.ZeroFilledFileContent;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.server.ServerTestHarness;
import io.siggi.databackup.util.ObjectWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restores against a real server from {@link ServerTestHarness}: backups made through the test-only scan path are
 * restored to another folder and compared with the source.
 */
class RestoreCommandTest {

    private static final String MACHINE = "laptop";
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    @TempDir
    Path dir;

    private ServerTestHarness harness;
    private ServerApi api;
    private Path data;
    private ByteArrayOutputStream outBytes;
    private PrintStream out;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        api = ServerApi.builder(harness.uri("/").toString(),
                        harness.createToken("upload:" + MACHINE, "read:" + MACHINE))
                .retryPolicy(new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(20), 2.0))
                .build();
        data = dir.resolve("data");
        outBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Read-only folders from a restore would stop @TempDir from cleaning up.
        if (POSIX && Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : paths.toList()) {
                    if (Files.isDirectory(path, NOFOLLOW)) {
                        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                    }
                }
            }
        }
        if (harness != null) harness.close();
    }

    private static Path write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    private static FileTime time(long seconds, int nanos) {
        return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
    }

    private static void chmod(Path path, String permissions) throws IOException {
        if (POSIX) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
    }

    /**
     * A source tree with nested folders, shared content, an empty file, a non-ASCII name, symlinks, odd modes and
     * sub-millisecond modified times.
     */
    private void buildSource() throws IOException {
        write(data, "a.txt", "hello");
        write(data, "docs/b.txt", "the b file");
        write(data, "docs/same.txt", "shared content");
        write(data, "docs/deeper/copy.txt", "shared content");
        write(data, "empty.txt", "");
        write(data, "Grüße – ünïcödé.txt", "non-ASCII name");
        Files.createDirectories(data.resolve("empty dir"));
        Files.createDirectories(data.resolve("locked"));
        write(data, "locked/inside.txt", "in a read-only folder");
        if (POSIX) {
            Files.createSymbolicLink(data.resolve("link-to-a"), Path.of("a.txt"));
            Files.createSymbolicLink(data.resolve("docs/link-out"), Path.of("../../outside/the/source"));
            Files.createSymbolicLink(data.resolve("dangling"), Path.of("no/such/file"));
        }
        chmod(data.resolve("a.txt"), "rw-r-----");
        chmod(data.resolve("docs/b.txt"), "r--------");
        chmod(data.resolve("docs/deeper/copy.txt"), "rwxr-x--x");
        chmod(data.resolve("docs/deeper"), "rwx--x---");
        chmod(data.resolve("locked/inside.txt"), "r--r--r--");

        long second = 1_700_000_000L;
        try (Stream<Path> paths = Files.walk(data)) {
            List<Path> all = new ArrayList<>(paths.toList());
            // Deepest first, so a folder's time isn't changed again by its children.
            all.sort((x, y) -> Integer.compare(y.getNameCount(), x.getNameCount()));
            int i = 0;
            for (Path path : all) {
                if (Files.isSymbolicLink(path)) continue;
                Files.setLastModifiedTime(path, time(second + i * 3600L, 123_456_789 + i));
                i++;
            }
            // One file with only millisecond precision.
            Files.setLastModifiedTime(data.resolve("empty.txt"), FileTime.fromMillis(1_600_000_000_250L));
        }
        chmod(data.resolve("locked"), "r-x------");
    }

    private Outcome backup() throws Exception {
        ScanSource source = new ScanSource(new Source("Data", data, List.of(), ExcludeMatcher.none()),
                data.toString(), null, List.of());
        return new BackupCommand(api, MACHINE, dir.resolve("state"), out).backup(List.of(source));
    }

    private RestoreCommand restorer(boolean overwrite) {
        return new RestoreCommand(api, out, overwrite, false);
    }

    /**
     * Compares two trees: types, file contents, symlink targets, modified times (files and folders) and modes.
     */
    private static void assertSameTree(Path expected, Path actual) throws IOException {
        List<String> expectedNames = names(expected);
        assertEquals(expectedNames, names(actual), "entries of " + actual);
        for (String name : expectedNames) {
            Path e = expected.resolve(name);
            Path a = actual.resolve(name);
            if (Files.isSymbolicLink(e)) {
                assertTrue(Files.isSymbolicLink(a), a + " is a symlink");
                assertEquals(Files.readSymbolicLink(e), Files.readSymbolicLink(a), a + " target");
                continue;
            }
            if (Files.isDirectory(e, NOFOLLOW)) {
                assertTrue(Files.isDirectory(a, NOFOLLOW), a + " is a folder");
                assertSameTree(e, a);
            } else {
                assertTrue(Files.isRegularFile(a, NOFOLLOW), a + " is a file");
                assertArrayEquals(Files.readAllBytes(e), Files.readAllBytes(a), a + " content");
            }
            assertEquals(Files.getLastModifiedTime(e, NOFOLLOW), Files.getLastModifiedTime(a, NOFOLLOW),
                    a + " modified time");
            if (POSIX) {
                assertEquals(PosixFilePermissions.toString(Files.getPosixFilePermissions(e, NOFOLLOW)),
                        PosixFilePermissions.toString(Files.getPosixFilePermissions(a, NOFOLLOW)), a + " mode");
            }
        }
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void restoredTreeMatchesTheSource() throws Exception {
        buildSource();
        Outcome backup = backup();
        Path target = dir.resolve("restored");

        RestoreCommand.Outcome outcome = restorer(false).restore(MACHINE, backup.backupName(), "Data", target);

        assertEquals(List.of(), outcome.failures());
        assertSameTree(data, target);
        assertEquals(7, outcome.files());
        // "shared content" is downloaded once and copied; the empty file needs no download.
        assertEquals(5, outcome.downloads());
        assertEquals(POSIX ? 3 : 0, outcome.symlinks());
    }

    @Test
    void wholeBackupAndLatestWork() throws Exception {
        buildSource();
        backup();
        Path target = dir.resolve("everything");

        RestoreCommand.Outcome outcome = restorer(false).restore(MACHINE, "latest", "/", target);

        assertEquals(List.of(), outcome.failures());
        assertEquals(List.of("Data"), names(target));
        assertSameTree(data, target.resolve("Data"));
    }

    @Test
    void singleFileGoesToTheDestinationOrIntoAnExistingFolder() throws Exception {
        buildSource();
        Outcome backup = backup();

        Path newFile = dir.resolve("out/new-name.txt");
        restorer(false).restore(MACHINE, backup.backupName(), "Data/docs/b.txt", newFile);
        assertEquals("the b file", Files.readString(newFile));
        assertEquals(Files.getLastModifiedTime(data.resolve("docs/b.txt")), Files.getLastModifiedTime(newFile));

        Path folder = Files.createDirectories(dir.resolve("folder"));
        restorer(false).restore(MACHINE, backup.backupName(), "Data/Grüße – ünïcödé.txt", folder);
        assertEquals("non-ASCII name", Files.readString(folder.resolve("Grüße – ünïcödé.txt")));
    }

    @Test
    void unknownPathFails() throws Exception {
        buildSource();
        Outcome backup = backup();
        RestoreFailedException e = assertThrows(RestoreFailedException.class,
                () -> restorer(false).restore(MACHINE, backup.backupName(), "Data/nope", dir.resolve("x")));
        assertTrue(e.getMessage().contains("No such path"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("x")));
    }

    @Test
    void existingFilesAreOnlyReplacedWithOverwrite() throws Exception {
        write(data, "a.txt", "hello");
        write(data, "sub/b.txt", "bee");
        Outcome backup = backup();
        Path target = dir.resolve("restored");
        Files.createDirectories(target.resolve("sub"));
        Files.writeString(target.resolve("sub/b.txt"), "local change");
        Files.writeString(target.resolve("unrelated.txt"), "keep me");

        RestoreFailedException e = assertThrows(RestoreFailedException.class,
                () -> restorer(false).restore(MACHINE, backup.backupName(), "Data", target));
        assertTrue(e.getMessage().contains("--overwrite"), e.getMessage());
        assertTrue(e.getMessage().contains("b.txt"), e.getMessage());
        // Nothing was restored.
        assertFalse(Files.exists(target.resolve("a.txt")));
        assertEquals("local change", Files.readString(target.resolve("sub/b.txt")));

        RestoreCommand.Outcome outcome = restorer(true).restore(MACHINE, backup.backupName(), "Data", target);
        assertEquals(List.of(), outcome.failures());
        assertEquals("hello", Files.readString(target.resolve("a.txt")));
        assertEquals("bee", Files.readString(target.resolve("sub/b.txt")));
        assertEquals("keep me", Files.readString(target.resolve("unrelated.txt")));
    }

    @Test
    void neverWritesThroughASymlinkInTheDestination() throws Exception {
        if (!POSIX) return;
        write(data, "sub/b.txt", "bee");
        write(data, "c.txt", "sea");
        Outcome backup = backup();

        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path outsideFile = write(dir, "outside-file.txt", "untouched");
        Path target = Files.createDirectories(dir.resolve("restored"));
        Files.createSymbolicLink(target.resolve("sub"), outside);
        Files.createSymbolicLink(target.resolve("c.txt"), outsideFile);

        assertThrows(RestoreFailedException.class,
                () -> restorer(false).restore(MACHINE, backup.backupName(), "Data", target));

        RestoreCommand.Outcome outcome = restorer(true).restore(MACHINE, backup.backupName(), "Data", target);
        assertEquals(List.of(), outcome.failures());
        assertEquals(List.of(), names(outside));
        assertEquals("untouched", Files.readString(outsideFile));
        assertFalse(Files.isSymbolicLink(target.resolve("sub")));
        assertEquals("bee", Files.readString(target.resolve("sub/b.txt")));
        assertFalse(Files.isSymbolicLink(target.resolve("c.txt")));
        assertEquals("sea", Files.readString(target.resolve("c.txt")));
    }

    @Test
    void aNonEmptyFolderIsNeverReplacedByAFile() throws Exception {
        write(data, "a.txt", "hello");
        Outcome backup = backup();
        Path target = dir.resolve("restored");
        write(target, "a.txt/keep.txt", "precious");

        RestoreFailedException e = assertThrows(RestoreFailedException.class,
                () -> restorer(true).restore(MACHINE, backup.backupName(), "Data", target));
        assertTrue(e.getMessage().contains("isn't empty"), e.getMessage());
        assertEquals("precious", Files.readString(target.resolve("a.txt/keep.txt")));
    }

    @Test
    void inlinedAndZeroFilledContentIsWrittenWithoutDownloading() throws Exception {
        DirectoryEntryDirectoryMemory root = new DirectoryEntryDirectoryMemory("");
        root.getExtra().add(new ExtraDataSnapshotId(UUID.randomUUID()));
        DirectoryEntryDirectoryMemory source = new DirectoryEntryDirectoryMemory("Data");
        DirectoryEntryFile mixed = new DirectoryEntryFile("mixed.bin", 1_600_000_000_000L, 10);
        InlinedFileContent inlined = new InlinedFileContent();
        inlined.setData("abc".getBytes(StandardCharsets.UTF_8));
        inlined.setOffset(2);
        inlined.setLength(3);
        ZeroFilledFileContent zeros = new ZeroFilledFileContent();
        zeros.setOffset(5);
        zeros.setLength(5);
        mixed.getFileContents().addAll(List.of(inlined, zeros));
        DirectoryEntryFile allZeros = new DirectoryEntryFile("zeros.bin", 1_600_000_000_000L, 4);
        ZeroFilledFileContent four = new ZeroFilledFileContent();
        four.setLength(4);
        allZeros.getFileContents().add(four);
        try (ObjectWriter<DirectoryEntry> writer = source.updateEntries()) {
            writer.write(mixed);
            writer.write(allZeros);
            writer.write(new DirectoryEntrySymlink("link", "mixed.bin"));
        }
        try (ObjectWriter<DirectoryEntry> writer = root.updateEntries()) {
            writer.write(source);
        }
        Path manifest = dir.resolve("manifest");
        BackupFile.writeToFile(manifest.toFile(), root);
        ManifestUploadResult upload = api.uploadManifest(MACHINE, manifest);
        assertEquals(List.of(), upload.missing());
        String backupName = api.commit(MACHINE, upload.pendingId());

        Path target = dir.resolve("restored");
        RestoreCommand.Outcome outcome = restorer(false).restore(MACHINE, backupName, "Data", target);

        assertEquals(List.of(), outcome.failures());
        assertEquals(0, outcome.downloads());
        assertArrayEquals(new byte[]{0, 0, 'a', 'b', 'c', 0, 0, 0, 0, 0}, Files.readAllBytes(target.resolve("mixed.bin")));
        assertArrayEquals(new byte[4], Files.readAllBytes(target.resolve("zeros.bin")));
        assertEquals(FileTime.fromMillis(1_600_000_000_000L), Files.getLastModifiedTime(target.resolve("mixed.bin")));
    }

    @Test
    void verifyChecksSizeAndHash() throws Exception {
        Path file = write(dir, "f.bin", "hello world");
        ContentIDFileContent content = new ContentIDFileContent();
        content.setOffset(6);
        content.setLength(5);
        content.setHash(MessageDigest.getInstance("SHA-256").digest("world".getBytes(StandardCharsets.UTF_8)));
        List<FileContent> contents = List.of(content);

        assertNull(RestoreCommand.verify(file, 11, contents));
        assertNotNull(RestoreCommand.verify(file, 12, contents));
        Files.writeString(file, "hello WORLD");
        assertNotNull(RestoreCommand.verify(file, 11, contents));
    }

    @Test
    void namesThatAreNotASinglePathElementAreRejected() {
        Path parent = dir.resolve("p");
        assertEquals(parent.resolve("ok.txt"), RestoreCommand.child(parent, "ok.txt"));
        for (String bad : new String[]{"", ".", "..", "a/b", "nul\0byte"}) {
            assertNull(RestoreCommand.child(parent, bad), bad);
        }
    }

    @Test
    void cliRestoresAndReportsTheSummary() throws Exception {
        write(data, "a.txt", "hello");
        backup();
        Path config = dir.resolve("client.json");
        String source = data.toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(config, "{\"serverUrl\": \"" + harness.uri("/") + "\", \"token\": \""
                + harness.createToken("read:" + MACHINE) + "\", \"machineName\": \"" + MACHINE + "\", "
                + "\"sources\": [{\"name\": \"Data\", \"path\": \"" + source + "\"}]}", StandardCharsets.UTF_8);
        Path target = dir.resolve("cli-restore");
        ByteArrayOutputStream cliOut = new ByteArrayOutputStream();
        ByteArrayOutputStream cliErr = new ByteArrayOutputStream();

        int code = ClientCli.run(new String[]{"--config", config.toString(), "restore", MACHINE, "latest", "Data",
                        target.toString()}, ClientPaths.defaults(), null, ServerApi::forConfig,
                new PrintStream(cliOut, true, StandardCharsets.UTF_8),
                new PrintStream(cliErr, true, StandardCharsets.UTF_8));

        assertEquals(ClientCli.OK, code, cliErr.toString(StandardCharsets.UTF_8));
        assertEquals("hello", Files.readString(target.resolve("a.txt")));
        assertTrue(cliOut.toString(StandardCharsets.UTF_8).contains("Restored 1 files"),
                cliOut.toString(StandardCharsets.UTF_8));

        code = ClientCli.run(new String[]{"--config", config.toString(), "restore", MACHINE, "latest", "Data",
                        target.toString()}, ClientPaths.defaults(), null, ServerApi::forConfig,
                new PrintStream(cliOut, true, StandardCharsets.UTF_8),
                new PrintStream(cliErr, true, StandardCharsets.UTF_8));
        assertEquals(ClientCli.FAILED, code);
        assertTrue(cliErr.toString(StandardCharsets.UTF_8).contains("--overwrite"),
                cliErr.toString(StandardCharsets.UTF_8));
    }
}
