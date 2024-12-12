package io.siggi.databackup.diskutil.btrfs;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.util.Util;

import java.io.File;

public class BtrfsSnapshot implements Snapshot {

    private final String path;

    public BtrfsSnapshot(String path) {
        this.path = path;
    }

    @Override
    public DiskUtil diskUtil() {
        return BtrfsDiskUtil.get();
    }

    public String path() {
        return path;
    }

    @Override
    public String mountedPath() throws SnapshotException {
        return path;
    }

    @Override
    public boolean isMounted() throws SnapshotException {
        return isValid();
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
                    BtrfsDiskUtil.BTRFS_PATH,
                    "subvolume",
                    "delete",
                    path
            });
            if (process.waitFor() != 0) throw new SnapshotException(Util.getErrorString(process));
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("Unable to delete snapshot.", e);
        }
    }

    @Override
    public boolean isValid() throws SnapshotException {
        return new File(mountedPath()).exists();
    }
}
