package io.siggi.databackup.server;

import io.siggi.databackup.auth.Authorization;
import io.siggi.databackup.auth.Authorizer;
import io.siggi.databackup.auth.nullimpl.NullAuthorizer;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.server.repository.BackupItem;
import io.siggi.databackup.server.repository.BackupRepositories;
import io.siggi.databackup.server.repository.BackupRepository;
import io.siggi.databackup.util.TimeUtil;
import io.siggi.databackup.util.compression.CompressionAlgorithm;
import io.siggi.databackup.util.compression.DataCompression;
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
            ContentReceiver.Result result = server.contentReceiver.handle(request);
            JsonObject response = new JsonObject();
            response.add("stored", toJsonArray(result.stored()));
            response.add("alreadyPresent", toJsonArray(result.alreadyPresent()));
            sendJson(request, response);
        } else if (url.startsWith("/content/")) {
            requireMethod(request, "GET");
            if (!auth.checkPermission("globalread")) throw forbidden("globalread");
            String contentId = url.substring(9);
            if (!CONTENT_ID_PATTERN.matcher(contentId).matches()) {
                throw new HttpError(400, "Invalid content ID");
            }
            StoredData storedData = server.getDataRepository().getStoredData(contentId);
            if (!storedData.dataExists()) throw new HttpError(404, "No such content");
            returnStoredData(request, storedData, null, -1L);
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
        if (wantsJson(request)) {
            sendJson(request, toJsonArray(machines));
        } else {
            sendText(request, machines);
        }
    }

    /**
     * {@code GET /backups/:machine/}: the machine's backups, oldest first. As JSON, each backup is
     * {@code {"name", "time", "snapshotId"}} with {@code time} in milliseconds since the epoch.
     */
    private void listBackups(HTTPRequest request, String machine) throws IOException, HttpError {
        BackupRepositories repositories = server.getBackupRepositories();
        if (!repositories.exists(machine)) throw new HttpError(404, "Unknown machine");
        List<BackupItem> items = repositories.get(machine).getAllItems();
        if (wantsJson(request)) {
            JsonArray array = new JsonArray();
            for (BackupItem item : items) {
                JsonObject object = new JsonObject();
                object.addProperty("name", item.getName());
                object.addProperty("time", item.getTime());
                object.addProperty("snapshotId", item.getUuid().toString());
                array.add(object);
            }
            sendJson(request, array);
            return;
        }
        List<String> backups = new ArrayList<>();
        for (BackupItem item : items) {
            backups.add(item.getName());
        }
        sendText(request, backups);
    }

    /**
     * Whether the client asked for JSON, through {@code ?json} or an {@code Accept} header naming
     * {@code application/json}.
     */
    static boolean wantsJson(HTTPRequest request) {
        if (request.get.containsKey("json")) return true;
        List<String> acceptHeaders = request.getHeaders("Accept");
        if (acceptHeaders == null) return false;
        for (String accept : acceptHeaders) {
            for (String range : accept.split(",")) {
                int semicolon = range.indexOf(';');
                String type = (semicolon == -1 ? range : range.substring(0, semicolon)).trim();
                if (type.equalsIgnoreCase("application/json")) return true;
            }
        }
        return false;
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

    /**
     * {@code GET /backups/:machine/:backup/[path]}: lists a directory (as text, or as JSON when
     * {@link #wantsJson(HTTPRequest) asked for}) or downloads a file. {@code GET /backups/:machine/:backup?manifest}
     * returns the backup's raw manifest file.
     */
    private void browse(HTTPRequest request, String machine, String backupPath) throws Exception {
        boolean endsWithSlash = backupPath.endsWith("/");
        if (endsWithSlash) backupPath = backupPath.substring(0, backupPath.length() - 1);
        // request.url is already URL-decoded by SiggiHTTP, and an encoded "/" is rejected before it gets here, so
        // splitting on "/" gives the decoded path pieces.
        String[] pathPieces = backupPath.split("/", -1);
        if (!isValidName(pathPieces[0])) throw new HttpError(400, "Invalid backup name");
        for (int i = 1; i < pathPieces.length; i++) {
            String piece = pathPieces[i];
            if (piece.isEmpty() || piece.equals(".") || piece.equals("..")) {
                throw new HttpError(400, "Invalid path");
            }
        }
        boolean manifest = request.get.containsKey("manifest");
        if (manifest && pathPieces.length != 1) {
            throw new HttpError(400, "?manifest only applies to a backup, not a path inside it");
        }
        BackupRepositories repositories = server.getBackupRepositories();
        if (!repositories.exists(machine)) throw new HttpError(404, "Unknown machine");
        BackupItem item = repositories.get(machine).get(pathPieces[0]);
        if (item == null) throw new HttpError(404, "Unknown backup");
        if (manifest) {
            sendManifest(request, machine, item);
            return;
        }
        try (BackupFile backup = BackupFile.open(item.getFile())) {
            DirectoryEntry target = backup.getRootDirectory();
            for (int i = 1; i < pathPieces.length; i++) {
                if (!target.isDirectory()) throw new HttpError(404, "No such path");
                target = target.asDirectory().getEntry(pathPieces[i]);
                if (target == null || target.isNull()) throw new HttpError(404, "No such path");
            }
            if (target.isDirectory()) {
                if (!endsWithSlash) {
                    request.response.completedRedirect(withTrailingSlash(request));
                    return;
                }
                listDirectory(request, target.asDirectory());
            } else if (target.isFile()) {
                if (endsWithSlash) throw new HttpError(404, "No such path");
                sendFile(request, target.asFile());
            } else {
                // Symlinks have no content to download; their target shows in the directory listing.
                throw new HttpError(404, "No such path");
            }
        }
    }

    /**
     * The request's own URL, still URL-encoded and with its query string, with a {@code /} added to the path.
     */
    private static String withTrailingSlash(HTTPRequest request) {
        String fullUrl = request.fullUrl;
        int question = fullUrl.indexOf('?');
        if (question == -1) return fullUrl + "/";
        return fullUrl.substring(0, question) + "/" + fullUrl.substring(question);
    }

    private void sendManifest(HTTPRequest request, String machine, BackupItem item) throws IOException, HttpError {
        File file = item.getFile();
        if (!file.isFile()) throw new HttpError(404, "Unknown backup");
        request.response.setHeader("Content-Disposition",
                contentDisposition("attachment", machine + "-" + item.getName() + ".manifest"));
        request.response.setHeader("X-Content-Type-Options", "nosniff");
        request.response.returnFile(file, OCTET_STREAM);
    }

    private static void listDirectory(HTTPRequest request, DirectoryEntryDirectory directory) throws IOException {
        if (wantsJson(request)) {
            JsonArray array = new JsonArray();
            for (DirectoryEntry entry : directory) {
                JsonObject json = entryToJson(entry);
                if (json != null) array.add(json);
            }
            sendJson(request, array);
            return;
        }
        List<String> lines = new ArrayList<>();
        for (DirectoryEntry entry : directory) {
            if (entry.isFile()) {
                lines.add("F " + entry.getName());
            } else if (entry.isDirectory()) {
                lines.add("D " + entry.getName() + "/");
            } else if (entry.isSymlink()) {
                lines.add("L " + entry.getName() + " -> " + entry.asSymlink().getTarget());
            }
        }
        sendText(request, lines);
    }

    /**
     * Describes one directory entry for the JSON directory listing: {@code name}, {@code type} ({@code file},
     * {@code directory} or {@code symlink}), {@code size} (files), {@code modified} in milliseconds since the epoch
     * and {@code modifiedNanos} (when known), {@code target} (symlinks) and {@code permissions}
     * ({@code {"mode", "string", "owner", "group"}}, when recorded). Returns null for entries that aren't any of
     * those types.
     */
    static JsonObject entryToJson(DirectoryEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("name", entry.getName());
        if (entry.isFile()) {
            json.addProperty("type", "file");
        } else if (entry.isDirectory()) {
            json.addProperty("type", "directory");
        } else if (entry.isSymlink()) {
            json.addProperty("type", "symlink");
        } else {
            return null;
        }
        if (entry.isFile()) {
            json.addProperty("size", entry.asFile().getSize());
        }
        ExtraDataNanosecondModifiedDate nanos = entry.getExtra(ExtraDataNanosecondModifiedDate.class);
        if (nanos != null) {
            json.addProperty("modified", nanos.seconds * 1000L + nanos.nanos / 1_000_000);
            json.addProperty("modifiedNanos", nanos.seconds * 1_000_000_000L + nanos.nanos);
        } else if (entry.isFile()) {
            json.addProperty("modified", entry.asFile().getLastModified());
        }
        if (entry.isSymlink()) {
            json.addProperty("target", entry.asSymlink().getTarget());
        }
        ExtraDataPosixPermissions permissions = entry.getExtra(ExtraDataPosixPermissions.class);
        if (permissions != null) {
            JsonObject perms = new JsonObject();
            perms.addProperty("mode", permissions.permissions);
            perms.addProperty("string", permissions.getPermissionsAsString());
            perms.addProperty("owner", permissions.owner);
            perms.addProperty("group", permissions.group);
            json.add("permissions", perms);
        }
        return json;
    }

    /**
     * Sends a file from a backup. A file that is exactly one piece of stored content is served straight from the
     * content store (so gzip-stored content can go out as-is to clients that accept it); anything else is assembled
     * from its contents in offset order, with gaps zero-filled up to the file's size.
     */
    private void sendFile(HTTPRequest request, DirectoryEntryFile file) throws IOException, HttpError {
        DataRepository repository = server.getDataRepository();
        long size = file.getSize();
        List<FileContent> contents = new ArrayList<>(file.getFileContents());
        contents.sort(Comparator.comparingLong(FileContent::getOffset));
        long end = 0L;
        for (FileContent content : contents) {
            if (content.getOffset() < end || content.getLength() < 0L
                    || content.getOffset() + content.getLength() > size) {
                throw new IOException("File " + file.getName() + " has overlapping or out-of-range contents");
            }
            end = content.getOffset() + content.getLength();
        }
        for (FileContent content : contents) {
            if (!content.isDataAvailable(repository)) {
                throw new HttpError(503, "Some of this file's content is missing on the server");
            }
        }
        if (contents.size() == 1 && contents.get(0).getOffset() == 0L && contents.get(0).getLength() == size) {
            StoredData storedData = contents.get(0).getStoredData(repository);
            if (storedData != null) {
                returnStoredData(request, storedData, file.getName(), size);
                return;
            }
        }
        request.response.setContentType(contentTypeFor(file.getName()));
        request.response.setHeader("Content-Disposition", contentDisposition("inline", file.getName()));
        request.response.setHeader("X-Content-Type-Options", "nosniff");
        request.response.contentLength(size);
        long position = 0L;
        for (FileContent content : contents) {
            if (content.getOffset() > position) {
                writeZeros(request.response, content.getOffset() - position);
            }
            try (InputStream in = content.getInputStream(repository)) {
                copyExactly(in, request.response, content.getLength());
            }
            position = content.getOffset() + content.getLength();
        }
        if (position < size) writeZeros(request.response, size - position);
        // An empty file writes no body; without this SiggiHTTP would treat the request as unanswered.
        request.response.sendHeaders();
    }

    private static void writeZeros(OutputStream out, long count) throws IOException {
        byte[] zeros = new byte[(int) Math.min(count, 16384L)];
        while (count > 0L) {
            int amount = (int) Math.min(count, zeros.length);
            out.write(zeros, 0, amount);
            count -= amount;
        }
    }

    private static void copyExactly(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[16384];
        while (count > 0L) {
            int read = in.read(buffer, 0, (int) Math.min(count, buffer.length));
            if (read == -1) throw new EOFException("Stored content ended " + count + " bytes early");
            out.write(buffer, 0, read);
            count -= read;
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

    private static final String OCTET_STREAM = "application/octet-stream";

    /**
     * Sends stored content.
     *
     * @param filename the name to offer the client and to pick the content type by, or null to use the first name
     *                 the content was uploaded under, if any
     * @param length   the content's uncompressed length if known, otherwise -1; used as {@code Content-Length} when
     *                 the content has to be decompressed on the way out
     */
    private void returnStoredData(HTTPRequest request, StoredData storedData, String filename, long length)
            throws IOException, HttpError {
        if (!storedData.dataExists()) throw new HttpError(404, "No such content");
        if (filename == null) {
            List<String> knownFilenames = storedData.getKnownFilenames();
            if (!knownFilenames.isEmpty()) filename = knownFilenames.get(0);
        }
        String contentType = contentTypeFor(filename);
        if (filename != null) {
            request.response.setHeader("Content-Disposition", contentDisposition("inline", filename));
        }
        request.response.setHeader("X-Content-Type-Options", "nosniff");
        String compression = storedData.getCompression();
        if (!compression.equals("none")) request.response.setHeader("Vary", "Accept-Encoding");
        // Compression identifiers are the content store's (DataCompression), where gzip is "gzip".
        if (compression.equals("none") || (compression.equals("gzip") && acceptsGzip(request))) {
            if (compression.equals("gzip")) {
                request.response.setHeader("Content-Encoding", "gzip");
            }
            request.response.returnFile(storedData.getFile(), contentType);
            return;
        }
        CompressionAlgorithm algorithm = DataCompression.getCompressionAlgorithm(compression);
        if (algorithm == null || !algorithm.supportsDecompression()) {
            throw new IOException("Unknown compression " + compression + " for content " + storedData.getContentId());
        }
        try (InputStream in = algorithm.decompress(new FileInputStream(storedData.getFile()))) {
            request.response.setContentType(contentType);
            if (length >= 0L) {
                request.response.contentLength(length);
                copyExactly(in, request.response, length);
            } else {
                IO.copy(in, request.response, null);
            }
            request.response.sendHeaders();
        }
    }

    /**
     * Picks a content type from a filename's extension. Types a browser could run scripts from (HTML, XHTML, XML,
     * SVG) are never sent for stored content, to keep backed-up files from running code in the server's origin;
     * those and unknown types become {@code application/octet-stream}.
     */
    private String contentTypeFor(String filename) {
        if (filename == null) return OCTET_STREAM;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) return OCTET_STREAM;
        String mime = server.httpServer.getMimeType(filename.substring(lastDot + 1));
        if (mime == null) return OCTET_STREAM;
        String lower = mime.toLowerCase(Locale.ROOT);
        if (lower.startsWith("text/html") || lower.contains("xml") || lower.startsWith("image/svg")
                || lower.startsWith("application/x-octet-stream")) {
            return OCTET_STREAM;
        }
        return mime;
    }

    private static boolean acceptsGzip(HTTPRequest request) {
        List<String> headers = request.getHeaders("Accept-Encoding");
        if (headers == null) return false;
        for (String header : headers) {
            for (String coding : header.split(",")) {
                String[] parts = coding.split(";");
                String name = parts[0].trim();
                if (!name.equalsIgnoreCase("gzip") && !name.equalsIgnoreCase("x-gzip")) continue;
                boolean accepted = true;
                for (int i = 1; i < parts.length; i++) {
                    String param = parts[i].trim();
                    if (param.startsWith("q=") || param.startsWith("Q=")) {
                        try {
                            accepted = Double.parseDouble(param.substring(2).trim()) > 0.0;
                        } catch (NumberFormatException e) {
                            accepted = false;
                        }
                    }
                }
                if (accepted) return true;
            }
        }
        return false;
    }

    /**
     * Builds a {@code Content-Disposition} value with an ASCII {@code filename} for old clients and an RFC 6266
     * {@code filename*} carrying the exact UTF-8 name.
     */
    static String contentDisposition(String disposition, String filename) {
        StringBuilder ascii = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < filename.length(); i++) {
            char c = filename.charAt(i);
            ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? c : '_');
        }
        for (byte b : filename.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) != -1) {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return disposition + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
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
