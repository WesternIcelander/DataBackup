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
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.util.TimeUtil;
import io.siggi.databackup.util.stream.IO;
import io.siggi.http.HTTPRequest;
import io.siggi.http.HTTPResponder;
import io.siggi.http.exception.EndResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
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
     * {@code .}. This keeps every name a single, non-hidden path component inside {@code backupsDir}.
     */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}");
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
        File machineDirectory = new File(server.backupsDir, machine);
        if (backupPath == null) {
            if (request.method.equals("PUT")) {
                if (!auth.checkPermission("upload:" + machine)) throw forbidden("upload:" + machine);
                uploadManifest(request, machineDirectory);
            } else if (request.method.equals("GET")) {
                if (!auth.checkPermission("read:" + machine)) throw forbidden("read:" + machine);
                listBackups(request, machineDirectory);
            } else {
                throw methodNotAllowed("GET, PUT");
            }
        } else {
            requireMethod(request, "GET");
            if (!auth.checkPermission("read:" + machine)) throw forbidden("read:" + machine);
            browse(request, machineDirectory, backupPath);
        }
    }

    private void listMachines(HTTPRequest request, Authorization auth) throws IOException {
        List<String> machines = new ArrayList<>();
        File[] files = server.backupsDir.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isDirectory() && isValidName(name) && auth.checkPermission("read:" + name)) {
                    machines.add(name);
                }
            }
        }
        Collections.sort(machines);
        sendText(request, machines);
    }

    private void listBackups(HTTPRequest request, File machineDirectory) throws IOException, HttpError {
        if (!machineDirectory.isDirectory()) throw new HttpError(404, "Unknown machine");
        List<String> backups = new ArrayList<>();
        File[] files = machineDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && isValidName(name)) backups.add(name);
            }
        }
        Collections.sort(backups);
        sendText(request, backups);
    }

    private void uploadManifest(HTTPRequest request, File machineDirectory) throws Exception {
        String contentEncoding = request.getHeader("Content-Encoding");
        boolean gzip;
        if (contentEncoding == null || contentEncoding.isEmpty() || contentEncoding.equalsIgnoreCase("identity")) {
            gzip = false;
        } else if (contentEncoding.equalsIgnoreCase("gzip")) {
            gzip = true;
        } else {
            throw new HttpError(400, "Unsupported Content-Encoding: " + contentEncoding);
        }
        long time = System.currentTimeMillis();
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
            try (BackupFile file = BackupFile.open(temporaryFile)) {
                BackupFile.writeToFile(temporaryCanonicalFile, file.getRootDirectory());
            } catch (IOException | RuntimeException e) {
                throw new HttpError(400, "Malformed manifest");
            }
            temporaryFile.delete();
            if (!machineDirectory.exists()) {
                machineDirectory.mkdirs();
            }
            File targetFile = new File(server.backupsDir, TimeUtil.toString(time));
            temporaryCanonicalFile.renameTo(targetFile);
            Set<String> missingContent = new HashSet<>();
            List<String> lines = new ArrayList<>();
            BiConsumer<String, String> missingContentHandler = (contentId, path) -> {
                if (!missingContent.add(contentId)) return;
                lines.add(contentId + ":" + path);
            };
            try (BackupFile file = BackupFile.open(temporaryCanonicalFile)) {
                scanForMissingContent("", file.getRootDirectory(), server.getDataRepository(), missingContentHandler);
            }
            sendText(request, lines);
        } finally {
            if (temporaryFile.exists()) temporaryFile.delete();
            if (temporaryCanonicalFile.exists()) temporaryCanonicalFile.delete();
        }
    }

    private void browse(HTTPRequest request, File machineDirectory, String backupPath) throws Exception {
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
        File backupFile = new File(machineDirectory, pathPieces[0]);
        if (!backupFile.isFile()) throw new HttpError(404, "Unknown backup");
        try (BackupFile backup = BackupFile.open(backupFile)) {
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

    /**
     * Sends an error response with a {@code text/plain} body. This is the only place error statuses are written.
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

    private void scanForMissingContent(String path, DirectoryEntryDirectory rootDirectory, DataRepository repository, BiConsumer<String, String> missingContentHandler) {
        for (DirectoryEntry entry : rootDirectory) {
            if (entry.isDirectory()) {
                String subPath = path + entry.getName() + "/";
                scanForMissingContent(subPath, entry.asDirectory(), repository, missingContentHandler);
            } else if (entry.isFile()) {
                String filePath = path + entry.getName();
                DirectoryEntryFile file = entry.asFile();
                for (FileContent content : file) {
                    StoredData storedData = content.getStoredData(repository);
                    if (storedData != null) {
                        if (storedData.dataExists()) {
                            missingContentHandler.accept(storedData.getContentId(), filePath);
                        }
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
