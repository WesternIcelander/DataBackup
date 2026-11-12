package io.siggi.databackup.server;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.server.repository.BackupItem;
import io.siggi.databackup.server.repository.BackupRepositories;
import io.siggi.databackup.server.repository.BackupRepository;
import io.siggi.databackup.server.retention.Pruner;
import io.siggi.databackup.util.TimeUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PruneTest {

    private static final long HOUR = 3600_000L;
    private static final long BASE = TimeUtil.fromString("2026-03-10-12-00-00");

    @TempDir
    Path dataDir;

    private Path writeBackup(String machine, long time) throws Exception {
        Path dir = dataDir.resolve("backups").resolve(machine);
        Files.createDirectories(dir);
        DirectoryEntryDirectoryMemory root = new DirectoryEntryDirectoryMemory("");
        root.getExtra().add(new ExtraDataSnapshotId(UUID.randomUUID()));
        Path file = dir.resolve(TimeUtil.toString(time));
        BackupFile.writeToFile(file.toFile(), root);
        return file;
    }

    private void writeConfig(String json) throws Exception {
        Files.writeString(dataDir.resolve(ServerConfig.FILE_NAME), json, StandardCharsets.UTF_8);
    }

    private static List<String> names(BackupRepository repository) {
        List<String> names = new ArrayList<>();
        for (BackupItem item : repository.getAllItems()) names.add(item.getName());
        return names;
    }

    private record Result(int exitCode, String out, String err) {
    }

    private Result cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ServerCli.run(args, dataDir.toFile(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cliDryRunListsButDeletesNothing() throws Exception {
        writeConfig("{\"retention\": {\"keepLast\": 2, \"keepDaily\": 0, \"keepWeekly\": 0, \"keepMonthly\": 0}}");
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 4; i++) files.add(writeBackup("laptop", BASE + i * HOUR));

        Result result = cli("prune", "--dry-run");
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("laptop: keeping 2, would delete 2"), result.out());
        assertTrue(result.out().contains("would delete " + TimeUtil.toString(BASE)), result.out());
        assertTrue(result.out().contains("would delete " + TimeUtil.toString(BASE + HOUR)), result.out());
        for (Path file : files) assertTrue(Files.exists(file), file + " still there");
    }

    @Test
    void cliPruneDeletesWhatThePolicyDoesNotKeepPerMachine() throws Exception {
        writeConfig("""
                {"retention": {"keepLast": 2, "keepDaily": 0, "keepWeekly": 0, "keepMonthly": 0},
                 "machineRetention": {"desktop": {"keepLast": 0}}}""");
        List<Path> laptop = new ArrayList<>();
        List<Path> desktop = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            laptop.add(writeBackup("laptop", BASE + i * HOUR));
            desktop.add(writeBackup("desktop", BASE + i * HOUR));
        }
        Path pending = dataDir.resolve("backups/laptop/.pending/" + UUID.randomUUID());
        Files.createDirectories(pending.getParent());
        Files.writeString(pending, "pending");

        Result result = cli("prune");
        assertEquals(0, result.exitCode(), result.err());
        assertTrue(result.out().contains("laptop: keeping 2, deleted 2"), result.out());
        assertTrue(result.out().contains("desktop: keeping 1, deleted 3"), result.out());

        assertFalse(Files.exists(laptop.get(0)));
        assertFalse(Files.exists(laptop.get(1)));
        assertTrue(Files.exists(laptop.get(2)));
        assertTrue(Files.exists(laptop.get(3)));
        for (int i = 0; i < 3; i++) assertFalse(Files.exists(desktop.get(i)));
        assertTrue(Files.exists(desktop.get(3)), "the newest backup is never deleted");
        assertTrue(Files.exists(pending), "pending manifests are left alone");

        Result again = cli("prune");
        assertEquals(0, again.exitCode(), again.err());
        assertTrue(again.out().contains("laptop: keeping 2, deleted 0"), again.out());
    }

    @Test
    void cliWithNoMachinesOrBadArguments() throws Exception {
        Result none = cli("prune");
        assertEquals(0, none.exitCode(), none.err());
        assertTrue(none.out().contains("No machines."), none.out());
        assertEquals(2, cli("prune", "--force").exitCode());
    }

    @Test
    void cliRejectsAnInvalidConfig() throws Exception {
        writeConfig("{\"retention\": {\"keepLast\": -1}}");
        writeBackup("laptop", BASE);
        Result result = cli("prune");
        assertEquals(1, result.exitCode());
        assertTrue(result.err().contains("keepLast"), result.err());
    }

    @Test
    void serverCleanupPrunesAndReloadsTheRepository() throws Exception {
        // Only keepLast, so the result doesn't depend on the system time zone the server counts days in.
        ServerConfig config = configWith("{\"retention\": {\"keepLast\": 2, \"keepDaily\": 0, \"keepWeekly\": 0,"
                + " \"keepMonthly\": 0}}");
        long day = 24 * HOUR;
        writeBackup("laptop", BASE - 2 * day);
        writeBackup("laptop", BASE - day);
        writeBackup("laptop", BASE - day + HOUR);
        writeBackup("laptop", BASE);
        writeBackup("laptop", BASE + HOUR);
        try (DataBackupServer server = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), null, config)) {
            BackupRepository repository = server.getBackupRepositories().get("laptop");
            assertEquals(5, repository.getAllItems().size());

            List<Pruner.MachineResult> dryRun = server.prune(true);
            assertEquals(1, dryRun.size());
            assertEquals(3, dryRun.get(0).deleted().size());
            assertEquals(5, repository.getAllItems().size(), "a dry run changes nothing");

            server.cleanup();
            assertEquals(List.of(TimeUtil.toString(BASE), TimeUtil.toString(BASE + HOUR)), names(repository));
            try (var files = Files.list(dataDir.resolve("backups/laptop"))) {
                assertEquals(2, files.count());
            }
        }
    }

    @Test
    void repositoryDeleteRemovesManifestsAndReloads() throws Exception {
        writeBackup("laptop", BASE);
        writeBackup("laptop", BASE + HOUR);
        BackupRepositories repositories = new BackupRepositories(dataDir.resolve("backups").toFile());
        BackupRepository repository = repositories.get("laptop");
        BackupItem oldest = repository.getAllItems().get(0);
        assertTrue(repository.delete(List.of(oldest)).isEmpty());
        assertEquals(List.of(TimeUtil.toString(BASE + HOUR)), names(repository));
        assertFalse(oldest.getFile().exists());
        assertEquals(null, repository.get(oldest.getUuid()));
    }

    private ServerConfig configWith(String json) throws Exception {
        writeConfig(json);
        return ServerConfig.load(dataDir.toFile());
    }
}
