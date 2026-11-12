package io.siggi.databackup.server;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.util.ObjectWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read-only web UI under {@code /ui/}.
 */
class WebUiTest {

    private static final String OLD_BACKUP = "2026-01-02-03-04-05";
    private static final String BACKUP = "2026-02-03-04-05-06";
    private static final String SCRIPT_NAME = "<script>alert(1)</script>";
    private static final byte[] HELLO = "hello web".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAGE = "<html><script>alert(2)</script></html>".getBytes(StandardCharsets.UTF_8);

    private ServerTestHarness harness;
    private String laptopToken;

    @BeforeEach
    void setUp() throws Exception {
        harness = ServerTestHarness.start();
        laptopToken = harness.createToken("read:laptop");
        Path backups = harness.dataDir().resolve("backups");
        Path laptop = backups.resolve("laptop");
        Path desktop = backups.resolve("desktop");
        Files.createDirectories(laptop);
        Files.createDirectories(desktop);
        writeBackup(laptop.resolve(OLD_BACKUP), dir("", dir("Data")));
        DirectoryEntryFile hello = file("hello.txt", HELLO);
        hello.getExtra().add(new ExtraDataPosixPermissions(0640, 501, 20));
        writeBackup(laptop.resolve(BACKUP), dir("",
                dir("Data",
                        hello,
                        file("page.html", PAGE),
                        file("my file ü.txt", HELLO),
                        file(SCRIPT_NAME, HELLO),
                        new DirectoryEntrySymlink("link", "<b>target</b>"),
                        dir("Sub dir", file("deep.txt", HELLO)),
                        dir("Empty"))));
        writeBackup(desktop.resolve(BACKUP), dir("", dir("Secret", file("secret.txt", HELLO))));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    // --- building backups ---

    private static DirectoryEntryFile file(String name, byte[] data) {
        DirectoryEntryFile file = new DirectoryEntryFile(name, 1_700_000_000_000L, data.length);
        InlinedFileContent content = new InlinedFileContent();
        content.setOffset(0);
        content.setData(data);
        content.setLength(data.length);
        file.getFileContents().add(content);
        return file;
    }

    private static DirectoryEntryDirectoryMemory dir(String name, DirectoryEntry... entries) throws IOException {
        DirectoryEntryDirectoryMemory dir = new DirectoryEntryDirectoryMemory(name);
        try (ObjectWriter<DirectoryEntry> writer = dir.updateEntries()) {
            for (DirectoryEntry entry : entries) writer.write(entry);
        }
        return dir;
    }

    private static void writeBackup(Path path, DirectoryEntryDirectoryMemory root) throws IOException {
        root.getExtra().add(new ExtraDataSnapshotId(UUID.randomUUID()));
        BackupFile.writeToFile(path.toFile(), root);
    }

    // --- requests ---

    private HttpResponse<String> uiGet(String path, String token) throws Exception {
        HttpRequest.Builder builder = harness.request(path, null).GET();
        if (token != null) builder.header("Cookie", "token=" + token);
        return harness.send(builder.build());
    }

    private HttpResponse<String> login(String token, String... headers) throws Exception {
        HttpRequest.Builder builder = harness.request("/ui/login", null)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("token=" + WebUi.percentEncode(token)));
        if (headers.length > 0) builder.headers(headers);
        return harness.send(builder.build());
    }

    private static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location").orElse(null);
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static void assertHtmlPage(int status, HttpResponse<String> response) {
        assertEquals(status, response.statusCode(), response::body);
        assertTrue(header(response, "Content-Type").startsWith("text/html"), header(response, "Content-Type"));
        assertTrue(header(response, "Content-Security-Policy").startsWith("default-src 'self'"),
                header(response, "Content-Security-Policy"));
        assertEquals("nosniff", header(response, "X-Content-Type-Options"));
        // No JavaScript, and no inline styles (the policy wouldn't allow them).
        assertFalse(response.body().contains("<script"), response.body());
        assertFalse(response.body().contains("<style"), response.body());
        assertFalse(response.body().contains("style="), response.body());
    }

    // --- login and logout ---

    @Test
    void loginPageNeedsNoToken() throws Exception {
        HttpResponse<String> response = uiGet("/ui/login", null);
        assertHtmlPage(200, response);
        assertTrue(response.body().contains("<form method=\"post\" action=\"/ui/login\">"), response.body());
        assertTrue(response.body().contains("name=\"token\""), response.body());
        // The stylesheet the pages link to is served without a token, as CSS.
        HttpResponse<String> css = uiGet("/ui/style.css", null);
        assertEquals(200, css.statusCode());
        assertTrue(header(css, "Content-Type").startsWith("text/css"));
    }

    @Test
    void pagesRedirectToLoginWithoutAValidToken() throws Exception {
        for (String path : new String[]{"/ui/", "/ui/backups/laptop/", "/ui/backups/laptop/" + BACKUP + "/Data/"}) {
            HttpResponse<String> response = uiGet(path, null);
            assertEquals(303, response.statusCode(), path);
            assertEquals("/ui/login", location(response), path);
        }
        // A revoked or made-up cookie is cleared on the way to the login page.
        HttpResponse<String> stale = uiGet("/ui/", "not-a-real-token");
        assertEquals(303, stale.statusCode());
        assertEquals("/ui/login", location(stale));
        assertTrue(header(stale, "Set-Cookie").contains("Max-Age=0"), header(stale, "Set-Cookie"));
        assertEquals("/ui/", location(uiGet("/ui", null)));
    }

    @Test
    void loginSetsTheTokenCookie() throws Exception {
        HttpResponse<String> response = login(laptopToken);
        assertEquals(303, response.statusCode(), response.body());
        assertEquals("/ui/", location(response));
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertEquals(1, cookies.size(), cookies::toString);
        String cookie = cookies.get(0);
        assertTrue(cookie.startsWith("token=" + laptopToken + ";"), cookie);
        assertTrue(cookie.contains("; HttpOnly"), cookie);
        assertTrue(cookie.contains("; SameSite=Strict"), cookie);
        assertTrue(cookie.contains("; Path=/"), cookie);
        assertFalse(cookie.contains("Secure"), cookie);
        // The cookie value works for the UI and for the API's download route.
        String value = cookie.substring("token=".length(), cookie.indexOf(';'));
        assertHtmlPage(200, uiGet("/ui/", value));
        // Logged in, the login page sends the browser on to the machine list.
        assertEquals("/ui/", location(uiGet("/ui/login", value)));
    }

    @Test
    void loginBehindHttpsSetsASecureCookie() throws Exception {
        HttpResponse<String> response = login(laptopToken, "X-Forwarded-Proto", "https");
        assertEquals(303, response.statusCode());
        assertTrue(header(response, "Set-Cookie").contains("; Secure"), header(response, "Set-Cookie"));
    }

    @Test
    void tokensWithSpecialCharactersSurviveTheCookie() throws Exception {
        String token = "a+b/c=d%e f";
        harness.writeToken(token, "odd", "read:laptop");
        HttpResponse<String> response = login(token);
        assertEquals(303, response.statusCode(), response.body());
        String cookie = header(response, "Set-Cookie");
        String value = cookie.substring("token=".length(), cookie.indexOf(';'));
        assertHtmlPage(200, uiGet("/ui/backups/laptop/", value));
    }

    @Test
    void loginRejectsInvalidAndEmptyTokens() throws Exception {
        HttpResponse<String> invalid = login("nope");
        assertHtmlPage(401, invalid);
        assertTrue(invalid.body().contains("isn&#39;t valid"), invalid.body());
        assertTrue(invalid.headers().allValues("Set-Cookie").isEmpty());
        HttpResponse<String> empty = login("");
        assertHtmlPage(400, empty);
        assertTrue(empty.headers().allValues("Set-Cookie").isEmpty());
    }

    @Test
    void logoutClearsTheCookie() throws Exception {
        HttpResponse<String> page = uiGet("/ui/", laptopToken);
        assertTrue(page.body().contains("href=\"/ui/logout\""), page.body());
        HttpResponse<String> response = uiGet("/ui/logout", laptopToken);
        assertEquals(303, response.statusCode());
        assertEquals("/ui/login", location(response));
        String cookie = header(response, "Set-Cookie");
        assertTrue(cookie.startsWith("token=;"), cookie);
        assertTrue(cookie.contains("Max-Age=0"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
    }

    // --- pages ---

    @Test
    void machineListShowsOnlyReadableMachines() throws Exception {
        HttpResponse<String> laptopOnly = uiGet("/ui/", laptopToken);
        assertHtmlPage(200, laptopOnly);
        assertTrue(laptopOnly.body().contains("href=\"/ui/backups/laptop/\""), laptopOnly.body());
        assertFalse(laptopOnly.body().contains("desktop"), laptopOnly.body());

        HttpResponse<String> all = uiGet("/ui/", harness.createToken("read:*"));
        assertTrue(all.body().contains("href=\"/ui/backups/laptop/\""), all.body());
        assertTrue(all.body().contains("href=\"/ui/backups/desktop/\""), all.body());

        HttpResponse<String> none = uiGet("/ui/", harness.createToken("upload:laptop"));
        assertHtmlPage(200, none);
        assertFalse(none.body().contains("/ui/backups/"), none.body());
        assertTrue(none.body().contains("no machines"), none.body());
    }

    @Test
    void otherMachinesAreForbidden() throws Exception {
        HttpResponse<String> backups = uiGet("/ui/backups/desktop/", laptopToken);
        assertHtmlPage(403, backups);
        assertFalse(backups.body().contains(BACKUP), backups.body());
        assertHtmlPage(403, uiGet("/ui/backups/desktop/" + BACKUP + "/Secret/", laptopToken));
        // Forbidden comes before not found, so machine names can't be probed.
        assertHtmlPage(403, uiGet("/ui/backups/ghost/", laptopToken));
        assertEquals(403, uiGet("/backups/desktop/" + BACKUP + "/Secret/secret.txt", laptopToken).statusCode());
    }

    @Test
    void backupListIsNewestFirst() throws Exception {
        HttpResponse<String> response = uiGet("/ui/backups/laptop/", laptopToken);
        assertHtmlPage(200, response);
        String body = response.body();
        int newest = body.indexOf("href=\"/ui/backups/laptop/" + BACKUP + "/\"");
        int oldest = body.indexOf("href=\"/ui/backups/laptop/" + OLD_BACKUP + "/\"");
        assertTrue(newest != -1 && oldest != -1 && newest < oldest, body);
        assertTrue(body.contains("2026-02-03 04:05:06 UTC"), body);
        // Breadcrumbs back to the machine list.
        assertTrue(body.contains("<a href=\"/ui/\">Machines</a>"), body);
        // Without the trailing slash, redirect.
        assertEquals("/ui/backups/laptop/", location(uiGet("/ui/backups/laptop", laptopToken)));
    }

    @Test
    void directoryBrowserShowsEntriesAndLinksToDownloads() throws Exception {
        String base = "/ui/backups/laptop/" + BACKUP;
        HttpResponse<String> root = uiGet(base + "/", laptopToken);
        assertHtmlPage(200, root);
        assertTrue(root.body().contains("href=\"" + base + "/Data/\""), root.body());

        HttpResponse<String> response = uiGet(base + "/Data/", laptopToken);
        assertHtmlPage(200, response);
        String body = response.body();
        // Breadcrumbs: machines, machine, backup, then the current folder unlinked.
        assertTrue(body.contains("<a href=\"/ui/backups/laptop/\">laptop</a>"), body);
        assertTrue(body.contains("<a href=\"" + base + "/\">" + BACKUP + "</a><span>/</span>Data</nav>"), body);
        // Directories link into the browser, files to the existing download route.
        assertTrue(body.contains("href=\"" + base + "/Data/Sub%20dir/\""), body);
        assertTrue(body.contains("href=\"/backups/laptop/" + BACKUP + "/Data/hello.txt\""), body);
        assertTrue(body.contains("href=\"/backups/laptop/" + BACKUP + "/Data/my%20file%20%C3%BC.txt\""), body);
        assertTrue(body.contains(">my file ü.txt</a>"), body);
        // Size, modified time and permissions.
        assertTrue(body.contains("title=\"" + HELLO.length + " bytes\">" + HELLO.length + " B"), body);
        assertTrue(body.contains("2023-11-14 22:13:20 UTC"), body);
        assertTrue(body.contains("<code>rw-r-----</code>"), body);
        // Directories come first.
        assertTrue(body.indexOf("Empty/</a>") < body.indexOf("hello.txt</a>"), body);

        HttpResponse<String> empty = uiGet(base + "/Data/Empty/", laptopToken);
        assertHtmlPage(200, empty);
        assertTrue(empty.body().contains("empty"), empty.body());
        assertEquals(base + "/Data/Sub%20dir/", location(uiGet(base + "/Data/Sub%20dir", laptopToken)));
        assertHtmlPage(404, uiGet(base + "/Data/nothing/", laptopToken));
        assertHtmlPage(404, uiGet("/ui/backups/laptop/2020-01-01-00-00-00/", laptopToken));
        assertHtmlPage(400, uiGet(base + "/Data/%2e%2e/", laptopToken));
    }

    @Test
    void downloadLinksWorkWithTheCookie() throws Exception {
        String href = "/backups/laptop/" + BACKUP + "/Data/my%20file%20%C3%BC.txt";
        HttpResponse<byte[]> download = harness.send(harness.request(href, null)
                .header("Cookie", "token=" + laptopToken).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, download.statusCode());
        assertArrayEquals(HELLO, download.body());
        // A file's UI path redirects to its download.
        assertEquals("/backups/laptop/" + BACKUP + "/Data/hello.txt",
                location(uiGet("/ui/backups/laptop/" + BACKUP + "/Data/hello.txt", laptopToken)));
        // Stored HTML still never comes back as text/html.
        HttpResponse<String> page = uiGet("/backups/laptop/" + BACKUP + "/Data/page.html", laptopToken);
        assertEquals(200, page.statusCode());
        assertFalse(header(page, "Content-Type").contains("html"), header(page, "Content-Type"));
        assertEquals("nosniff", header(page, "X-Content-Type-Options"));
    }

    @Test
    void namesAreEscaped() throws Exception {
        HttpResponse<String> response = uiGet("/ui/backups/laptop/" + BACKUP + "/Data/", laptopToken);
        assertHtmlPage(200, response);
        String body = response.body();
        assertFalse(body.contains(SCRIPT_NAME), body);
        assertTrue(body.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), body);
        // The link to it is percent-encoded.
        assertTrue(body.contains("/Data/%3Cscript%3Ealert%281%29%3C%2Fscript%3E\""), body);
        // Symlink targets too.
        assertFalse(body.contains("<b>target</b>"), body);
        assertTrue(body.contains("&lt;b&gt;target&lt;/b&gt;"), body);
    }

    @Test
    void wrongMethodsAndUnknownPages() throws Exception {
        HttpResponse<String> post = harness.send(harness.request("/ui/", null).header("Cookie", "token=" + laptopToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertHtmlPage(405, post);
        assertHtmlPage(404, uiGet("/ui/nothing", laptopToken));
    }

    @Test
    void helpers() {
        assertEquals("0 B", WebUi.humanSize(0));
        assertEquals("1023 B", WebUi.humanSize(1023));
        assertEquals("1.0 KiB", WebUi.humanSize(1024));
        assertEquals("15 MiB", WebUi.humanSize(15L * 1024 * 1024));
        assertEquals("&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;&amp;", WebUi.escape("<a href=\"x\" title='y'>&"));
        assertEquals("a%20b%2Bc%2F%C3%BC~._-", WebUi.percentEncode("a b+c/ü~._-"));
    }
}
