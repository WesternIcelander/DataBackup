package io.siggi.databackup.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.content.ZeroFilledFileContent;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.util.ObjectWriter;
import io.siggi.databackup.util.TimeUtil;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backup listing, browsing and download: {@code GET /backups/}, {@code GET /backups/:machine/},
 * {@code GET /backups/:machine/:backup/...}, {@code ?manifest}, the JSON listings and {@code GET /content/:hash}.
 */
class BrowseAndDownloadTest {

    private static final String OLD_BACKUP = "2026-01-02-03-04-05";
    private static final String BACKUP = "2026-02-03-04-05-06";
    private static final UUID SNAPSHOT_ID = UUID.randomUUID();

    private static final byte[] ALPHA = "alpha content".getBytes(StandardCharsets.UTF_8);
    /**
     * Stored gzip-compressed in the content store.
     */
    private static final byte[] GZ_DATA = "gzip stored content, gzip stored content, gzip stored content"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] MISSING = "never uploaded".getBytes(StandardCharsets.UTF_8);

    private ServerTestHarness harness;
    private String token;
    private String base;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        token = harness.createToken("read:laptop");
        base = "/backups/laptop/" + BACKUP;

        String uploader = harness.createToken("upload:laptop");
        assertEquals(200, harness.uploadContent(uploader, new ServerTestHarness.FilePart("page.html", ALPHA))
                .statusCode());
        storeGzipped(GZ_DATA);

        Path laptop = harness.dataDir().resolve("backups").resolve("laptop");
        Files.createDirectories(laptop);
        Files.createDirectories(harness.dataDir().resolve("backups").resolve("desktop"));
        writeBackup(laptop.resolve(OLD_BACKUP), UUID.randomUUID(), dir("", dir("Data")));
        writeBackup(laptop.resolve(BACKUP), SNAPSHOT_ID, tree());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    // --- building backups ---

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void storeGzipped(byte[] data) throws Exception {
        Path temp = Files.createTempFile(harness.dataDir(), "gz", ".tmp");
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(temp))) {
            out.write(data);
        }
        StoredData storedData = harness.server().getDataRepository().getStoredData(sha256(data));
        assertTrue(storedData.store(temp.toFile(), "gzip"));
    }

    private static ContentIDFileContent contentId(long offset, byte[] data) throws Exception {
        ContentIDFileContent content = new ContentIDFileContent();
        content.setOffset(offset);
        content.setLength(data.length);
        content.setHash(HexFormat.of().parseHex(sha256(data)));
        return content;
    }

    private static InlinedFileContent inlined(long offset, byte[] data) {
        InlinedFileContent content = new InlinedFileContent();
        content.setOffset(offset);
        content.setData(data);
        content.setLength(data.length);
        return content;
    }

    private static ZeroFilledFileContent zeros(long offset, long length) {
        ZeroFilledFileContent content = new ZeroFilledFileContent();
        content.setOffset(offset);
        content.setLength(length);
        return content;
    }

    private static DirectoryEntryFile file(String name, long size, FileContent... contents) {
        DirectoryEntryFile file = new DirectoryEntryFile(name, 1_700_000_000_000L, size);
        file.getFileContents().addAll(Arrays.asList(contents));
        return file;
    }

    private static DirectoryEntryDirectoryMemory dir(String name, DirectoryEntry... entries) throws IOException {
        DirectoryEntryDirectoryMemory dir = new DirectoryEntryDirectoryMemory(name);
        try (ObjectWriter<DirectoryEntry> writer = dir.updateEntries()) {
            for (DirectoryEntry entry : entries) writer.write(entry);
        }
        return dir;
    }

    private static void writeBackup(Path path, UUID snapshotId, DirectoryEntryDirectoryMemory root) throws IOException {
        root.getExtra().add(new ExtraDataSnapshotId(snapshotId));
        BackupFile.writeToFile(path.toFile(), root);
    }

    private static final byte[] HI = "hi".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ABC = "abc".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNICODE = "unicode name".getBytes(StandardCharsets.UTF_8);

    private static DirectoryEntryDirectoryMemory tree() throws Exception {
        DirectoryEntryFile alpha = file("a.txt", ALPHA.length, contentId(0, ALPHA));
        alpha.getExtra().add(new ExtraDataPosixPermissions(0644, 501, 20));
        alpha.getExtra().add(new ExtraDataNanosecondModifiedDate(1_700_000_000L, 123_456_789));
        DirectoryEntryDirectoryMemory sub = dir("Sub dir", dir("deep", file("deep.txt", HI.length, inlined(0, HI))));
        sub.getExtra().add(new ExtraDataPosixPermissions(0755, 0, 0));
        DirectoryEntryDirectoryMemory data = dir("Data",
                alpha,
                file("page.html", ALPHA.length, contentId(0, ALPHA)),
                file("image.svg", HI.length, inlined(0, HI)),
                file("inline.txt", HI.length, inlined(0, HI)),
                file("zeros.bin", 1000, zeros(0, 1000)),
                // A sparse file: 5 zero bytes, "abc", then 12 more zero bytes that no content covers.
                file("sparse.bin", 20, inlined(5, ABC)),
                // Stored content followed by inlined data; listed out of order to check sorting by offset.
                file("multi.bin", ALPHA.length + HI.length, inlined(ALPHA.length, HI), contentId(0, ALPHA)),
                file("empty.txt", 0),
                file("gz.txt", GZ_DATA.length, contentId(0, GZ_DATA)),
                file("partial.bin", MISSING.length + HI.length, contentId(0, MISSING), inlined(MISSING.length, HI)),
                file("my file ü.txt", UNICODE.length, inlined(0, UNICODE)),
                new DirectoryEntrySymlink("link", "a.txt"),
                sub);
        return dir("", data);
    }

    // --- assertions ---

    private static void assertDownload(byte[] expected, HttpResponse<byte[]> response) {
        assertEquals(200, response.statusCode(), () -> new String(response.body(), StandardCharsets.UTF_8));
        assertArrayEquals(expected, response.body());
        assertEquals(String.valueOf(expected.length), response.headers().firstValue("Content-Length").orElse(null));
    }

    private static String contentType(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    private static JsonArray jsonArray(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response::body);
        assertTrue(contentType(response).startsWith("application/json"), contentType(response));
        return JsonParser.parseString(response.body()).getAsJsonArray();
    }

    private static JsonObject findEntry(JsonArray entries, String name) {
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.get("name").getAsString().equals(name)) return entry;
        }
        throw new AssertionError("No entry " + name + " in " + entries);
    }

    private HttpResponse<byte[]> getBytes(String path, String... headers) throws Exception {
        java.net.http.HttpRequest.Builder builder = harness.request(path, token).GET();
        if (headers.length > 0) builder.headers(headers);
        return harness.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // --- machine and backup listings ---

    @Test
    void machinesAreListedAsTextAndJson() throws Exception {
        String all = harness.createToken("read:*");
        assertEquals("desktop\nlaptop\n", harness.get("/backups/", all).body());
        JsonArray json = jsonArray(harness.get("/backups/?json", all));
        assertEquals(2, json.size());
        assertEquals("desktop", json.get(0).getAsString());
        assertEquals("laptop", json.get(1).getAsString());
        // Only machines the token can read.
        JsonArray laptopOnly = jsonArray(harness.get("/backups?json", token));
        assertEquals(1, laptopOnly.size());
        assertEquals("laptop", laptopOnly.get(0).getAsString());
    }

    @Test
    void backupsAreListedOldestFirstAsTextAndJson() throws Exception {
        HttpResponse<String> text = harness.get("/backups/laptop/", token);
        assertEquals(200, text.statusCode());
        assertEquals(OLD_BACKUP + "\n" + BACKUP + "\n", text.body());
        // Without the trailing slash too.
        assertEquals(text.body(), harness.get("/backups/laptop", token).body());

        JsonArray json = jsonArray(harness.send(harness.request("/backups/laptop/", token)
                .header("Accept", "text/html;q=0.9, application/json").GET().build()));
        assertEquals(2, json.size());
        JsonObject latest = json.get(1).getAsJsonObject();
        assertEquals(BACKUP, latest.get("name").getAsString());
        assertEquals(TimeUtil.fromString(BACKUP), latest.get("time").getAsLong());
        assertEquals(SNAPSHOT_ID.toString(), latest.get("snapshotId").getAsString());
        assertEquals(OLD_BACKUP, json.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void emptyMachineHasNoBackups() throws Exception {
        HttpResponse<String> response = harness.get("/backups/desktop/", harness.createToken("read:desktop"));
        assertEquals(200, response.statusCode());
        assertEquals("", response.body());
        assertEquals(0, jsonArray(harness.get("/backups/desktop/?json", harness.createToken("read:desktop"))).size());
    }

    // --- browsing ---

    @Test
    void browsingDescendsIntoNestedDirectories() throws Exception {
        HttpResponse<String> data = harness.get(base + "/Data/", token);
        assertEquals(200, data.statusCode());
        assertTrue(data.body().contains("F a.txt\n"), data.body());
        assertTrue(data.body().contains("D Sub dir/\n"), data.body());
        assertTrue(data.body().contains("L link -> a.txt\n"), data.body());

        HttpResponse<String> sub = harness.get(base + "/Data/Sub%20dir/", token);
        assertEquals(200, sub.statusCode(), sub.body());
        assertEquals("D deep/\n", sub.body());

        HttpResponse<String> deep = harness.get(base + "/Data/Sub%20dir/deep/", token);
        assertEquals("F deep.txt\n", deep.body());

        assertDownload(HI, getBytes(base + "/Data/Sub%20dir/deep/deep.txt"));
        // A name that only exists one level up must not be found deeper down (the old loop never descended).
        assertEquals(404, harness.get(base + "/Data/Sub%20dir/a.txt", token).statusCode());
        assertEquals(404, harness.get(base + "/Data/Sub%20dir/deep/nope/", token).statusCode());
    }

    @Test
    void pathPiecesAreUrlDecoded() throws Exception {
        assertDownload(UNICODE, getBytes(base + "/Data/my%20file%20%C3%BC.txt"));
    }

    @Test
    void directoryWithoutSlashRedirectsKeepingEncodingAndQuery() throws Exception {
        HttpResponse<String> response = harness.get(base + "/Data/Sub%20dir?json", token);
        assertEquals(303, response.statusCode());
        assertEquals(base + "/Data/Sub%20dir/?json", response.headers().firstValue("Location").orElse(null));

        HttpResponse<String> root = harness.get(base, token);
        assertEquals(303, root.statusCode());
        assertEquals(base + "/", root.headers().firstValue("Location").orElse(null));
    }

    @Test
    void symlinkCannotBeDownloaded() throws Exception {
        assertEquals(404, harness.get(base + "/Data/link", token).statusCode());
    }

    @Test
    void directoryListingAsJson() throws Exception {
        JsonArray root = jsonArray(harness.get(base + "/?json", token));
        assertEquals(1, root.size());
        assertEquals("directory", root.get(0).getAsJsonObject().get("type").getAsString());

        JsonArray viaQuery = jsonArray(harness.get(base + "/Data/?json", token));
        JsonArray viaAccept = jsonArray(harness.send(harness.request(base + "/Data/", token)
                .header("Accept", "application/json").GET().build()));
        assertEquals(viaQuery, viaAccept);
        assertEquals(13, viaQuery.size());

        JsonObject alpha = findEntry(viaQuery, "a.txt");
        assertEquals("file", alpha.get("type").getAsString());
        assertEquals(ALPHA.length, alpha.get("size").getAsLong());
        assertEquals(1_700_000_000_123L, alpha.get("modified").getAsLong());
        assertEquals(1_700_000_000_123_456_789L, alpha.get("modifiedNanos").getAsLong());
        JsonObject permissions = alpha.getAsJsonObject("permissions");
        assertEquals(0644, permissions.get("mode").getAsInt());
        assertEquals("rw-r--r--", permissions.get("string").getAsString());
        assertEquals(501, permissions.get("owner").getAsLong());
        assertEquals(20, permissions.get("group").getAsLong());
        assertNull(alpha.get("target"));

        JsonObject inline = findEntry(viaQuery, "inline.txt");
        assertEquals(1_700_000_000_000L, inline.get("modified").getAsLong());
        assertNull(inline.get("modifiedNanos"));
        assertNull(inline.get("permissions"));

        JsonObject link = findEntry(viaQuery, "link");
        assertEquals("symlink", link.get("type").getAsString());
        assertEquals("a.txt", link.get("target").getAsString());
        assertNull(link.get("size"));

        JsonObject sub = findEntry(viaQuery, "Sub dir");
        assertEquals("directory", sub.get("type").getAsString());
        assertEquals(0755, sub.getAsJsonObject("permissions").get("mode").getAsInt());
        assertNull(sub.get("size"));

        assertEquals("my file ü.txt", findEntry(viaQuery, "my file ü.txt").get("name").getAsString());
    }

    // --- file download ---

    @Test
    void downloadsEveryContentType() throws Exception {
        assertDownload(ALPHA, getBytes(base + "/Data/a.txt"));
        assertDownload(HI, getBytes(base + "/Data/inline.txt"));
        assertDownload(new byte[1000], getBytes(base + "/Data/zeros.bin"));
        byte[] sparse = new byte[20];
        System.arraycopy(ABC, 0, sparse, 5, ABC.length);
        assertDownload(sparse, getBytes(base + "/Data/sparse.bin"));
        ByteArrayOutputStream multi = new ByteArrayOutputStream();
        multi.writeBytes(ALPHA);
        multi.writeBytes(HI);
        assertDownload(multi.toByteArray(), getBytes(base + "/Data/multi.bin"));
        assertDownload(new byte[0], getBytes(base + "/Data/empty.txt"));
    }

    @Test
    void missingContentGives503WithMessage() throws Exception {
        HttpResponse<String> response = harness.get(base + "/Data/partial.bin", token);
        assertEquals(503, response.statusCode());
        assertTrue(contentType(response).startsWith("text/plain"));
        assertTrue(response.body().contains("missing"), response.body());
    }

    @Test
    void contentTypeAndDispositionComeFromTheEntryName() throws Exception {
        HttpResponse<byte[]> text = getBytes(base + "/Data/a.txt");
        assertTrue(contentType(text).startsWith("text/plain"), contentType(text));
        assertEquals("inline; filename=\"a.txt\"; filename*=UTF-8''a.txt",
                text.headers().firstValue("Content-Disposition").orElse(null));
        assertEquals("nosniff", text.headers().firstValue("X-Content-Type-Options").orElse(null));

        HttpResponse<byte[]> unicode = getBytes(base + "/Data/my%20file%20%C3%BC.txt");
        assertEquals("inline; filename=\"my file _.txt\"; filename*=UTF-8''my%20file%20%C3%BC.txt",
                unicode.headers().firstValue("Content-Disposition").orElse(null));

        assertEquals("application/octet-stream", contentType(getBytes(base + "/Data/zeros.bin")));
    }

    @Test
    void scriptableTypesAreNeverSent() throws Exception {
        for (String name : new String[]{"page.html", "image.svg"}) {
            HttpResponse<byte[]> response = getBytes(base + "/Data/" + name);
            assertEquals(200, response.statusCode());
            assertEquals("application/octet-stream", contentType(response), name);
        }
    }

    @Test
    void gzipStoredContentIsSentCompressedOnlyWhenAccepted() throws Exception {
        HttpResponse<byte[]> plain = getBytes(base + "/Data/gz.txt");
        assertDownload(GZ_DATA, plain);
        assertNull(plain.headers().firstValue("Content-Encoding").orElse(null));

        HttpResponse<byte[]> refused = getBytes(base + "/Data/gz.txt", "Accept-Encoding", "gzip;q=0, identity");
        assertDownload(GZ_DATA, refused);

        HttpResponse<byte[]> gzipped = getBytes(base + "/Data/gz.txt", "Accept-Encoding", "deflate, gzip");
        assertEquals(200, gzipped.statusCode());
        assertEquals("gzip", gzipped.headers().firstValue("Content-Encoding").orElse(null));
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzipped.body()))) {
            assertArrayEquals(GZ_DATA, in.readAllBytes());
        }
    }

    // --- /content/:hash ---

    @Test
    void contentByHashUsesKnownFilenameSafely() throws Exception {
        String reader = harness.createToken("globalread");
        HttpResponse<byte[]> response = harness.getBytes("/content/" + sha256(ALPHA), reader);
        assertEquals(200, response.statusCode());
        assertArrayEquals(ALPHA, response.body());
        // Uploaded as page.html, but HTML is never sent for stored content.
        assertEquals("application/octet-stream", contentType(response));
        assertEquals("inline; filename=\"page.html\"; filename*=UTF-8''page.html",
                response.headers().firstValue("Content-Disposition").orElse(null));
    }

    @Test
    void contentByHashWithoutKnownFilename() throws Exception {
        // The gzipped content was stored directly, so it has no known filename.
        String reader = harness.createToken("globalread");
        HttpResponse<byte[]> response = harness.getBytes("/content/" + sha256(GZ_DATA), reader);
        assertEquals(200, response.statusCode());
        assertArrayEquals(GZ_DATA, response.body());
        assertEquals("application/octet-stream", contentType(response));
        assertFalse(response.headers().firstValue("Content-Disposition").isPresent());

        byte[] unnamed = "uploaded without a name".getBytes(StandardCharsets.UTF_8);
        harness.uploadContent(harness.createToken("upload:laptop"), new ServerTestHarness.FilePart(null, unnamed));
        HttpResponse<byte[]> unnamedResponse = harness.getBytes("/content/" + sha256(unnamed), reader);
        assertEquals(200, unnamedResponse.statusCode());
        assertArrayEquals(unnamed, unnamedResponse.body());
    }

    // --- ?manifest ---

    @Test
    void manifestIsReturnedRaw() throws Exception {
        byte[] onDisk = Files.readAllBytes(harness.dataDir().resolve("backups/laptop/" + BACKUP));
        for (String path : new String[]{base + "?manifest", base + "/?manifest"}) {
            HttpResponse<byte[]> response = getBytes(path);
            assertDownload(onDisk, response);
            assertEquals("application/octet-stream", contentType(response));
            String disposition = response.headers().firstValue("Content-Disposition").orElse("");
            assertTrue(disposition.startsWith("attachment;"), disposition);
        }
        // The downloaded manifest opens as a backup.
        Path copy = Files.createTempFile(harness.dataDir(), "copy", ".manifest");
        Files.write(copy, getBytes(base + "?manifest").body());
        try (BackupFile file = BackupFile.open(copy.toFile())) {
            assertNotNull(file.getRootDirectory().getEntry("Data"));
            assertEquals(SNAPSHOT_ID, file.getRootDirectory().getExtra(ExtraDataSnapshotId.class).uuid);
        }
    }

    @Test
    void manifestErrors() throws Exception {
        assertEquals(403, harness.get(base + "?manifest", harness.createToken("read:desktop", "globalread"))
                .statusCode());
        assertEquals(404, harness.get("/backups/laptop/2020-01-01-00-00-00?manifest", token).statusCode());
        assertEquals(400, harness.get(base + "/Data/?manifest", token).statusCode());
        assertEquals(400, harness.get(base + "/Data/a.txt?manifest", token).statusCode());
    }
}
