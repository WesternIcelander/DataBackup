package io.siggi.databackup.client;

import io.siggi.databackup.client.BackupCommand.Outcome;
import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.client.ManifestBuilder.ScanSource;
import io.siggi.databackup.client.api.BackupInfo;
import io.siggi.databackup.client.api.DirectoryListingEntry;
import io.siggi.databackup.client.api.RetryPolicy;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.server.ServerTestHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code list} command against a real server from {@link ServerTestHarness}, with backups made through the
 * test-only scan path.
 */
class ListCommandTest {

    private static final String MACHINE = "laptop";
    /**
     * 2023-11-14 22:13:20.123 UTC.
     */
    private static final long MODIFIED = 1_700_000_000_123L;

    @TempDir
    Path dir;

    private ServerTestHarness harness;
    private String token;
    private Path data;
    private ClientConfig config;

    private record Result(int exitCode, String out, String err) {
    }

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        token = harness.createToken("upload:" + MACHINE, "read:" + MACHINE, "read:desktop");
        data = dir.resolve("data");
        Files.createDirectories(data.resolve("Sub dir"));
        Path file = data.resolve("notes.txt");
        Files.writeString(file, "hello");
        Files.setLastModifiedTime(file, FileTime.fromMillis(MODIFIED));
        Files.writeString(data.resolve("Sub dir").resolve("inner.txt"), "inner file");
        config = writeConfig(token);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    private ClientConfig writeConfig(String token) throws Exception {
        Path file = dir.resolve("client-" + System.nanoTime() + ".json");
        String source = data.toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(file, "{\"serverUrl\": \"" + harness.uri("/") + "\", \"token\": \"" + token
                + "\", \"machineName\": \"" + MACHINE + "\", "
                + "\"sources\": [{\"name\": \"Data\", \"path\": \"" + source + "\"}]}", StandardCharsets.UTF_8);
        return ClientConfig.load(file, dir.resolve("state"));
    }

    private static ServerApi api(ClientConfig config) {
        return ServerApi.builder(config.getServerUrl(), config.getToken())
                .retryPolicy(new RetryPolicy(2, Duration.ofMillis(10), Duration.ofMillis(20), 2.0))
                .build();
    }

    private Outcome backup() throws Exception {
        ScanSource scan = new ScanSource(new Source("Data", data, List.of(), ExcludeMatcher.none()), data.toString(),
                null, List.of());
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new BackupCommand(api(config), MACHINE, dir.resolve("state"), quiet).backup(List.of(scan));
    }

    private Result list(String... args) {
        return list(config, args);
    }

    private Result list(ClientConfig config, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Function<ClientConfig, ServerApi> factory = ListCommandTest::api;
        int code = ListCommand.run(config, List.of(args), factory, ZoneOffset.UTC,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void listsReadableMachines() throws Exception {
        backup();
        Result r = list();
        assertEquals(ClientCli.OK, r.exitCode(), r.err());
        // "desktop" is readable but has no backups yet, so the server doesn't know it.
        assertEquals(MACHINE + System.lineSeparator(), r.out());
    }

    @Test
    void listsBackupsWithTheirTimes() throws Exception {
        Outcome first = backup();
        Outcome second = backup();
        Result r = list(MACHINE);
        assertEquals(ClientCli.OK, r.exitCode(), r.err());
        List<BackupInfo> backups = api(config).listBackups(MACHINE);
        String[] lines = r.out().split("\\R");
        assertEquals(2, lines.length, r.out());
        assertTrue(lines[0].startsWith(first.backupName() + "  "), r.out());
        assertTrue(lines[1].startsWith(second.backupName() + "  "), r.out());
        // The backup name is the UTC time, so with UTC as the zone the two agree.
        String name = first.backupName();
        String expectedTime = name.substring(0, 10) + " " + name.substring(11).replace('-', ':') + " +00:00";
        assertEquals(first.backupName() + "  " + expectedTime, lines[0]);
        assertEquals(backups.get(0).name(), first.backupName());
    }

    @Test
    void listsADirectory() throws Exception {
        Outcome outcome = backup();
        Result root = list(MACHINE, outcome.backupName());
        assertEquals(ClientCli.OK, root.exitCode(), root.err());
        assertTrue(root.out().matches("d .* Data/\\R"), root.out());

        Result r = list(MACHINE, outcome.backupName(), "Data");
        assertEquals(ClientCli.OK, r.exitCode(), r.err());
        String[] lines = r.out().split("\\R");
        assertEquals(2, lines.length, r.out());
        String notes = List.of(lines).stream().filter(l -> l.endsWith(" notes.txt")).findFirst().orElseThrow();
        assertTrue(notes.startsWith("f "), notes);
        assertTrue(notes.matches("f \\S+ +5  2023-11-14 22:13:20 \\+00:00 +notes\\.txt"), notes);
        assertTrue(List.of(lines).stream().anyMatch(l -> l.startsWith("d ") && l.endsWith(" Sub dir/")), r.out());

        // Leading, trailing and doubled slashes are fine, and spaces in names work.
        Result sub = list(MACHINE, outcome.backupName(), "/Data//Sub dir/");
        assertEquals(ClientCli.OK, sub.exitCode(), sub.err());
        assertTrue(sub.out().matches("f \\S+ +10  .* inner\\.txt\\R"), sub.out());
    }

    @Test
    void latestIsTheNewestBackup() throws Exception {
        backup();
        Files.writeString(data.resolve("added.txt"), "only in the second backup");
        backup();
        Result r = list(MACHINE, ListCommand.LATEST, "Data");
        assertEquals(ClientCli.OK, r.exitCode(), r.err());
        assertTrue(r.out().contains(" added.txt"), r.out());
    }

    @Test
    void latestWithoutBackupsFails() throws Exception {
        // The server doesn't know a machine until it has a backup.
        Result r = list("desktop", ListCommand.LATEST);
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().startsWith("List failed: the server answered HTTP 404: Unknown machine"), r.err());
    }

    @Test
    void aFilePathListsJustThatEntry() throws Exception {
        Outcome outcome = backup();
        Result r = list(MACHINE, outcome.backupName(), "Data/notes.txt");
        assertEquals(ClientCli.OK, r.exitCode(), r.err());
        assertTrue(r.out().matches("f .* notes\\.txt\\R"), r.out());
    }

    @Test
    void symlinksShowTheirTarget() throws Exception {
        try {
            Files.createSymbolicLink(data.resolve("link"), Path.of("notes.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "symlinks not supported here: " + e);
        }
        Outcome outcome = backup();
        Result r = list(MACHINE, outcome.backupName(), "Data");
        assertTrue(r.out().contains(" link -> notes.txt"), r.out());
        Result single = list(MACHINE, outcome.backupName(), "Data/link");
        assertTrue(single.out().matches("l .* link -> notes\\.txt\\R"), single.out());
    }

    @Test
    void missingPathsAndBackupsFail() throws Exception {
        Outcome outcome = backup();
        Result r = list(MACHINE, outcome.backupName(), "Data/nope.txt");
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().contains("404"), r.err());
        assertTrue(r.err().contains("No such path"), r.err());

        r = list(MACHINE, outcome.backupName(), "Data/nope/deeper");
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().contains("No such path"), r.err());

        r = list(MACHINE, "2001-01-01-00-00-00");
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().contains("Unknown backup"), r.err());
    }

    @Test
    void unreadableMachineFailsWith403() throws Exception {
        backup();
        ClientConfig other = writeConfig(harness.createToken("read:desktop"));
        Result r = list(other, MACHINE);
        assertEquals(ClientCli.FAILED, r.exitCode());
        assertTrue(r.err().startsWith("List failed: the server answered HTTP 403"), r.err());
    }

    @Test
    void theCliRunsList() throws Exception {
        backup();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ClientCli.run(new String[]{"--config", config.getConfigFile().toString(), "list"},
                new ClientPaths(dir.resolve("default.json"), dir.resolve("state")),
                io.siggi.databackup.client.snapshot.SnapshotPlatform.system(), ListCommandTest::api,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        assertEquals(ClientCli.OK, code, err.toString(StandardCharsets.UTF_8));
        assertEquals(MACHINE + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void timesUseTheGivenZone() {
        ListCommand command = new ListCommand(null, ZoneId.of("Europe/Berlin"),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        assertEquals("2023-11-14 23:13:20 +01:00", command.formatTime(MODIFIED));
        assertEquals("? -                    -  -                          odd",
                command.formatEntry(new DirectoryListingEntry("odd", DirectoryListingEntry.Type.OTHER, null, null,
                        null, null, null)));
    }
}
