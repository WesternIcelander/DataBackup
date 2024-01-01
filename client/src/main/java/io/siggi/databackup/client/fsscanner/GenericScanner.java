package io.siggi.databackup.client.fsscanner;

import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.scanner.FileMetadataScanner;
import java.io.File;
import java.util.function.Consumer;

public class GenericScanner extends FileSystemScanner {

    private File rootDirectory;
    private DirectoryEntryDirectory diffBase;
    private Consumer<FileMetadataScanner> preScanHandler;

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
    public DirectoryEntryDirectory scan() throws InterruptedException {
        return null;
    }
}
