package io.siggi.databackup.server;

import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.server.ServerTestHarness.FilePart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content uploaded through {@code POST /upload} is stored gzipped when that helps, and downloads through
 * {@code GET /content/:hash} work whether or not the client accepts gzip.
 */
class ContentCompressionTest {

    private static final byte[] TEXT = "a compressible line of text\n".repeat(1000).getBytes(StandardCharsets.UTF_8);

    private ServerTestHarness harness;
    private String uploader;
    private String reader;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        uploader = harness.createToken("upload:laptop");
        reader = harness.createToken("globalread");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private StoredData storedData(byte[] data) throws Exception {
        return harness.server().getDataRepository().getStoredData(sha256(data));
    }

    private HttpResponse<byte[]> download(byte[] data, String acceptEncoding) throws Exception {
        var builder = harness.request("/content/" + sha256(data), reader).GET();
        if (acceptEncoding != null) builder.header("Accept-Encoding", acceptEncoding);
        HttpResponse<byte[]> response = harness.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        return response;
    }

    @Test
    void compressibleUploadIsStoredGzippedAndDownloadsBothWays() throws Exception {
        assertEquals(200, harness.uploadContent(uploader, new FilePart("notes.txt", TEXT)).statusCode());
        StoredData storedData = storedData(TEXT);
        assertEquals("gzip", storedData.getCompression());
        assertTrue(storedData.getFile().length() < TEXT.length / 2);

        HttpResponse<byte[]> plain = download(TEXT, null);
        assertFalse(plain.headers().firstValue("Content-Encoding").isPresent());
        assertArrayEquals(TEXT, plain.body());

        HttpResponse<byte[]> gzipped = download(TEXT, "gzip");
        assertEquals("gzip", gzipped.headers().firstValue("Content-Encoding").orElse(null));
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped.body()))) {
            assertArrayEquals(TEXT, in.readAllBytes());
        }
        // No temp files are left behind.
        try (var files = Files.list(harness.dataDir().resolve("tmp"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void incompressibleAndAlreadyCompressedUploadsAreStoredPlain() throws Exception {
        byte[] random = new byte[200_000];
        new Random(1).nextBytes(random);
        byte[] jpegName = ("not really a jpeg\n".repeat(500)).getBytes(StandardCharsets.UTF_8);
        assertEquals(200, harness.uploadContent(uploader,
                new FilePart("random.bin", random), new FilePart("photo.jpg", jpegName)).statusCode());

        for (byte[] data : new byte[][]{random, jpegName}) {
            StoredData storedData = storedData(data);
            assertEquals("none", storedData.getCompression());
            assertTrue(storedData.isDoNotCompress());
            assertArrayEquals(data, Files.readAllBytes(storedData.getFile().toPath()));
            for (String accept : new String[]{null, "gzip"}) {
                HttpResponse<byte[]> response = download(data, accept);
                assertFalse(response.headers().firstValue("Content-Encoding").isPresent());
                assertArrayEquals(data, response.body());
            }
        }
    }
}
