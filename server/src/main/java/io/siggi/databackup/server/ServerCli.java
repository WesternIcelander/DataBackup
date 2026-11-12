package io.siggi.databackup.server;

import io.siggi.databackup.auth.simple.SimpleTokenStore;
import io.siggi.databackup.auth.simple.SimpleTokenStore.CreatedToken;
import io.siggi.databackup.auth.simple.SimpleTokenStore.TokenInfo;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The server's command line: {@code serve} (the default), {@code token create|list|revoke} and {@code help}. The data
 * folder is chosen by {@link DataBackupServer#defaultDataRoot()} ({@code -Ddatabackupdir=...}).
 */
final class ServerCli {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private static final String USAGE_TEXT = """
            Usage: java [-Ddatabackupdir=<data folder>] -jar databackup-server.jar <command>

            Commands:
              serve                                   Run the server (the default when no command is given).
              token create --name <name> --permission <permission>...
                                                      Create a token and print it. It is shown only once.
              token list                              List tokens: id, name, creation time and permissions.
              token revoke <id>                       Revoke a token by its id (or by its file's SHA-256).
              help                                    Show this help.

            Permissions: upload:<machine>, read:<machine>, upload:*, read:*, globalread, admin.
            --permission may be repeated, and may be followed by several permissions.
            Settings are read from server.json in the data folder.
            """;

    private ServerCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, DataBackupServer.defaultDataRoot(), out, err);
    }

    static int run(String[] args, File dataRoot, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? "serve" : args[0];
        try {
            switch (command) {
                case "serve":
                    if (args.length > 1) return usage(err, "serve takes no arguments");
                    return serve(dataRoot, err);
                case "token":
                    return token(args, dataRoot, out, err);
                case "help":
                case "--help":
                case "-h":
                    out.print(USAGE_TEXT);
                    return OK;
                default:
                    return usage(err, "Unknown command: " + command);
            }
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            return FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FAILED;
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.println();
        err.print(USAGE_TEXT);
        return USAGE;
    }

    private static boolean ensureDataRoot(File dataRoot, PrintStream err) {
        if (dataRoot.isDirectory() || dataRoot.mkdirs()) return true;
        err.println("Required directory does not exist: " + dataRoot.getPath());
        return false;
    }

    private static int serve(File dataRoot, PrintStream err) throws IOException, InterruptedException {
        if (!ensureDataRoot(dataRoot, err)) return FAILED;
        DataBackupServer server = new DataBackupServer(dataRoot, null);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.shutdown();
            } catch (InterruptedException ignored) {
            }
        }, "ShutdownHook"));
        server.start();
        err.println("DataBackup server listening on port " + server.getPort() + ", data folder "
                + dataRoot.getAbsolutePath());
        server.awaitTermination();
        return OK;
    }

    private static int token(String[] args, File dataRoot, PrintStream out, PrintStream err) throws IOException {
        if (args.length < 2) return usage(err, "token needs a subcommand: create, list or revoke");
        SimpleTokenStore store = new SimpleTokenStore(new File(dataRoot, "tokens"));
        switch (args[1]) {
            case "create":
                return tokenCreate(args, dataRoot, store, out, err);
            case "list":
                if (args.length > 2) return usage(err, "token list takes no arguments");
                return tokenList(store, out);
            case "revoke":
                if (args.length != 3) return usage(err, "token revoke needs exactly one id");
                return tokenRevoke(args[2], store, out, err);
            default:
                return usage(err, "Unknown token subcommand: " + args[1]);
        }
    }

    private static int tokenCreate(String[] args, File dataRoot, SimpleTokenStore store, PrintStream out,
                                   PrintStream err) throws IOException {
        String name = null;
        List<String> permissions = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--name")) {
                if (i + 1 >= args.length) return usage(err, "--name needs a value");
                if (name != null) return usage(err, "--name given more than once");
                name = args[++i];
            } else if (arg.startsWith("--name=")) {
                if (name != null) return usage(err, "--name given more than once");
                name = arg.substring("--name=".length());
            } else if (arg.equals("--permission")) {
                int start = permissions.size();
                while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    permissions.add(args[++i]);
                }
                if (permissions.size() == start) return usage(err, "--permission needs a value");
            } else if (arg.startsWith("--permission=")) {
                permissions.add(arg.substring("--permission=".length()));
            } else {
                return usage(err, "Unexpected argument: " + arg);
            }
        }
        if (name == null || name.isBlank()) return usage(err, "token create needs --name");
        if (permissions.isEmpty()) return usage(err, "token create needs at least one --permission");
        for (String permission : permissions) {
            if (!isValidPermission(permission)) {
                return usage(err, "Invalid permission: " + permission);
            }
        }
        if (!ensureDataRoot(dataRoot, err)) return FAILED;
        CreatedToken created;
        try {
            created = store.create(name, permissions);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return usage(err, e.getMessage());
        }
        TokenInfo info = created.info();
        err.println("Created token " + info.id() + " (" + info.name() + ") with permissions: "
                + String.join(", ", info.permissions()));
        err.println("This is the only time the token is shown; store it now:");
        out.println(created.token());
        return OK;
    }

    private static int tokenList(SimpleTokenStore store, PrintStream out) throws IOException {
        List<TokenInfo> tokens = store.list();
        if (tokens.isEmpty()) {
            out.println("No tokens.");
            return OK;
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"ID", "NAME", "CREATED", "PERMISSIONS"});
        for (TokenInfo token : tokens) {
            rows.add(new String[]{
                    token.id() != null ? token.id() : "(sha256 " + token.hash() + ")",
                    token.name() != null ? token.name() : "-",
                    token.created() != null ? token.created() : "-",
                    token.permissions().isEmpty() ? "-" : String.join(",", token.permissions())});
        }
        int[] widths = new int[3];
        for (String[] row : rows) {
            for (int c = 0; c < widths.length; c++) widths[c] = Math.max(widths[c], row[c].length());
        }
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < widths.length; c++) {
                line.append(row[c]).append(" ".repeat(widths[c] - row[c].length() + 2));
            }
            line.append(row[3]);
            out.println(line);
        }
        return OK;
    }

    private static int tokenRevoke(String id, SimpleTokenStore store, PrintStream out, PrintStream err)
            throws IOException {
        TokenInfo revoked;
        try {
            revoked = store.revoke(id);
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return FAILED;
        }
        if (revoked == null) {
            err.println("No token with id " + id);
            return FAILED;
        }
        out.println("Revoked token " + (revoked.id() != null ? revoked.id() : revoked.hash())
                + (revoked.name() != null ? " (" + revoked.name() + ")" : ""));
        return OK;
    }

    /**
     * Whether a permission is one the server understands (see the permission model): {@code admin},
     * {@code globalread}, or {@code read:}/{@code upload:} followed by {@code *} or a valid machine name.
     */
    static boolean isValidPermission(String permission) {
        if (permission == null) return false;
        if (permission.equals("admin") || permission.equals("globalread")) return true;
        int colon = permission.indexOf(':');
        if (colon == -1) return false;
        String prefix = permission.substring(0, colon);
        String machine = permission.substring(colon + 1);
        if (!prefix.equals("read") && !prefix.equals("upload")) return false;
        return machine.equals("*") || RequestHandler.isValidName(machine);
    }
}
