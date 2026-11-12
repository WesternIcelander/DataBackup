package io.siggi.databackup.client.snapshot;

import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.diskutil.DiskUtil;

/**
 * A backup source matched to the snapshottable volume or subvolume it is.
 *
 * @param source   the configured source
 * @param diskUtil the {@link DiskUtil} that snapshots it
 * @param volume   what to pass to {@link DiskUtil#createSnapshots}: an APFS mount point, a btrfs subvolume path or
 *                 a Windows drive root such as {@code C:\}. Usually the source path, except that {@code /} on macOS
 *                 becomes {@code /System/Volumes/Data}.
 */
public record SnapshotVolume(Source source, DiskUtil diskUtil, String volume) {
}
