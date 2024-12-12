package io.siggi.databackup.diskutil.ntfs;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.util.Util;

import java.io.File;
import java.util.UUID;

public class NtfsSnapshot implements Snapshot {

    private final UUID volumeId;
    private final UUID shadowCopyId;

    public NtfsSnapshot(UUID volumeId, UUID shadowCopyId) {
        if (volumeId == null || shadowCopyId == null) throw new NullPointerException();
        this.volumeId = volumeId;
        this.shadowCopyId = shadowCopyId;
    }

    public UUID volumeId() {
        return volumeId;
    }

    public UUID shadowCopyId() {
        return shadowCopyId;
    }

    @Override
    public DiskUtil diskUtil() {
        return NtfsDiskUtil.get();
    }

    @Override
    public String mountedPath() throws SnapshotException {
        return NtfsDiskUtil.getMountPoint(shadowCopyId);
    }

    @Override
    public boolean isMounted() throws SnapshotException {
        return isValid() && mountedPath() != null;
    }

    @Override
    public String mount() throws SnapshotException {
        return mountedPath();
    }

    @Override
    public void unmount() throws SnapshotException {
    }

    @Override
    public void delete() throws SnapshotException {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {
                    NtfsDiskUtil.VSSADMIN_PATH,
                    "delete",
                    "shadows",
                    "/Shadow={" + shadowCopyId().toString() + "}",
                    "/Quiet"
            });
            if (process.waitFor() != 0) {
                throw new SnapshotException(Util.getErrorString(process));
            }
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("Unable to delete snapshot.", e);
        }
        NtfsDiskUtil.invalidateSnapshotCache(0L);
    }

    @Override
    public boolean isValid() throws SnapshotException {
        return NtfsDiskUtil.getSnapshot(shadowCopyId) != null;
    }
}
