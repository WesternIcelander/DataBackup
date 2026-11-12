package io.siggi.databackup.client.snapshot;

import java.util.List;

/**
 * The snapshots of one backup run, mounted and recorded in {@code snapshots.json}. {@link #close()} (in a
 * {@code finally} block or try-with-resources) unmounts and deletes them and removes them from the file.
 */
public final class SnapshotSession implements AutoCloseable {

    private final SnapshotManager manager;
    private final List<SourceSnapshot> snapshots;
    private final List<SnapshotStore.Entry> entries;
    private boolean closed;

    SnapshotSession(SnapshotManager manager, List<SourceSnapshot> snapshots, List<SnapshotStore.Entry> entries) {
        this.manager = manager;
        this.snapshots = List.copyOf(snapshots);
        this.entries = List.copyOf(entries);
    }

    /**
     * One snapshot per source, in the configured order.
     */
    public List<SourceSnapshot> snapshots() {
        return snapshots;
    }

    /**
     * Deletes the snapshots. Does nothing the second time.
     *
     * @throws SnapshotCleanupException if any couldn't be deleted; they stay recorded for a later cleanup
     */
    @Override
    public void close() throws SnapshotCleanupException {
        if (closed) return;
        closed = true;
        manager.deleteAll(entries);
    }
}
