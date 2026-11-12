package io.siggi.databackup.client.api;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.server.ServerTestHarness;
import io.siggi.databackup.util.ObjectWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ServerApi} against a real server started by the server module's {@link ServerTestHarness}.
 */
class ServerApiTest {

    private static final byte[] ALPHA = "alpha content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BETA = "beta content, which is a bit longer".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    private ServerTestHarness harness;
    private ServerApi api;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        api = apiFor(harness.createToken("upload:laptop", "read:laptop"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    private ServerApi apiFor(String token) {
        return ServerApi.builder(harness.uri("/").toString(), token)
                .retryPolicy(new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50), 2.0))
                .build();
    }

    // --- helpers ---

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static DirectoryEntryFile contentFile(String name, byte[] data) throws Exception {
        ContentIDFileContent content = new ContentIDFileContent();
        content.setOffset(0L);
        content.setLength(data.length);
        content.setHash(HexFormat.of().parseHex(sha256(data)));
        DirectoryEntryFile file = new DirectoryEntryFile(name, 1_700_000_000_123L, data.length);
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
     * A manifest with ALPHA in {@code Data/a.txt} (with permissions), BETA in {@code Data/Sub dir/b+c.txt} and a
     * symlink.
     */
    private Path manifest(UUID snapshotId) throws Exception {
        DirectoryEntryFile alpha = contentFile("a.txt", ALPHA);
        alpha.getExtra().add(new ExtraDataPosixPermissions(0644, 501, 20));
        DirectoryEntryDirectoryMemory data = dir("Data",
                alpha,
                dir("Sub dir", contentFile("b+c.txt", BETA)),
                new DirectoryEntrySymlink("link", "a.txt"));
        DirectoryEntryDirectoryMemory root = dir("", data);
        root.getExtra().add(new ExtraDataSnapshotId(snapshotId));
        Path file = Files.createTempFile(tempDir, "manifest", ".bin");
        BackupFile.writeToFile(file.toFile(), root);
        return file;
    }

    /**
     * Uploads a manifest and its content, commits it, and returns the backup name.
     */
    private String backup(UUID snapshotId) throws Exception {
        ManifestUploadResult upload = api.uploadManifest("laptop", manifest(snapshotId));
        List<ContentSource> sources = new ArrayList<>();
        for (String id : upload.missing()) {
            byte[] data = id.equals(sha256(ALPHA)) ? ALPHA : BETA;
            sources.add(ContentSource.ofBytes(id, "x.txt", data));
        }
        assertTrue(api.uploadContent(sources).allReceived());
        return api.commit("laptop", upload.pendingId());
    }

    // --- tests ---

    @Test
    void fullBackupFlow() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        ManifestUploadResult upload = api.uploadManifest("laptop", manifest(snapshotId));
        assertNotNull(UUID.fromString(upload.pendingId()));
        assertEquals(Set.of(sha256(ALPHA), sha256(BETA)), Set.copyOf(upload.missing()));
        assertEquals(2, upload.missing().size());

        MissingContentException missing = assertThrows(MissingContentException.class,
                () -> api.commit("laptop", upload.pendingId()));
        assertEquals(409, missing.getStatus());
        assertEquals(upload.missing(), missing.getMissing());

        Path alphaFile = tempDir.resolve("alpha.txt");
        Files.write(alphaFile, ALPHA);
        ContentUploadResult first = api.uploadContent(List.of(ContentSource.ofFile(alphaFile, sha256(ALPHA))));
        assertEquals(List.of(sha256(ALPHA)), first.stored());
        assertEquals(List.of(), first.alreadyPresent());

        ContentUploadResult second = api.uploadContent(List.of(
                ContentSource.ofFile(alphaFile, sha256(ALPHA)),
                ContentSource.ofStream(sha256(BETA), "beta.txt", BETA.length,
                        () -> new java.io.ByteArrayInputStream(BETA))));
        assertEquals(List.of(sha256(BETA)), second.stored());
        assertEquals(List.of(sha256(ALPHA)), second.alreadyPresent());
        assertTrue(second.allReceived());

        String backup = api.commit("laptop", upload.pendingId());
        assertTrue(backup.matches("\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}"), backup);

        assertEquals(List.of("laptop"), api.listMachines());
        List<BackupInfo> backups = api.listBackups("laptop");
        assertEquals(1, backups.size());
        assertEquals(backup, backups.get(0).name());
        assertEquals(snapshotId, backups.get(0).snapshotId());
        assertTrue(backups.get(0).time() > 0);
    }

    @Test
    void uploadContentBatchesByCountAndSize() throws Exception {
        Random random = new Random(1);
        List<ContentSource> sources = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            byte[] data = new byte[100 + i];
            random.nextBytes(data);
            ids.add(sha256(data));
            sources.add(ContentSource.ofBytes(sha256(data), "f" + i + ".bin", data));
        }
        ServerApi small = ServerApi.builder(harness.uri("/").toString(), harness.createToken("upload:laptop"))
                .maxBatchCount(3).maxBatchBytes(250).build();
        List<List<ContentSource>> batches = small.batches(sources);
        // 250 bytes allow only two pieces of 100+ bytes per batch.
        assertEquals(4, batches.size());
        for (List<ContentSource> batch : batches) assertTrue(batch.size() <= 2);

        ServerApi byCount = ServerApi.builder(harness.uri("/").toString(), harness.createToken("upload:laptop"))
                .maxBatchCount(3).build();
        assertEquals(List.of(3, 3, 1), byCount.batches(sources).stream().map(List::size).toList());

        // A piece bigger than the limit still goes, alone.
        ServerApi tiny = ServerApi.builder(harness.uri("/").toString(), harness.createToken("upload:laptop"))
                .maxBatchBytes(10).build();
        assertEquals(7, tiny.batches(sources).size());

        ContentUploadResult result = small.uploadContent(sources);
        assertEquals(ids, result.stored());
        assertTrue(result.allReceived());
    }

    @Test
    void uploadContentReportsContentThatDidNotMatchItsId() throws Exception {
        String wrongId = sha256("something else".getBytes(StandardCharsets.UTF_8));
        ContentUploadResult result = api.uploadContent(List.of(
                ContentSource.ofBytes(wrongId, "changed.txt", ALPHA),
                ContentSource.ofBytes(sha256(BETA), null, BETA)));
        assertEquals(List.of(sha256(ALPHA), sha256(BETA)), result.stored());
        assertEquals(List.of(wrongId), result.notReceived());
        assertFalse(result.allReceived());
    }

    @Test
    void listAndDownload() throws Exception {
        String backup = backup(UUID.randomUUID());

        List<DirectoryListingEntry> root = api.listDirectory("laptop", backup, "");
        assertEquals(1, root.size());
        assertEquals("Data", root.get(0).name());
        assertEquals(DirectoryListingEntry.Type.DIRECTORY, root.get(0).type());
        assertEquals(root, api.listDirectory("laptop", backup, "/"));

        List<DirectoryListingEntry> data = api.listDirectory("laptop", backup, "Data");
        Map<String, DirectoryListingEntry> byName = new HashMap<>();
        for (DirectoryListingEntry entry : data) byName.put(entry.name(), entry);
        assertEquals(Set.of("a.txt", "Sub dir", "link"), byName.keySet());
        assertEquals(DirectoryListingEntry.Type.DIRECTORY, byName.get("Sub dir").type());
        DirectoryListingEntry a = byName.get("a.txt");
        assertEquals(DirectoryListingEntry.Type.FILE, a.type());
        assertEquals((long) ALPHA.length, a.size());
        assertEquals(1_700_000_000_123L, a.modified());
        assertNotNull(a.permissions());
        assertEquals(0644, a.permissions().mode());
        assertEquals("rw-r--r--", a.permissions().string());
        assertEquals(501, a.permissions().owner());
        assertEquals(20, a.permissions().group());
        DirectoryListingEntry link = byName.get("link");
        assertEquals(DirectoryListingEntry.Type.SYMLINK, link.type());
        assertEquals("a.txt", link.target());
        assertNull(link.size());

        List<DirectoryListingEntry> sub = api.listDirectory("laptop", backup, "Data/Sub dir/");
        assertEquals(List.of("b+c.txt"), sub.stream().map(DirectoryListingEntry::name).toList());

        Path out = tempDir.resolve("out").resolve("b.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, "old contents that get replaced");
        api.downloadFile("laptop", backup, "Data/Sub dir/b+c.txt", out);
        assertArrayEquals(BETA, Files.readAllBytes(out));
        api.downloadFile("laptop", backup, "/Data/a.txt", out);
        assertArrayEquals(ALPHA, Files.readAllBytes(out));
        try (var files = Files.list(out.getParent())) {
            assertEquals(List.of(out), files.toList(), "no temp files left behind");
        }

        Path manifestOut = tempDir.resolve("downloaded.manifest");
        api.downloadManifest("laptop", backup, manifestOut);
        try (BackupFile manifest = BackupFile.open(manifestOut.toFile())) {
            assertNotNull(manifest.getRootDirectory().getEntry("Data"));
        }
    }

    @Test
    void downloadsGzipStoredContent() throws Exception {
        // Compressible content is stored gzipped; the client asks for gzip and must decompress it.
        byte[] big = "compress me please ".repeat(5000).getBytes(StandardCharsets.UTF_8);
        DirectoryEntryDirectoryMemory root = dir("", dir("Data", contentFile("big.txt", big)));
        root.getExtra().add(new ExtraDataSnapshotId(UUID.randomUUID()));
        Path manifest = Files.createTempFile(tempDir, "manifest", ".bin");
        BackupFile.writeToFile(manifest.toFile(), root);

        ManifestUploadResult upload = api.uploadManifest("laptop", manifest);
        api.uploadContent(List.of(ContentSource.ofBytes(sha256(big), "big.txt", big)));
        String backup = api.commit("laptop", upload.pendingId());

        Path out = tempDir.resolve("big.txt");
        api.downloadFile("laptop", backup, "Data/big.txt", out);
        assertArrayEquals(big, Files.readAllBytes(out));
    }

    @Test
    void errorsCarryStatusAndMessage() throws Exception {
        ServerApiException e = assertThrows(ServerApiException.class, () -> api.listBackups("desktop"));
        assertEquals(403, e.getStatus());
        assertEquals("Missing permission: read:desktop", e.getServerMessage());
        assertFalse(e.isTransient());

        e = assertThrows(ServerApiException.class, () -> apiFor("not-a-token").listMachines());
        assertEquals(401, e.getStatus());
        assertEquals("Invalid token", e.getServerMessage());

        backup(UUID.randomUUID());
        e = assertThrows(ServerApiException.class,
                () -> api.listDirectory("laptop", "2000-01-01-00-00-00", ""));
        assertEquals(404, e.getStatus());
        assertEquals("Unknown backup", e.getServerMessage());

        String backup = api.listBackups("laptop").get(0).name();
        e = assertThrows(ServerApiException.class,
                () -> api.downloadFile("laptop", backup, "Data/nope.txt", tempDir.resolve("nope")));
        assertEquals(404, e.getStatus());
        assertFalse(Files.exists(tempDir.resolve("nope")));

        e = assertThrows(ServerApiException.class,
                () -> api.commit("laptop", UUID.randomUUID().toString()));
        assertEquals(404, e.getStatus());
        assertFalse(e instanceof MissingContentException);
    }

    @Test
    void duplicateSnapshotIdIsAConflict() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        backup(snapshotId);
        ServerApiException e = assertThrows(ServerApiException.class,
                () -> api.uploadManifest("laptop", manifest(snapshotId)));
        assertEquals(409, e.getStatus());
        assertFalse(e instanceof MissingContentException);
        assertTrue(e.getServerMessage().contains(snapshotId.toString()), e.getServerMessage());
    }

    @Test
    void serverUrlWithTrailingSlashOrNot() throws Exception {
        String token = harness.createToken("read:laptop");
        String base = harness.uri("/").toString();
        assertTrue(base.endsWith("/"));
        String noSlash = base.substring(0, base.length() - 1);
        assertEquals(List.of(), ServerApi.builder(noSlash, token).build().listMachines());
        assertEquals(List.of(), ServerApi.builder(base + "/", token).build().listMachines());
    }

    @Test
    void segmentsAreEncoded() {
        assertEquals("Sub%20dir", ServerApi.segment("Sub dir"));
        assertEquals("b%2Bc.txt", ServerApi.segment("b+c.txt"));
        assertEquals("%C3%A9t%C3%A9", ServerApi.segment("été"));
        assertEquals("a%3Fb%23c%25", ServerApi.segment("a?b#c%"));
        assertEquals(List.of("a", "b"), ServerApi.pathPieces("//a//b/"));
        assertEquals("a_b_c_", ServerApi.quotable("a\"b\\c\n"));
        assertEquals("", ServerApi.quotable(null));
    }
}
