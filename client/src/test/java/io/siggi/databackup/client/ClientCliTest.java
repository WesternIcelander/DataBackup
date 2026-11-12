package io.siggi.databackup.client;

import com.google.gson.JsonObject;
import io.siggi.databackup.client.api.RetryPolicy;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.client.snapshot.SnapshotPlatform;
import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.MountedDisk;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.diskutil.SnapshotSerializer;
import io.siggi.databackup.osutils.OS;
import io.siggi.databackup.server.ServerTestHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCliTest {

    @TempDir
    Path dir;

    private record Result(int exitCode, String out, String err) {
    }

    private ClientPaths defaults() {
        return new ClientPaths(dir.resolve("default-client.json"), dir.resolve("state"));
    }

    private Result run(String... args) {
        return run(new FakeCliPlatform(), args);
    }

    private Result run(SnapshotPlatform platform, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ClientCli.run(args, defaults(), platform,
                config -> ServerApi.builder(config.getServerUrl(), config.getToken())
                        .retryPolicy(new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(20), 2.0))
                        .build(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path writeConfig(String name) throws Exception {
        return writeConfig(name, "http://localhost:1", "t");
    }

    private Path writeConfig(String name, String serverUrl, String token) throws Exception {
        Path file = dir.resolve(name);
        String source = dir.resolve("src").toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(file, "{\"serverUrl\": \"" + serverUrl + "\", \"token\": \"" + token
                + "\", \"machineName\": \"m\", "
                + "\"sources\": [{\"name\": \"Data\", \"path\": \"" + source + "\"}]}", StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void helpPrintsUsage() {
        for (String[] args : new String[][]{{"help"}, {"--help"}, {"-h"}, {"help", "backup"}}) {
            Result r = run(args);
            assertEquals(ClientCli.OK, r.exitCode());
            assertTrue(r.out().startsWith("Usage: databackup"), r.out());
            assertTrue(r.out().contains("snapshots cleanup"), r.out());
            assertTrue(r.out().contains(defaults().configFile().toString()), r.out());
        }
    }

    @Test
    void usageErrorsExitWith2() {
        assertUsageError(run(), "No command given");
        assertUsageError(run("frobnicate"), "Unknown command: frobnicate");
        assertUsageError(run("--verbose", "backup"), "Unknown option: --verbose");
        assertUsageError(run("backup", "--overwrite"), "Unknown option: --overwrite");
        assertUsageError(run("backup", "extra"), "backup takes no arguments");
        assertUsageError(run("list", "a", "b", "c", "d"), "at most 3");
        assertUsageError(run("restore", "m", "b", "/p"), "restore needs");
        assertUsageError(run("snapshots"), "snapshots needs a subcommand");
        assertUsageError(run("snapshots", "list"), "snapshots needs a subcommand");
        assertUsageError(run("--config"), "--config needs a value");
        assertUsageError(run("backup", "--config"), "--config needs a value");
        assertUsageError(run("--config", "a", "backup", "--config", "b"), "--config given more than once");
    }

    private static void assertUsageError(Result r, String message) {
        assertEquals(ClientCli.CONFIG_ERROR, r.exitCode(), r.err());
        assertTrue(r.err().contains(message), r.err());
        assertTrue(r.err().contains("Usage: databackup"), r.err());
    }

    @Test
    void missingDefaultConfigIsAConfigError() {
        Result r = run("backup");
        assertEquals(ClientCli.CONFIG_ERROR, r.exitCode());
        assertTrue(r.err().contains("Config file not found: " + defaults().configFile()), r.err());
    }

    @Test
    void invalidConfigIsAConfigError() throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{\"serverUrl\": \"http://h\"}", StandardCharsets.UTF_8);
        Result r = run("--config", file.toString(), "list");
        assertEquals(ClientCli.CONFIG_ERROR, r.exitCode());
        assertTrue(r.err().contains("Invalid config file " + file), r.err());
    }

    @Test
    void configOptionIsAcceptedBeforeOrAfterTheCommand() throws Exception {
        Path file = writeConfig("custom.json");
        for (String[] args : new String[][]{
                {"--config", file.toString(), "backup"},
                {"--config=" + file, "list"},
                {"list", "machine", "--config", file.toString()},
                {"restore", "m", "latest", "/a", dir.resolve("out").toString(), "--overwrite", "--config=" + file},
                {"snapshots", "cleanup", "--config", file.toString()}}) {
            Result r = run(args);
            // The config must have been read and accepted.
            if (args[0].equals("snapshots")) {
                assertEquals(ClientCli.OK, r.exitCode(), r.err());
                assertTrue(r.out().contains("No leftover snapshots."), r.out());
                continue;
            }
            assertEquals(ClientCli.FAILED, r.exitCode(), r.err());
            if (List.of(args).contains("backup")) {
                // The fake platform says the source doesn't exist.
                assertTrue(r.err().contains("These sources can't be snapshotted"), r.err());
                continue;
            }
            if (List.of(args).contains("list")) {
                // Nothing listens on the configured server.
                assertTrue(r.err().startsWith("List failed: "), r.err());
                continue;
            }
            // Restore: nothing listens on the configured server.
            assertTrue(r.err().startsWith("Restore failed: "), r.err());
        }
    }

    /**
     * A Linux system where {@code dir/src} is a btrfs subvolume, with snapshots kept in memory.
     */
    private static final class FakeCliPlatform implements SnapshotPlatform {
        final Set<String> existing = new HashSet<>();
        final List<String> log = new ArrayList<>();
        String subvolume;
        boolean failDelete;

        @Override
        public OS os() {
            return OS.LINUX;
        }

        @Override
        public List<MountedDisk> mounts() {
            return List.of(new MountedDisk("/dev/sda2", "/", "btrfs", List.of("rw")));
        }

        @Override
        public Path realPath(Path path) throws IOException {
            if (!path.toString().equals(subvolume)) throw new IOException("no such file");
            return path;
        }

        @Override
        public boolean isDirectory(Path path) {
            return path.toString().equals(subvolume);
        }

        @Override
        public String checkBtrfsSubvolume(Path path) {
            return path.toString().equals(subvolume) ? null : "not a subvolume";
        }

        @Override
        public String windowsFileSystemType(Path driveRoot) throws IOException {
            throw new IOException("not Windows");
        }

        @Override
        public DiskUtil diskUtil(String fileSystemType) {
            return fileSystemType.equals("btrfs") ? diskUtil : null;
        }

        final DiskUtil diskUtil = new DiskUtil() {
            @Override
            public String filesystem() {
                return "btrfs";
            }

            @Override
            public SnapshotSerializer serializer() {
                return new SnapshotSerializer() {
                    @Override
                    public Snapshot deserialize(JsonObject object) {
                        return snapshot(object.get("path").getAsString());
                    }

                    @Override
                    public JsonObject serialize(Snapshot snapshot) {
                        JsonObject object = new JsonObject();
                        object.addProperty("path", snapshot.toString());
                        return object;
                    }
                };
            }

            @Override
            public boolean supportsSnapshots() {
                return true;
            }

            @Override
            public boolean snapshotsRequireDestination() {
                return true;
            }

            @Override
            public Map<String, Snapshot> createSnapshots(Collection<String> filesystems,
                                                         Function<String, String> destination) {
                Map<String, Snapshot> result = new HashMap<>();
                for (String fs : filesystems) {
                    String path = destination.apply(fs);
                    try {
                        // Stands in for the snapshot's contents, so the manifest can be built from it.
                        Files.createDirectories(Path.of(path));
                        Files.writeString(Path.of(path, "hello.txt"), "hello");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    existing.add(path);
                    log.add("create " + path);
                    result.put(fs, snapshot(path));
                }
                return result;
            }
        };

        Snapshot snapshot(String path) {
            return new Snapshot() {
                @Override
                public DiskUtil diskUtil() {
                    return diskUtil;
                }

                @Override
                public String mountedPath() {
                    return path;
                }

                @Override
                public boolean isMounted() {
                    return existing.contains(path);
                }

                @Override
                public String mount() {
                    return path;
                }

                @Override
                public void unmount() {
                }

                @Override
                public void delete() throws SnapshotException {
                    if (failDelete) throw new SnapshotException("device busy");
                    existing.remove(path);
                    log.add("delete " + path);
                }

                @Override
                public boolean isValid() {
                    return existing.contains(path);
                }

                @Override
                public String toString() {
                    return path;
                }
            };
        }
    }

    @Test
    void backupTakesSnapshotsUploadsAndDeletesThem() throws Exception {
        try (ServerTestHarness harness = ServerTestHarness.start()) {
            String token = harness.createToken("upload:m", "read:m");
            Path file = writeConfig("custom.json", harness.uri("/").toString(), token);
            FakeCliPlatform platform = new FakeCliPlatform();
            platform.subvolume = dir.resolve("src").toAbsolutePath().toString();
            Result r = run(platform, "--config", file.toString(), "backup");
            assertEquals(ClientCli.OK, r.exitCode(), r.err());
            assertTrue(r.out().contains("Manifest: 1 files"), r.out());
            assertTrue(r.out().matches("(?s).*\\nBackup \\d{4}-\\d\\d-\\d\\d-\\d\\d-\\d\\d-\\d\\d complete: 1 files "
                    + "\\(5 B\\), 5 B uploaded, took \\d+\\.\\ds\\.\\R"), r.out());
            assertFalse(Files.exists(dir.resolve("state").resolve("pending-manifest")));
            assertTrue(Files.isRegularFile(dir.resolve("state").resolve("last-manifest")));
            assertEquals(2, platform.log.size(), platform.log.toString());
            assertTrue(platform.log.get(0).startsWith("create " + dir.resolve("state").resolve("snapshots")
                    .resolve("Data-")), platform.log.toString());
            assertEquals(platform.log.get(0).replace("create", "delete"), platform.log.get(1));
            assertTrue(platform.existing.isEmpty());
            assertTrue(r.out().contains("Snapshot of Data"), r.out());
            assertTrue(r.out().contains("Deleted snapshot of Data"), r.out());
            // The snapshot is deleted only after the commit.
            assertTrue(r.out().indexOf("Committing...") < r.out().indexOf("Deleted snapshot of Data"), r.out());
            assertFalse(Files.exists(dir.resolve("state").resolve("snapshots.json")));
            HttpResponse<String> listing = harness.get("/backups/m/?json", token);
            assertEquals(200, listing.statusCode());
            assertTrue(listing.body().contains("\"name\""), listing.body());
        }
    }

    @Test
    void backupFailsCleanlyWhenTheServerIsUnreachable() throws Exception {
        Path file = writeConfig("custom.json");
        FakeCliPlatform platform = new FakeCliPlatform();
        platform.subvolume = dir.resolve("src").toAbsolutePath().toString();
        Result r = run(platform, "--config", file.toString(), "backup");
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().startsWith("Backup failed: "), r.err());
        assertTrue(Files.isRegularFile(dir.resolve("state").resolve("pending-manifest")));
        assertFalse(Files.exists(dir.resolve("state").resolve("last-manifest")));
        assertTrue(platform.existing.isEmpty());
    }

    @Test
    void failedDeleteIsReportedAndCleanedUpLater() throws Exception {
        Path file = writeConfig("custom.json");
        FakeCliPlatform platform = new FakeCliPlatform();
        platform.subvolume = dir.resolve("src").toAbsolutePath().toString();
        platform.failDelete = true;
        Result r = run(platform, "--config", file.toString(), "backup");
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().contains("Some snapshots couldn't be deleted"), r.err());
        assertTrue(r.err().contains("device busy"), r.err());
        assertEquals(1, platform.existing.size());
        assertTrue(Files.exists(dir.resolve("state").resolve("snapshots.json")));

        Result cleanup = run(platform, "--config", file.toString(), "snapshots", "cleanup");
        assertEquals(ClientCli.FAILED, cleanup.exitCode());
        assertTrue(cleanup.err().contains("couldn't be deleted"), cleanup.err());

        platform.failDelete = false;
        cleanup = run(platform, "--config", file.toString(), "snapshots", "cleanup");
        assertEquals(ClientCli.OK, cleanup.exitCode(), cleanup.err());
        assertTrue(cleanup.out().contains("Cleaned up 1 leftover snapshot."), cleanup.out());
        assertTrue(platform.existing.isEmpty());
        assertFalse(Files.exists(dir.resolve("state").resolve("snapshots.json")));
    }

    @Test
    void backupCleansUpLeftoversFirst() throws Exception {
        Path file = writeConfig("custom.json");
        FakeCliPlatform platform = new FakeCliPlatform();
        platform.subvolume = dir.resolve("src").toAbsolutePath().toString();
        platform.failDelete = true;
        run(platform, "--config", file.toString(), "backup");
        String leftover = platform.existing.iterator().next();
        platform.failDelete = false;
        platform.log.clear();
        Result r = run(platform, "--config", file.toString(), "backup");
        assertEquals("delete " + leftover, platform.log.get(0), platform.log.toString());
        assertTrue(r.out().contains("Deleted leftover snapshot of Data"), r.out());
        assertTrue(platform.existing.isEmpty());
    }

    @Test
    void backupRefusesSourcesThatCantBeSnapshotted() throws Exception {
        Path file = writeConfig("custom.json");
        FakeCliPlatform platform = new FakeCliPlatform();
        platform.subvolume = "/somewhere/else";
        Result r = run(platform, "--config", file.toString(), "backup");
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().contains("These sources can't be snapshotted:"), r.err());
        assertTrue(r.err().contains("Source \"Data\""), r.err());
        assertTrue(platform.log.isEmpty());
    }

    @Test
    void doubleDashEndsOptions() throws Exception {
        Path file = writeConfig("custom.json");
        Result r = run("--config", file.toString(), "restore", "m", "b", "--", "--weird-path", "dest");
        assertEquals(ClientCli.FAILED, r.exitCode(), r.err());
        // Got past argument parsing: nothing listens on the configured server.
        assertTrue(r.err().startsWith("Restore failed: "), r.err());
    }
}
