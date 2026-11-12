package io.siggi.databackup.client.snapshot;

import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.MountedDisk;
import io.siggi.databackup.osutils.OS;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Works out which filesystem each backup source is on and picks the {@link DiskUtil} that snapshots it. Every source
 * must be a whole snapshottable volume:
 * <ul>
 *     <li>macOS: the mount point of a writable APFS volume. {@code /} means the data volume,
 *     {@code /System/Volumes/Data}, since the system volume is a sealed, read-only snapshot.</li>
 *     <li>Linux: a btrfs subvolume, which needn't be a mount point. {@code btrfs subvolume show} confirms it (this
 *     needs root), and the mount table confirms the filesystem holding it is btrfs.</li>
 *     <li>Windows: an NTFS drive root such as {@code C:\}.</li>
 * </ul>
 */
public final class VolumeResolver {

    static final String MACOS_DATA_VOLUME = "/System/Volumes/Data";

    private final SnapshotPlatform platform;

    public VolumeResolver(SnapshotPlatform platform) {
        this.platform = platform;
    }

    /**
     * Resolves every source, collecting all problems before failing.
     *
     * @throws SnapshotSetupException listing every source that can't be snapshotted, and why
     */
    public List<SnapshotVolume> resolve(List<Source> sources) throws SnapshotSetupException {
        List<SnapshotVolume> result = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<MountedDisk> mounts = null;
        for (Source source : sources) {
            try {
                switch (platform.os()) {
                    case MACOS, LINUX -> {
                        if (mounts == null) mounts = platform.mounts();
                        result.add(platform.os() == OS.MACOS
                                ? resolveMac(source, mounts) : resolveLinux(source, mounts));
                    }
                    case WINDOWS -> result.add(resolveWindows(source));
                    default -> throw new SourceException("snapshots aren't supported on this operating system ("
                            + System.getProperty("os.name") + ")");
                }
            } catch (SourceException e) {
                problems.add("Source \"" + source.name() + "\" (" + source.path() + "): " + e.getMessage());
            } catch (IOException e) {
                problems.add("Source \"" + source.name() + "\" (" + source.path() + "): "
                        + IoMessages.describe(e));
            }
        }
        if (!problems.isEmpty()) throw new SnapshotSetupException(problems);
        return result;
    }

    private SnapshotVolume resolveMac(Source source, List<MountedDisk> mounts) throws SourceException {
        Path path = realPath(source.path());
        String pathString = path.toString();
        if (pathString.equals("/") && platform.isDirectory(Path.of(MACOS_DATA_VOLUME))) {
            pathString = MACOS_DATA_VOLUME;
        }
        MountedDisk disk = null;
        for (MountedDisk mount : mounts) {
            if (mount.path().equals(pathString)) disk = mount; // the last one mounted there wins
        }
        if (disk == null) {
            MountedDisk holder = containingMount(pathString, mounts);
            throw new SourceException("not the mount point of a volume. Back up the whole volume"
                    + (holder == null ? "" : " (" + holder.path() + ")")
                    + " and use excludes to leave out what you don't want.");
        }
        return forDisk(source, disk, pathString, "APFS");
    }

    private SnapshotVolume resolveLinux(Source source, List<MountedDisk> mounts) throws SourceException,
            IOException {
        Path path = realPath(source.path());
        String pathString = path.toString();
        MountedDisk holder = containingMount(pathString, mounts);
        if (holder == null) throw new SourceException("can't find the filesystem it's on in the mount table");
        SnapshotVolume volume = forDisk(source, holder, pathString, "btrfs");
        String problem = platform.checkBtrfsSubvolume(path);
        if (problem != null) {
            throw new SourceException("not a btrfs subvolume, or it can't be checked (" + problem
                    + "). Sources on Linux must be btrfs subvolumes (see btrfs subvolume create), and backups"
                    + " must run as root.");
        }
        return volume;
    }

    private SnapshotVolume forDisk(Source source, MountedDisk disk, String volume, String expected)
            throws SourceException {
        DiskUtil diskUtil = platform.diskUtil(disk.type());
        if (diskUtil == null || !diskUtil.supportsSnapshots()) {
            throw new SourceException("on a " + disk.type() + " filesystem (" + disk.path()
                    + "), which DataBackup can't snapshot. Sources on this OS must be on " + expected + ".");
        }
        if (disk.isReadOnly()) {
            throw new SourceException("on a read-only filesystem (" + disk.path() + "), which can't be snapshotted");
        }
        return new SnapshotVolume(source, diskUtil, volume);
    }

    private SnapshotVolume resolveWindows(Source source) throws SourceException, IOException {
        // Parsed as a string so the rules are the same (and testable) whichever OS runs the tests.
        Path path = source.path();
        String pathString = path.toString().replace('/', '\\');
        boolean onDrive = pathString.length() >= 2 && pathString.charAt(1) == ':'
                && isAsciiLetter(pathString.charAt(0));
        if (!onDrive) {
            throw new SourceException("not on a drive with a letter; only whole drives such as C:\\ can be"
                    + " snapshotted");
        }
        String drive = Character.toUpperCase(pathString.charAt(0)) + ":\\";
        String rest = pathString.substring(2);
        if (!rest.isEmpty() && !rest.equals("\\")) {
            throw new SourceException("not a whole drive. Back up " + drive
                    + " and use excludes to leave out what you don't want.");
        }
        if (!platform.isDirectory(path)) throw new SourceException("the drive doesn't exist");
        String type = platform.windowsFileSystemType(path);
        DiskUtil diskUtil = type == null ? null : platform.diskUtil(type);
        if (diskUtil == null || !diskUtil.supportsSnapshots()) {
            throw new SourceException("on a " + type + " filesystem, which DataBackup can't snapshot. Sources on"
                    + " Windows must be NTFS drives.");
        }
        return new SnapshotVolume(source, diskUtil, drive);
    }

    private static boolean isAsciiLetter(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }

    private Path realPath(Path path) throws SourceException {
        try {
            return platform.realPath(path);
        } catch (NoSuchFileException e) {
            throw new SourceException("doesn't exist");
        } catch (IOException e) {
            throw new SourceException("can't be read (" + IoMessages.describe(e) + ")");
        }
    }

    /**
     * The mount whose path is the longest prefix of {@code path}; the last mounted wins a tie.
     */
    static MountedDisk containingMount(String path, List<MountedDisk> mounts) {
        MountedDisk best = null;
        for (MountedDisk mount : mounts) {
            String mountPath = mount.path();
            boolean contains = path.equals(mountPath) || mountPath.equals("/")
                    || path.startsWith(mountPath.endsWith("/") ? mountPath : mountPath + "/");
            if (!contains) continue;
            if (best == null || mountPath.length() >= best.path().length()) best = mount;
        }
        return best;
    }

    private static final class SourceException extends Exception {
        SourceException(String message) {
            super(message);
        }
    }
}
