package io.siggi.databackup.client.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.siggi.databackup.client.ClientConfig;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Client for the DataBackup server's HTTP API ({@code docs/server-api.md}), built on {@link HttpClient}.
 * <p>
 * Error statuses become a {@link ServerApiException} carrying the status and the server's message. Transient
 * failures (connection errors, timeouts, truncated responses and {@code 5xx} statuses) are retried according to the
 * {@link RetryPolicy}; other errors are thrown at once. Every request, including a retried one, is complete on its
 * own: uploads re-read their sources, and downloads restart into a fresh temp file.
 * <p>
 * Retrying a commit whose response was lost can answer {@code 404 No such pending backup} or
 * {@code 409 ... already has a backup with snapshot ID ...} although the first attempt worked; callers that see
 * either after a retry can check {@link #listBackups} for the manifest's snapshot ID.
 * <p>
 * Instances are thread-safe.
 */
public final class ServerApi {

    private static final String USER_AGENT = "DataBackup-client";
    private static final int MAX_ERROR_BODY = 16 * 1024;

    private final String baseUrl;
    private final String token;
    private final HttpClient client;
    private final RetryPolicy retryPolicy;
    private final Duration requestTimeout;
    private final int maxBatchCount;
    private final long maxBatchBytes;

    private ServerApi(Builder builder) {
        String url = builder.serverUrl;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.baseUrl = url;
        this.token = builder.token;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(builder.connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.retryPolicy = builder.retryPolicy;
        this.requestTimeout = builder.requestTimeout;
        this.maxBatchCount = builder.maxBatchCount;
        this.maxBatchBytes = builder.maxBatchBytes;
    }

    /**
     * Starts a builder for the server at {@code serverUrl} (for example {@code https://backup.example.com} or
     * {@code http://host:8080/databackup}), authenticating with {@code token}.
     */
    public static Builder builder(String serverUrl, String token) {
        return new Builder(serverUrl, token);
    }

    /**
     * A client for the config's server and token, with default settings.
     */
    public static ServerApi forConfig(ClientConfig config) {
        return builder(config.getServerUrl(), config.getToken()).build();
    }

    public static final class Builder {
        private final String serverUrl;
        private final String token;
        private RetryPolicy retryPolicy = RetryPolicy.defaults();
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration requestTimeout = null;
        private int maxBatchCount = 1000;
        private long maxBatchBytes = 64L * 1024 * 1024;

        private Builder(String serverUrl, String token) {
            Objects.requireNonNull(serverUrl, "serverUrl");
            URI uri = URI.create(serverUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("serverUrl must be an http or https URL: " + serverUrl);
            }
            this.serverUrl = serverUrl;
            this.token = Objects.requireNonNull(token, "token");
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout);
            return this;
        }

        /**
         * How long to wait for each response's headers, or null (the default) for no limit. Uploads only get their
         * response once the whole body is sent, so keep this generous.
         */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /**
         * The most pieces of content sent in one {@code POST /upload} request (default 1000).
         */
        public Builder maxBatchCount(int maxBatchCount) {
            if (maxBatchCount < 1) throw new IllegalArgumentException("maxBatchCount must be at least 1");
            this.maxBatchCount = maxBatchCount;
            return this;
        }

        /**
         * The most content bytes sent in one {@code POST /upload} request (default 64 MiB). A single piece larger
         * than this is sent in a request of its own.
         */
        public Builder maxBatchBytes(long maxBatchBytes) {
            if (maxBatchBytes < 1) throw new IllegalArgumentException("maxBatchBytes must be at least 1");
            this.maxBatchBytes = maxBatchBytes;
            return this;
        }

        public ServerApi build() {
            return new ServerApi(this);
        }
    }

    // --- upload ---

    /**
     * Uploads a manifest file ({@code PUT /backups/:machine}), gzip-compressed, as a pending backup.
     */
    public ManifestUploadResult uploadManifest(String machine, Path manifest) throws IOException, InterruptedException {
        String path = "/backups/" + segment(machine);
        Path gzipped = Files.createTempFile("databackup-manifest", ".gz");
        try {
            try (InputStream in = Files.newInputStream(manifest);
                 OutputStream out = new GZIPOutputStream(Files.newOutputStream(gzipped), 64 * 1024)) {
                in.transferTo(out);
            }
            JsonObject json = execute("PUT", path, () -> {
                try {
                    return request(path)
                            .header("Content-Type", "application/octet-stream")
                            .header("Content-Encoding", "gzip")
                            .PUT(HttpRequest.BodyPublishers.ofFile(gzipped))
                            .build();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, ServerApi::readJsonObject);
            return new ManifestUploadResult(requireString(json, "pendingId"), strings(json, "missing"));
        } finally {
            Files.deleteIfExists(gzipped);
        }
    }

    /**
     * Uploads content ({@code POST /upload}) as multipart requests, batched by {@code maxBatchCount} and
     * {@code maxBatchBytes}. The server computes each piece's ID itself; IDs it didn't report are returned in
     * {@link ContentUploadResult#notReceived()}.
     */
    public ContentUploadResult uploadContent(List<ContentSource> sources) throws IOException, InterruptedException {
        List<String> stored = new ArrayList<>();
        List<String> alreadyPresent = new ArrayList<>();
        List<String> notReceived = new ArrayList<>();
        for (List<ContentSource> batch : batches(sources)) {
            JsonObject json = uploadBatch(batch);
            List<String> batchStored = strings(json, "stored");
            List<String> batchPresent = strings(json, "alreadyPresent");
            stored.addAll(batchStored);
            alreadyPresent.addAll(batchPresent);
            Set<String> received = new HashSet<>(batchStored);
            received.addAll(batchPresent);
            for (ContentSource source : batch) {
                if (!received.contains(source.contentId())) notReceived.add(source.contentId());
            }
        }
        return new ContentUploadResult(stored, alreadyPresent, notReceived);
    }

    List<List<ContentSource>> batches(List<ContentSource> sources) {
        List<List<ContentSource>> batches = new ArrayList<>();
        List<ContentSource> current = new ArrayList<>();
        long currentBytes = 0;
        for (ContentSource source : sources) {
            if (!current.isEmpty()
                    && (current.size() >= maxBatchCount || currentBytes + source.size() > maxBatchBytes)) {
                batches.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(source);
            currentBytes += source.size();
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    private JsonObject uploadBatch(List<ContentSource> batch) throws IOException, InterruptedException {
        String boundary = "databackup-" + UUID.randomUUID();
        return execute("POST", "/upload", () -> {
            List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
            for (ContentSource source : batch) {
                String header = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + quotable(source.filename()) + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n";
                parts.add(HttpRequest.BodyPublishers.ofByteArray(header.getBytes(StandardCharsets.UTF_8)));
                parts.add(contentPublisher(source));
                parts.add(HttpRequest.BodyPublishers.ofByteArray("\r\n".getBytes(StandardCharsets.UTF_8)));
            }
            parts.add(HttpRequest.BodyPublishers.ofByteArray(
                    ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8)));
            return request("/upload")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0])))
                    .build();
        }, ServerApi::readJsonObject);
    }

    private static HttpRequest.BodyPublisher contentPublisher(ContentSource source) {
        if (source.path() != null) {
            try {
                return HttpRequest.BodyPublishers.ofFile(source.path());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // fromPublisher refuses a length of 0, so empty content is sent as an empty byte array.
        if (source.size() == 0) return HttpRequest.BodyPublishers.ofByteArray(new byte[0]);
        return HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return source.open();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }), source.size());
    }

    /**
     * A filename safe inside a quoted multipart header value. The server only keeps the last path component.
     */
    static String quotable(String filename) {
        if (filename == null) return "";
        StringBuilder sb = new StringBuilder(filename.length());
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            sb.append(c == '"' || c == '\\' || c < 0x20 || c == 0x7f ? '_' : c);
        }
        return sb.toString();
    }

    /**
     * Commits a pending backup and returns the backup's name.
     *
     * @throws MissingContentException when content is still missing (upload it, then commit again)
     */
    public String commit(String machine, String pendingId) throws IOException, InterruptedException {
        String path = "/backups/" + segment(machine) + "/.pending/" + segment(pendingId) + "/commit";
        JsonObject json = execute("POST", path,
                () -> request(path).POST(HttpRequest.BodyPublishers.noBody()).build(),
                ServerApi::readJsonObject);
        return requireString(json, "backup");
    }

    // --- read ---

    /**
     * The machines this token can read, sorted by name.
     */
    public List<String> listMachines() throws IOException, InterruptedException {
        JsonElement json = getJson("/backups/?json");
        List<String> machines = new ArrayList<>();
        for (JsonElement element : asArray(json)) machines.add(asString(element));
        return machines;
    }

    /**
     * A machine's committed backups, oldest first.
     */
    public List<BackupInfo> listBackups(String machine) throws IOException, InterruptedException {
        JsonElement json = getJson("/backups/" + segment(machine) + "/?json");
        List<BackupInfo> backups = new ArrayList<>();
        try {
            for (JsonElement element : asArray(json)) {
                JsonObject object = element.getAsJsonObject();
                backups.add(new BackupInfo(requireString(object, "name"), object.get("time").getAsLong(),
                        UUID.fromString(requireString(object, "snapshotId"))));
            }
        } catch (RuntimeException e) {
            throw malformed(e);
        }
        return backups;
    }

    /**
     * Lists a directory inside a backup. {@code path} is relative to the backup's root, with {@code /} between
     * names; empty or {@code "/"} is the root (whose entries are the sources).
     */
    public List<DirectoryListingEntry> listDirectory(String machine, String backup, String path)
            throws IOException, InterruptedException {
        StringBuilder url = new StringBuilder("/backups/").append(segment(machine)).append('/')
                .append(segment(backup)).append('/');
        for (String piece : pathPieces(path)) url.append(segment(piece)).append('/');
        url.append("?json");
        JsonElement json = getJson(url.toString());
        List<DirectoryListingEntry> entries = new ArrayList<>();
        try {
            for (JsonElement element : asArray(json)) entries.add(parseEntry(element.getAsJsonObject()));
        } catch (RuntimeException e) {
            throw malformed(e);
        }
        return entries;
    }

    private static DirectoryListingEntry parseEntry(JsonObject object) throws IOException {
        DirectoryListingEntry.Permissions permissions = null;
        if (object.has("permissions") && object.get("permissions").isJsonObject()) {
            JsonObject p = object.getAsJsonObject("permissions");
            permissions = new DirectoryListingEntry.Permissions(p.get("mode").getAsInt(),
                    optString(p, "string"), p.get("owner").getAsLong(), p.get("group").getAsLong());
        }
        return new DirectoryListingEntry(requireString(object, "name"),
                DirectoryListingEntry.Type.parse(optString(object, "type")),
                optLong(object, "size"), optLong(object, "modified"), optLong(object, "modifiedNanos"),
                optString(object, "target"), permissions);
    }

    /**
     * Downloads a backup's raw manifest to {@code destination}, replacing it. The file is written to a temp file
     * next to it first, so a failed download leaves no partial file.
     */
    public void downloadManifest(String machine, String backup, Path destination)
            throws IOException, InterruptedException {
        download("/backups/" + segment(machine) + "/" + segment(backup) + "?manifest", destination);
    }

    /**
     * Downloads a file from a backup to {@code destination}, replacing it. {@code path} is relative to the backup's
     * root, with {@code /} between names. The file is written to a temp file next to it first, so a failed
     * download leaves no partial file.
     */
    public void downloadFile(String machine, String backup, String path, Path destination)
            throws IOException, InterruptedException {
        List<String> pieces = pathPieces(path);
        if (pieces.isEmpty()) throw new IllegalArgumentException("path names no file");
        StringBuilder url = new StringBuilder("/backups/").append(segment(machine)).append('/')
                .append(segment(backup));
        for (String piece : pieces) url.append('/').append(segment(piece));
        download(url.toString(), destination);
    }

    private void download(String path, Path destination) throws IOException, InterruptedException {
        Path absolute = destination.toAbsolutePath();
        Path dir = absolute.getParent();
        Path temp = Files.createTempFile(dir, "." + absolute.getFileName() + ".", ".part");
        try {
            execute("GET", path, () -> request(path).header("Accept-Encoding", "gzip").GET().build(),
                    (headers, body) -> {
                        writeBody(headers, body, temp);
                        return null;
                    });
            Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void writeBody(HttpHeaders headers, InputStream body, Path temp) throws IOException {
        String encoding = headers.firstValue("Content-Encoding").orElse("identity").trim().toLowerCase(Locale.ROOT);
        boolean gzip = encoding.equals("gzip") || encoding.equals("x-gzip");
        if (!gzip && !encoding.equals("identity")) {
            throw new IOException("Unsupported Content-Encoding from server: " + encoding);
        }
        long expected = gzip ? -1 : headers.firstValueAsLong("Content-Length").orElse(-1);
        long written;
        try (InputStream in = gzip ? new GZIPInputStream(body, 64 * 1024) : body;
             OutputStream out = Files.newOutputStream(temp)) {
            written = in.transferTo(out);
        }
        if (expected >= 0 && written != expected) {
            throw new EOFException("Download truncated: got " + written + " of " + expected + " bytes");
        }
    }

    // --- plumbing ---

    @FunctionalInterface
    interface BodyReader<T> {
        T read(HttpHeaders headers, InputStream body) throws IOException;
    }

    @FunctionalInterface
    interface RequestFactory {
        HttpRequest create();
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("User-Agent", USER_AGENT);
        if (requestTimeout != null) builder.timeout(requestTimeout);
        return builder;
    }

    private JsonElement getJson(String path) throws IOException, InterruptedException {
        return execute("GET", path, () -> request(path).header("Accept", "application/json").GET().build(),
                (headers, body) -> parseJson(body));
    }

    /**
     * Sends a request, retrying transient failures, and reads a successful ({@code 2xx}) response's body with
     * {@code reader}. A failure while reading the body (such as a dropped connection) is retried too.
     */
    <T> T execute(String method, String path, RequestFactory factory, BodyReader<T> reader)
            throws IOException, InterruptedException {
        int attempt = 1;
        while (true) {
            try {
                HttpRequest request;
                try {
                    request = factory.create();
                } catch (UncheckedIOException e) {
                    throw new LocalIOException(e.getCause());
                }
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status >= 200 && status <= 299) {
                        return reader.read(response.headers(), body);
                    }
                    throw errorFor(status, response.headers(), body);
                }
            } catch (LocalIOException e) {
                throw e.getCause();
            } catch (ServerApiException e) {
                if (!e.isTransient() || attempt >= retryPolicy.maxAttempts()) throw e;
            } catch (IOException e) {
                if (attempt >= retryPolicy.maxAttempts()) throw e;
            }
            Duration delay = retryPolicy.delayBeforeRetry(attempt);
            if (!delay.isZero()) Thread.sleep(delay.toMillis());
            attempt++;
        }
    }

    /**
     * A local I/O failure (such as an unreadable manifest) that retrying the request can't fix.
     */
    private static final class LocalIOException extends IOException {
        LocalIOException(IOException cause) {
            super(cause);
        }

        @Override
        public synchronized IOException getCause() {
            return (IOException) super.getCause();
        }
    }

    private static ServerApiException errorFor(int status, HttpHeaders headers, InputStream body) throws IOException {
        String text = new String(body.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8).strip();
        String contentType = headers.firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.startsWith("application/json")) {
            try {
                JsonObject json = JsonParser.parseString(text).getAsJsonObject();
                String message = optString(json, "error");
                if (message == null) message = "HTTP " + status;
                if (status == 409 && json.has("missing")) {
                    return new MissingContentException(message, strings(json, "missing"));
                }
                return new ServerApiException(status, message);
            } catch (RuntimeException | IOException e) {
                // Not the JSON we expected: fall back to the text.
            }
        }
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline).strip();
        if (text.isEmpty()) text = "HTTP " + status;
        return new ServerApiException(status, text);
    }

    private static JsonElement parseJson(InputStream body) throws IOException {
        String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw malformed(e);
        }
    }

    private static JsonObject readJsonObject(HttpHeaders headers, InputStream body) throws IOException {
        JsonElement json = parseJson(body);
        if (!json.isJsonObject()) throw new IOException("Malformed response from server: expected a JSON object");
        return json.getAsJsonObject();
    }

    private static IOException malformed(Exception e) {
        return new IOException("Malformed response from server: " + e.getMessage(), e);
    }

    private static JsonArray asArray(JsonElement json) throws IOException {
        if (!json.isJsonArray()) throw new IOException("Malformed response from server: expected a JSON array");
        return json.getAsJsonArray();
    }

    private static String asString(JsonElement element) throws IOException {
        if (!element.isJsonPrimitive()) throw new IOException("Malformed response from server: expected a string");
        return element.getAsString();
    }

    private static String requireString(JsonObject json, String key) throws IOException {
        String value = optString(json, key);
        if (value == null) throw new IOException("Malformed response from server: missing \"" + key + "\"");
        return value;
    }

    private static String optString(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }

    private static Long optLong(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsLong();
    }

    private static List<String> strings(JsonObject json, String key) throws IOException {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonArray()) {
            throw new IOException("Malformed response from server: missing \"" + key + "\"");
        }
        List<String> list = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) list.add(asString(item));
        return list;
    }

    /**
     * Splits a backup path on {@code /}, skipping empty pieces.
     */
    static List<String> pathPieces(String path) {
        List<String> pieces = new ArrayList<>();
        if (path == null) return pieces;
        for (String piece : path.split("/")) {
            if (!piece.isEmpty()) pieces.add(piece);
        }
        return pieces;
    }

    /**
     * Percent-encodes one path segment (spaces as {@code %20}, never {@code +}).
     */
    static String segment(String value) {
        Objects.requireNonNull(value);
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
