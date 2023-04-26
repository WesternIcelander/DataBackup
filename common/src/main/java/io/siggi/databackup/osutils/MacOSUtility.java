package io.siggi.databackup.osutils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MacOSUtility {
    private MacOSUtility() {
    }

    public static String localSnapshot() {
        String snapshotTimestamp = null;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/usr/bin/tmutil", "localsnapshot"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Created local snapshot with date")) {
                        snapshotTimestamp = line.substring(line.indexOf(": ") + 2);
                        break;
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        }
        return snapshotTimestamp;
    }

    public static List<ApfsSnapshot> listSnapshots(String device) {
        List<ApfsSnapshot> snapshots = new ArrayList<>();
        UUID uuid = null;
        String name = null;
        long xid = -1L;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/usr/sbin/diskutil", "ap", "listSnapshots", device});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("+-- ")) {
                        if (uuid != null && name != null && xid != -1L) {
                            snapshots.add(new ApfsSnapshot(device, uuid, name, xid));
                            xid = -1L;
                            name = null;
                        }
                        uuid = UUID.fromString(line.substring(4));
                        continue;
                    }
                    int colon = line.indexOf(":");
                    if (colon == -1) continue;
                    String key = line.substring(1, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (key.equals("Name")) {
                        name = value;
                    } else if (key.equals("XID")) {
                        xid = Long.parseLong(value);
                    }
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        }
        if (uuid != null && name != null && xid != -1L) {
            snapshots.add(new ApfsSnapshot(device, uuid, name, xid));
        }
        return snapshots;
    }

    public static boolean deleteSnapshot(ApfsSnapshot snapshot) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/usr/sbin/diskutil", "ap", "deleteSnapshot", snapshot.device(), "-xid", Long.toString(snapshot.xid())});
            return process.waitFor() == 0;
        } catch (Exception ignored) {
        }
        return false;
    }

    public static List<MountedDisk> getMountedDisks() {
        List<MountedDisk> mountedDisks = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/sbin/mount"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int endOfDisk = line.indexOf(" on ");
                    int endOfPath = line.lastIndexOf(" (");
                    if (endOfDisk == -1 || endOfPath == -1) continue;
                    int startOfPath = endOfDisk + 4;
                    int startOfOptions = endOfPath + 2;
                    int endOfOptions = line.indexOf(")", startOfOptions);
                    String disk = line.substring(0, endOfDisk);
                    String path = line.substring(startOfPath, endOfPath);
                    String options = line.substring(startOfOptions, endOfOptions).replace(", ", ",");
                    int firstComma = options.indexOf(",");
                    if (firstComma == -1) continue;
                    String type = options.substring(0, firstComma);
                    List<String> optionsArray = List.of(options.substring(firstComma + 1).split(","));
                    mountedDisks.add(new MountedDisk(
                        disk,
                        path,
                        type,
                        optionsArray
                    ));
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        }
        return mountedDisks;
    }

    public static MountedDisk getDataDisk(List<MountedDisk> disks) {
        for (MountedDisk disk : disks) {
            if (disk.path().equals("/System/Volumes/Data")) return disk;
        }
        for (MountedDisk disk : disks) {
            if (disk.path().equals("/")) return disk;
        }
        return null;
    }

    public static void deleteSnapshots(String date, List<MountedDisk> allDisks, MountedDisk exceptDisk) {
        for (MountedDisk disk : allDisks) {
            if (disk.equals(exceptDisk)) continue;
            List<ApfsSnapshot> apfsSnapshots = listSnapshots(disk.dev());
            apfsSnapshots.forEach(snapshot -> {
                if (!snapshot.name().contains(date)) return;
                deleteSnapshot(snapshot);
            });
        }
    }

    public static boolean mountSnapshot(ApfsSnapshot snapshot, File target) {
        try {
            return Runtime.getRuntime().exec(new String[]{
                "/sbin/mount_apfs",
                "-s",
                snapshot.name(),
                "-o",
                "ro",
                snapshot.device(),
                target.getAbsolutePath()
            }).waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean unmountVolume(File target) {
        try {
            return Runtime.getRuntime().exec(new String[]{
                "/sbin/umount",
                target.getAbsolutePath()
            }).waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

}
