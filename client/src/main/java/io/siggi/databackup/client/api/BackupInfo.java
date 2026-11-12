package io.siggi.databackup.client.api;

import java.util.UUID;

/**
 * One committed backup of a machine.
 *
 * @param name       the backup's name, its commit time as {@code yyyy-MM-dd-HH-mm-ss} in UTC
 * @param time       the backup time in milliseconds since the epoch
 * @param snapshotId the UUID from the manifest's {@code ExtraDataSnapshotId}
 */
public record BackupInfo(String name, long time, UUID snapshotId) {
}
