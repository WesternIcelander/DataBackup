package io.siggi.databackup.client.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retry behavior, against a scripted JDK HTTP server that fails a set number of times.
 */
class ServerApiRetryTest {

    private static final RetryPolicy FAST = new RetryPolicy(4, Duration.ofMillis(5), Duration.ofMillis(20), 2.0);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> bodies = new ArrayList<>();

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    /**
     * Each request gets the next of {@code responses}; the last one repeats.
     */
    private interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    private ServerApi start(Responder... responses) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            int n = requests.getAndIncrement();
            byte[] body = exchange.getRequestBody().readAllBytes();
            synchronized (bodies) {
                bodies.add(new String(body, StandardCharsets.ISO_8859_1));
            }
            responses[Math.min(n, responses.length - 1)].respond(exchange);
            exchange.close();
        });
        server.start();
        return ServerApi.builder("http://127.0.0.1:" + server.getAddress().getPort(), "token")
                .retryPolicy(FAST).build();
    }

    private static Responder status(int status, String contentType, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
        };
    }

    private static Responder text(int status, String body) {
        return status(status, "text/plain; charset=utf-8", body);
    }

    private static Responder json(String body) {
        return status(200, "application/json; charset=utf-8", body);
    }

    /**
     * Promises {@code declared} bytes but sends fewer, then closes the connection.
     */
    private static Responder truncated(byte[] data, int declared) {
        return exchange -> {
            exchange.sendResponseHeaders(200, declared);
            OutputStream out = exchange.getResponseBody();
            out.write(data);
            out.flush();
            // Closing the exchange early makes the JDK server drop the connection.
        };
    }

    @Test
    void retriesServerErrorsThenSucceeds() throws Exception {
        ServerApi api = start(text(503, "busy"), text(500, "oops"), json("[\"laptop\"]"));
        assertEquals(List.of("laptop"), api.listMachines());
        assertEquals(3, requests.get());
    }

    @Test
    void givesUpAfterMaxAttempts() throws Exception {
        ServerApi api = start(text(502, "Bad gateway\nmore detail"));
        ServerApiException e = assertThrows(ServerApiException.class, api::listMachines);
        assertEquals(502, e.getStatus());
        assertEquals("Bad gateway", e.getServerMessage());
        assertTrue(e.isTransient());
        assertEquals(FAST.maxAttempts(), requests.get());
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        ServerApi api = start(text(403, "Missing permission: read:laptop"), json("[]"));
        ServerApiException e = assertThrows(ServerApiException.class, () -> api.listBackups("laptop"));
        assertEquals(403, e.getStatus());
        assertEquals(1, requests.get());
    }

    @Test
    void emptyErrorBodyGetsAMessage() throws Exception {
        ServerApi api = start(text(404, ""));
        ServerApiException e = assertThrows(ServerApiException.class, api::listMachines);
        assertEquals("HTTP 404", e.getServerMessage());
    }

    @Test
    void commitConflictWithMissingContent() throws Exception {
        ServerApi api = start(status(409, "application/json; charset=utf-8",
                "{\"error\": \"Content is still missing\", \"missing\": [\"aa\", \"bb\"]}"));
        MissingContentException e = assertThrows(MissingContentException.class,
                () -> api.commit("laptop", "8d2e6c1a-0000-4000-8000-000000000000"));
        assertEquals(List.of("aa", "bb"), e.getMissing());
        assertEquals(1, requests.get());
    }

    @Test
    void retriesUploadsWithTheWholeBody() throws Exception {
        ServerApi api = start(text(500, "oops"), json("{\"stored\": [\"id1\"], \"alreadyPresent\": [\"id2\"]}"));
        Path file = tempDir.resolve("one.txt");
        Files.writeString(file, "first file");
        ContentUploadResult result = api.uploadContent(List.of(
                ContentSource.ofFile(file, "id1"),
                ContentSource.ofBytes("id2", "two.txt", "second".getBytes(StandardCharsets.UTF_8))));
        assertEquals(List.of("id1"), result.stored());
        assertEquals(List.of("id2"), result.alreadyPresent());
        assertEquals(2, bodies.size());
        assertEquals(bodies.get(0).replaceAll("databackup-[0-9a-f-]+", "B"),
                bodies.get(1).replaceAll("databackup-[0-9a-f-]+", "B"));
        assertTrue(bodies.get(1).contains("filename=\"one.txt\"\r\n"));
        assertTrue(bodies.get(1).contains("\r\n\r\nfirst file\r\n"));
        assertTrue(bodies.get(1).contains("\r\n\r\nsecond\r\n"));
    }

    @Test
    void retriesTruncatedDownloads() throws Exception {
        byte[] data = "the whole file".getBytes(StandardCharsets.UTF_8);
        ServerApi api = start(truncated("the wh".getBytes(StandardCharsets.UTF_8), data.length),
                status(200, "text/plain", "the whole file"));
        Path out = tempDir.resolve("file.txt");
        api.downloadFile("laptop", "2026-01-01-00-00-00", "Data/file.txt", out);
        assertArrayEquals(data, Files.readAllBytes(out));
        assertEquals(2, requests.get());
    }

    @Test
    void retriesConnectionErrorsThenThrowsThem() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        ServerApi api = ServerApi.builder("http://127.0.0.1:" + port, "token").retryPolicy(FAST).build();
        long start = System.nanoTime();
        IOException e = assertThrows(IOException.class, api::listMachines);
        assertFalse(e instanceof ServerApiException);
        assertTrue(e instanceof ConnectException || e.getCause() instanceof ConnectException, e.toString());
        // Three waits of 5, 10 and 20 ms.
        assertTrue(System.nanoTime() - start >= 35_000_000L);
    }

    @Test
    void localFailuresAreNotRetried() throws Exception {
        ServerApi api = start(json("{}"));
        Path gone = tempDir.resolve("gone.txt");
        Files.writeString(gone, "here for now");
        ContentSource source = ContentSource.ofFile(gone, "id");
        Files.delete(gone);
        assertThrows(java.io.FileNotFoundException.class, () -> api.uploadContent(List.of(source)));
        assertThrows(java.nio.file.NoSuchFileException.class, () -> api.uploadManifest("laptop", gone));
        assertEquals(0, requests.get());
    }

    @Test
    void retryPolicyBackoff() {
        RetryPolicy policy = new RetryPolicy(10, Duration.ofSeconds(1), Duration.ofSeconds(5), 2.0);
        assertEquals(Duration.ofSeconds(1), policy.delayBeforeRetry(1));
        assertEquals(Duration.ofSeconds(2), policy.delayBeforeRetry(2));
        assertEquals(Duration.ofSeconds(4), policy.delayBeforeRetry(3));
        assertEquals(Duration.ofSeconds(5), policy.delayBeforeRetry(4));
        assertEquals(1, RetryPolicy.none().maxAttempts());
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, 1));
    }
}
