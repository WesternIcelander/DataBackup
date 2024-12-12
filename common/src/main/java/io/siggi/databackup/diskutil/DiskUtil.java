package io.siggi.databackup.diskutil;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

public interface DiskUtil {
    String filesystem();
    SnapshotSerializer serializer();
    boolean supportsSnapshots();
    boolean snapshotsRequireDestination();
    Map<String, Snapshot> createSnapshots(Collection<String> filesystems, Function<String,String> destination) throws SnapshotException;
}
