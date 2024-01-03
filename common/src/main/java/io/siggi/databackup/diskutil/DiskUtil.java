package io.siggi.databackup.diskutil;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

public interface DiskUtil<S extends Snapshot> {
    boolean supportsSnapshots();
    boolean snapshotsRequireDestination();
    Map<String,S> createSnapshots(Collection<String> filesystems, Function<String,String> destination) throws SnapshotException;
}
