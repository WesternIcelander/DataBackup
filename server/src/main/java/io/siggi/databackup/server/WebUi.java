package io.siggi.databackup.server;

import com.google.gson.JsonObject;
import io.siggi.databackup.auth.Authorization;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.server.RequestHandler.HttpError;
import io.siggi.databackup.server.repository.BackupItem;
import io.siggi.databackup.server.repository.BackupRepositories;
import io.siggi.http.HTTPRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The read-only web UI under {@code /ui/}: a login page that stores the token in a cookie, then a machine list, a
 * backup list per machine and a directory browser whose file links point at the existing download route
 * ({@code /backups/:machine/:backup/path}), which reads the same cookie.
 * <p>
 * Pages are rendered on the server without JavaScript. Every name is HTML-escaped and every URL piece is
 * percent-encoded, and pages are sent with a strict {@code Content-Security-Policy}; the stylesheet is served from
 * {@code /ui/style.css} so that the policy needs no {@code 'unsafe-inline'}.
 */
final class WebUi {

    static final String PREFIX = "/ui";
    private static final String HOME = PREFIX + "/";
    private static final String LOGIN = PREFIX + "/login";
    private static final String LOGOUT = PREFIX + "/logout";
    private static final String STYLE = PREFIX + "/style.css";
    private static final String BROWSE = PREFIX + "/backups/";

    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final String CSS = """
            body{font:15px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;color:#1d1d1f;background:#fff}
            header{display:flex;justify-content:space-between;align-items:center;padding:.6em 1em;background:#26364a}
            header a{color:#fff;text-decoration:none}
            .brand{font-weight:600}
            main{padding:1em;max-width:72em}
            h1{font-size:1.3em;margin:.4em 0 .8em;overflow-wrap:anywhere}
            a{color:#0b57d0}
            .crumbs{color:#666;overflow-wrap:anywhere}
            .crumbs span{margin:0 .35em}
            table{border-collapse:collapse;width:100%}
            th,td{text-align:left;padding:.3em .6em;border-bottom:1px solid #e3e3e3;vertical-align:top}
            td.name{overflow-wrap:anywhere}
            th.num,td.num{text-align:right;white-space:nowrap}
            td.meta{white-space:nowrap;font-variant-numeric:tabular-nums}
            .muted{color:#777}
            .error{color:#b3261e}
            form{display:flex;flex-direction:column;gap:.6em;max-width:24em}
            input,button{font:inherit;padding:.45em .6em}
            @media (prefers-color-scheme:dark){body{background:#161616;color:#e3e3e3}a{color:#8ab4f8}
            th,td{border-color:#333}.crumbs,.muted{color:#999}.error{color:#f28b82}}
            """;

    private final RequestHandler requestHandler;
    private final DataBackupServer server;

    WebUi(RequestHandler requestHandler, DataBackupServer server) {
        this.requestHandler = requestHandler;
        this.server = server;
    }

    static boolean handles(String url) {
        return url.equals(PREFIX) || url.startsWith(PREFIX + "/");
    }

    /**
     * Handles a request under {@code /ui}.
     *
     * @param token the token from the request (header or cookie), or null if there is none
     * @param auth  the token's authorization, or null if there is no token or it isn't valid
     */
    void handle(HTTPRequest request, String token, Authorization auth) throws Exception {
        try {
            route(request, token, auth);
        } catch (HttpError e) {
            if (request.response.alreadyWrote()) {
                throw new IOException("Cannot send " + e.status + " (" + e.getMessage() + "), response already started");
            }
            sendPage(request, e.status, "Error " + e.status, crumbs(), auth != null,
                    "<p class=\"error\">" + escape(e.getMessage()) + "</p>\n<p><a href=\"" + HOME
                            + "\">Back to the machine list</a></p>\n");
        }
    }

    private void route(HTTPRequest request, String token, Authorization auth) throws Exception {
        String url = request.url;
        if (url.equals(PREFIX)) {
            requireGet(request);
            request.response.completedRedirect(HOME);
            return;
        }
        if (url.equals(STYLE)) {
            requireGet(request);
            byte[] css = CSS.getBytes(StandardCharsets.UTF_8);
            request.response.setContentType("text/css; charset=utf-8");
            request.response.setHeader("X-Content-Type-Options", "nosniff");
            request.response.setHeader("Cache-Control", "max-age=3600");
            request.response.contentLength(css.length);
            request.response.write(css);
            return;
        }
        if (url.equals(LOGIN)) {
            login(request, auth);
            return;
        }
        if (url.equals(LOGOUT)) {
            if (!request.method.equals("GET") && !request.method.equals("POST")) {
                throw new HttpError(405, "Method not allowed").header("Allow", "GET, POST");
            }
            request.response.addHeader("Set-Cookie", clearCookie(request));
            request.response.completedRedirect(LOGIN);
            return;
        }
        if (auth == null) {
            // Not logged in, or the cookie's token was revoked: drop the stale cookie and ask for a token.
            if (token != null && !token.isEmpty()) request.response.addHeader("Set-Cookie", clearCookie(request));
            request.response.completedRedirect(LOGIN);
            return;
        }
        requireGet(request);
        if (url.equals(HOME) || url.equals(BROWSE) || url.equals(PREFIX + "/backups")) {
            if (!url.equals(HOME)) {
                request.response.completedRedirect(HOME);
                return;
            }
            machineList(request, auth);
        } else if (url.startsWith(BROWSE)) {
            browse(request, auth, url.substring(BROWSE.length()));
        } else {
            throw new HttpError(404, "Not found");
        }
    }

    private static void requireGet(HTTPRequest request) throws HttpError {
        if (!request.method.equals("GET")) {
            throw new HttpError(405, "Method not allowed").header("Allow", "GET");
        }
    }

    // --- login and logout ---

    private void login(HTTPRequest request, Authorization auth) throws Exception {
        if (request.method.equals("GET")) {
            if (auth != null) {
                request.response.completedRedirect(HOME);
                return;
            }
            sendLoginPage(request, 200, null);
            return;
        }
        if (!request.method.equals("POST")) {
            throw new HttpError(405, "Method not allowed").header("Allow", "GET, POST");
        }
        String token = request.post.get("token");
        token = token == null ? "" : token.trim();
        if (token.isEmpty()) {
            sendLoginPage(request, 400, "Enter a token.");
            return;
        }
        if (requestHandler.authorize(token) == null) {
            sendLoginPage(request, 401, "That token isn't valid.");
            return;
        }
        request.response.addHeader("Set-Cookie", tokenCookie(request, percentEncode(token)));
        request.response.completedRedirect(HOME);
    }

    private static String tokenCookie(HTTPRequest request, String value) {
        return "token=" + value + "; Path=/; HttpOnly; SameSite=Strict" + (request.isSecure() ? "; Secure" : "");
    }

    private static String clearCookie(HTTPRequest request) {
        return tokenCookie(request, "") + "; Max-Age=0";
    }

    private void sendLoginPage(HTTPRequest request, int status, String error) throws IOException {
        StringBuilder body = new StringBuilder();
        if (error != null) body.append("<p class=\"error\">").append(escape(error)).append("</p>\n");
        body.append("""
                <form method="post" action="/ui/login">
                <label for="token">Access token</label>
                <input type="password" id="token" name="token" autocomplete="current-password" required autofocus>
                <button type="submit">Log in</button>
                </form>
                <p class="muted">Create a token on the server with <code>token create</code>. You'll see the \
                machines its <code>read:</code> permissions allow.</p>
                """);
        sendPage(request, status, "Log in", null, false, body.toString());
    }

    // --- pages ---

    private void machineList(HTTPRequest request, Authorization auth) throws IOException {
        List<String> machines = new ArrayList<>();
        for (String name : server.getBackupRepositories().listNames()) {
            if (RequestHandler.isValidName(name) && auth.checkPermission("read:" + name)) machines.add(name);
        }
        machines.sort(String.CASE_INSENSITIVE_ORDER);
        StringBuilder body = new StringBuilder();
        if (machines.isEmpty()) {
            body.append("<p class=\"muted\">There are no machines this token can read.</p>\n");
        } else {
            body.append("<table>\n<thead><tr><th>Machine</th></tr></thead>\n<tbody>\n");
            for (String machine : machines) {
                body.append("<tr><td class=\"name\"><a href=\"").append(escape(BROWSE + percentEncode(machine) + "/"))
                        .append("\">").append(escape(machine)).append("</a></td></tr>\n");
            }
            body.append("</tbody>\n</table>\n");
        }
        sendPage(request, 200, "Machines", null, true, body.toString());
    }

    /**
     * {@code /ui/backups/:machine/[:backup/[path/]]}.
     */
    private void browse(HTTPRequest request, Authorization auth, String rest) throws Exception {
        boolean endsWithSlash = rest.endsWith("/");
        if (endsWithSlash) rest = rest.substring(0, rest.length() - 1);
        String[] pieces = rest.split("/", -1);
        String machine = pieces[0];
        if (!RequestHandler.isValidName(machine)) throw new HttpError(400, "Invalid machine name");
        if (!auth.checkPermission("read:" + machine)) {
            throw new HttpError(403, "This token can't read the machine " + machine + ".");
        }
        if (pieces.length > 1 && !RequestHandler.isValidName(pieces[1])) throw new HttpError(400, "Invalid backup name");
        for (int i = 2; i < pieces.length; i++) {
            String piece = pieces[i];
            if (piece.isEmpty() || piece.equals(".") || piece.equals("..")) throw new HttpError(400, "Invalid path");
        }
        BackupRepositories repositories = server.getBackupRepositories();
        if (!repositories.exists(machine)) throw new HttpError(404, "Unknown machine");
        if (pieces.length == 1) {
            if (!endsWithSlash) {
                request.response.completedRedirect(uiUrl(pieces, pieces.length, true));
                return;
            }
            backupList(request, machine, repositories.get(machine).getAllItems());
            return;
        }
        BackupItem item = repositories.get(machine).get(pieces[1]);
        if (item == null) throw new HttpError(404, "Unknown backup");
        try (BackupFile backup = BackupFile.open(item.getFile())) {
            DirectoryEntry target = backup.getRootDirectory();
            for (int i = 2; i < pieces.length; i++) {
                if (!target.isDirectory()) throw new HttpError(404, "No such path");
                target = target.asDirectory().getEntry(pieces[i]);
                if (target == null || target.isNull()) throw new HttpError(404, "No such path");
            }
            if (target.isDirectory()) {
                if (!endsWithSlash) {
                    request.response.completedRedirect(uiUrl(pieces, pieces.length, true));
                    return;
                }
                directoryPage(request, pieces, target);
            } else if (target.isFile() && !endsWithSlash) {
                // A file's page is its download.
                request.response.completedRedirect(downloadUrl(pieces, pieces.length));
            } else {
                throw new HttpError(404, "No such path");
            }
        }
    }

    private void backupList(HTTPRequest request, String machine, List<BackupItem> items) throws IOException {
        String[] pieces = {machine};
        StringBuilder body = new StringBuilder();
        if (items.isEmpty()) {
            body.append("<p class=\"muted\">This machine has no backups yet.</p>\n");
        } else {
            body.append("<table>\n<thead><tr><th>Backup</th><th>Time</th></tr></thead>\n<tbody>\n");
            List<BackupItem> newestFirst = new ArrayList<>(items);
            newestFirst.sort(Comparator.comparingLong(BackupItem::getTime).reversed());
            for (BackupItem item : newestFirst) {
                String href = uiUrl(pieces, 1, true) + percentEncode(item.getName()) + "/";
                body.append("<tr><td class=\"name\"><a href=\"").append(escape(href)).append("\">")
                        .append(escape(item.getName())).append("</a></td><td class=\"meta\">")
                        .append(escape(formatTime(item.getTime()))).append("</td></tr>\n");
            }
            body.append("</tbody>\n</table>\n");
        }
        sendPage(request, 200, machine, crumbs(pieces), true, body.toString());
    }

    private void directoryPage(HTTPRequest request, String[] pieces, DirectoryEntry directory) throws IOException {
        List<JsonObject> entries = new ArrayList<>();
        for (DirectoryEntry entry : directory.asDirectory()) {
            JsonObject json = RequestHandler.entryToJson(entry);
            if (json != null) entries.add(json);
        }
        entries.sort(Comparator.comparing((JsonObject e) -> !e.get("type").getAsString().equals("directory"))
                .thenComparing(e -> e.get("name").getAsString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.get("name").getAsString()));
        String[] childPieces = new String[pieces.length + 1];
        System.arraycopy(pieces, 0, childPieces, 0, pieces.length);
        StringBuilder body = new StringBuilder();
        if (entries.isEmpty()) {
            body.append("<p class=\"muted\">This folder is empty.</p>\n");
        } else {
            body.append("<table>\n<thead><tr><th>Name</th><th class=\"num\">Size</th><th>Modified</th>"
                    + "<th>Permissions</th></tr></thead>\n<tbody>\n");
            for (JsonObject entry : entries) {
                String name = entry.get("name").getAsString();
                String type = entry.get("type").getAsString();
                childPieces[pieces.length] = name;
                body.append("<tr><td class=\"name\">");
                switch (type) {
                    case "directory" -> body.append("<a href=\"")
                            .append(escape(uiUrl(childPieces, childPieces.length, true))).append("\">")
                            .append(escape(name)).append("/</a>");
                    case "file" -> body.append("<a href=\"")
                            .append(escape(downloadUrl(childPieces, childPieces.length))).append("\">")
                            .append(escape(name)).append("</a>");
                    default -> body.append(escape(name)).append(" <span class=\"muted\">&rarr; ")
                            .append(escape(entry.get("target").getAsString())).append("</span>");
                }
                body.append("</td><td class=\"num\"");
                if (entry.has("size")) {
                    long size = entry.get("size").getAsLong();
                    body.append(" title=\"").append(size).append(" bytes\">").append(escape(humanSize(size)));
                } else {
                    body.append(">");
                }
                body.append("</td><td class=\"meta\">");
                if (entry.has("modified")) body.append(escape(formatTime(entry.get("modified").getAsLong())));
                body.append("</td><td class=\"meta\">");
                if (entry.has("permissions")) {
                    body.append("<code>")
                            .append(escape(entry.getAsJsonObject("permissions").get("string").getAsString()))
                            .append("</code>");
                }
                body.append("</td></tr>\n");
            }
            body.append("</tbody>\n</table>\n");
        }
        sendPage(request, 200, pieces[pieces.length - 1], crumbs(pieces), true, body.toString());
    }

    // --- rendering ---

    /**
     * Breadcrumbs for a location given as path pieces ({@code machine, backup, dir, ...}). The first crumb is the
     * machine list, and the last piece is shown unlinked.
     */
    private static String crumbs(String... pieces) {
        StringBuilder sb = new StringBuilder("<nav class=\"crumbs\"><a href=\"" + HOME + "\">Machines</a>");
        for (int i = 0; i < pieces.length; i++) {
            sb.append("<span>/</span>");
            if (i == pieces.length - 1) {
                sb.append(escape(pieces[i]));
            } else {
                sb.append("<a href=\"").append(escape(uiUrl(pieces, i + 1, true))).append("\">")
                        .append(escape(pieces[i])).append("</a>");
            }
        }
        return sb.append("</nav>\n").toString();
    }

    private static void sendPage(HTTPRequest request, int status, String title, String crumbs, boolean loggedIn,
                                 String body) throws IOException {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>").append(escape(title)).append(" - DataBackup</title>\n")
                .append("<link rel=\"stylesheet\" href=\"").append(STYLE).append("\">\n</head>\n<body>\n")
                .append("<header><a class=\"brand\" href=\"").append(HOME).append("\">DataBackup</a>");
        if (loggedIn) html.append("<a href=\"").append(LOGOUT).append("\">Log out</a>");
        html.append("</header>\n<main>\n");
        if (crumbs != null) html.append(crumbs);
        html.append("<h1>").append(escape(title)).append("</h1>\n").append(body).append("</main>\n</body>\n</html>\n");
        byte[] bytes = html.toString().getBytes(StandardCharsets.UTF_8);
        if (status != 200) request.response.setHeader(status + " " + RequestHandler.reasonPhrase(status));
        request.response.setContentType("text/html; charset=utf-8");
        request.response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        request.response.setHeader("X-Content-Type-Options", "nosniff");
        request.response.setHeader("X-Frame-Options", "DENY");
        request.response.setHeader("Referrer-Policy", "no-referrer");
        request.response.setHeader("Cache-Control", "no-store");
        request.response.contentLength(bytes.length);
        request.response.write(bytes);
    }

    /**
     * {@code /ui/backups/} followed by the first {@code count} pieces, each percent-encoded.
     */
    private static String uiUrl(String[] pieces, int count, boolean trailingSlash) {
        StringBuilder sb = new StringBuilder(BROWSE);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append('/');
            sb.append(percentEncode(pieces[i]));
        }
        if (trailingSlash) sb.append('/');
        return sb.toString();
    }

    /**
     * The existing download route, {@code /backups/:machine/:backup/path}, for the first {@code count} pieces.
     */
    private static String downloadUrl(String[] pieces, int count) {
        StringBuilder sb = new StringBuilder("/backups");
        for (int i = 0; i < count; i++) sb.append('/').append(percentEncode(pieces[i]));
        return sb.toString();
    }

    /**
     * Percent-encodes everything but RFC 3986 unreserved characters, using UTF-8.
     */
    static String percentEncode(String text) {
        StringBuilder sb = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }

    /**
     * Escapes text for use in HTML element content and double- or single-quoted attribute values.
     */
    static String escape(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String formatTime(long millis) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(millis));
    }

    static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, value < 10.0 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }
}
