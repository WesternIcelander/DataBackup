package io.siggi.databackup.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.server.ServerTestHarness.FilePart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /upload}: content upload as {@code multipart/form-data}.
 */
class ContentUploadTest {

    private ServerTestHarness harness;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        token = harness.createToken("upload:laptop");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static JsonObject json(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response::body);
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"), "Content-Type was " + contentType);
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static List<String> strings(JsonArray array) {
        List<String> list = new ArrayList<>();
        array.forEach(element -> list.add(element.getAsString()));
        return list;
    }

    private StoredData storedData(byte[] data) throws Exception {
        return harness.server().getDataRepository().getStoredData(sha256(data));
    }

    @Test
    void multiFileUploadStoresEachPartUnderItsHash() throws Exception {
        byte[] small = "small content".getBytes(StandardCharsets.UTF_8);
        byte[] empty = new byte[0];
        // Bigger than any buffer along the way, and containing CR/LF and dashes like a multipart boundary.
        byte[] big = new byte[3 * 1024 * 1024 + 17];
        new Random(42).nextBytes(big);
        System.arraycopy("\r\n--".getBytes(StandardCharsets.US_ASCII), 0, big, 1000, 4);

        long before = System.currentTimeMillis();
        JsonObject response = json(harness.uploadContent(token,
                new FilePart("small.txt", small),
                new FilePart("empty.bin", empty),
                new FilePart("big.bin", big)));

        assertEquals(List.of(sha256(small), sha256(empty), sha256(big)), strings(response.getAsJsonArray("stored")));
        assertEquals(List.of(), strings(response.getAsJsonArray("alreadyPresent")));
        for (byte[] data : new byte[][]{small, empty, big}) {
            StoredData storedData = storedData(data);
            assertTrue(storedData.dataExists());
            byte[] onDisk = Files.readAllBytes(storedData.getFile().toPath());
            assertArrayEquals(data, onDisk);
            // The stored content's hash matches its ID.
            assertEquals(storedData.getContentId(), sha256(onDisk));
            assertTrue(storedData.getLastTouch() >= before - 1000L, "new content was not touched");
        }
        assertEquals(List.of("small.txt"), storedData(small).getKnownFilenames());
        assertEquals(List.of("big.bin"), storedData(big).getKnownFilenames());
        // No temp files are left behind.
        try (var files = Files.list(harness.dataDir().resolve("tmp"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void reuploadingExistingContentTouchesItAndReportsAlreadyPresent() throws Exception {
        byte[] data = "already here".getBytes(StandardCharsets.UTF_8);
        byte[] other = "new one".getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of(sha256(data)),
                strings(json(harness.uploadContent(token, new FilePart("first.txt", data))).getAsJsonArray("stored")));

        // Age the metadata so the touch on re-upload is visible.
        StoredData storedData = storedData(data);
        assertTrue(storedData.getMetadataFile().setLastModified(System.currentTimeMillis() - 86_400_000L));
        long before = System.currentTimeMillis();

        JsonObject response = json(harness.uploadContent(token,
                new FilePart("second.txt", data),
                new FilePart("other.txt", other),
                new FilePart("third.txt", data)));
        assertEquals(List.of(sha256(other)), strings(response.getAsJsonArray("stored")));
        // A part repeated within the same upload counts as already present the second time.
        assertEquals(List.of(sha256(data), sha256(data)), strings(response.getAsJsonArray("alreadyPresent")));

        StoredData reread = storedData(data);
        assertTrue(reread.getLastTouch() >= before - 1000L, "existing content was not touched");
        assertArrayEquals(data, Files.readAllBytes(reread.getFile().toPath()));
        assertEquals(List.of("first.txt", "second.txt", "third.txt"), reread.getKnownFilenames());
    }

    @Test
    void filenamesAreRecordedOnlyWhenUsable() throws Exception {
        byte[] noName = "no name".getBytes(StandardCharsets.UTF_8);
        byte[] withPath = "with path".getBytes(StandardCharsets.UTF_8);
        byte[] windowsPath = "windows path".getBytes(StandardCharsets.UTF_8);
        json(harness.uploadContent(token,
                new FilePart(null, noName),
                new FilePart("some/dir/photo.jpg", withPath),
                new FilePart("C:\\Users\\me\\notes.txt", windowsPath)));
        assertEquals(List.of(), storedData(noName).getKnownFilenames());
        assertEquals(List.of("photo.jpg"), storedData(withPath).getKnownFilenames());
        assertEquals(List.of("notes.txt"), storedData(windowsPath).getKnownFilenames());
    }

    @Test
    void anyUploadPermissionIsEnough() throws Exception {
        byte[] a = "a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "b".getBytes(StandardCharsets.UTF_8);
        json(harness.uploadContent(harness.createToken("upload:desktop"), new FilePart("a", a)));
        json(harness.uploadContent(harness.createToken("upload:*"), new FilePart("b", b)));
        assertTrue(storedData(a).dataExists());
        assertTrue(storedData(b).dataExists());
        HttpResponse<String> denied = harness.uploadContent(harness.createToken("read:*", "globalread"),
                new FilePart("c", "c".getBytes(StandardCharsets.UTF_8)));
        assertEquals(403, denied.statusCode());
    }

    @Test
    void uploadWithNoFilePartsStoresNothing() throws Exception {
        JsonObject response = json(harness.uploadContent(token));
        assertEquals(0, response.getAsJsonArray("stored").size());
        assertEquals(0, response.getAsJsonArray("alreadyPresent").size());
    }
}
