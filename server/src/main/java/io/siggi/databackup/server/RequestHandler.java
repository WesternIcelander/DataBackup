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

import java.io.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;

public class RequestHandler implements HTTPResponder {

    RequestHandler(DataBackupServer server) {
        this.server = server;
    }

    private final DataBackupServer server;
    private Authorizer authorizer;

    public void setAuthorizer(Authorizer authorizer) {
        if (authorizer == null) authorizer = new NullAuthorizer();
        this.authorizer = authorizer;
    }

    @Override
    public void respond(HTTPRequest request) throws Exception {
        Authorization auth = authorizer.getAuthorization(getAuthToken(request));
        if (auth == null) {
            return;
        }
        if (request.url.equals("/upload")) {
            if (!request.method.equals("POST")) return;
            if (!auth.checkPermission("upload")) return;
            server.contentReceiver.handle(request);
        } else if (request.url.startsWith("/content/")) {
            if (!auth.checkPermission("globalread")) return;
            String contentId = request.url.substring(9);
            if (contentId.contains("/")) return;
            StoredData storedData = server.getDataRepository().getStoredData(contentId);
            returnStoredData(request, storedData);
            return;
        } else if (request.url.startsWith("/backups/")) {
            String backupPath = request.url.substring(9);
            String repository;
            int slashPos = backupPath.indexOf("/");
            if (slashPos != -1) {
                repository = backupPath.substring(0, slashPos);
                backupPath = backupPath.substring(slashPos + 1);
            } else {
                repository = backupPath;
                backupPath = null;
            }
            File machineDirectory = new File(server.backupsDir, repository);
            if (request.method.equals("PUT")) {
                if (!auth.checkPermission("upload")
                        || !auth.checkPermission("upload:" + repository)
                        || backupPath != null) return;
                long time = System.currentTimeMillis();
                File temporaryFile = new File(server.tmpDir, UUID.randomUUID().toString());
                File temporaryCanonicalFile = new File(server.tmpDir, UUID.randomUUID().toString());
                try {
                    try (FileOutputStream out = new FileOutputStream(temporaryFile)) {
                        InputStream in = request.inStream;
                        if (request.getHeader("Content-Encoding").equals("gzip")) {
                            in = new GZIPInputStream(in);
                        }
                        IO.copy(in, out, null);
                    }
                    try (BackupFile file = BackupFile.open(temporaryFile)) {
                        BackupFile.writeToFile(temporaryCanonicalFile, file.getRootDirectory());
                    }
                    temporaryFile.delete();
                    if (!machineDirectory.exists()) {
                        machineDirectory.mkdirs();
                    }
                    File targetFile = new File(server.backupsDir, TimeUtil.toString(time));
                    temporaryCanonicalFile.renameTo(targetFile);
                    Set<String> missingContent = new HashSet<>();
                    BiConsumer<String, String> missingContentHandler = (contentId, path) -> {
                        if (!missingContent.add(contentId)) return;
                        try {
                            request.response.write(contentId + ":" + path + "\n");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    };
                    try (BackupFile file = BackupFile.open(temporaryCanonicalFile)) {
                        scanForMissingContent("", file.getRootDirectory(), server.getDataRepository(), missingContentHandler);
                    }
                } finally {
                    if (temporaryFile.exists()) temporaryFile.delete();
                    if (temporaryCanonicalFile.exists()) temporaryCanonicalFile.delete();
                }
            } else {
                if (backupPath == null) {
                    String[] backupList = server.backupsDir.list();
                    if (backupList == null) {
                        return;
                    }
                    Arrays.sort(backupList, String::compareTo);

                    request.response.setContentType("text/plain");
                    for (String backup : backupList) {
                        request.response.write(backup + "\n");
                    }
                } else {
                    boolean endsWithSlash = backupPath.endsWith("/");
                    String[] pathPieces = backupPath.split("/");
                    File backupFile = new File(server.backupsDir, pathPieces[0]);
                    try (BackupFile backup = BackupFile.open(backupFile)) {
                        DirectoryEntry targetFile = backup.getRootDirectory();
                        for (int i = 1; i < pathPieces.length; i++) {
                            if (!targetFile.isDirectory()) {
                                return;
                            }
                            targetFile.asDirectory().getEntry(pathPieces[i]);
                        }
                        if (targetFile.isDirectory()) {
                            if (!endsWithSlash) {
                                request.response.completedRedirect(request.url + "/");
                                return;
                            }
                            request.response.setContentType("text/plain");
                            for (DirectoryEntry entry : targetFile.asDirectory()) {
                                if (entry.isFile()) {
                                    request.response.write("F " + entry.getName() + "\n");
                                } else if (entry.isDirectory()) {
                                    request.response.write("D " + entry.getName() + "/\n");
                                } else if (entry.isSymlink()) {
                                    request.response.write("L " + entry.getName() + " -> " + entry.asSymlink().getTarget() + "\n");
                                }
                            }
                        } else if (targetFile.isFile()) {
                            if (endsWithSlash) return;
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
                            if (allDataAvailable) {
                                request.response.setContentType("application/x-octet-stream");
                                request.response.contentLength(totalSize);
                                for (FileContent content : fileContents) {
                                    try (InputStream in = content.getInputStream(server.getDataRepository())) {
                                        IO.copy(in, request.response, null);
                                    }
                                }
                                return;
                            }
                            // TODO: output an error message
                        }
                    }
                }
            }
        }
    }

    private void returnStoredData(HTTPRequest request, StoredData storedData) throws IOException {
        if (!storedData.dataExists()) return;
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
        if (algorithm == null) return;
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
