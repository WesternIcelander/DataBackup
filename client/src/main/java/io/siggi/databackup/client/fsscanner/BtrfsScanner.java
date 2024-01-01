package io.siggi.databackup.client.fsscanner;

import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.scanner.FileMetadataScanner;
import java.io.File;
import java.util.function.Consumer;

public class BtrfsScanner extends FileSystemScanner {

    private File rootDirectory;
    private File snapshotTarget;
    private DirectoryEntryDirectory diffBase;

    @Override
    public void setRootDirectory(File rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    @Override
    public void setDiffBase(DirectoryEntryDirectory root) {
        this.diffBase = root;
    }

    @Override
    public FileMetadataScanner getFileMetadataScanner() {
        return null;
    }

    @Override
    public boolean requiresSnapshotTarget() {
        return true;
    }

    @Override
    public boolean isSnapshotAMountPoint() {
        return false;
    }

    @Override
    public void setSnapshotTarget(File directory) {
        this.snapshotTarget = directory;
    }

    @Override
    public DirectoryEntryDirectory scan() throws InterruptedException {
        return null;
    }
}
