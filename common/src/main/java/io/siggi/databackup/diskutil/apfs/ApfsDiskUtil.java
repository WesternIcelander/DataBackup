package io.siggi.databackup.diskutil.apfs;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.diskutil.MountedDisk;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApfsDiskUtil implements DiskUtil<ApfsSnapshot> {

    private ApfsDiskUtil() {
    }

    private static final ApfsDiskUtil instance = new ApfsDiskUtil();

    public static ApfsDiskUtil get() {
        return instance;
    }

    @Override
    public String filesystem() {
        return "apfs";
    }

    @Override
    public boolean supportsSnapshots() {
        return true;
    }

    @Override
    public boolean snapshotsRequireDestination() {
        return false;
    }

    @Override
    public Map<String, ApfsSnapshot> createSnapshots(Collection<String> filesystems, Function<String, String> destination) throws SnapshotException {
        updateUuidMappings();
        String snapshotTime = localSnapshot();
        if (snapshotTime == null) {
            throw new SnapshotException("Unable to perform a local snapshot.");
        }
        Map<String, ApfsSnapshot> resultMap = new HashMap<>();
        List<MountedDisk> mountedDisks = MountedDisk.getAll();
        List<ApfsSnapshot> toDelete = new ArrayList<>();
        for (MountedDisk disk : mountedDisks) {
            if (disk.isReadOnly() || !disk.type().equals("apfs")) continue;
            List<ApfsSnapshot> apfsSnapshots = listSnapshots(uuidForDevice(disk.dev()), disk.dev());

            String matchedItem;
            if (filesystems.contains(disk.dev())) matchedItem = disk.dev();
            else if (filesystems.contains(disk.path())) matchedItem = disk.path();
            else matchedItem = null;

            for (ApfsSnapshot snapshot : apfsSnapshots) {
                if (snapshot.name().contains(snapshotTime)) {
                    if (matchedItem == null)
                        toDelete.add(snapshot);
                    else
                        resultMap.put(matchedItem, snapshot);
                }
            }
        }
        for (ApfsSnapshot snapshot : toDelete) {
            try {
                snapshot.delete();
            } catch (Exception ignored) {
            }
        }
        return resultMap;
    }

    private static final Map<UUID, String> uuidToDevice = new HashMap<>();
    private static final Map<String, UUID> deviceToUuid = new HashMap<>();

    public static UUID uuidForDevice(String device) {
        if (device.startsWith("/dev/")) device = device.substring(5);
        UUID result = deviceToUuid.get(device);
        if (result != null) return result;
        updateUuidMappings();
        return deviceToUuid.get(device);
    }

    public static String deviceForUuid(UUID uuid) {
        String result = uuidToDevice.get(uuid);
        if (result != null) return result;
        updateUuidMappings();
        return uuidToDevice.get(uuid);
    }

    private static final Pattern volumeLinePattern = Pattern.compile("Volume (disk[0-9]+s[0-9]+) ([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})");

    private static void updateUuidMappings() {
        try {
            uuidToDevice.clear();
            deviceToUuid.clear();
            Process process = Runtime.getRuntime().exec(new String[]{"/usr/sbin/diskutil", "ap", "list"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = volumeLinePattern.matcher(line);
                    if (matcher.find()) {
                        String disk = matcher.group(1);
                        UUID diskUuid = UUID.fromString(matcher.group(2));
                        deviceToUuid.put(disk, diskUuid);
                        uuidToDevice.put(diskUuid, disk);
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private String localSnapshot() {
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

    private static long snapshotCacheTime = 0L;
    private static final Map<UUID,List<ApfsSnapshot>> snapshotCache = new HashMap<>();

    static void invalidateSnapshotCache(long maxAge) {
        if (maxAge == 0L || System.currentTimeMillis() - snapshotCacheTime >= maxAge) {
            snapshotCacheTime = 0L;
            snapshotCache.clear();
        }
    }

    static List<ApfsSnapshot> listSnapshots(UUID deviceUuid, String device) {
        invalidateSnapshotCache(10000L);
        List<ApfsSnapshot> snapshots = snapshotCache.get(deviceUuid);
        if (snapshots != null) return Collections.unmodifiableList(snapshots);
        snapshots = new ArrayList<>();
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
                            snapshots.add(new ApfsSnapshot(deviceUuid, uuid, name, xid));
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
            snapshots.add(new ApfsSnapshot(deviceUuid, uuid, name, xid));
        }
        snapshotCache.put(deviceUuid, snapshots);
        return Collections.unmodifiableList(snapshots);
    }
}
