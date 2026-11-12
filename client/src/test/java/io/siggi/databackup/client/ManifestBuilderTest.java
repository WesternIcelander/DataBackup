package io.siggi.databackup.client;

import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.client.snapshot.SnapshotVolume;
import io.siggi.databackup.client.snapshot.SourceSnapshot;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.extra.ExtraData;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.data.extra.ExtraDataSnapshotInfoApfs;
import io.siggi.databackup.data.extra.ExtraDataSnapshotInfoBtrfs;
import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotSerializer;
import io.siggi.databackup.diskutil.apfs.ApfsSnapshot;
import io.siggi.databackup.diskutil.btrfs.BtrfsSnapshot;
import io.siggi.databackup.util.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Builds manifests from plain temp folders through the test-only {@link ManifestBuilder#build} path.
 */
class ManifestBuilderTest {

    @TempDir
    Path dir;

    Path stateDir;
    Path data;
    Path home;
    ByteArrayOutputStream outBytes;
    PrintStream out;

    @BeforeEach
    void setUp() throws Exception {
        stateDir = dir.resolve("state");
        data = dir.resolve("data");
        home = dir.resolve("home");
        write(data, "a.txt", "hello");
        write(data, "docs/b.txt", "the b file");
        write(data, "docs/c.log", "excluded globally");
        write(data, "cache/x", "excluded by the source");
        write(data, "skipped/.nobackup", "");
        write(data, "skipped/y", "excluded by .nobackup");
        write(home, "notes.txt", "notes");
        write(home, "project/node_modules/z.js", "excluded globally");
        outBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    }

    private static void write(Path root, String path, String text) throws Exception {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Source source(String name, Path path, List<String> excludes) {
        ExcludeMatcher global = ExcludeMatcher.of(List.of("**/*.log", "**/node_modules"));
        return new Source(name, path, excludes, global.and(ExcludeMatcher.of(excludes)));
    }

    private List<ManifestBuilder.ScanSource> plainSources(List<ExtraData> dataExtra) {
        return List.of(
                new ManifestBuilder.ScanSource(source("Data", data, List.of("cache")), data.toString(), null,
                        dataExtra),
                new ManifestBuilder.ScanSource(source("Home", home, List.of()), home.toString(), null, List.of()));
    }

    private ManifestBuilder.Result build() throws Exception {
        return new ManifestBuilder(stateDir, out).build(plainSources(List.of()));
    }

    private static Map<String, DirectoryEntryFile> files(DirectoryEntryDirectory directory) {
        Map<String, DirectoryEntryFile> files = new TreeMap<>();
        collect(directory, "", files);
        return files;
    }

    private static void collect(DirectoryEntryDirectory directory, String path, Map<String, DirectoryEntryFile> files) {
        for (DirectoryEntry entry : directory) {
            if (entry.isFile()) files.put(path + entry.getName(), entry.asFile());
            else if (entry.isDirectory()) collect(entry.asDirectory(), path + entry.getName() + "/", files);
        }
    }

    private static void assertHash(String expected, DirectoryEntryFile file) {
        ContentIDFileContent content = assertInstanceOf(ContentIDFileContent.class, file.getFileContents().get(0));
        assertArrayEquals(Util.sha256().digest(expected.getBytes(StandardCharsets.UTF_8)), content.getHash());
        assertEquals(expected.length(), file.getSize());
    }

    @Test
    void buildsTheLayout() throws Exception {
        ExtraDataSnapshotInfoBtrfs info = new ExtraDataSnapshotInfoBtrfs("/state/snapshots/Data-1");
        ManifestBuilder.Result result = new ManifestBuilder(stateDir, out).build(plainSources(List.of(info)));

        assertEquals(stateDir.resolve("pending-manifest"), result.manifest());
        assertFalse(Files.exists(stateDir.resolve("pending-manifest.tmp")));
        assertEquals(3L, result.files());
        assertEquals(5L + 10L + 5L, result.bytes());
        assertEquals(3L, result.hashedFiles());
        assertEquals(0L, result.reusedFiles());
        assertTrue(result.failedItems().isEmpty(), result.failedItems().toString());
        assertEquals(List.of("Data/cache", "Data/docs/c.log", "Data/skipped", "Home/project/node_modules"),
                result.ignoredItems().stream().sorted().toList());

        try (BackupFile manifest = BackupFile.open(result.manifest().toFile())) {
            DirectoryEntryDirectory root = manifest.getRootDirectory();
            assertEquals("", root.getName());
            assertEquals(new ExtraDataSnapshotId(result.snapshotId()), root.getExtra(ExtraDataSnapshotId.class));
            assertEquals(List.of("Data", "Home"), root.getEntries().keySet().stream().sorted().toList());

            DirectoryEntryDirectory dataDir = root.getEntry("Data").asDirectory();
            assertEquals(new ExtraDataFilePath(data.toString().replace('\\', '/')),
                    dataDir.getExtra(ExtraDataFilePath.class));
            assertEquals(info, dataDir.getExtra(ExtraDataSnapshotInfoBtrfs.class));
            assertNull(root.getEntry("Home").getExtra(ExtraDataSnapshotInfoBtrfs.class));

            Map<String, DirectoryEntryFile> files = files(root);
            assertEquals(List.of("Data/a.txt", "Data/docs/b.txt", "Home/notes.txt"), List.copyOf(files.keySet()));
            assertHash("hello", files.get("Data/a.txt"));
            assertHash("the b file", files.get("Data/docs/b.txt"));
            assertHash("notes", files.get("Home/notes.txt"));
            // the excluded node_modules' parent is still there
            assertNotNull(root.getEntry("Home").asDirectory().getEntry("project"));
        }
        String output = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Manifest: 3 files (20 B), 3 hashed (20 B), 0 reused"), output);
        assertTrue(output.contains("Ignored (excluded or .nobackup): 4"), output);
        assertTrue(output.contains("  Data/cache"), output);
    }

    @Test
    void reusesHashesFromTheLastManifest() throws Exception {
        ManifestBuilder.Result first = build();
        Files.move(first.manifest(), stateDir.resolve(ManifestBuilder.LAST_MANIFEST));

        write(data, "docs/b.txt", "the b file, changed");
        write(home, "new.txt", "new");
        ManifestBuilder.Result second = build();
        assertNotEquals(first.snapshotId(), second.snapshotId());
        assertEquals(4L, second.files());
        assertEquals(2L, second.hashedFiles());
        assertEquals("the b file, changed".length() + 3L, second.hashedBytes());
        assertEquals(2L, second.reusedFiles());
        try (BackupFile manifest = BackupFile.open(second.manifest().toFile())) {
            Map<String, DirectoryEntryFile> files = files(manifest.getRootDirectory());
            assertHash("hello", files.get("Data/a.txt"));
            assertHash("the b file, changed", files.get("Data/docs/b.txt"));
            assertHash("notes", files.get("Home/notes.txt"));
            assertHash("new", files.get("Home/new.txt"));
        }
    }

    @Test
    void reusedHashesMatchSourcesByName() throws Exception {
        ManifestBuilder.Result first = build();
        Files.move(first.manifest(), stateDir.resolve(ManifestBuilder.LAST_MANIFEST));
        // Same files, but "Home" now holds what "Data" held: nothing may be reused across sources.
        Source swapped = source("Home", data, List.of("cache"));
        ManifestBuilder.Result second = new ManifestBuilder(stateDir, out).build(List.of(
                new ManifestBuilder.ScanSource(swapped, data.toString(), null, List.of())));
        assertEquals(0L, second.reusedFiles());
        assertEquals(2L, second.hashedFiles());
    }

    @Test
    void unreadableLastManifestMeansHashingEverything() throws Exception {
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve(ManifestBuilder.LAST_MANIFEST), "not a manifest");
        ManifestBuilder.Result result = build();
        assertEquals(3L, result.hashedFiles());
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("Warning: can't read"));
    }

    @Test
    void unreadableFilesAreLeftOutAndReported() throws Exception {
        Path locked = data.resolve("docs/b.txt");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(locked), "running as root");
            ManifestBuilder.Result result = build();
            assertEquals(List.of("Data/docs/b.txt"), result.failedItems());
            assertEquals(2L, result.files());
            assertEquals(10L, result.bytes());
            try (BackupFile manifest = BackupFile.open(result.manifest().toFile())) {
                assertEquals(List.of("Data/a.txt", "Home/notes.txt"),
                        List.copyOf(files(manifest.getRootDirectory()).keySet()));
            }
            String output = outBytes.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Failed (couldn't be read, left out of the backup): 1"), output);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rw-------"));
        }
    }

    @Test
    void printsHashingProgress() throws Exception {
        byte[] big = new byte[64 * 1024 * 1024];
        Files.write(data.resolve("big.bin"), big);
        new ManifestBuilder(stateDir, out, 1L).build(plainSources(List.of()));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).contains("Hashing: "));
    }

    @Test
    void livePaths() {
        assertEquals("/", ManifestBuilder.livePath(Path.of("/")));
        assertEquals("/System/Volumes/Data", ManifestBuilder.livePath(Path.of("/System/Volumes/Data/")));
        assertEquals("/home/user/subvolume", ManifestBuilder.livePath(Path.of("/home/user/subvolume")));
    }

    @Test
    void snapshotInfo() {
        ApfsSnapshot apfs = new ApfsSnapshot(UUID.randomUUID(), UUID.randomUUID(), "com.example.snap", 1234L);
        assertEquals(new ExtraDataSnapshotInfoApfs("com.example.snap", 1234L), ManifestBuilder.snapshotInfo(apfs));
        assertEquals(new ExtraDataSnapshotInfoBtrfs("/var/lib/x/Data-1"),
                ManifestBuilder.snapshotInfo(new BtrfsSnapshot("/var/lib/x/Data-1")));
        assertNull(ManifestBuilder.snapshotInfo(null));
    }

    @Test
    void ntfsSourcesListTheirRootFromTheLiveDrive() {
        Source c = new Source("C", Path.of("/C"), List.of(), ExcludeMatcher.none());
        String shadow = "\\\\?\\GLOBALROOT\\Device\\HarddiskVolumeShadowCopy3";
        ManifestBuilder.ScanSource ntfs = ManifestBuilder.scanSourceFor(
                new SourceSnapshot(new SnapshotVolume(c, diskUtil("ntfs"), "C:\\"), null, shadow));
        assertEquals(shadow, ntfs.scanPath());
        assertEquals("C:\\", ntfs.rootListingPath());
        assertTrue(ntfs.extra().isEmpty());

        BtrfsSnapshot snapshot = new BtrfsSnapshot("/state/snapshots/C-1");
        ManifestBuilder.ScanSource btrfs = ManifestBuilder.scanSourceFor(
                new SourceSnapshot(new SnapshotVolume(c, diskUtil("btrfs"), "/C"), snapshot, snapshot.path()));
        assertNull(btrfs.rootListingPath());
        assertEquals(List.of(new ExtraDataSnapshotInfoBtrfs(snapshot.path())), btrfs.extra());
    }

    private static DiskUtil diskUtil(String filesystem) {
        return new DiskUtil() {
            @Override
            public String filesystem() {
                return filesystem;
            }

            @Override
            public SnapshotSerializer serializer() {
                return null;
            }

            @Override
            public boolean supportsSnapshots() {
                return true;
            }

            @Override
            public boolean snapshotsRequireDestination() {
                return false;
            }

            @Override
            public Map<String, Snapshot> createSnapshots(Collection<String> volumes,
                                                         Function<String, String> destination) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
