package io.siggi.databackup.client;

import io.siggi.databackup.client.fsscanner.ApfsScanner;
import io.siggi.databackup.client.fsscanner.FileSystemScanner;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.util.Serialization;
import java.io.File;
import java.io.RandomAccessFile;

public class DataBackupClient {
    public static void main(String[] args) throws Throwable {
        FileSystemScanner scanner = new ApfsScanner();
        scanner.setRootDirectory(new File("/"));
        File snapshotMountPoint = new File("/tmp/snapshot");
        scanner.setSnapshotTarget(snapshotMountPoint);
        DirectoryEntryDirectory scan = scanner.scan();
        File outputFile = new File("scan");
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            Serialization.serializeDirectory(raf, scan);
        }
    }
}
