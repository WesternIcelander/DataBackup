package io.siggi.databackup.diskutil;

public interface Snapshot {
    DiskUtil<?> diskUtil();
    String mountedPath() throws SnapshotException;
    boolean isMounted() throws SnapshotException;
    String mount() throws SnapshotException;
    void unmount() throws SnapshotException;
    void delete() throws SnapshotException;
    boolean isValid() throws SnapshotException;
}
