package io.siggi.databackup.client.snapshot;

import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.diskutil.Snapshot;

/**
 * A source's snapshot, taken and mounted.
 *
 * @param volume   the source and the volume that was snapshotted
 * @param snapshot the snapshot
 * @param scanPath where the snapshot's contents can be read: a mount point, a btrfs snapshot folder, or a Windows
 *                 shadow copy device path ({@code \\?\GLOBALROOT\Device\HarddiskVolumeShadowCopyN}, which plain NIO
 *                 can't open, so it is kept as a string)
 */
public record SourceSnapshot(SnapshotVolume volume, Snapshot snapshot, String scanPath) {

    public Source source() {
        return volume.source();
    }
}
