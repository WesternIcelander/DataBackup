package io.siggi.databackup.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCliTest {

    private ServerTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    private record Result(int exitCode, String out, String err) {
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ServerCli.run(args, harness.dataDir().toFile(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String idOf(Result created) {
        // "Created token <id> (<name>) ..."
        String line = created.err().lines().filter(l -> l.startsWith("Created token ")).findFirst().orElseThrow();
        return line.substring("Created token ".length(), line.indexOf(' ', "Created token ".length()));
    }

    @Test
    void createdTokenWorksAgainstTheServer() throws Exception {
        Result created = run("token", "create", "--name", "laptop", "--permission", "read:laptop", "upload:laptop");
        assertEquals(0, created.exitCode(), created.err());
        String token = created.out().strip();
        assertFalse(token.isEmpty());
        assertEquals(1, created.out().lines().count(), "stdout holds only the token");

        HttpResponse<String> allowed = harness.get("/backups/laptop/", token);
        assertTrue(allowed.statusCode() == 200 || allowed.statusCode() == 404, "status " + allowed.statusCode());
        assertEquals(403, harness.get("/backups/desktop/", token).statusCode());
    }

    @Test
    void listShowsTokensWithoutSecrets() {
        Result a = run("token", "create", "--name", "alpha", "--permission", "admin");
        Result b = run("token", "create", "--name=beta", "--permission=read:*", "--permission", "globalread");
        assertEquals(0, a.exitCode());
        assertEquals(0, b.exitCode());

        Result list = run("token", "list");
        assertEquals(0, list.exitCode());
        assertTrue(list.out().startsWith("ID"), list.out());
        assertTrue(list.out().contains(idOf(a)));
        assertTrue(list.out().contains(idOf(b)));
        assertTrue(list.out().contains("alpha"));
        assertTrue(list.out().contains("read:*,globalread"));
        assertFalse(list.out().contains(a.out().strip()), "list must not print tokens");
        assertFalse(list.out().contains(b.out().strip()), "list must not print tokens");
    }

    @Test
    void listWithNoTokens() {
        Result list = run("token", "list");
        assertEquals(0, list.exitCode());
        assertEquals("No tokens.", list.out().strip());
    }

    @Test
    void revokedTokenIsRejected() throws Exception {
        Result created = run("token", "create", "--name", "temp", "--permission", "read:*");
        String token = created.out().strip();
        assertEquals(200, harness.get("/backups/", token).statusCode());

        Result revoke = run("token", "revoke", idOf(created));
        assertEquals(0, revoke.exitCode(), revoke.err());
        assertTrue(revoke.out().contains("temp"));
        assertEquals(401, harness.get("/backups/", token).statusCode());
        assertFalse(run("token", "list").out().contains(idOf(created)));

        Result again = run("token", "revoke", idOf(created));
        assertEquals(ServerCli.FAILED, again.exitCode());
    }

    @Test
    void badArgumentsAreUsageErrors() {
        assertEquals(ServerCli.USAGE, run("token", "create", "--permission", "admin").exitCode());
        assertEquals(ServerCli.USAGE, run("token", "create", "--name", "x").exitCode());
        assertEquals(ServerCli.USAGE, run("token", "create", "--name", "x", "--permission").exitCode());
        assertEquals(ServerCli.USAGE, run("token", "create", "--name", "x", "--permission", "root").exitCode());
        assertEquals(ServerCli.USAGE, run("token", "create", "--name", "x", "--permission", "read:../x").exitCode());
        assertEquals(ServerCli.USAGE, run("token", "revoke").exitCode());
        assertEquals(ServerCli.USAGE, run("token").exitCode());
        assertEquals(ServerCli.USAGE, run("bogus").exitCode());
        assertEquals(0, run("help").exitCode());
        assertEquals("No tokens.", run("token", "list").out().strip(), "no token was created by a bad command");
    }

    @Test
    void permissionValidation() {
        assertTrue(ServerCli.isValidPermission("admin"));
        assertTrue(ServerCli.isValidPermission("globalread"));
        assertTrue(ServerCli.isValidPermission("read:*"));
        assertTrue(ServerCli.isValidPermission("upload:my-laptop.local"));
        assertFalse(ServerCli.isValidPermission("read:"));
        assertFalse(ServerCli.isValidPermission("read:.hidden"));
        assertFalse(ServerCli.isValidPermission("write:x"));
        assertFalse(ServerCli.isValidPermission("*"));
    }
}
