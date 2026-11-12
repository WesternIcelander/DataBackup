package io.siggi.databackup.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Starts a real {@link DataBackupServer} on an ephemeral loopback port with a fresh temp data folder, and offers
 * helpers for writing tokens and making HTTP calls. Close it to stop the server and delete the data folder.
 *
 * <pre>
 * try (ServerTestHarness harness = ServerTestHarness.start()) {
 *     String token = harness.createToken("read:laptop");
 *     HttpResponse&lt;String&gt; response = harness.get("/backups/laptop/", token);
 * }
 * </pre>
 */
public final class ServerTestHarness implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Path dataDir;
    private final DataBackupServer server;
    private final HttpClient client;
    private final URI baseUri;

    private ServerTestHarness(Path dataDir, DataBackupServer server) {
        this.dataDir = dataDir;
        this.server = server;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT)
                .build();
        this.baseUri = URI.create("http://127.0.0.1:" + server.getPort());
    }

    /**
     * Creates a temp data folder and starts a server on it, listening on a free loopback port.
     */
    public static ServerTestHarness start() throws IOException {
        Path dataDir = Files.createTempDirectory("databackup-server-test");
        DataBackupServer server;
        try {
            server = new DataBackupServer(dataDir.toFile(),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        } catch (IOException | RuntimeException e) {
            deleteRecursively(dataDir);
            throw e;
        }
        server.start();
        return new ServerTestHarness(dataDir, server);
    }

    public DataBackupServer server() {
        return server;
    }

    public Path dataDir() {
        return dataDir;
    }

    public HttpClient client() {
        return client;
    }

    /**
     * Returns the absolute URI for a server path such as {@code /backups/laptop/}.
     */
    public URI uri(String path) {
        if (!path.startsWith("/")) path = "/" + path;
        return baseUri.resolve(path);
    }

    /**
     * Writes a token file with the given permissions and returns the new random token.
     */
    public String createToken(String... permissions) throws IOException {
        String token = UUID.randomUUID().toString();
        writeToken(token, "user-" + token.substring(0, 8), permissions);
        return token;
    }

    /**
     * Writes {@code tokens/<sha256(token)>.txt} in the data folder, the format {@code SimpleAuthorizer} reads.
     */
    public void writeToken(String token, String id, String... permissions) throws IOException {
        Path tokenDir = dataDir.resolve("tokens");
        Files.createDirectories(tokenDir);
        List<String> lines = new ArrayList<>();
        lines.add("id=" + id);
        lines.add("name=" + id);
        for (String permission : permissions) {
            lines.add("permission=" + permission);
        }
        Files.write(tokenDir.resolve(sha256Hex(token) + ".txt"), lines, StandardCharsets.UTF_8);
    }

    /**
     * Starts a request builder for a server path, with the bearer token set when {@code token} isn't null.
     */
    public HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).timeout(TIMEOUT);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        return client.send(request, bodyHandler);
    }

    public HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public HttpResponse<String> get(String path, String token) throws IOException, InterruptedException {
        return send(request(path, token).GET().build());
    }

    public HttpResponse<byte[]> getBytes(String path, String token) throws IOException, InterruptedException {
        return send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public HttpResponse<String> put(String path, String token, byte[] body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(path, token).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) builder.headers(headers);
        return send(builder.build());
    }

    public HttpResponse<String> post(String path, String token, byte[] body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(path, token).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) builder.headers(headers);
        return send(builder.build());
    }

    @Override
    public void close() throws Exception {
        try {
            server.shutdown();
        } finally {
            deleteRecursively(dataDir);
        }
    }

    static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
