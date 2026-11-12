package io.siggi.databackup.client.snapshot;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.MountedDisk;
import io.siggi.databackup.osutils.OS;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The facts about the running system that snapshot orchestration needs. {@link #system()} is the real one; tests
 * pass a fake so the source-to-{@link DiskUtil} mapping and the {@code snapshots.json} bookkeeping can run anywhere.
 */
public interface SnapshotPlatform {

    /**
     * The real system.
     */
    static SnapshotPlatform system() {
        return SystemSnapshotPlatform.INSTANCE;
    }

    OS os();

    /**
     * The mounted filesystems (macOS and Linux only).
     */
    List<MountedDisk> mounts() throws IOException;

    /**
     * Resolves symlinks in a path that must exist.
     *
     * @throws IOException if the path doesn't exist or can't be read
     */
    Path realPath(Path path) throws IOException;

    /**
     * Whether a directory exists at the path.
     */
    boolean isDirectory(Path path);

    /**
     * Checks that a path is the top of a btrfs subvolume ({@code btrfs subvolume show}, which needs root).
     *
     * @return null if it is one, otherwise the reason it isn't (such as the command's error output)
     */
    String checkBtrfsSubvolume(Path path) throws IOException;

    /**
     * The filesystem type of a Windows drive root, such as {@code NTFS}.
     */
    String windowsFileSystemType(Path driveRoot) throws IOException;

    /**
     * The {@link DiskUtil} for a filesystem type ({@code apfs}, {@code btrfs}, {@code ntfs}, matched ignoring case),
     * or null if DataBackup can't snapshot it.
     */
    DiskUtil diskUtil(String fileSystemType);
}
