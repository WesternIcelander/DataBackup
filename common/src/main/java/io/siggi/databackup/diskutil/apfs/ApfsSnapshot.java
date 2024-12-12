package io.siggi.databackup.diskutil.apfs;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.diskutil.MountedDisk;
import io.siggi.databackup.util.Util;

import java.io.File;
import java.util.List;
import java.util.UUID;

public class ApfsSnapshot implements Snapshot {

    private final UUID volumeUuid;
    private final UUID snapshotUuid;
    private final String name;
    private final long xid;

    public ApfsSnapshot(UUID volumeUuid, UUID snapshotUuid, String name, long xid) {
        if (volumeUuid == null || snapshotUuid == null || name == null) {
            throw new NullPointerException();
        }
        this.volumeUuid = volumeUuid;
        this.snapshotUuid = snapshotUuid;
        this.name = name;
        this.xid = xid;
    }

    public UUID volumeUuid() {
        return volumeUuid;
    }

    public String device() {
        return ApfsDiskUtil.deviceForUuid(volumeUuid);
    }

    public UUID snapshotUuid() {
        return snapshotUuid;
    }

    public String name() {
        return name;
    }

    public long xid() {
        return xid;
    }

    @Override
    public DiskUtil<?> diskUtil() {
        return ApfsDiskUtil.get();
    }

    @Override
    public String mountedPath() {
        String deviceToMatch = name() + "@/dev/" + device();
        List<MountedDisk> mountedDisks = MountedDisk.getAll();
        for (MountedDisk disk : mountedDisks) {
            if (disk.dev().equals(deviceToMatch)) {
                return disk.path();
            }
        }
        return null;
    }

    @Override
    public boolean isMounted() {
        return mountedPath() != null;
    }

    @Override
    public String mount() throws SnapshotException {
        String mountedPath = mountedPath();
        if (mountedPath != null) return mountedPath;
        mountedPath = "/private/tmp/snapshot-" + UUID.randomUUID().toString();
        File mountedPathFile = new File(mountedPath);
        if (!mountedPathFile.mkdirs()) {
            throw new SnapshotException("Unable to create directory to mount snapshot in.");
        }
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "/sbin/mount_apfs",
                    "-s",
                    name(),
                    "-o",
                    "ro",
                    "-o",
                    "nobrowse",
                    "/dev/" + device(),
                    mountedPath
            });
            if (process.waitFor() != 0) throw new SnapshotException(Util.getErrorString(process));
            return mountedPath;
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            mountedPathFile.delete();
            throw new SnapshotException("Unable to mount snapshot", e);
        }
    }

    @Override
    public void unmount() throws SnapshotException {
        String mountedPath = mountedPath();
        if (mountedPath == null) return;
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"umount", mountedPath});
            if (process.waitFor() != 0) throw new SnapshotException(Util.getErrorString(process));
            new File(mountedPath).delete();
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("Unable to unmount snapshot", e);
        }
    }

    @Override
    public void delete() throws SnapshotException {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/usr/sbin/diskutil", "ap", "deleteSnapshot", device(), "-xid", Long.toString(xid())});
            if (process.waitFor() != 0) throw new SnapshotException(Util.getErrorString(process));
            ApfsDiskUtil.invalidateSnapshotCache(0L);
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("Unable to delete snapshot.", e);
        }
    }

    @Override
    public boolean isValid() {
        List<ApfsSnapshot> apfsSnapshots = ApfsDiskUtil.listSnapshots(volumeUuid(), device());
        for (ApfsSnapshot snapshot : apfsSnapshots) {
            if (snapshot.volumeUuid().equals(volumeUuid())
                    && snapshot.snapshotUuid().equals(snapshotUuid())) {
                return true;
            }
        }
        return false;
    }
}
