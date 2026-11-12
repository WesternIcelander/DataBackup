package io.siggi.databackup.server;

import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.util.Util;
import io.siggi.databackup.util.stream.IO;
import io.siggi.databackup.util.stream.MessageDigestOutputStream;
import io.siggi.databackup.util.stream.TeeOutputStream;
import io.siggi.http.HTTPRequest;
import io.siggi.http.io.MultipartFormDataParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Receives content for {@code POST /upload}: a {@code multipart/form-data} body whose file parts are stored in the
 * {@link DataRepository} under the SHA-256 of their bytes. Content is global, so it isn't tied to a machine.
 */
public class ContentReceiver {
    private final DataRepository repository;
    private final File tmpDir;

    public ContentReceiver(DataRepository repository, File tmpDir) {
        this.repository = repository;
        this.tmpDir = tmpDir;
    }

    /**
     * What an upload did with each file part, by content ID, in the order received. Content already on the server
     * (including a part repeated within the same upload) is listed in {@link #alreadyPresent}.
     */
    public record Result(List<String> stored, List<String> alreadyPresent) {
    }

    public Result handle(HTTPRequest request) throws IOException, InterruptedException, RequestHandler.HttpError {
        List<String> stored = new ArrayList<>();
        List<String> alreadyPresent = new ArrayList<>();
        MultipartFormDataParser parser;
        try {
            parser = new MultipartFormDataParser(request.inStream, request.getHeader("Content-Type"), 1024);
        } catch (IOException | RuntimeException e) {
            throw new RequestHandler.HttpError(400, "Malformed multipart body");
        }
        while (true) {
            MultipartFormDataParser.Part part;
            try {
                part = parser.nextPart();
            } catch (IOException | RuntimeException e) {
                throw new RequestHandler.HttpError(400, "Malformed multipart body");
            }
            if (part == null) break;
            if (!part.isFile()) continue;
            File tmpFile = new File(tmpDir, UUID.randomUUID().toString());
            try {
                InputStream fileIn = part.getInputStream();
                MessageDigest sha256 = Util.sha256();
                MessageDigestOutputStream digestOut = new MessageDigestOutputStream(sha256);
                try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                    TeeOutputStream teeOut = new TeeOutputStream(out, digestOut);
                    IO.copyInterruptible(fileIn, teeOut, null);
                }
                String contentId = Util.bytesToHex(sha256.digest());
                StoredData storedData = repository.getStoredData(contentId);
                // One content ID at a time, so two uploads of the same content can't interleave their metadata
                // updates.
                synchronized (lockFor(contentId)) {
                    String filename = baseName(part.getFilename());
                    if (storedData.dataExists()) {
                        alreadyPresent.add(contentId);
                    } else if (storedData.storeCompressing(tmpFile, filename)) {
                        // Compressed with gzip when that saves enough; see the repository's CompressionPolicy.
                        stored.add(contentId);
                    } else {
                        throw new IOException("Could not store content " + contentId);
                    }
                    // Starts (or restarts) the garbage-collection grace period for this content.
                    storedData.touch();
                    if (filename != null) storedData.addKnownFilename(filename);
                }
            } finally {
                if (tmpFile.exists()) tmpFile.delete();
            }
        }
        return new Result(stored, alreadyPresent);
    }

    private static final Object[] LOCKS = new Object[64];

    static {
        for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object();
    }

    private static Object lockFor(String contentId) {
        return LOCKS[Math.floorMod(contentId.hashCode(), LOCKS.length)];
    }

    /**
     * Returns the last path component of a multipart filename (some clients send a full path), or null when there's
     * nothing usable to record.
     */
    static String baseName(String filename) {
        if (filename == null) return null;
        int cut = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = filename.substring(cut + 1).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
        if (name.contains("\n") || name.contains("\r")) return null;
        return name;
    }
}
