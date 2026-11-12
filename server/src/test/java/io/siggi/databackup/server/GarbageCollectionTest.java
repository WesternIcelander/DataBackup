package io.siggi.databackup.server;

import com.google.gson.JsonParser;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.server.gc.GarbageCollector;
import io.siggi.databackup.util.ObjectWriter;
import io.siggi.databackup.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GarbageCollectionTest {

    private static final long HOUR = 3600_000L;
    private static final long OLD = System.currentTimeMillis() - 72 * HOUR;

    @TempDir
    Path dataDir;

    // --- helpers ---

    private DataRepository repository() {
        return new DataRepository(dataDir.resolve("repository").toFile());
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Stores content directly in the repository with the given last-touch time, returning its ID.
     */
    private String store(byte[] data, long lastTouch) throws Exception {
        String id = sha256(data);
        Path tmp = Files.createTempFile(dataDir, "content", ".tmp");
        Files.write(tmp, data);
        StoredData storedData = repository().getStoredData(id);
        assertTrue(storedData.storeCompressing(tmp.toFile(), null));
        storedData.touch();
        assertTrue(storedData.getMetadataFile().setLastModified(lastTouch));
        return id;
    }

    private boolean exists(String contentId) {
        return repository().getStoredData(contentId).dataExists();
    }

    private static DirectoryEntryFile contentFile(String name, byte[] data) throws Exception {
        ContentIDFileContent content = new ContentIDFileContent();
        content.setOffset(0L);
        content.setLength(data.length);
        content.setHash(HexFormat.of().parseHex(sha256(data)));
        DirectoryEntryFile file = new DirectoryEntryFile(name, 1000L, data.length);
        file.getFileContents().add(content);
        return file;
    }

    private static DirectoryEntryDirectoryMemory dir(String name, DirectoryEntry... entries) throws IOException {
        DirectoryEntryDirectoryMemory dir = new DirectoryEntryDirectoryMemory(name);
        try (ObjectWriter<DirectoryEntry> writer = dir.updateEntries()) {
            for (DirectoryEntry entry : entries) writer.write(entry);
        }
        return dir;
    }

    /**
     * Writes a manifest referencing the given contents (nested one directory down) to {@code file}.
     */
    private static void writeManifest(Path file, byte[]... contents) throws Exception {
        DirectoryEntry[] files = new DirectoryEntry[contents.length];
        for (int i = 0; i < contents.length; i++) files[i] = contentFile("f" + i, contents[i]);
        DirectoryEntryDirectoryMemory root = dir("", dir("Data", dir("sub", files)));
        root.getExtra().add(new ExtraDataSnapshotId(UUID.randomUUID()));
        Files.createDirectories(file.getParent());
        BackupFile.writeToFile(file.toFile(), root);
    }

    private Path committed(String machine, long time) {
        return dataDir.resolve("backups").resolve(machine).resolve(TimeUtil.toString(time));
    }

    private Path pending(String machine) {
        return dataDir.resolve("backups").resolve(machine).resolve(".pending").resolve(UUID.randomUUID().toString());
    }

    private GarbageCollector collector() {
        return DataBackupServer.createGarbageCollector(dataDir.toFile(), repository(), ServerConfig.defaults());
    }

    private record Result(int exitCode, String out, String err) {
    }

    private Result cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ServerCli.run(args, dataDir.toFile(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    // --- tests ---

    @Test
    void deletesOnlyUnreferencedContentOlderThanTheGracePeriod() throws Exception {
        byte[] referencedOld = bytes("referenced by a committed backup");
        byte[] referencedOtherMachine = bytes("referenced by another machine's backup");
        byte[] pendingOnly = bytes("referenced only by a pending manifest");
        byte[] unreferencedOld = bytes("unreferenced and old, " + "x".repeat(500));
        byte[] unreferencedNew = bytes("unreferenced but uploaded recently");
        String referencedOldId = store(referencedOld, OLD);
        String otherId = store(referencedOtherMachine, OLD);
        String pendingOnlyId = store(pendingOnly, OLD);
        String unreferencedOldId = store(unreferencedOld, OLD);
        String unreferencedNewId = store(unreferencedNew, System.currentTimeMillis() - HOUR);
        long onDisk = repository().getStoredData(unreferencedOldId).getFile().length();

        writeManifest(committed("laptop", OLD), referencedOld);
        writeManifest(committed("desktop", OLD), referencedOtherMachine);
        Path pendingNew = pending("laptop");
        writeManifest(pendingNew, pendingOnly, bytes("referenced but never uploaded"));

        GarbageCollector.Result dryRun = collector().run(true, System.currentTimeMillis());
        assertEquals(1, dryRun.contentDeleted());
        assertEquals(onDisk, dryRun.contentBytes());
        assertTrue(exists(unreferencedOldId), "a dry run deletes nothing");

        GarbageCollector.Result result = collector().run(false, System.currentTimeMillis());
        assertEquals(1, result.contentDeleted());
        assertEquals(onDisk, result.contentBytes());
        assertEquals(1, result.contentKeptNew());
        assertEquals(4, result.contentReferenced());
        assertTrue(result.failed().isEmpty(), result.failed().toString());

        assertTrue(exists(referencedOldId), "referenced content survives");
        assertTrue(exists(otherId), "content referenced by another machine survives");
        assertTrue(exists(pendingOnlyId), "a pending manifest's content survives");
        assertTrue(exists(unreferencedNewId), "unreferenced content within the grace period survives");
        assertFalse(exists(unreferencedOldId), "unreferenced old content is deleted");
        assertFalse(repository().getStoredData(unreferencedOldId).getMetadataFile().exists());
        assertTrue(Files.exists(pendingNew));
    }

    @Test
    void deletesOldPendingManifestsAndTheirContentAndOldTempFiles() throws Exception {
        byte[] content = bytes("only an abandoned upload needs this");
        String id = store(content, OLD);
        Path oldPending = pending("laptop");
        writeManifest(oldPending, content);
        assertTrue(oldPending.toFile().setLastModified(OLD));
        long pendingSize = Files.size(oldPending);

        Path tmp = dataDir.resolve("tmp");
        Files.createDirectories(tmp);
        Path oldTmp = Files.writeString(tmp.resolve("old"), "stale upload");
        assertTrue(oldTmp.toFile().setLastModified(OLD));
        Path newTmp = Files.writeString(tmp.resolve("new"), "upload in progress");

        GarbageCollector.Result result = collector().run(false, System.currentTimeMillis());
        assertEquals(1, result.pendingDeleted());
        assertEquals(pendingSize, result.pendingBytes());
        assertEquals(1, result.tmpDeleted());
        assertEquals("stale upload".length(), result.tmpBytes());
        assertEquals(1, result.contentDeleted());
        assertEquals(3, result.itemsDeleted());
        assertFalse(Files.exists(oldPending));
        assertFalse(Files.exists(oldTmp));
        assertTrue(Files.exists(newTmp));
        assertFalse(exists(id), "content only an expired pending manifest referenced is deleted");
    }

    @Test
    void anUnreadableCommittedManifestStopsContentDeletion() throws Exception {
        String id = store(bytes("unreferenced"), OLD);
        Path manifest = committed("laptop", OLD);
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "not a manifest");
        Files.writeString(manifest.resolveSibling("notes.txt"), "not a backup name, ignored");

        assertThrows(GarbageCollector.ManifestUnreadableException.class,
                () -> collector().run(false, System.currentTimeMillis()));
        assertTrue(exists(id));
    }

    @Test
    void worksWithNothingThere() throws Exception {
        GarbageCollector.Result result = collector().run(false, System.currentTimeMillis());
        assertEquals(0, result.itemsDeleted());
    }

    @Test
    void cliRunsAndRefusesWhileTheServerRuns() throws Exception {
        String id = store(bytes("unreferenced"), OLD);

        Result dryRun = cli("gc", "--dry-run");
        assertEquals(0, dryRun.exitCode(), dryRun.err());
        assertTrue(dryRun.out().contains("would free 1 item(s)"), dryRun.out());
        assertTrue(exists(id));

        assertEquals(2, cli("gc", "--force").exitCode());

        try (DataBackupServer server = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            server.start();
            // The server's own cleanup (run once at startup) may collect the content meanwhile; only the refusal is
            // checked here.
            Result refused = cli("gc");
            assertEquals(1, refused.exitCode());
            assertTrue(refused.err().contains("server is running"), refused.err());
        }

        id = store(bytes("unreferenced"), OLD);
        Result real = cli("gc");
        assertEquals(0, real.exitCode(), real.err());
        assertTrue(real.out().contains("freed 1 item(s)"), real.out());
        assertFalse(exists(id));
    }

    @Test
    void aSecondServerCannotStartOnTheSameDataFolder() throws Exception {
        try (DataBackupServer first = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             DataBackupServer second = new DataBackupServer(dataDir.toFile(),
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            first.start();
            assertThrows(IllegalStateException.class, second::start);
        }
        // Released on shutdown.
        try (DataBackupServer again = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            again.start();
        }
    }

    @Test
    void aSecondServerLeavesTheRunningServersTempFilesAlone() throws Exception {
        try (DataBackupServer first = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            first.start();
            Path upload = dataDir.resolve("tmp").resolve("in-progress-upload");
            Files.write(upload, bytes("half an upload"));
            try (DataBackupServer second = new DataBackupServer(dataDir.toFile(),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
                assertThrows(IllegalStateException.class, second::start);
            }
            assertTrue(Files.exists(upload), "the first server's temp file was deleted");
        }
        // A server that does start clears leftovers.
        Path leftover = dataDir.resolve("tmp").resolve("leftover");
        Files.write(leftover, bytes("leftover"));
        try (DataBackupServer again = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            again.start();
            assertFalse(Files.exists(leftover));
        }
    }

    @Test
    void uploadedAndCommittedContentSurvivesServerCleanup() throws Exception {
        try (ServerTestHarness harness = ServerTestHarness.start()) {
            String token = harness.createToken("upload:laptop", "read:laptop");
            byte[] kept = bytes("kept content");
            byte[] orphan = bytes("orphan content");
            HttpResponse<String> upload = harness.uploadContent(token,
                    new ServerTestHarness.FilePart("kept.txt", kept),
                    new ServerTestHarness.FilePart("orphan.txt", orphan));
            assertEquals(200, upload.statusCode(), upload.body());

            Path manifest = harness.dataDir().resolve("upload-manifest");
            writeManifest(manifest, kept);
            HttpResponse<String> put = harness.put("/backups/laptop", token, Files.readAllBytes(manifest));
            assertEquals(200, put.statusCode(), put.body());
            String pendingId = JsonParser.parseString(put.body()).getAsJsonObject().get("pendingId").getAsString();
            HttpResponse<String> commit = harness.post("/backups/laptop/.pending/" + pendingId + "/commit", token,
                    new byte[0]);
            assertEquals(200, commit.statusCode(), commit.body());

            DataRepository repository = harness.server().getDataRepository();
            for (String id : new String[]{sha256(kept), sha256(orphan)}) {
                StoredData storedData = repository.getStoredData(id);
                assertTrue(storedData.getMetadataFile().setLastModified(OLD));
            }

            harness.server().cleanup();

            assertTrue(repository.getStoredData(sha256(kept)).dataExists(), "committed content survives");
            assertFalse(repository.getStoredData(sha256(orphan)).dataExists(), "old orphaned content is deleted");
            HttpResponse<String> download = harness.get("/backups/laptop/" + JsonParser.parseString(commit.body())
                    .getAsJsonObject().get("backup").getAsString() + "/Data/sub/f0", token);
            assertEquals(200, download.statusCode(), download.body());
            assertEquals("kept content", download.body());
        }
    }

    @Test
    void gcWaitsForACommitInProgress() throws Exception {
        try (DataBackupServer server = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            server.getMaintenanceLock().readLock().lock();
            CompletableFuture<GarbageCollector.Result> gc;
            try {
                gc = CompletableFuture.supplyAsync(() -> {
                    try {
                        return server.gc(false);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                assertThrows(TimeoutException.class, () -> gc.get(300, TimeUnit.MILLISECONDS),
                        "gc must wait while the commit lock is held");
            } finally {
                server.getMaintenanceLock().readLock().unlock();
            }
            assertEquals(0, gc.get(10, TimeUnit.SECONDS).itemsDeleted());
        }
    }
}
