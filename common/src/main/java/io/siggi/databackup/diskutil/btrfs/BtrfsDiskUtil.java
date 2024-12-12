package io.siggi.databackup.diskutil.btrfs;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.diskutil.apfs.ApfsSnapshotSerializer;
import io.siggi.databackup.util.Util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class BtrfsDiskUtil implements DiskUtil {

    static final String BTRFS_PATH = "/usr/bin/btrfs";

    private BtrfsDiskUtil() {
    }

    private static final BtrfsDiskUtil instance = new BtrfsDiskUtil();

    public static BtrfsDiskUtil get() {
        return instance;
    }

    @Override
    public String filesystem() {
        return "btrfs";
    }

    @Override
    public BtrfsSnapshotSerializer serializer() {
        return BtrfsSnapshotSerializer.get();
    }

    @Override
    public boolean supportsSnapshots() {
        return true;
    }

    @Override
    public boolean snapshotsRequireDestination() {
        return true;
    }

    @Override
    public Map<String, Snapshot> createSnapshots(Collection<String> filesystems, Function<String, String> destination) throws SnapshotException {
        Map<String,Snapshot> snapshots = new HashMap<>();
        try {
            for (String path : filesystems) {
                String dest = destination.apply(path);
                BtrfsSnapshot snapshot = createSnapshot(path, dest);
                snapshots.put(path, snapshot);
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

    private BtrfsSnapshot createSnapshot(String source, String target) throws SnapshotException {
        try {
            Process process = Runtime.getRuntime().exec(new String[] {
                    BTRFS_PATH,
                    "subvolume",
                    "snapshot",
                    "-r",
                    source,
                    target
            });
            if (process.waitFor() != 0) throw new SnapshotException(Util.getErrorString(process));
            return new BtrfsSnapshot(target);
        } catch (SnapshotException e) {
            throw e;
        } catch (Exception e) {
            throw new SnapshotException("Unable to create snapshot.", e);
        }
    }
}
