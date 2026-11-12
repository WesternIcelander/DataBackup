package io.siggi.databackup.client.snapshot;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.MountedDisk;
import io.siggi.databackup.diskutil.apfs.ApfsDiskUtil;
import io.siggi.databackup.diskutil.btrfs.BtrfsDiskUtil;
import io.siggi.databackup.diskutil.ntfs.NtfsDiskUtil;
import io.siggi.databackup.osutils.OS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class SystemSnapshotPlatform implements SnapshotPlatform {

    static final SystemSnapshotPlatform INSTANCE = new SystemSnapshotPlatform();

    private static final String[] BTRFS_PATHS = {"/usr/bin/btrfs", "/bin/btrfs", "/usr/sbin/btrfs", "/sbin/btrfs"};

    private SystemSnapshotPlatform() {
    }

    @Override
    public OS os() {
        return OS.get();
    }

    @Override
    public List<MountedDisk> mounts() throws IOException {
        try {
            return MountedDisk.getAll();
        } catch (UnsupportedOperationException e) {
            throw new IOException("Can't list mounted filesystems on this OS");
        }
    }

    @Override
    public Path realPath(Path path) throws IOException {
        return path.toRealPath();
    }

    @Override
    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public String checkBtrfsSubvolume(Path path) throws IOException {
        String btrfs = null;
        for (String candidate : BTRFS_PATHS) {
            if (Files.isExecutable(Path.of(candidate))) {
                btrfs = candidate;
                break;
            }
        }
        if (btrfs == null) return "the btrfs command isn't installed (install btrfs-progs)";
        Process process = new ProcessBuilder(btrfs, "subvolume", "show", path.toString())
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().close();
        String output = readAll(process.getInputStream());
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "btrfs subvolume show timed out";
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running btrfs subvolume show");
        }
        if (process.exitValue() == 0) return null;
        String message = output.trim();
        if (message.isEmpty()) message = "btrfs subvolume show exited with " + process.exitValue();
        return message;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Override
    public String windowsFileSystemType(Path driveRoot) throws IOException {
        return Files.getFileStore(driveRoot).type();
    }

    @Override
    public DiskUtil diskUtil(String fileSystemType) {
        return switch (fileSystemType.toLowerCase(Locale.ROOT)) {
            case "apfs" -> ApfsDiskUtil.get();
            case "btrfs" -> BtrfsDiskUtil.get();
            case "ntfs" -> NtfsDiskUtil.get();
            default -> null;
        };
    }
}
