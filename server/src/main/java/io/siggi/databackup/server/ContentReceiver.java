package io.siggi.databackup.server;

import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.util.Util;
import io.siggi.databackup.util.stream.IO;
import io.siggi.databackup.util.stream.LimitedInputStream;
import io.siggi.databackup.util.stream.MessageDigestOutputStream;
import io.siggi.databackup.util.stream.TeeOutputStream;
import io.siggi.http.HTTPRequest;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

public class ContentReceiver {
    private final DataRepository repository;
    private final File tmpDir;

    public ContentReceiver(DataRepository repository, File tmpDir) {
        this.repository = repository;
        this.tmpDir = tmpDir;
    }

    public void handle(HTTPRequest request) throws IOException, InterruptedException {
        List<String> allItems = new LinkedList<>();
        InputStream in = request.inStream;
        while (true) {
            File tmpFile = null;
            try {
                long fileLength;
                try {
                    fileLength = IO.readLong(in);
                } catch (EOFException e) {
                    break;
                }
                if (fileLength == 0L) break;
                tmpFile = new File(tmpDir, UUID.randomUUID().toString());
                InputStream fileIn = new LimitedInputStream(in, fileLength, false);
                MessageDigest sha256 = Util.sha256();
                MessageDigestOutputStream digestOut = new MessageDigestOutputStream(sha256);
                try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                    TeeOutputStream teeOut = new TeeOutputStream(out, digestOut);
                    IO.copyInterruptible(fileIn, teeOut, null);
                }
                if (tmpFile.length() != fileLength) {
                    break;
                }
                byte[] digest = sha256.digest();
                String contentId = Util.bytesToHex(digest);
                if (repository.getStoredData(contentId).store(tmpFile, null)) {
                    allItems.add(contentId);
                }
            } finally {
                if (tmpFile != null && tmpFile.exists()) tmpFile.delete();
            }
        }
    }
}
