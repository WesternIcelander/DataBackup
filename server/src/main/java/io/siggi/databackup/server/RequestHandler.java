package io.siggi.databackup.server;

import io.siggi.databackup.auth.Authorization;
import io.siggi.databackup.auth.Authorizer;
import io.siggi.databackup.auth.nullimpl.NullAuthorizer;
import io.siggi.databackup.compression.CompressionAlgorithm;
import io.siggi.databackup.compression.CompressionAlgorithms;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.server.repository.BackupItem;
import io.siggi.databackup.server.repository.BackupRepositories;
import io.siggi.databackup.server.repository.BackupRepository;
import io.siggi.databackup.util.TimeUtil;
import io.siggi.databackup.util.stream.IO;
import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPResponder;
import io.siggi.http.exception.EndResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

public class RequestHandler implements HTTPResponder {

    RequestHandler(DataBackupServer server, Authorizer authorizer) {
        this.server = server;
        setAuthorizer(authorizer);
    }

    private final DataBackupServer server;
    private Authorizer authorizer;

    public void setAuthorizer(Authorizer authorizer) {
        if (authorizer == null) authorizer = new NullAuthorizer();
        this.authorizer = authorizer;
    }

    /**
     * Names allowed for machines and backups: letters, digits, {@code .}, {@code _} and {@code -}, not starting with
     * {@code .}. This keeps every name a single, non-hidden path component inside the backups folder.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern CONTENT_ID_PATTERN = Pattern.compile("[0-9a-f]{64}");

    static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    @Override
    public void respond(HTTPRequest request) throws Exception {
        try {
            route(request);
        } catch (EndResponse e) {
            throw e;
        } catch (HttpError e) {
            if (request.response.alreadyWrote()) {
                // Too late to change the status. Throwing makes SiggiHTTP drop the connection, so the client sees
                // the response is incomplete.
                throw new IOException("Cannot send " + e.status + " (" + e.getMessage() + "), response already started");
            }
            sendError(request, e.status, e.getMessage(), e.headers);
        } catch (Exception e) {
            System.err.println("Error handling " + request.method + " " + request.url + ":");
            e.printStackTrace();
            if (request.response.alreadyWrote()) throw e;
            sendError(request, 500, "Internal server error", null);
        }
    }

    private void route(HTTPRequest request) throws Exception {
        String token = getAuthToken(request);
        if (token == null || token.isEmpty()) {
            throw new HttpError(401, "Missing token").header("WWW-Authenticate", "Bearer");
        }
        Authorization auth = authorizer.getAuthorization(token);
        if (auth == null) {
            throw new HttpError(401, "Invalid token").header("WWW-Authenticate", "Bearer");
        }
        String url = request.url;
        if (url.equals("/upload")) {
            requireMethod(request, "POST");
            if (!auth.checkAnyPermission("upload")) throw forbidden("upload:<machine>");
            String contentType = request.getHeader("Content-Type");
            if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                throw new HttpError(400, "Expected multipart/form-data");
            }
            server.contentReceiver.handle(request);
        } else if (url.startsWith("/content/")) {
            requireMethod(request, "GET");
            if (!auth.checkPermission("globalread")) throw forbidden("globalread");
            String contentId = url.substring(9);
            if (!CONTENT_ID_PATTERN.matcher(contentId).matches()) {
                throw new HttpError(400, "Invalid content ID");
            }
            StoredData storedData = server.getDataRepository().getStoredData(contentId);
            if (!storedData.dataExists()) throw new HttpError(404, "No such content");
            returnStoredData(request, storedData);
        } else if (url.equals("/backups") || url.equals("/backups/")) {
            requireMethod(request, "GET");
            listMachines(request, auth);
        } else if (url.startsWith("/backups/")) {
            handleBackups(request, auth, url.substring(9));
        } else {
            throw new HttpError(404, "Not found");
        }
    }

    private void handleBackups(HTTPRequest request, Authorization auth, String backupPath) throws Exception {
        String machine;
        int slashPos = backupPath.indexOf("/");
        if (slashPos != -1) {
            machine = backupPath.substring(0, slashPos);
            backupPath = backupPath.substring(slashPos + 1);
            if (backupPath.isEmpty()) backupPath = null;
        } else {
            machine = backupPath;
            backupPath = null;
        }
        if (!isValidName(machine)) throw new HttpError(400, "Invalid machine name");
        if (backupPath == null) {
            if (request.method.equals("PUT")) {
                if (!auth.checkPermission("upload:" + machine)) throw forbidden("upload:" + machine);
                uploadManifest(request, server.getBackupRepositories().get(machine));
            } else if (request.method.equals("GET")) {
                if (!auth.checkPermission("read:" + machine)) throw forbidden("read:" + machine);
                listBackups(request, machine);
            } else {
                throw methodNotAllowed("GET, PUT");
            }
        } else if (backupPath.equals(PENDING_DIR) || backupPath.startsWith(PENDING_DIR + "/")) {
            handlePending(request, auth, machine, backupPath.substring(PENDING_DIR.length()));
        } else {
            requireMethod(request, "GET");
            if (!auth.checkPermission("read:" + machine)) throw forbidden("read:" + machine);
            browse(request, machine, backupPath);
        }
    }

    private static final String PENDING_DIR = ".pending";
    private static final Pattern PENDING_ID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * Handles {@code /backups/:machine/.pending...}; {@code rest} is what follows {@code .pending}. The only route is
     * {@code POST /backups/:machine/.pending/:pendingId/commit}.
     */
    private void handlePending(HTTPRequest request, Authorization auth, String machine, String rest) throws Exception {
        String[] pieces = rest.split("/", -1);
        if (pieces.length != 3 || !pieces[0].isEmpty() || !pieces[2].equals("commit")) {
            throw new HttpError(404, "Not found");
        }
        requireMethod(request, "POST");
        if (!auth.checkPermission("upload:" + machine)) throw forbidden("upload:" + machine);
        String pendingId = pieces[1];
        if (!PENDING_ID_PATTERN.matcher(pendingId).matches()) throw new HttpError(400, "Invalid pending ID");
        commitManifest(request, server.getBackupRepositories().get(machine), pendingId);
    }

    private void listMachines(HTTPRequest request, Authorization auth) throws IOException {
        List<String> machines = new ArrayList<>();
        for (String name : server.getBackupRepositories().listNames()) {
            if (isValidName(name) && auth.checkPermission("read:" + name)) {
                machines.add(name);
            }
        }
        Collections.sort(machines);
        sendText(request, machines);
    }

    private void listBackups(HTTPRequest request, String machine) throws IOException, HttpError {
        BackupRepositories repositories = server.getBackupRepositories();
        if (!repositories.exists(machine)) throw new HttpError(404, "Unknown machine");
        List<String> backups = new ArrayList<>();
        for (BackupItem item : repositories.get(machine).getAllItems()) {
            backups.add(item.getName());
        }
        sendText(request, backups);
    }

    /**
     * {@code PUT /backups/:machine}: stores the manifest in canonical form as a pending backup and reports which
     * content the server still needs. The backup only becomes visible after
     * {@link #commitManifest(HTTPRequest, BackupRepository, String) commit}.
     */
    private void uploadManifest(HTTPRequest request, BackupRepository repository) throws Exception {
        String contentEncoding = request.getHeader("Content-Encoding");
        boolean gzip;
        if (contentEncoding == null || contentEncoding.isEmpty() || contentEncoding.equalsIgnoreCase("identity")) {
            gzip = false;
        } else if (contentEncoding.equalsIgnoreCase("gzip")) {
            gzip = true;
        } else {
            throw new HttpError(400, "Unsupported Content-Encoding: " + contentEncoding);
        }
        File temporaryFile = new File(server.tmpDir, UUID.randomUUID().toString());
        File temporaryCanonicalFile = new File(server.tmpDir, UUID.randomUUID().toString());
        try {
            try (FileOutputStream out = new FileOutputStream(temporaryFile)) {
                InputStream in = request.inStream;
                try {
                    if (gzip) in = new GZIPInputStream(in);
                    IO.copy(in, out, null);
                } catch (ZipException | EOFException e) {
                    throw new HttpError(400, "Malformed gzip data");
                }
            }
            UUID snapshotUuid;
            try (BackupFile file = BackupFile.open(temporaryFile)) {
                DirectoryEntryDirectory root = file.getRootDirectory();
                ExtraDataSnapshotId snapshotId = root.getExtra(ExtraDataSnapshotId.class);
                if (snapshotId == null) throw new HttpError(400, "Manifest has no snapshot ID");
                snapshotUuid = snapshotId.uuid;
                BackupFile.writeToFile(temporaryCanonicalFile, root);
            } catch (IOException | RuntimeException e) {
                throw new HttpError(400, "Malformed manifest");
            }
            temporaryFile.delete();
            if (repository.get(snapshotUuid) != null) {
                throw new HttpError(409, "This machine already has a backup with snapshot ID " + snapshotUuid);
            }
            String pendingId = UUID.randomUUID().toString();
            File pendingDirectory = repository.getPendingDirectory();
            pendingDirectory.mkdirs();
            File pendingFile = new File(pendingDirectory, pendingId);
            if (!temporaryCanonicalFile.renameTo(pendingFile)) {
                throw new IOException("Could not move manifest to " + pendingFile);
            }
            List<String> missing;
            try (BackupFile file = BackupFile.open(pendingFile)) {
                missing = findMissingContent(file.getRootDirectory(), null);
            }
            JsonObject response = new JsonObject();
            response.addProperty("pendingId", pendingId);
            response.add("missing", toJsonArray(missing));
            sendJson(request, response);
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete();
            if (temporaryCanonicalFile.exists()) temporaryCanonicalFile.delete();
        }
    }

    /**
     * {@code POST /backups/:machine/.pending/:pendingId/commit}: turns a pending manifest into a listed backup once
     * all of its content is on the server.
     */
    private void commitManifest(HTTPRequest request, BackupRepository repository, String pendingId) throws Exception {
        // One commit per machine at a time, so the duplicate and name checks below can't race.
        synchronized (repository) {
            File pendingFile = new File(repository.getPendingDirectory(), pendingId);
            if (!pendingFile.isFile()) throw new HttpError(404, "No such pending backup");
            List<StoredData> referenced = new ArrayList<>();
            List<String> missing;
            UUID snapshotUuid;
            try (BackupFile file = BackupFile.open(pendingFile)) {
                DirectoryEntryDirectory root = file.getRootDirectory();
                ExtraDataSnapshotId snapshotId = root.getExtra(ExtraDataSnapshotId.class);
                if (snapshotId == null) throw new IOException("Pending manifest " + pendingFile + " has no snapshot ID");
                snapshotUuid = snapshotId.uuid;
                missing = findMissingContent(root, referenced);
            }
            if (!missing.isEmpty()) {
                JsonObject response = new JsonObject();
                response.addProperty("error", "Content is still missing");
                response.add("missing", toJsonArray(missing));
                request.response.setHeader("409 " + reasonPhrase(409));
                sendJson(request, response);
                return;
            }
            if (repository.get(snapshotUuid) != null) {
                throw new HttpError(409, "This machine already has a backup with snapshot ID " + snapshotUuid);
            }
            // Protect the content from garbage collection before the backup that needs it becomes visible.
            for (StoredData storedData : referenced) storedData.touch();
            // Backup names have one-second resolution; move on to the next free second if this one is taken.
            long time = System.currentTimeMillis();
            while (repository.get(TimeUtil.toString(time)) != null
                    || new File(repository.getDirectory(), TimeUtil.toString(time)).exists()) {
                time += 1000L;
            }
            BackupItem item = repository.add(pendingFile, time);
            if (item == null) throw new IOException("Could not add " + pendingFile + " to " + repository.getName());
            JsonObject response = new JsonObject();
            response.addProperty("backup", item.getName());
            sendJson(request, response);
        }
    }

    private void browse(HTTPRequest request, String machine, String backupPath) throws Exception {
        boolean endsWithSlash = backupPath.endsWith("/");
        if (endsWithSlash) backupPath = backupPath.substring(0, backupPath.length() - 1);
        String[] pathPieces = backupPath.split("/", -1);
        if (!isValidName(pathPieces[0])) throw new HttpError(400, "Invalid backup name");
        for (int i = 1; i < pathPieces.length; i++) {
            String piece = pathPieces[i];
            if (piece.isEmpty() || piece.equals(".") || piece.equals("..")) {
                throw new HttpError(400, "Invalid path");
            }
        }
        BackupRepositories repositories = server.getBackupRepositories();
        if (!repositories.exists(machine)) throw new HttpError(404, "Unknown machine");
        BackupItem item = repositories.get(machine).get(pathPieces[0]);
        if (item == null) throw new HttpError(404, "Unknown backup");
        try (BackupFile backup = BackupFile.open(item.getFile())) {
            DirectoryEntry targetFile = backup.getRootDirectory();
            for (int i = 1; i < pathPieces.length; i++) {
                if (!targetFile.isDirectory()) throw new HttpError(404, "No such path");
                targetFile = targetFile.asDirectory().getEntry(pathPieces[i]);
                if (targetFile == null) throw new HttpError(404, "No such path");
            }
            if (targetFile.isDirectory()) {
                if (!endsWithSlash) {
                    request.response.completedRedirect(request.url + "/");
                    return;
                }
                List<String> lines = new ArrayList<>();
                for (DirectoryEntry entry : targetFile.asDirectory()) {
                    if (entry.isFile()) {
                        lines.add("F " + entry.getName());
                    } else if (entry.isDirectory()) {
                        lines.add("D " + entry.getName() + "/");
                    } else if (entry.isSymlink()) {
                        lines.add("L " + entry.getName() + " -> " + entry.asSymlink().getTarget());
                    }
                }
                sendText(request, lines);
            } else if (targetFile.isFile()) {
                if (endsWithSlash) throw new HttpError(404, "No such path");
                DirectoryEntryFile file = targetFile.asFile();
                List<FileContent> fileContents = file.getFileContents();
                if (fileContents.size() == 1) {
                    FileContent fileContent = fileContents.get(0);
                    StoredData storedData = fileContent.getStoredData(server.getDataRepository());
                    if (storedData != null && storedData.dataExists()) {
                        returnStoredData(request, storedData);
                        return;
                    }
                }
                boolean allDataAvailable = true;
                long totalSize = 0L;
                for (FileContent content : fileContents) {
                    totalSize += content.getLength();
                    if (!content.isDataAvailable(server.getDataRepository())) {
                        allDataAvailable = false;
                    }
                }
                if (!allDataAvailable) {
                    throw new HttpError(503, "Some of this file's content is missing on the server");
                }
                request.response.setContentType("application/x-octet-stream");
                request.response.contentLength(totalSize);
                for (FileContent content : fileContents) {
                    try (InputStream in = content.getInputStream(server.getDataRepository())) {
                        IO.copy(in, request.response, null);
                    }
                }
            } else {
                throw new HttpError(404, "No such path");
            }
        }
    }

    private static void requireMethod(HTTPRequest request, String method) throws HttpError {
        if (!request.method.equals(method)) throw methodNotAllowed(method);
    }

    private static HttpError methodNotAllowed(String allowed) {
        return new HttpError(405, "Method not allowed").header("Allow", allowed);
    }

    private static HttpError forbidden(String permission) {
        return new HttpError(403, "Missing permission: " + permission);
    }

    private static void sendText(HTTPRequest request, List<String> lines) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) sb.append(line).append("\n");
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        request.response.setContentType("text/plain; charset=utf-8");
        request.response.contentLength(bytes.length);
        request.response.write(bytes);
    }

    private static void sendJson(HTTPRequest request, JsonElement json) throws IOException {
        byte[] bytes = (GSON.toJson(json) + "\n").getBytes(StandardCharsets.UTF_8);
        request.response.setContentType("application/json; charset=utf-8");
        request.response.contentLength(bytes.length);
        request.response.write(bytes);
    }

    private static JsonArray toJsonArray(List<String> strings) {
        JsonArray array = new JsonArray();
        for (String string : strings) array.add(string);
        return array;
    }

    /**
     * Sends an error response with a {@code text/plain} body. This is the only place error statuses are written,
     * except for a commit's {@code 409} for missing content, which sends JSON so clients can read the missing list.
     */
    static void sendError(HTTPRequest request, int status, String message, Map<String, String> headers) throws IOException {
        request.response.setHeader(status + " " + reasonPhrase(status));
        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                request.response.setHeader(header.getKey(), header.getValue());
            }
        }
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        request.response.setContentType("text/plain; charset=utf-8");
        request.response.contentLength(body.length);
        request.response.write(body);
    }

    private static String reasonPhrase(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 409 -> "Conflict";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Error";
        };
    }

    /**
     * Thrown inside request handling to end the request with an error response.
     */
    static final class HttpError extends Exception {
        final int status;
        final Map<String, String> headers = new LinkedHashMap<>();

        HttpError(int status, String message) {
            super(message, null, false, false);
            this.status = status;
        }

        HttpError header(String key, String value) {
            headers.put(key, value);
            return this;
        }
    }

    private void returnStoredData(HTTPRequest request, StoredData storedData) throws IOException, HttpError {
        if (!storedData.dataExists()) throw new HttpError(404, "No such content");
        String knownName = storedData.getKnownFilenames().get(0);
        String knownMime = null;
        if (knownName != null) {
            int lastDot = knownName.lastIndexOf(".");
            if (lastDot != -1) {
                String extension = knownName.substring(lastDot + 1);
                knownMime = server.httpServer.getMimeType(extension);
                // never send text/html as it may result in unwanted clientside code execution
                if (knownMime.startsWith("text/html")) knownMime = null;
            }
        }
        String compression = storedData.getCompression();
        String acceptEncoding = request.getHeader("Accept-Encoding");
        if (acceptEncoding == null) acceptEncoding = "";
        String contentDispositionLine = knownName == null ? null : ("inline; name=\"" + io.siggi.http.util.Util.headerUrlEncode(knownName) + "\"");
        String contentType = knownMime == null ? "application/x-octet-stream" : knownMime;
        if (compression.equals("none")
                || (compression.equals("gz") && acceptEncoding.contains("gzip"))) {
            if (compression.equals("gz")) {
                request.response.setHeader("Content-Encoding", "gzip");
            }
            if (contentDispositionLine != null)
                request.response.setHeader("Content-Disposition", contentDispositionLine);
            request.response.returnFile(storedData.getFile(), knownMime);
            return;
        }
        CompressionAlgorithm algorithm = CompressionAlgorithms.get(compression);
        if (algorithm == null) {
            throw new IOException("Unknown compression " + compression + " for content " + storedData.getContentId());
        }
        try (InputStream in = algorithm.decompress(new FileInputStream(storedData.getFile()))) {
            if (contentDispositionLine != null)
                request.response.setHeader("Content-Disposition", contentDispositionLine);
            request.response.setContentType(contentType);
            IO.copy(in, request.response, null);
        }
    }

    /**
     * Returns the IDs of content the tree references but the data repository doesn't have, each once, in the order
     * they're first found.
     *
     * @param referenced if not null, receives every referenced {@link StoredData} that exists, each once
     */
    private List<String> findMissingContent(DirectoryEntryDirectory root, List<StoredData> referenced) {
        Set<String> seen = new HashSet<>();
        List<String> missing = new ArrayList<>();
        findMissingContent(root, server.getDataRepository(), seen, missing, referenced);
        return missing;
    }

    private static void findMissingContent(DirectoryEntryDirectory directory, DataRepository repository,
                                           Set<String> seen, List<String> missing, List<StoredData> referenced) {
        for (DirectoryEntry entry : directory) {
            if (entry.isDirectory()) {
                findMissingContent(entry.asDirectory(), repository, seen, missing, referenced);
            } else if (entry.isFile()) {
                for (FileContent content : entry.asFile()) {
                    StoredData storedData = content.getStoredData(repository);
                    if (storedData == null || !seen.add(storedData.getContentId())) continue;
                    if (!storedData.dataExists()) {
                        missing.add(storedData.getContentId());
                    } else if (referenced != null) {
                        referenced.add(storedData);
                    }
                }
            }
        }
    }

    private String getAuthToken(HTTPRequest request) {
        String authorizationHeader = request.getHeader("Authorization");
        if (authorizationHeader != null && authorizationHeader.toLowerCase().startsWith("bearer ")) {
            return authorizationHeader.substring(7);
        }
        String cookie = request.cookies.get("token");
        return cookie;
    }
}
