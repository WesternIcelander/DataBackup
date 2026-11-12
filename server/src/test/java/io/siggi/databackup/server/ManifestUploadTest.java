package io.siggi.databackup.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.util.ObjectWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload-then-commit flow: {@code PUT /backups/:machine} and
 * {@code POST /backups/:machine/.pending/:pendingId/commit}.
 */
class ManifestUploadTest {

    private static final byte[] ALPHA = "alpha content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BETA = "beta content".getBytes(StandardCharsets.UTF_8);

    private ServerTestHarness harness;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        token = harness.createToken("upload:laptop", "read:laptop");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
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
        DirectoryEntryFile file = new DirectoryEntryFile(name, 1000L, data.length);
        file.getFileContents().add(content);
        return file;
    }

    private static DirectoryEntryFile inlinedFile(String name, byte[] data) {
        InlinedFileContent inlined = new InlinedFileContent();
        inlined.setOffset(0L);
        inlined.setData(data);
        inlined.setLength(data.length);
        DirectoryEntryFile file = new DirectoryEntryFile(name, 1000L, data.length);
        file.getFileContents().add(inlined);
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
     * A manifest referencing ALPHA twice (two files with the same content), BETA once, and one inlined file.
     */
    private byte[] manifest(UUID snapshotId) throws Exception {
        DirectoryEntryDirectoryMemory data = dir("Data",
                contentFile("a.txt", ALPHA),
                contentFile("a-copy.txt", ALPHA),
                dir("sub", contentFile("b.txt", BETA)),
                inlinedFile("small.txt", "hi".getBytes(StandardCharsets.UTF_8)));
        DirectoryEntryDirectoryMemory root = dir("", data);
        if (snapshotId != null) root.getExtra().add(new ExtraDataSnapshotId(snapshotId));
        Path file = Files.createTempFile(harness.dataDir(), "manifest", ".bin");
        try {
            BackupFile.writeToFile(file.toFile(), root);
            return Files.readAllBytes(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private void storeContent(byte[] data) throws Exception {
        Path tmp = Files.createTempFile(harness.dataDir(), "content", ".bin");
        Files.write(tmp, data);
        assertTrue(harness.server().getDataRepository().getStoredData(sha256(data)).store(tmp.toFile(), null));
    }

    private static JsonObject json(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"), "Content-Type was " + contentType);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array) {
        List<String> list = new ArrayList<>();
        array.forEach(element -> list.add(element.getAsString()));
        return list;
    }

    private HttpResponse<String> commit(String pendingId) throws Exception {
        return harness.post("/backups/laptop/.pending/" + pendingId + "/commit", token, new byte[0]);
    }

    private JsonObject upload(byte[] body, String... headers) throws Exception {
        HttpResponse<String> response = harness.put("/backups/laptop", token, body, headers);
        assertEquals(200, response.statusCode(), response::body);
        return json(response);
    }

    // --- tests ---

    @Test
    void uploadContentThenCommitListsTheBackup() throws Exception {
        JsonObject uploaded = upload(manifest(UUID.randomUUID()));
        String pendingId = uploaded.get("pendingId").getAsString();
        // Each missing content ID is listed once, even though ALPHA is referenced twice.
        assertEquals(List.of(sha256(ALPHA), sha256(BETA)), strings(uploaded.getAsJsonArray("missing")));
        assertTrue(Files.isRegularFile(harness.dataDir().resolve("backups/laptop/.pending/" + pendingId)));

        // Pending backups aren't listed.
        assertEquals("", harness.get("/backups/laptop/", token).body());

        storeContent(ALPHA);
        storeContent(BETA);
        long beforeCommit = System.currentTimeMillis();

        HttpResponse<String> committed = commit(pendingId);
        assertEquals(200, committed.statusCode(), committed::body);
        String backup = json(committed).get("backup").getAsString();
        assertFalse(Files.exists(harness.dataDir().resolve("backups/laptop/.pending/" + pendingId)));

        HttpResponse<String> list = harness.get("/backups/laptop/", token);
        assertEquals(backup + "\n", list.body());
        // Downloading stored content crashes in returnStoredData until the download step fixes it, so check the
        // committed tree through browsing and an inlined file.
        HttpResponse<String> sub = harness.get("/backups/laptop/" + backup + "/Data/sub/", token);
        assertEquals(200, sub.statusCode());
        assertEquals("F b.txt\n", sub.body());
        assertEquals("hi", harness.get("/backups/laptop/" + backup + "/Data/small.txt", token).body());

        // Commit touches the referenced content so garbage collection's grace period covers it.
        for (byte[] data : new byte[][]{ALPHA, BETA}) {
            StoredData storedData = harness.server().getDataRepository().getStoredData(sha256(data));
            assertTrue(storedData.getLastTouch() >= beforeCommit - 1000L, "content was not touched");
        }

        // The pending manifest is gone, so a second commit is a 404.
        assertEquals(404, commit(pendingId).statusCode());
    }

    @Test
    void nothingMissingWhenContentIsAlreadyThere() throws Exception {
        storeContent(ALPHA);
        JsonObject uploaded = upload(manifest(UUID.randomUUID()));
        assertEquals(List.of(sha256(BETA)), strings(uploaded.getAsJsonArray("missing")));
        storeContent(BETA);
        assertEquals(0, upload(manifest(UUID.randomUUID())).getAsJsonArray("missing").size());
    }

    @Test
    void commitWithContentStillMissingGives409() throws Exception {
        String pendingId = upload(manifest(UUID.randomUUID())).get("pendingId").getAsString();
        storeContent(ALPHA);

        HttpResponse<String> response = commit(pendingId);
        assertEquals(409, response.statusCode());
        assertEquals(List.of(sha256(BETA)), strings(json(response).getAsJsonArray("missing")));
        assertEquals("", harness.get("/backups/laptop/", token).body());

        // Still pending, so it can be committed once the content arrives.
        storeContent(BETA);
        assertEquals(200, commit(pendingId).statusCode());
    }

    @Test
    void duplicateSnapshotIdGives409() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        storeContent(ALPHA);
        storeContent(BETA);
        String first = upload(manifest(snapshotId)).get("pendingId").getAsString();
        // A second pending upload of the same snapshot is allowed (the client may retry), but only one can commit.
        String second = upload(manifest(snapshotId)).get("pendingId").getAsString();
        assertNotEquals(first, second);
        assertEquals(200, commit(first).statusCode());
        assertEquals(409, commit(second).statusCode());

        HttpResponse<String> again = harness.put("/backups/laptop", token, manifest(snapshotId));
        assertEquals(409, again.statusCode(), again::body);
        assertTrue(again.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
        assertEquals(1, harness.get("/backups/laptop/", token).body().lines().count());
    }

    @Test
    void sameSnapshotIdOnAnotherMachineIsFine() throws Exception {
        UUID snapshotId = UUID.randomUUID();
        storeContent(ALPHA);
        storeContent(BETA);
        assertEquals(200, commit(upload(manifest(snapshotId)).get("pendingId").getAsString()).statusCode());
        String desktop = harness.createToken("upload:desktop");
        HttpResponse<String> response = harness.put("/backups/desktop", desktop, manifest(snapshotId));
        assertEquals(200, response.statusCode(), response::body);
    }

    @Test
    void manifestWithoutSnapshotIdGives400() throws Exception {
        HttpResponse<String> response = harness.put("/backups/laptop", token, manifest(null));
        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("snapshot ID"), response.body());
        assertFalse(Files.exists(harness.dataDir().resolve("backups/laptop/.pending")));
    }

    @Test
    void gzippedAndPlainUploadsBothWork() throws Exception {
        storeContent(ALPHA);
        storeContent(BETA);
        String plain = upload(manifest(UUID.randomUUID())).get("pendingId").getAsString();
        String gzipped = upload(gzip(manifest(UUID.randomUUID())), "Content-Encoding", "gzip")
                .get("pendingId").getAsString();
        String identity = upload(manifest(UUID.randomUUID()), "Content-Encoding", "identity")
                .get("pendingId").getAsString();
        List<String> names = new ArrayList<>();
        for (String pendingId : new String[]{plain, gzipped, identity}) {
            HttpResponse<String> response = commit(pendingId);
            assertEquals(200, response.statusCode(), response::body);
            names.add(json(response).get("backup").getAsString());
        }
        // Commits in the same second get distinct names.
        assertEquals(3, names.stream().distinct().count());
        String list = harness.get("/backups/laptop/", token).body();
        for (String name : names) {
            assertTrue(list.contains(name + "\n"), list);
            HttpResponse<String> small = harness.get("/backups/laptop/" + name + "/Data/small.txt", token);
            assertEquals("hi", small.body());
        }
    }

    @Test
    void commitErrors() throws Exception {
        String pendingId = upload(manifest(UUID.randomUUID())).get("pendingId").getAsString();
        String base = "/backups/laptop/.pending/";
        // Unknown pending ID.
        assertEquals(404, commit(UUID.randomUUID().toString()).statusCode());
        // Malformed pending ID.
        assertEquals(400, commit("not-a-uuid").statusCode());
        // Wrong method.
        HttpResponse<String> get = harness.get(base + pendingId + "/commit", token);
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElse(null));
        // Unknown routes under .pending.
        assertEquals(404, harness.post(base + pendingId, token, new byte[0]).statusCode());
        assertEquals(404, harness.post(base, token, new byte[0]).statusCode());
        assertEquals(404, harness.post(base + pendingId + "/other", token, new byte[0]).statusCode());
        // Permissions: read alone isn't enough, and another machine's upload permission isn't either.
        assertEquals(403, harness.post(base + pendingId + "/commit", harness.createToken("read:laptop"),
                new byte[0]).statusCode());
        assertEquals(403, harness.post(base + pendingId + "/commit", harness.createToken("upload:desktop"),
                new byte[0]).statusCode());
        // A pending ID from one machine can't be committed on another.
        String both = harness.createToken("upload:*");
        assertEquals(404, harness.post("/backups/desktop/.pending/" + pendingId + "/commit", both,
                new byte[0]).statusCode());
    }
}
