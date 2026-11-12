package io.siggi.databackup.server;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.util.ObjectWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Error statuses and permission checks of {@link RequestHandler}.
 */
class RequestHandlerErrorTest {

    private static final String BACKUP = "2026-01-02-03-04-05";
    private static final String MISSING_HASH = "ab".repeat(32);

    private ServerTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        Path laptop = harness.dataDir().resolve("backups").resolve("laptop");
        Files.createDirectories(laptop);
        Files.createDirectories(harness.dataDir().resolve("backups").resolve("desktop"));
        writeBackup(laptop.resolve(BACKUP));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    private static void writeBackup(Path file) throws IOException {
        InlinedFileContent inlined = new InlinedFileContent();
        byte[] hello = "hello".getBytes(StandardCharsets.UTF_8);
        inlined.setOffset(0L);
        inlined.setData(hello);
        inlined.setLength(hello.length);
        DirectoryEntryFile helloFile = new DirectoryEntryFile("hello.txt", 1000L, hello.length);
        helloFile.getFileContents().add(inlined);

        ContentIDFileContent missing = new ContentIDFileContent();
        missing.setOffset(0L);
        missing.setLength(10L);
        missing.setHash(new byte[32]);
        for (int i = 0; i < 32; i++) missing.getHash()[i] = (byte) 0xab;
        DirectoryEntryFile missingFile = new DirectoryEntryFile("missing.bin", 1000L, 10L);
        missingFile.getFileContents().add(missing);

        DirectoryEntryDirectoryMemory data = dir("Data", helloFile, missingFile);
        DirectoryEntryDirectoryMemory root = dir("", data);
        root.getExtra().add(new ExtraDataSnapshotId(UUID.randomUUID()));
        BackupFile.writeToFile(file.toFile(), root);
    }

    private static DirectoryEntryDirectoryMemory dir(String name, DirectoryEntry... entries) throws IOException {
        DirectoryEntryDirectoryMemory dir = new DirectoryEntryDirectoryMemory(name);
        try (ObjectWriter<DirectoryEntry> writer = dir.updateEntries()) {
            for (DirectoryEntry entry : entries) writer.write(entry);
        }
        return dir;
    }

    private static void assertError(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), () -> "body: " + response.body());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("text/plain"), "Content-Type was " + contentType);
        assertFalse(response.body().isBlank(), "error body should explain the error");
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        return harness.send(harness.request(path, token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build());
    }

    // --- sanity: the happy paths these error tests sit next to ---

    @Test
    void readableBackupCanBeListedAndBrowsed() throws Exception {
        String token = harness.createToken("read:laptop");
        HttpResponse<String> list = harness.get("/backups/laptop/", token);
        assertEquals(200, list.statusCode());
        assertEquals(BACKUP + "\n", list.body());

        HttpResponse<String> root = harness.get("/backups/laptop/" + BACKUP + "/", token);
        assertEquals(200, root.statusCode());
        assertEquals("D Data/\n", root.body());

        HttpResponse<String> data = harness.get("/backups/laptop/" + BACKUP + "/Data/", token);
        assertEquals(200, data.statusCode());
        assertTrue(data.body().contains("F hello.txt\n"), data.body());

        HttpResponse<String> file = harness.get("/backups/laptop/" + BACKUP + "/Data/hello.txt", token);
        assertEquals(200, file.statusCode());
        assertEquals("hello", file.body());
    }

    @Test
    void machineListOnlyShowsReadableMachines() throws Exception {
        HttpResponse<String> response = harness.get("/backups/", harness.createToken("read:laptop"));
        assertEquals(200, response.statusCode());
        assertEquals("laptop\n", response.body());

        HttpResponse<String> all = harness.get("/backups/", harness.createToken("read:*"));
        assertEquals("desktop\nlaptop\n", all.body());

        HttpResponse<String> none = harness.get("/backups/", harness.createToken("upload:laptop"));
        assertEquals(200, none.statusCode());
        assertEquals("", none.body());
    }

    // --- 401 ---

    @Test
    void missingTokenGives401() throws Exception {
        for (String path : new String[]{"/backups/", "/backups/laptop/", "/content/" + MISSING_HASH, "/upload", "/nope"}) {
            HttpResponse<String> response = harness.get(path, null);
            assertError(401, response);
            assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElse(null));
        }
    }

    @Test
    void invalidTokenGives401() throws Exception {
        assertError(401, harness.get("/backups/laptop/", "not-a-real-token"));
        assertError(401, harness.put("/backups/laptop", "not-a-real-token", new byte[100]));
    }

    @Test
    void tokenCookieIsAccepted() throws Exception {
        String token = harness.createToken("read:laptop");
        HttpResponse<String> response = harness.send(harness.request("/backups/laptop/", null)
                .header("Cookie", "token=" + token).GET().build());
        assertEquals(200, response.statusCode());
    }

    // --- 403 ---

    @Test
    void readingAnotherMachineGives403() throws Exception {
        String token = harness.createToken("read:desktop", "upload:laptop", "globalread");
        assertError(403, harness.get("/backups/laptop/", token));
        assertError(403, harness.get("/backups/laptop", token));
        assertError(403, harness.get("/backups/laptop/" + BACKUP + "/", token));
        assertError(403, harness.get("/backups/laptop/" + BACKUP + "/Data/hello.txt", token));
    }

    @Test
    void forbiddenComesBeforeNotFound() throws Exception {
        // Without read:ghost the client must not learn whether "ghost" exists.
        assertError(403, harness.get("/backups/ghost/", harness.createToken("read:laptop")));
    }

    @Test
    void uploadingForAnotherMachineGives403() throws Exception {
        assertError(403, harness.put("/backups/laptop", harness.createToken("read:laptop"), new byte[10]));
        assertError(403, harness.put("/backups/laptop", harness.createToken("upload:desktop"), new byte[10]));
        // The old bare "upload" permission is not part of the permission model.
        assertError(403, harness.put("/backups/laptop", harness.createToken("upload"), new byte[10]));
    }

    @Test
    void contentWithoutGlobalReadGives403() throws Exception {
        assertError(403, harness.get("/content/" + MISSING_HASH, harness.createToken("read:*", "upload:*")));
    }

    @Test
    void contentUploadWithoutUploadPermissionGives403() throws Exception {
        String multipart = "multipart/form-data; boundary=xyz";
        assertError(403, harness.post("/upload", harness.createToken("read:*", "globalread"), new byte[0],
                "Content-Type", multipart));
        assertError(403, harness.post("/upload", harness.createToken("upload"), new byte[0],
                "Content-Type", multipart));
    }

    // --- 404 ---

    @Test
    void unknownRouteGives404() throws Exception {
        String token = harness.createToken("read:*", "upload:*", "globalread", "admin");
        assertError(404, harness.get("/nope", token));
        assertError(404, harness.get("/", token));
        assertError(404, harness.get("/backupsx/laptop", token));
    }

    @Test
    void unknownMachineGives404() throws Exception {
        assertError(404, harness.get("/backups/ghost/", harness.createToken("read:*")));
        assertError(404, harness.get("/backups/ghost/" + BACKUP + "/", harness.createToken("read:ghost")));
    }

    @Test
    void unknownBackupGives404() throws Exception {
        String token = harness.createToken("read:laptop");
        assertError(404, harness.get("/backups/laptop/2020-01-01-00-00-00/", token));
        assertError(404, harness.get("/backups/laptop/2020-01-01-00-00-00/Data/hello.txt", token));
    }

    @Test
    void unknownPathGives404() throws Exception {
        String token = harness.createToken("read:laptop");
        String base = "/backups/laptop/" + BACKUP;
        assertError(404, harness.get(base + "/Nope/", token));
        assertError(404, harness.get(base + "/Data/nope.txt", token));
        // A file can't be descended into or listed as a directory.
        assertError(404, harness.get(base + "/Data/hello.txt/more", token));
        assertError(404, harness.get(base + "/Data/hello.txt/", token));
    }

    @Test
    void unknownContentGives404() throws Exception {
        assertError(404, harness.get("/content/" + MISSING_HASH, harness.createToken("globalread")));
    }

    // --- 405 ---

    @Test
    void wrongMethodGives405() throws Exception {
        String token = harness.createToken("read:*", "upload:*", "globalread");
        HttpResponse<String> upload = harness.get("/upload", token);
        assertError(405, upload);
        assertEquals("POST", upload.headers().firstValue("Allow").orElse(null));

        HttpResponse<String> content = send("POST", "/content/" + MISSING_HASH, token);
        assertError(405, content);
        assertEquals("GET", content.headers().firstValue("Allow").orElse(null));

        HttpResponse<String> machine = send("DELETE", "/backups/laptop", token);
        assertError(405, machine);
        assertEquals("GET, PUT", machine.headers().firstValue("Allow").orElse(null));

        assertError(405, harness.put("/backups/laptop/" + BACKUP, token, new byte[10]));
        assertError(405, send("DELETE", "/backups/laptop/" + BACKUP + "/Data/hello.txt", token));
        assertError(405, harness.put("/backups/", token, new byte[10]));
    }

    // --- 400 ---

    @Test
    void invalidMachineNameGives400() throws Exception {
        String token = harness.createToken("read:*", "upload:*");
        for (String machine : new String[]{"%2e%2e", ".hidden", ".", "a%20b", "a%5Cb", "%00"}) {
            assertError(400, harness.get("/backups/" + machine + "/", token));
            assertError(400, harness.put("/backups/" + machine, token, new byte[10]));
        }
    }

    @Test
    void invalidBackupNameOrPathGives400() throws Exception {
        String token = harness.createToken("read:*");
        // "%2F" decodes to "/", so this tries to climb out of the machine folder.
        assertError(400, harness.get("/backups/laptop/%2e%2e%2Fdesktop/", token));
        assertError(400, harness.get("/backups/laptop/.hidden/", token));
        assertError(400, harness.get("/backups/laptop/" + BACKUP + "/Data//hello.txt", token));
        assertError(400, harness.get("/backups/laptop/" + BACKUP + "/Data/%2e%2e/Data/", token));
    }

    @Test
    void invalidContentIdGives400() throws Exception {
        String token = harness.createToken("globalread");
        assertError(400, harness.get("/content/xyz", token));
        assertError(400, harness.get("/content/" + MISSING_HASH.toUpperCase(), token));
        assertError(400, harness.get("/content/%2e%2e%2F%2e%2e%2Ftokens", token));
        assertError(400, harness.get("/content/", token));
    }

    @Test
    void malformedManifestGives400() throws Exception {
        String token = harness.createToken("upload:laptop");
        assertError(400, harness.put("/backups/laptop", token, "not a manifest".getBytes(StandardCharsets.UTF_8)));
        assertError(400, harness.put("/backups/laptop", token, new byte[0]));
    }

    @Test
    void badContentEncodingGives400() throws Exception {
        String token = harness.createToken("upload:laptop");
        assertError(400, harness.put("/backups/laptop", token, new byte[]{1, 2, 3}, "Content-Encoding", "br"));
        assertError(400, harness.put("/backups/laptop", token, "not gzip".getBytes(StandardCharsets.UTF_8),
                "Content-Encoding", "gzip"));
    }

    @Test
    void contentUploadWithoutMultipartGives400() throws Exception {
        String token = harness.createToken("upload:laptop");
        assertError(400, harness.post("/upload", token, new byte[]{1, 2, 3}));
        assertError(400, harness.post("/upload", token, new byte[]{1, 2, 3}, "Content-Type", "text/plain"));
    }

    // --- 500 and 503 ---

    @Test
    void unexpectedErrorGives500() throws Exception {
        Files.writeString(harness.dataDir().resolve("backups/laptop/2025-01-01-00-00-00"), "corrupt");
        HttpResponse<String> response = harness.get("/backups/laptop/2025-01-01-00-00-00/",
                harness.createToken("read:laptop"));
        assertError(500, response);
        // No stack traces or internals in the body; those go to the server log.
        assertEquals("Internal server error\n", response.body());
    }

    @Test
    void missingContentGives503() throws Exception {
        assertError(503, harness.get("/backups/laptop/" + BACKUP + "/Data/missing.bin",
                harness.createToken("read:laptop")));
    }
}
