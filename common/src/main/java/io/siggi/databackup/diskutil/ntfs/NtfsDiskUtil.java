package io.siggi.databackup.diskutil.ntfs;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

public class NtfsDiskUtil implements DiskUtil {

    private NtfsDiskUtil() {
    }

    private static final NtfsDiskUtil instance = new NtfsDiskUtil();

    public static NtfsDiskUtil get() {
        return instance;
    }

    @Override
    public String filesystem() {
        return "ntfs";
    }

    @Override
    public NtfsSnapshotSerializer serializer() {
        return NtfsSnapshotSerializer.get();
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
    public Map<String, Snapshot> createSnapshots(Collection<String> drives, Function<String, String> destination) throws SnapshotException {
        for (String drive : drives) {
            if (!validateDrive(drive)) {
                throw new SnapshotException(drive + " is not a valid drive.");
            }
            if (!new File(drive.charAt(0) + ":").exists()) {
                throw new SnapshotException(drive + " does not exist.");
            }
        }
        Map<String, Snapshot> snapshots = new HashMap<>();
        try {
            for (String drive : drives) {
                String drivePath = drive.substring(0, 1).toUpperCase() + ":\\";
                snapshots.put(drive, createSnapshot(drivePath));
            }
        } catch (SnapshotException e) {
            for (Snapshot snapshot : snapshots.values()) {
                try {
                    snapshot.delete();
                } catch (SnapshotException e2) {
                }
            }
            throw e;
        }
        return snapshots;
    }

    private NtfsSnapshot createSnapshot(String drive) throws SnapshotException {
        UUID snapshotUuid = VssCim.create(drive);
        NtfsSnapshot snapshot = getSnapshot(snapshotUuid);
        if (snapshot == null) {
            throw new SnapshotException("Created shadow copy {" + snapshotUuid + "} of " + drive
                    + " but it isn't in the list of shadow copies.");
        }
        return snapshot;
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

    static synchronized boolean invalidateSnapshotCache(long maxAge) {
        if (maxAge == 0L || System.currentTimeMillis() - snapshotCacheTime >= maxAge) {
            snapshotCacheTime = 0L;
            snapshotCache.clear();
            snapshotCacheBySnapshotId.clear();
            cacheMountPoints.clear();
            return true;
        }
        return false;
    }

    static synchronized List<NtfsSnapshot> listSnapshots(UUID volumeId) throws SnapshotException {
        if (invalidateSnapshotCache(10000L)) {
            buildSnapshotCache();
        }
        List<NtfsSnapshot> ntfsSnapshots = snapshotCache.get(volumeId);
        if (ntfsSnapshots == null) return Collections.emptyList();
        return Collections.unmodifiableList(ntfsSnapshots);
    }

    static synchronized NtfsSnapshot getSnapshot(UUID snapshotId) throws SnapshotException {
        NtfsSnapshot snapshot = snapshotCacheBySnapshotId.get(snapshotId);
        if (snapshot != null) return snapshot;
        invalidateSnapshotCache(0L);
        buildSnapshotCache();
        return snapshotCacheBySnapshotId.get(snapshotId);
    }

    static synchronized String getMountPoint(UUID snapshotId) throws SnapshotException {
        String mountPoint = cacheMountPoints.get(snapshotId);
        if (mountPoint != null) return mountPoint;
        invalidateSnapshotCache(0L);
        buildSnapshotCache();
        return cacheMountPoints.get(snapshotId);
    }

    private static void buildSnapshotCache() throws SnapshotException {
        for (VssCim.ShadowCopy shadowCopy : VssCim.list()) {
            NtfsSnapshot snapshot = new NtfsSnapshot(shadowCopy.volumeId(), shadowCopy.id());
            snapshotCache.computeIfAbsent(shadowCopy.volumeId(), u -> new ArrayList<>()).add(snapshot);
            snapshotCacheBySnapshotId.put(shadowCopy.id(), snapshot);
            if (shadowCopy.deviceObject() != null) {
                cacheMountPoints.put(shadowCopy.id(), shadowCopy.deviceObject());
            }
        }
        snapshotCacheTime = System.currentTimeMillis();
    }
}
