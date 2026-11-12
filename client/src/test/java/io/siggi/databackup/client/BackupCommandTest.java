package io.siggi.databackup.client;

import io.siggi.databackup.client.BackupCommand.BackupFailedException;
import io.siggi.databackup.client.BackupCommand.Outcome;
import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.client.ManifestBuilder.ScanSource;
import io.siggi.databackup.client.api.BackupInfo;
import io.siggi.databackup.client.api.RetryPolicy;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.server.ServerTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end backups through the test-only scan path: plain temp folders, a real server from
 * {@link ServerTestHarness}, then the backup is listed and downloaded.
 */
class BackupCommandTest {

    private static final String MACHINE = "laptop";

    @TempDir
    Path dir;

    private ServerTestHarness harness;
    private ServerApi api;
    private Path stateDir;
    private Path data;
    private Path home;
    private ByteArrayOutputStream outBytes;
    private PrintStream out;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        api = ServerApi.builder(harness.uri("/").toString(),
                        harness.createToken("upload:" + MACHINE, "read:" + MACHINE))
                .retryPolicy(new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(20), 2.0))
                .build();
        stateDir = dir.resolve("state");
        data = dir.resolve("data");
        home = dir.resolve("home");
        write(data, "a.txt", "hello");
        write(data, "docs/b.txt", "the b file");
        write(data, "docs/same.txt", "shared content");
        write(data, "empty.txt", "");
        write(home, "notes.txt", "notes");
        write(home, "copy-of-same.txt", "shared content");
        outBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    private static void write(Path root, String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private List<ScanSource> sources() {
        return List.of(scan("Data", data), scan("Home", home));
    }

    private static ScanSource scan(String name, Path path) {
        return new ScanSource(new Source(name, path, List.of(), ExcludeMatcher.none()), path.toString(), null,
                List.of());
    }

    private BackupCommand command() {
        return new BackupCommand(api, MACHINE, stateDir, out);
    }

    private String download(String backup, String path) throws Exception {
        Path target = dir.resolve("download-" + System.nanoTime());
        api.downloadFile(MACHINE, backup, path, target);
        return Files.readString(target);
    }

    @Test
    void backupIsListedAndDownloadable() throws Exception {
        Outcome outcome = command().backup(sources());

        List<BackupInfo> backups = api.listBackups(MACHINE);
        assertEquals(1, backups.size());
        assertEquals(outcome.backupName(), backups.get(0).name());
        assertEquals(outcome.snapshotId(), backups.get(0).snapshotId());
        assertEquals(6, outcome.files());

        assertEquals("hello", download(outcome.backupName(), "Data/a.txt"));
        assertEquals("the b file", download(outcome.backupName(), "Data/docs/b.txt"));
        assertEquals("shared content", download(outcome.backupName(), "Data/docs/same.txt"));
        assertEquals("", download(outcome.backupName(), "Data/empty.txt"));
        assertEquals("notes", download(outcome.backupName(), "Home/notes.txt"));
        assertEquals("shared content", download(outcome.backupName(), "Home/copy-of-same.txt"));

        // Shared content is read and uploaded once.
        assertEquals(5, outcome.uploadedIds().size(), outcome.uploadedIds().toString());
        assertEquals(1, outcome.uploadedIds().stream().filter(id -> {
            try {
                return id.equals(sha256("shared content"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).count());
        assertEquals("hello".length() + "the b file".length() + "shared content".length() + "notes".length(),
                outcome.uploadedBytes());

        assertTrue(Files.isRegularFile(stateDir.resolve(ManifestBuilder.LAST_MANIFEST)));
        assertFalse(Files.exists(stateDir.resolve(ManifestBuilder.PENDING_MANIFEST)));
    }

    @Test
    void secondBackupUploadsOnlyChangedContent() throws Exception {
        Outcome first = command().backup(sources());
        write(data, "docs/b.txt", "the b file, changed");

        Outcome second = command().backup(sources());
        assertEquals(List.of(sha256("the b file, changed")), second.uploadedIds());
        assertEquals("the b file, changed".length(), second.uploadedBytes());
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("5 reused from the last backup"),
                outBytes.toString(StandardCharsets.UTF_8));

        assertEquals(2, api.listBackups(MACHINE).size());
        assertEquals("the b file", download(first.backupName(), "Data/docs/b.txt"));
        assertEquals("the b file, changed", download(second.backupName(), "Data/docs/b.txt"));
        assertEquals("hello", download(second.backupName(), "Data/a.txt"));
    }

    @Test
    void nothingIsUploadedWhenTheServerHasEverything() throws Exception {
        command().backup(sources());
        Files.delete(stateDir.resolve(ManifestBuilder.LAST_MANIFEST));
        Outcome again = command().backup(sources());
        assertTrue(again.uploadedIds().isEmpty());
        assertEquals(0L, again.uploadedBytes());
        assertEquals(2, api.listBackups(MACHINE).size());
    }

    @Test
    void fileChangedAfterHashingFailsTheBackup() throws Exception {
        ManifestBuilder.Result manifest = new ManifestBuilder(stateDir, out).build(sources());
        write(data, "docs/b.txt", "THE B FILE"); // same length, different bytes
        Files.delete(home.resolve("notes.txt"));

        BackupFailedException e = assertThrows(BackupFailedException.class,
                () -> command().uploadAndCommit(manifest, Map.of("Data", data.toString(), "Home", home.toString())));
        assertTrue(e.getMessage().contains("2 files changed or vanished"), e.getMessage());
        assertTrue(e.getMessage().contains("Data/docs/b.txt: its content no longer matches its hash"),
                e.getMessage());
        assertTrue(e.getMessage().contains("Home/notes.txt: can't be read"), e.getMessage());

        assertTrue(api.listBackups(MACHINE).isEmpty());
        assertTrue(Files.exists(stateDir.resolve(ManifestBuilder.PENDING_MANIFEST)));
        assertFalse(Files.exists(stateDir.resolve(ManifestBuilder.LAST_MANIFEST)));
    }

    @Test
    void fileThatGotShorterFailsTheBackup() throws Exception {
        ManifestBuilder.Result manifest = new ManifestBuilder(stateDir, out).build(sources());
        write(data, "docs/b.txt", "short");
        BackupFailedException e = assertThrows(BackupFailedException.class,
                () -> command().uploadAndCommit(manifest, Map.of("Data", data.toString(), "Home", home.toString())));
        assertTrue(e.getMessage().contains("Data/docs/b.txt: it got shorter after it was hashed"), e.getMessage());
        assertTrue(api.listBackups(MACHINE).isEmpty());
    }

    @Test
    void exactLengthStreamSkipsTruncatesAndPads() throws Exception {
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean shortened = new AtomicBoolean();
        try (var in = new BackupCommand.ExactLengthInputStream(new ByteArrayInputStream(bytes), 2, 5,
                () -> shortened.set(true))) {
            assertArrayEquals("23456".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        assertFalse(shortened.get());
        try (var in = new BackupCommand.ExactLengthInputStream(new ByteArrayInputStream(bytes), 8, 4,
                () -> shortened.set(true))) {
            assertArrayEquals(new byte[]{'8', '9', 0, 0}, in.readAllBytes());
        }
        assertTrue(shortened.get());
    }

    @Test
    void summaryLine() {
        Outcome outcome = new Outcome("2026-11-12-10-00-00", java.util.UUID.randomUUID(), 12, 2048,
                List.of("x"), 1024);
        assertEquals("Backup 2026-11-12-10-00-00 complete: 12 files (2.0 KiB), 1.0 KiB uploaded, took 1.5s.",
                BackupCommand.summary(outcome, 1_500_000_000L));
    }
}
