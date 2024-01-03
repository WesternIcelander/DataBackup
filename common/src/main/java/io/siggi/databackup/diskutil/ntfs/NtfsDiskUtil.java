package io.siggi.databackup.diskutil.ntfs;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.util.Util;

import java.io.BufferedReader;
import java.io.File;
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

public class NtfsDiskUtil implements DiskUtil<NtfsSnapshot> {

    static final String WMIC_PATH = "C:\\Windows\\System32\\wbem\\WMIC.exe";
    static final String VSSADMIN_PATH = "C:\\Windows\\System32\\vssadmin.exe";

    @Override
    public boolean supportsSnapshots() {
        return true;
    }

    @Override
    public boolean snapshotsRequireDestination() {
        return false;
    }

    @Override
    public Map<String, NtfsSnapshot> createSnapshots(Collection<String> drives, Function<String, String> destination) throws SnapshotException {
        for (String drive : drives) {
            if (!validateDrive(drive)) {
                throw new SnapshotException(drive + " is not a valid drive.");
            }
            if (!new File(drive).exists()) {
                throw new SnapshotException(drive + " does not exist.");
            }
        }
        Map<String, NtfsSnapshot> snapshots = new HashMap<>();
        for (String drive : drives) {
            String drivePath = drive.substring(0, 1).toUpperCase() + ":\\";
            snapshots.put(drive, createSnapshot(drivePath));
        }
        return snapshots;
    }

    private NtfsSnapshot createSnapshot(String drive) throws SnapshotException {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {
                    WMIC_PATH,
                    "shadowcopy",
                    "call",
                    "create",
                    "Volume=" + drive
            });
            UUID snapshotUuid = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("ShadowID")) {
                        Matcher matcher = Util.uuidPattern.matcher(line);
                        if (matcher.find()) {
                            snapshotUuid = UUID.fromString(matcher.group());
                        }
                    }
                }
            }
            if (snapshotUuid == null) {
                if (process.waitFor() != 0) {
                    throw new SnapshotException(Util.getErrorString(process));
                }
                throw new SnapshotException("Unable to create snapshot, wmic output did not contain ShadowID.");
            }
            return getSnapshot(snapshotUuid);
        } catch (Exception e) {
            throw new SnapshotException("Unable to create snapshot.", e);
        }
    }

    private boolean validateDrive(String drive) {
        if (drive.length() > 3) return false;
        if (drive.length() == 3 && drive.charAt(2) != '\\') return false;
        if (drive.length() >= 2 && drive.charAt(1) != ':') return false;
        char driveLetter = drive.charAt(0);
        return driveLetter >= 'A' && driveLetter <= 'Z' || driveLetter >= 'a' && driveLetter <= 'z';
    }

    private static long snapshotCacheTime = 0L;
    private static final Map<UUID, List<NtfsSnapshot>> snapshotCache = new HashMap<>();
    private static final Map<UUID, NtfsSnapshot> snapshotCacheBySnapshotId = new HashMap<>();
    private static final Map<UUID, String> cacheMountPoints = new HashMap<>();

    static boolean invalidateSnapshotCache(long maxAge) {
        if (maxAge == 0L || System.currentTimeMillis() - snapshotCacheTime >= maxAge) {
            snapshotCacheTime = 0L;
            snapshotCache.clear();
            snapshotCacheBySnapshotId.clear();
            return true;
        }
        return false;
    }

    static List<NtfsSnapshot> listSnapshots(UUID volumeId) {
        if (invalidateSnapshotCache(10000L)) {
            buildSnapshotCache();
        }
        List<NtfsSnapshot> ntfsSnapshots = snapshotCache.get(volumeId);
        if (ntfsSnapshots == null) return Collections.emptyList();
        return Collections.unmodifiableList(ntfsSnapshots);
    }

    static NtfsSnapshot getSnapshot(UUID snapshotId) {
        NtfsSnapshot snapshot = snapshotCacheBySnapshotId.get(snapshotId);
        if (snapshot != null) return snapshot;
        invalidateSnapshotCache(0L);
        buildSnapshotCache();
        return snapshotCacheBySnapshotId.get(snapshotId);
    }

    static String getMountPoint(UUID snapshotId) {
        String mountPoint = cacheMountPoints.get(snapshotId);
        if (mountPoint != null) return mountPoint;
        invalidateSnapshotCache(0L);
        buildSnapshotCache();
        return cacheMountPoints.get(snapshotId);
    }

    private static void buildSnapshotCache() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    VSSADMIN_PATH,
                    "list",
                    "shadows"
            });
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                UUID shadowCopyUuid = null;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Shadow Copy ID: ")) {
                        Matcher uuidMatcher = Util.uuidPattern.matcher(line);
                        if (uuidMatcher.find()) {
                            shadowCopyUuid = UUID.fromString(uuidMatcher.group());
                        }
                    } else if (line.contains("Original Volume: ")) {
                        Matcher uuidMatcher = Util.uuidPattern.matcher(line);
                        if (uuidMatcher.find()) {
                            UUID volumeUuid = UUID.fromString(uuidMatcher.group());
                            NtfsSnapshot snapshot = new NtfsSnapshot(volumeUuid, shadowCopyUuid);
                            List<NtfsSnapshot> ntfsSnapshots = snapshotCache.computeIfAbsent(volumeUuid, u -> new ArrayList<>());
                            ntfsSnapshots.add(snapshot);
                            snapshotCacheBySnapshotId.put(volumeUuid, snapshot);
                        }
                    } else if (line.contains("Shadow Copy Volume: ")) {
                        String value = line.substring(line.indexOf(": ") + 2).trim();
                        cacheMountPoints.put(shadowCopyUuid, value);
                    }
                }
            }
        } catch (Exception e) {
        }
    }
}
