package io.siggi.databackup.client.fsscanner;

import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.extra.ExtraDataMacOSFSEvents;
import io.siggi.databackup.data.extra.ExtraDataSnapshotInfoApfs;
import io.siggi.databackup.osutils.ApfsSnapshot;
import io.siggi.databackup.osutils.MacOSUtility;
import io.siggi.databackup.osutils.MountedDisk;
import io.siggi.databackup.scanner.DiffAction;
import io.siggi.databackup.scanner.FileMetadataScanner;
import io.siggi.jfsevents.FSEvent;
import io.siggi.jfsevents.FSEventConstants;
import io.siggi.jfsevents.JFSEvents;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public class ApfsScanner extends FileSystemScanner {

    private File rootDirectory;
    private File snapshotTarget;
    private MountedDisk disk;
    private DirectoryEntryDirectory diffBase;
    private FileMetadataScanner fileMetadataScanner;

    @Override
    public void setRootDirectory(File rootDirectory) {
        if (fileMetadataScanner != null) throw new IllegalStateException("Already created FileMetadataScanner");
        String absolutePath = rootDirectory.getAbsolutePath();
        if (absolutePath.equals("/")) {
            File alternativeRootDirectory = new File("/System/Volumes/Data");
            if (alternativeRootDirectory.exists()) {
                rootDirectory = alternativeRootDirectory;
                absolutePath = alternativeRootDirectory.getAbsolutePath();
            }
        }
        MountedDisk disk = null;
        for (MountedDisk mountedDisk : MacOSUtility.getMountedDisks()) {
            if (mountedDisk.type().equals("apfs") && mountedDisk.path().equals(absolutePath)) {
                disk = mountedDisk;
                break;
            }
        }
        if (disk == null) {
            throw new IllegalStateException("Directory must be an APFS volume root.");
        }
        this.rootDirectory = rootDirectory;
        this.disk = disk;
    }

    @Override
    public void setDiffBase(DirectoryEntryDirectory root) {
        this.diffBase = root;
    }

    @Override
    public FileMetadataScanner getFileMetadataScanner() {
        if (fileMetadataScanner == null) {
            if (rootDirectory == null) {
                throw new IllegalStateException("root directory not set");
            }
            if (snapshotTarget == null) {
                throw new IllegalStateException("snapshot target directory not set");
            }
            fileMetadataScanner = new FileMetadataScanner(snapshotTarget);
        }
        return fileMetadataScanner;
    }

    @Override
    public boolean requiresSnapshotTarget() {
        return true;
    }

    @Override
    public boolean isSnapshotAMountPoint() {
        return true;
    }

    @Override
    public void setSnapshotTarget(File directory) {
        if (fileMetadataScanner != null) throw new IllegalStateException("Already created FileMetadataScanner");
        this.snapshotTarget = directory;
    }

    @Override
    public DirectoryEntryDirectory scan() throws InterruptedException {
        if (rootDirectory == null) throw new IllegalStateException("Root directory must be set!");
        if (snapshotTarget == null) throw new IllegalStateException("Snapshot target must be set!");

        long rootDeviceId = JFSEvents.getDeviceId(rootDirectory.getAbsolutePath());
        ExtraDataMacOSFSEvents oldFsEvents = diffBase == null ? null : diffBase.getExtra(ExtraDataMacOSFSEvents.class);
        ExtraDataMacOSFSEvents newFsEvents;
        Map<String, DiffAction> diffActionMap = new HashMap<>();
        Function<String, DiffAction> diffActionFunction = (path) -> diffActionMap.getOrDefault(path, DiffAction.DO_NOT_SCAN);

        UUID deviceUuid = JFSEvents.getUuidForDevice(rootDeviceId);
        if (oldFsEvents != null && !oldFsEvents.fsEventsUuid.equals(deviceUuid)) {
            // if the UUID has changed, we cannot use fsevents to determine what files have changed.
            // The UUID could change because of:
            // - The system crashed, causing the fsevents db to be corrupt, and wiped on the next boot
            // - (unlikely) the 64 bit unsigned integer for event ID wrapped around back to 0.
            oldFsEvents = null;
        }
        try (JFSEvents jfsEvents = new JFSEvents()) {
            long sinceEventId = oldFsEvents == null ? -1L : oldFsEvents.lastEventId;
            jfsEvents.start(rootDeviceId, sinceEventId, 0.0, 0);
            long latestEventId = 0L;
            if (sinceEventId == -1L) {
                latestEventId = jfsEvents.getLatestEventId();
                diffActionFunction = null;
            } else {
                FSEvent event;
                while ((event = jfsEvents.readEvent()) != null) {
                    if ((event.getFlags() & FSEventConstants.kFSEventStreamEventFlagHistoryDone) != 0) {
                        break;
                    }
                    latestEventId = event.getId();
                    String path = event.getPath();
                    DiffAction currentDiffAction = diffActionMap.get(path);
                    DiffAction newDiffAction = DiffAction.PARTIAL_SCAN;
                    if ((event.getFlags() & FSEventConstants.kFSEventStreamEventFlagMustScanSubDirs) != 0) {
                        newDiffAction = DiffAction.FULL_SCAN;
                    }
                    if (currentDiffAction == null || currentDiffAction.level < newDiffAction.level) {
                        diffActionMap.put(path, newDiffAction);
                    }
                    for (String parent : getAllParents(path)) {
                        diffActionMap.putIfAbsent(parent, DiffAction.SUBDIRS_ONLY);
                    }
                }
            }
            newFsEvents = new ExtraDataMacOSFSEvents(latestEventId, deviceUuid);
        }

        // If we want direct access to snapshots, we need Apple's entitlement.
        // The only way to get this is to be a well known developer of a backup
        // application and follow Apple's rules.  Apple's rules are not really
        // a problem, it's just we're not well known.

        // The only way to create a snapshot without Apple's entitlement is
        // by calling tmutil localsnapshot, which creates a snapshot on *all*
        // read/write mounted APFS volumes, and then deleting the snapshot on
        // the volumes we don't need a snapshot on. Thanks, Apple!
        String snapshotTimestampString = MacOSUtility.localSnapshot();
        if (snapshotTimestampString == null) {
            throw new RuntimeException("Unable to create a snapshot");
        }
        List<MountedDisk> allDisks = MacOSUtility.getMountedDisks();
        allDisks.removeIf(d -> d.isReadOnly() || !d.type().equals("apfs") || d.path().equals(disk.path()));
        MacOSUtility.deleteSnapshots(snapshotTimestampString, allDisks, disk);

        ApfsSnapshot snapshot = null;
        for (ApfsSnapshot snap : MacOSUtility.listSnapshots(disk.dev())) {
            if (snap.name().contains(snapshotTimestampString)) {
                snapshot = snap;
                break;
            }
        }
        if (snapshot == null) throw new RuntimeException("Created snapshot was not found.");
        snapshotTarget.mkdirs();
        MacOSUtility.mountSnapshot(snapshot, snapshotTarget);
        boolean shouldRemoveSnapshot = true;

        try {
            FileMetadataScanner fileMetadataScanner = getFileMetadataScanner();
            if (diffBase != null) {
                fileMetadataScanner.createDiff(diffBase, diffActionFunction);
            }
            fileMetadataScanner.grabFiles();
            if (diffBase != null) {
                fileMetadataScanner.copyHashes(diffBase);
            }
            fileMetadataScanner.fillInHashes(null);
            DirectoryEntryDirectoryMemory result = fileMetadataScanner.rootDirectory;
            result.getExtra().add(newFsEvents);
            result.getExtra().add(new ExtraDataSnapshotInfoApfs(snapshot.name(), snapshot.xid()));
            shouldRemoveSnapshot = false;
            return result;
        } finally {
            if (shouldRemoveSnapshot) {
                try {
                    MacOSUtility.unmountVolume(snapshotTarget);
                    snapshotTarget.delete();
                    MacOSUtility.deleteSnapshot(snapshot);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static List<String> getAllParents(String path) {
        List<String> parents = new ArrayList<>();
        while (!path.isEmpty()) {
            int lastSlash = path.lastIndexOf("/");
            if (lastSlash == -1) path = "";
            else path = path.substring(0, lastSlash);
            parents.add(path);
        }
        return parents;
    }
}
