package io.siggi.databackup.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ClientCli.run(args, defaults(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Path writeConfig(String name) throws Exception {
        Path file = dir.resolve(name);
        String source = dir.resolve("src").toAbsolutePath().toString().replace("\\", "\\\\");
        Files.writeString(file, "{\"serverUrl\": \"http://localhost:1\", \"token\": \"t\", \"machineName\": \"m\", "
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
            // The commands themselves come in later steps; the config must have been read and accepted.
            assertEquals(ClientCli.FAILED, r.exitCode(), r.err());
            assertTrue(r.err().contains("not implemented yet"), r.err());
            assertTrue(r.err().contains(file.toString()), r.err());
        }
    }

    @Test
    void doubleDashEndsOptions() throws Exception {
        Path file = writeConfig("custom.json");
        Result r = run("--config", file.toString(), "list", "m", "b", "--", "--weird-path");
        assertEquals(ClientCli.FAILED, r.exitCode(), r.err());
        assertTrue(r.err().contains("not implemented yet"), r.err());
    }
}
