package io.siggi.databackup.client.fsscanner;

import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.scanner.FileMetadataScanner;
import java.io.File;
import java.util.function.Consumer;

public abstract class FileSystemScanner {
    /**
     * Set the root directory to scan.
     *
     * @param rootDirectory the root directory to scan
     */
    public abstract void setRootDirectory(File rootDirectory);

    /**
     * Provide a previous scan result to produce a diff.
     *
     * @param root the previous scan result
     */
    public abstract void setDiffBase(DirectoryEntryDirectory root);

    /**
     * Get the {@link FileMetadataScanner}. May throw {@link IllegalStateException} if {@link FileMetadataScanner}
     * cannot be initialized due to missing parameters.
     *
     * @throws IllegalStateException if a {@link FileMetadataScanner} cannot be initialized due to missing parameters.
     */
    public abstract FileMetadataScanner getFileMetadataScanner();

    /**
     * Check if this scanner requires a snapshot target.
     *
     * @return if this scanner uses snapshots and requires a target directory for a snapshot.
     * @throws UnsupportedOperationException if this scanner does not use snapshots
     */
    public boolean requiresSnapshotTarget() {
        return false;
    }

    /**
     * Check if the directory set by {@link #setSnapshotTarget(File)} is a mount point. If it is a mount point, the
     * snapshot is mounted when access is required. If it is not a mount point, generally the snapshot stays at that
     * location on disk even through a reboot until it is deleted.
     *
     * @return whether a snapshot is a mount point or not
     * @throws UnsupportedOperationException if this scanner does not use snapshots
     */
    public boolean isSnapshotAMountPoint() {
        throw new UnsupportedOperationException();
    }

    /**
     * Set the directory to create or mount a snapshot at. See {@link #requiresSnapshotTarget()} and
     * {@link #isSnapshotAMountPoint()}.
     *
     * @param directory the directory to create or mount a snapshot at.
     * @throws UnsupportedOperationException if this scanner does not use snapshots
     */
    public void setSnapshotTarget(File directory) {
        throw new UnsupportedOperationException();
    }

    /**
     * Perform the scan. To stop a scan, just call {@link Thread#interrupt()} on the thread running the scan, and the
     * scan will stop as soon as possible. A scan is not guaranteed to stop immediately when interrupted.
     *
     * @return The results of the scan
     * @throws InterruptedException if the scan was interrupted
     */
    public abstract DirectoryEntryDirectory scan() throws InterruptedException;
}
