package io.siggi.databackup.client.snapshot;

import io.siggi.databackup.osutils.OS;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.siggi.databackup.client.snapshot.FakeSnapshotPlatform.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeResolverTest {

    private final FakeSnapshotPlatform platform = new FakeSnapshotPlatform();

    private SnapshotVolume resolveOne(String name, String path) throws SnapshotSetupException {
        List<SnapshotVolume> volumes = new VolumeResolver(platform).resolve(List.of(source(name, path)));
        assertEquals(1, volumes.size());
        return volumes.get(0);
    }

    private String problem(String path) {
        SnapshotSetupException e = assertThrows(SnapshotSetupException.class,
                () -> new VolumeResolver(platform).resolve(List.of(source("S", path))));
        assertEquals(1, e.problems().size(), e.getMessage());
        String problem = e.problems().get(0);
        assertTrue(problem.startsWith("Source \"S\" ("), problem);
        return problem;
    }

    private void macMounts() {
        platform.os = OS.MACOS;
        platform.mount("/dev/disk3s1s1", "/", "apfs", "sealed", "local", "read-only", "journaled");
        platform.mount("/dev/disk3s5", "/System/Volumes/Data", "apfs", "local", "journaled", "nobrowse");
        platform.mount("/dev/disk5s1", "/Volumes/External", "apfs", "local", "nodev", "nosuid", "journaled");
        platform.mount("/dev/disk6s1", "/Volumes/Stick", "msdos", "local", "nodev", "nosuid");
        platform.mount("/dev/disk7s1", "/Volumes/Archive", "apfs", "local", "read-only");
        platform.directories.add("/Users/me");
    }

    @Test
    void macRootMapsToTheDataVolume() throws Exception {
        macMounts();
        SnapshotVolume volume = resolveOne("Data", "/");
        assertEquals("/System/Volumes/Data", volume.volume());
        assertSame(platform.fake("apfs"), volume.diskUtil());
        assertEquals("Data", volume.source().name());
        assertEquals("/System/Volumes/Data", resolveOne("Data", "/System/Volumes/Data").volume());
    }

    @Test
    void macOtherApfsVolume() throws Exception {
        macMounts();
        assertEquals("/Volumes/External", resolveOne("Ext", "/Volumes/External").volume());
    }

    @Test
    void macSymlinkedSourceIsResolved() throws Exception {
        macMounts();
        platform.symlinks.put("/ext", "/Volumes/External");
        assertEquals("/Volumes/External", resolveOne("Ext", "/ext").volume());
    }

    @Test
    void macRejectsFoldersFilesystemsAndReadOnlyVolumes() {
        macMounts();
        String folder = problem("/Users/me");
        assertTrue(folder.contains("not the mount point of a volume"), folder);
        assertTrue(folder.contains("(/System/Volumes/Data)") || folder.contains("(/)"), folder);
        String fat = problem("/Volumes/Stick");
        assertTrue(fat.contains("msdos filesystem"), fat);
        assertTrue(fat.contains("must be on APFS"), fat);
        String readOnly = problem("/Volumes/Archive");
        assertTrue(readOnly.contains("read-only"), readOnly);
        String missing = problem("/Volumes/Gone");
        assertTrue(missing.contains("doesn't exist"), missing);
    }

    private void linuxMounts() {
        platform.os = OS.LINUX;
        platform.mount("/dev/sda2", "/", "btrfs", "rw", "relatime", "subvolid=5", "subvol=/");
        platform.mount("/dev/sdb1", "/data", "ext4", "rw", "relatime");
        platform.mount("/dev/sdc1", "/backup", "btrfs", "ro");
        platform.directories.add("/home/claude/subvolume");
        platform.directories.add("/home/claude/plain");
        platform.directories.add("/data/sub");
        platform.directories.add("/backup/sub");
        platform.btrfsSubvolumes.add("/home/claude/subvolume");
        platform.btrfsSubvolumes.add("/");
        platform.btrfsSubvolumes.add("/backup/sub");
    }

    @Test
    void linuxSubvolumeThatIsNotAMountPoint() throws Exception {
        linuxMounts();
        SnapshotVolume volume = resolveOne("Sub", "/home/claude/subvolume");
        assertEquals("/home/claude/subvolume", volume.volume());
        assertSame(platform.fake("btrfs"), volume.diskUtil());
        assertEquals("/", resolveOne("Root", "/").volume());
    }

    @Test
    void linuxRejectsPlainFoldersOtherFilesystemsAndReadOnlyMounts() {
        linuxMounts();
        String plain = problem("/home/claude/plain");
        assertTrue(plain.contains("not a btrfs subvolume"), plain);
        assertTrue(plain.contains("ERROR: Not a Btrfs subvolume"), plain);
        String ext4 = problem("/data/sub");
        assertTrue(ext4.contains("ext4 filesystem (/data)"), ext4);
        assertTrue(ext4.contains("must be on btrfs"), ext4);
        String readOnly = problem("/backup/sub");
        assertTrue(readOnly.contains("read-only"), readOnly);
        String missing = problem("/nope");
        assertTrue(missing.contains("doesn't exist"), missing);
    }

    @Test
    void unsupportedFilesystemTypeFromDiskUtil() {
        linuxMounts();
        platform.fake("btrfs").supportsSnapshots = false;
        String problem = problem("/home/claude/subvolume");
        assertTrue(problem.contains("can't snapshot"), problem);
    }

    @Test
    void mountTableFailureIsReported() {
        linuxMounts();
        platform.mountsFail = true;
        String problem = problem("/home/claude/subvolume");
        assertTrue(problem.contains("mount failed"), problem);
    }

    @Test
    void containingMountPicksTheLongestPrefix() {
        linuxMounts();
        assertEquals("/data", VolumeResolver.containingMount("/data/sub", platform.mounts).path());
        assertEquals("/", VolumeResolver.containingMount("/database", platform.mounts).path());
        assertEquals("/", VolumeResolver.containingMount("/", platform.mounts).path());
    }

    private void windowsDrives() {
        platform.os = OS.WINDOWS;
        platform.windowsDrives.put("C", "NTFS");
        platform.windowsDrives.put("E", "FAT32");
    }

    @Test
    void windowsDriveRoots() throws Exception {
        windowsDrives();
        for (String path : List.of("C:\\", "c:\\", "C:", "C:/")) {
            SnapshotVolume volume = resolveOne("C", path);
            assertEquals("C:\\", volume.volume(), path);
            assertSame(platform.fake("ntfs"), volume.diskUtil());
        }
    }

    @Test
    void windowsRejectsFoldersOtherFilesystemsAndMissingDrives() {
        windowsDrives();
        String folder = problem("C:\\Users");
        assertTrue(folder.contains("not a whole drive. Back up C:\\"), folder);
        String fat = problem("E:\\");
        assertTrue(fat.contains("FAT32 filesystem"), fat);
        String missing = problem("Z:\\");
        assertTrue(missing.contains("doesn't exist"), missing);
        String unc = problem("\\\\server\\share\\");
        assertTrue(unc.contains("drive with a letter"), unc);
    }

    @Test
    void otherOperatingSystemsAreRejected() {
        platform.os = OS.OTHER;
        String problem = problem("/x");
        assertTrue(problem.contains("aren't supported on this operating system"), problem);
    }

    @Test
    void everyProblemIsReported() {
        linuxMounts();
        SnapshotSetupException e = assertThrows(SnapshotSetupException.class,
                () -> new VolumeResolver(platform).resolve(List.of(
                        source("A", "/home/claude/plain"),
                        source("B", "/home/claude/subvolume"),
                        source("C", "/data/sub"))));
        assertEquals(2, e.problems().size(), e.getMessage());
        assertTrue(e.problems().get(0).startsWith("Source \"A\""));
        assertTrue(e.problems().get(1).startsWith("Source \"C\""));
    }
}
