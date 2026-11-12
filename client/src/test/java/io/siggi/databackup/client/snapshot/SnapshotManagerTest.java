package io.siggi.databackup.client.snapshot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.databackup.client.snapshot.FakeSnapshotPlatform.FakeDiskUtil;
import io.siggi.databackup.osutils.OS;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.siggi.databackup.client.snapshot.FakeSnapshotPlatform.source;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotManagerTest {

    @TempDir
    Path stateDir;

    private final FakeSnapshotPlatform platform = new FakeSnapshotPlatform();
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final PrintStream log = new PrintStream(logBytes, true, StandardCharsets.UTF_8);
    private FakeDiskUtil btrfs;
    private FakeDiskUtil apfs;

    @BeforeEach
    void setUp() {
        btrfs = platform.fake("btrfs");
        apfs = platform.fake("apfs");
    }

    private SnapshotManager manager() throws IOException {
        return new SnapshotManager(stateDir, platform, log);
    }

    private static SnapshotVolume volume(String name, FakeDiskUtil diskUtil, String path) {
        return new SnapshotVolume(source(name, path), diskUtil, path);
    }

    private Path file() {
        return stateDir.resolve(SnapshotStore.FILE_NAME);
    }

    private JsonArray recorded() throws IOException {
        if (!Files.exists(file())) return new JsonArray();
        return JsonParser.parseString(Files.readString(file())).getAsJsonArray();
    }

    @Test
    void takesRecordsMountsAndDeletes() throws Exception {
        SnapshotManager manager = manager();
        List<SnapshotVolume> volumes = List.of(
                volume("Home", btrfs, "/home"),
                volume("Data", apfs, "/System/Volumes/Data"),
                volume("Root", btrfs, "/"));
        SnapshotSession session = manager.takeSnapshots(volumes);

        // One createSnapshots call per filesystem, with all its volumes.
        assertEquals(List.of(List.of("/home", "/")), btrfs.createCalls);
        assertEquals(List.of(List.of("/System/Volumes/Data")), apfs.createCalls);

        List<SourceSnapshot> snapshots = session.snapshots();
        assertEquals(List.of("Home", "Data", "Root"), snapshots.stream().map(s -> s.source().name()).toList());
        for (SourceSnapshot s : snapshots) {
            assertTrue(s.snapshot().isMounted());
            assertEquals(s.snapshot().mountedPath(), s.scanPath());
        }

        // Recorded before the caller scans.
        JsonArray recorded = recorded();
        assertEquals(3, recorded.size());
        for (int i = 0; i < 3; i++) {
            JsonObject entry = recorded.get(i).getAsJsonObject();
            assertTrue(entry.has("source"));
            JsonObject snapshot = entry.getAsJsonObject("snapshot");
            assertTrue(snapshot.has("type") && snapshot.has("id"), snapshot.toString());
        }

        session.close();
        assertTrue(btrfs.existing.isEmpty());
        assertTrue(apfs.existing.isEmpty());
        assertTrue(btrfs.mounted.isEmpty() && apfs.mounted.isEmpty());
        assertFalse(Files.exists(file()), "snapshots.json is removed once empty");
        session.close(); // a second close does nothing
        assertEquals(3, btrfs.deleted.size() + apfs.deleted.size());
    }

    @Test
    void destinationsGoUnderTheStateDirWhenRequired() throws Exception {
        SnapshotSession session = manager().takeSnapshots(List.of(
                volume("Home", btrfs, "/home"), volume("Data", apfs, "/System/Volumes/Data")));
        Path folder = stateDir.resolve(SnapshotManager.BTRFS_SNAPSHOT_DIR);
        assertTrue(Files.isDirectory(folder));
        assertEquals(1, btrfs.destinations.size());
        Path destination = Path.of(btrfs.destinations.get("/home"));
        assertEquals(folder.toAbsolutePath(), destination.getParent());
        assertTrue(destination.getFileName().toString().startsWith("Home-"), destination.toString());
        assertTrue(apfs.destinations.isEmpty());
        session.close();
    }

    @Test
    void failureInALaterGroupDeletesEarlierSnapshots() throws Exception {
        apfs.failCreate.add("/System/Volumes/Data");
        SnapshotSetupException e = assertThrows(SnapshotSetupException.class, () -> manager().takeSnapshots(
                List.of(volume("Home", btrfs, "/home"), volume("Data", apfs, "/System/Volumes/Data"))));
        assertTrue(e.getMessage().contains("create failed for /System/Volumes/Data"), e.getMessage());
        assertEquals(1, btrfs.deleted.size());
        assertTrue(btrfs.existing.isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    void btrfsCreateFailureMentionsTheSnapshotFolder() throws Exception {
        btrfs.failCreate.add("/home");
        SnapshotSetupException e = assertThrows(SnapshotSetupException.class,
                () -> manager().takeSnapshots(List.of(volume("Home", btrfs, "/home"))));
        assertTrue(e.getMessage().contains("same btrfs filesystem"), e.getMessage());
    }

    @Test
    void aMissingSnapshotFailsAndCleansUp() throws Exception {
        apfs.skipCreate.add("/Volumes/B");
        SnapshotSetupException e = assertThrows(SnapshotSetupException.class, () -> manager().takeSnapshots(
                List.of(volume("A", apfs, "/Volumes/A"), volume("B", apfs, "/Volumes/B"))));
        assertTrue(e.getMessage().contains("No apfs snapshot was created for B (/Volumes/B)"), e.getMessage());
        assertTrue(apfs.existing.isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    void unexpectedExtraSnapshotsAreTrackedAndDeleted() throws Exception {
        apfs.extraCreated.add("/Volumes/Other");
        SnapshotSession session = manager().takeSnapshots(List.of(volume("A", apfs, "/Volumes/A")));
        assertEquals(1, session.snapshots().size());
        assertEquals(2, recorded().size());
        session.close();
        assertTrue(apfs.existing.isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    void mountFailureCleansUp() throws Exception {
        apfs.failMount.add("/Volumes/A");
        SnapshotSetupException e = assertThrows(SnapshotSetupException.class,
                () -> manager().takeSnapshots(List.of(volume("A", apfs, "/Volumes/A"))));
        assertTrue(e.getMessage().contains("Can't mount the snapshot of A"), e.getMessage());
        assertTrue(apfs.existing.isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    void sameVolumeTwiceIsRejected() throws Exception {
        SnapshotSetupException e = assertThrows(SnapshotSetupException.class, () -> manager().takeSnapshots(
                List.of(volume("A", btrfs, "/home"), volume("B", btrfs, "/home"))));
        assertTrue(e.getMessage().contains("same volume"), e.getMessage());
        assertTrue(btrfs.createCalls.isEmpty());
    }

    @Test
    void failedDeletesStayRecordedUntilCleanup() throws Exception {
        SnapshotSession session = manager().takeSnapshots(
                List.of(volume("A", apfs, "/Volumes/A"), volume("B", apfs, "/Volumes/B")));
        apfs.failDelete.add("/Volumes/B");
        SnapshotCleanupException e = assertThrows(SnapshotCleanupException.class, session::close);
        assertEquals(1, e.problems().size());
        assertTrue(e.getMessage().contains("Can't delete the snapshot of B"), e.getMessage());
        assertTrue(e.getMessage().contains("snapshots cleanup"), e.getMessage());
        JsonArray recorded = recorded();
        assertEquals(1, recorded.size());
        assertEquals("B", recorded.get(0).getAsJsonObject().get("source").getAsString());

        // A later run (a new manager reading the file) cleans it up.
        assertThrows(SnapshotCleanupException.class, () -> manager().cleanupLeftovers());
        assertEquals(1, recorded().size());
        apfs.failDelete.clear();
        assertEquals(1, manager().cleanupLeftovers());
        assertTrue(apfs.existing.isEmpty());
        assertFalse(Files.exists(file()));
    }

    @Test
    void leftoversFromACrashedRunAreDeleted() throws Exception {
        // A run that never closes its session, as if the process died while scanning.
        manager().takeSnapshots(List.of(volume("Home", btrfs, "/home"), volume("A", apfs, "/Volumes/A"),
                volume("B", apfs, "/Volumes/B")));
        assertEquals(3, recorded().size());
        // One of them was deleted by hand in the meantime.
        String gone = apfs.existing.iterator().next();
        apfs.existing.remove(gone);
        apfs.mounted.remove(gone);

        SnapshotManager next = manager();
        assertEquals(3, next.store().entries().size());
        assertEquals(3, next.cleanupLeftovers());
        assertTrue(btrfs.existing.isEmpty());
        assertTrue(apfs.existing.isEmpty());
        assertTrue(apfs.mounted.isEmpty(), "unmounted before deleting");
        assertFalse(Files.exists(file()));
        assertTrue(logBytes.toString(StandardCharsets.UTF_8).contains("no longer exists"));
        assertEquals(0, manager().cleanupLeftovers());
    }

    @Test
    void unreadableEntriesAreKeptAndReported() throws Exception {
        Files.writeString(file(), "[{\"source\": \"X\", \"snapshot\": {\"type\": \"zfs\", \"name\": \"x\"}}]");
        SnapshotCleanupException e = assertThrows(SnapshotCleanupException.class,
                () -> manager().cleanupLeftovers());
        assertTrue(e.getMessage().contains("Unknown snapshot type: zfs"), e.getMessage());
        assertTrue(Files.exists(file()));

        // New snapshots are added alongside it without losing it.
        SnapshotSession session = manager().takeSnapshots(List.of(volume("A", apfs, "/Volumes/A")));
        assertEquals(2, recorded().size());
        session.close();
        JsonArray recorded = recorded();
        assertEquals(1, recorded.size());
        assertEquals("zfs", recorded.get(0).getAsJsonObject().getAsJsonObject("snapshot").get("type")
                .getAsString());
    }

    @Test
    void corruptFileIsAnError() throws Exception {
        Files.writeString(file(), "{not json");
        IOException e = assertThrows(IOException.class, this::manager);
        assertTrue(e.getMessage().contains("snapshots.json"), e.getMessage());
        Files.writeString(file(), "{}");
        assertThrows(IOException.class, this::manager);
        Files.writeString(file(), "  \n");
        assertEquals(0, manager().cleanupLeftovers());
    }

    @Test
    void endToEndWithTheResolver() throws Exception {
        platform.os = OS.LINUX;
        platform.mount("/dev/sda2", "/", "btrfs", "rw");
        platform.directories.add("/home/claude/subvolume");
        platform.btrfsSubvolumes.add("/home/claude/subvolume");
        List<SnapshotVolume> volumes = new VolumeResolver(platform).resolve(
                List.of(source("Sub", "/home/claude/subvolume")));
        try (SnapshotSession session = manager().takeSnapshots(volumes)) {
            assertEquals(1, session.snapshots().size());
            assertEquals(List.of(List.of("/home/claude/subvolume")), btrfs.createCalls);
        }
        assertTrue(btrfs.existing.isEmpty());
    }

    @Test
    void stateLockIsExclusive() throws Exception {
        try (StateLock lock = StateLock.acquire(stateDir)) {
            IOException e = assertThrows(IOException.class, () -> StateLock.acquire(stateDir));
            assertTrue(e.getMessage().contains("Another DataBackup client run"), e.getMessage());
        }
        StateLock.acquire(stateDir).close();
    }
}
