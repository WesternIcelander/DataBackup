package io.siggi.databackup.client.snapshot;

import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Takes, records and deletes the snapshots of a backup run.
 * <ol>
 *     <li>{@link #cleanupLeftovers()} deletes snapshots a crashed run left in {@code snapshots.json}.</li>
 *     <li>{@link #takeSnapshots(List)} snapshots every source (one {@link DiskUtil#createSnapshots} call per
 *     filesystem type), records each snapshot in {@code snapshots.json} as soon as it exists, and mounts it.</li>
 *     <li>Closing the returned {@link SnapshotSession} unmounts and deletes the snapshots and removes them from the
 *     file.</li>
 * </ol>
 * The caller holds the state folder's {@link StateLock} throughout.
 */
public final class SnapshotManager {

    /**
     * btrfs snapshots go in this folder under the state folder.
     */
    public static final String BTRFS_SNAPSHOT_DIR = "snapshots";

    private static final DateTimeFormatter DESTINATION_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path stateDir;
    private final SnapshotStore store;
    private final PrintStream log;

    /**
     * @param stateDir the client's state folder
     * @param platform supplies the {@link DiskUtil}s, used to read back recorded snapshots
     * @param log      where progress messages go
     * @throws IOException if {@code snapshots.json} exists but can't be read
     */
    public SnapshotManager(Path stateDir, SnapshotPlatform platform, PrintStream log) throws IOException {
        this.stateDir = stateDir;
        this.store = SnapshotStore.open(stateDir, platform::diskUtil);
        this.log = log;
    }

    public SnapshotStore store() {
        return store;
    }

    /**
     * Deletes every snapshot recorded in {@code snapshots.json}, removing each from the file once it is gone.
     * Snapshots that no longer exist are just removed from the file.
     *
     * @return how many entries were cleaned up (deleted or found already gone)
     * @throws SnapshotCleanupException if any couldn't be deleted (they stay in the file)
     */
    public int cleanupLeftovers() throws SnapshotCleanupException {
        List<String> problems = new ArrayList<>();
        int cleaned = 0;
        for (SnapshotStore.Entry entry : store.entries()) {
            String description = "snapshot of " + entry.source() + " " + entry.json();
            if (entry.snapshot() == null) {
                problems.add("Can't read the recorded " + description + ": " + entry.error()
                        + ". Delete it by hand, then remove it from " + store.file() + ".");
                continue;
            }
            Snapshot snapshot = entry.snapshot();
            try {
                if (snapshot.isValid()) {
                    deleteSnapshot(snapshot);
                    log.println("Deleted leftover " + description);
                } else {
                    log.println("Leftover " + description + " no longer exists");
                }
            } catch (SnapshotException e) {
                problems.add("Can't delete leftover " + description + ": " + message(e));
                continue;
            }
            try {
                store.remove(entry);
                cleaned++;
            } catch (IOException e) {
                problems.add("Deleted leftover " + description + " but can't update " + store.file() + ": "
                        + IoMessages.describe(e));
            }
        }
        if (!problems.isEmpty()) throw new SnapshotCleanupException(problems);
        return cleaned;
    }

    /**
     * Snapshots every volume and mounts the snapshots. On failure, every snapshot already taken is deleted again.
     *
     * @throws SnapshotSetupException if any snapshot can't be taken, recorded or mounted
     */
    public SnapshotSession takeSnapshots(List<SnapshotVolume> volumes) throws SnapshotSetupException {
        // Group by DiskUtil, keeping the configured order.
        Map<DiskUtil, List<SnapshotVolume>> byDiskUtil = new LinkedHashMap<>();
        for (SnapshotVolume volume : volumes) {
            byDiskUtil.computeIfAbsent(volume.diskUtil(), d -> new ArrayList<>()).add(volume);
        }
        Map<SnapshotVolume, SnapshotStore.Entry> taken = new HashMap<>();
        List<SnapshotStore.Entry> takenOrder = new ArrayList<>();
        try {
            for (Map.Entry<DiskUtil, List<SnapshotVolume>> group : byDiskUtil.entrySet()) {
                createGroup(group.getKey(), group.getValue(), taken, takenOrder);
            }
            List<SourceSnapshot> result = new ArrayList<>();
            for (SnapshotVolume volume : volumes) {
                Snapshot snapshot = taken.get(volume).snapshot();
                String scanPath;
                try {
                    scanPath = snapshot.mount();
                } catch (SnapshotException e) {
                    throw new SnapshotSetupException("Can't mount the snapshot of " + volume.source().name() + ": "
                            + message(e), e);
                }
                if (scanPath == null) {
                    throw new SnapshotSetupException(List.of("Can't find where the snapshot of "
                            + volume.source().name() + " is mounted (" + snapshot + ")"));
                }
                result.add(new SourceSnapshot(volume, snapshot, scanPath));
                log.println("Snapshot of " + volume.source().name() + " (" + volume.volume() + ") is at " + scanPath);
            }
            return new SnapshotSession(this, result, takenOrder);
        } catch (SnapshotSetupException e) {
            try {
                deleteAll(takenOrder);
            } catch (SnapshotCleanupException cleanup) {
                List<String> problems = new ArrayList<>(e.problems());
                problems.addAll(cleanup.problems());
                SnapshotSetupException combined = new SnapshotSetupException(problems);
                combined.addSuppressed(e);
                throw combined;
            }
            throw e;
        }
    }

    private void createGroup(DiskUtil diskUtil, List<SnapshotVolume> volumes,
                             Map<SnapshotVolume, SnapshotStore.Entry> taken, List<SnapshotStore.Entry> takenOrder)
            throws SnapshotSetupException {
        Map<String, SnapshotVolume> byVolume = new LinkedHashMap<>();
        for (SnapshotVolume volume : volumes) {
            if (byVolume.putIfAbsent(volume.volume(), volume) != null) {
                throw new SnapshotSetupException(List.of("Sources " + byVolume.get(volume.volume()).source().name()
                        + " and " + volume.source().name() + " are the same volume (" + volume.volume()
                        + "); configure it once"));
            }
        }
        Map<String, String> destinations = new HashMap<>();
        if (diskUtil.snapshotsRequireDestination()) {
            Path folder = stateDir.resolve(BTRFS_SNAPSHOT_DIR);
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                throw new SnapshotSetupException("Can't create the snapshot folder " + folder + ": " + e, e);
            }
            String time = LocalDateTime.now().format(DESTINATION_TIME);
            for (SnapshotVolume volume : volumes) {
                String name = volume.source().name() + "-" + time + "-"
                        + UUID.randomUUID().toString().substring(0, 8);
                destinations.put(volume.volume(), folder.resolve(name).toAbsolutePath().toString());
            }
        }
        Collection<String> requested = byVolume.keySet();
        String names = String.join(", ", volumes.stream().map(v -> v.source().name()).toList());
        log.println("Creating " + diskUtil.filesystem() + " snapshots of " + names);
        Map<String, Snapshot> created;
        try {
            created = diskUtil.createSnapshots(new ArrayList<>(requested), volume -> {
                String destination = destinations.get(volume);
                if (destination == null) {
                    throw new IllegalArgumentException("No snapshot destination for " + volume);
                }
                return destination;
            });
        } catch (SnapshotException e) {
            throw new SnapshotSetupException("Can't create " + diskUtil.filesystem() + " snapshots of " + names
                    + ": " + message(e)
                    + (diskUtil.snapshotsRequireDestination()
                    ? " (snapshots go in " + stateDir.resolve(BTRFS_SNAPSHOT_DIR)
                    + ", which must be on the same btrfs filesystem as the source)" : ""), e);
        } catch (RuntimeException e) {
            throw new SnapshotSetupException("Can't create " + diskUtil.filesystem() + " snapshots of " + names
                    + ": " + e, e);
        }
        if (created == null) created = Map.of();
        // Record everything that was created, matched or not, so nothing is ever left untracked.
        List<SnapshotStore.SourceSnapshotPair> pairs = new ArrayList<>();
        List<SnapshotVolume> pairVolumes = new ArrayList<>();
        for (Map.Entry<String, Snapshot> entry : created.entrySet()) {
            if (entry.getValue() == null) continue;
            SnapshotVolume volume = byVolume.get(entry.getKey());
            pairs.add(new SnapshotStore.SourceSnapshotPair(
                    volume == null ? "?" + entry.getKey() : volume.source().name(), entry.getValue()));
            pairVolumes.add(volume);
        }
        List<SnapshotStore.Entry> entries;
        try {
            entries = store.add(pairs);
        } catch (IOException e) {
            // Not recorded: delete them right away, since a crash later would leave them untracked.
            List<String> problems = new ArrayList<>();
            problems.add("Can't record snapshots in " + store.file() + ": " + IoMessages.describe(e));
            for (SnapshotStore.SourceSnapshotPair pair : pairs) {
                try {
                    deleteSnapshot(pair.snapshot());
                } catch (SnapshotException e2) {
                    problems.add("Can't delete the snapshot of " + pair.source() + " (" + pair.snapshot()
                            + "): " + message(e2) + ". Delete it by hand.");
                }
            }
            throw new SnapshotSetupException(problems);
        }
        for (int i = 0; i < entries.size(); i++) {
            takenOrder.add(entries.get(i));
            if (pairVolumes.get(i) != null) taken.put(pairVolumes.get(i), entries.get(i));
        }
        List<String> missing = new ArrayList<>();
        for (SnapshotVolume volume : volumes) {
            if (!taken.containsKey(volume)) missing.add(volume.source().name() + " (" + volume.volume() + ")");
        }
        if (!missing.isEmpty()) {
            throw new SnapshotSetupException(List.of("No " + diskUtil.filesystem() + " snapshot was created for "
                    + String.join(", ", missing)));
        }
    }

    /**
     * Unmounts and deletes snapshots, removing each from {@code snapshots.json} once it is gone. Tries every one.
     */
    void deleteAll(List<SnapshotStore.Entry> entries) throws SnapshotCleanupException {
        List<String> problems = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            SnapshotStore.Entry entry = entries.get(i);
            try {
                deleteSnapshot(entry.snapshot());
                log.println("Deleted snapshot of " + entry.source());
            } catch (SnapshotException e) {
                problems.add("Can't delete the snapshot of " + entry.source() + " (" + entry.snapshot() + "): "
                        + message(e) + ". Run \"databackup snapshots cleanup\" to try again.");
                continue;
            }
            try {
                store.remove(entry);
            } catch (IOException e) {
                problems.add("Deleted the snapshot of " + entry.source() + " but can't update " + store.file()
                        + ": " + IoMessages.describe(e));
            }
        }
        if (!problems.isEmpty()) throw new SnapshotCleanupException(problems);
    }

    private static void deleteSnapshot(Snapshot snapshot) throws SnapshotException {
        SnapshotException unmountError = null;
        try {
            snapshot.unmount();
        } catch (SnapshotException e) {
            unmountError = e;
        }
        try {
            snapshot.delete();
        } catch (SnapshotException e) {
            if (unmountError != null) e.addSuppressed(unmountError);
            throw e;
        }
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause.getMessage() != null && !message.contains(cause.getMessage())) {
            message += " (" + cause.getMessage() + ")";
        }
        return message.trim();
    }
}
