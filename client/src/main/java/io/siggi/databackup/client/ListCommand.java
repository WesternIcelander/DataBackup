package io.siggi.databackup.client;

import io.siggi.databackup.client.api.BackupInfo;
import io.siggi.databackup.client.api.DirectoryListingEntry;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.client.api.ServerApiException;
import io.siggi.databackup.client.snapshot.IoMessages;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The {@code list} command:
 * <ul>
 *     <li>{@code list}: the machines the token can read, one per line.</li>
 *     <li>{@code list <machine>}: that machine's backups, oldest first, as name and time.</li>
 *     <li>{@code list <machine> <backup> [path]}: a directory inside a backup, one entry per line with type, size,
 *     modified time and name. A path naming a file or symlink lists just that entry.</li>
 * </ul>
 * {@code latest} works as a backup name (see {@link #resolveBackup}). Times are shown in {@code zone}.
 */
final class ListCommand {

    /**
     * The backup name that stands for a machine's newest backup.
     */
    static final String LATEST = "latest";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT);

    private final ServerApi api;
    private final ZoneId zone;
    private final PrintStream out;

    ListCommand(ServerApi api, ZoneId zone, PrintStream out) {
        this.api = api;
        this.zone = zone;
        this.out = out;
    }

    static int run(ClientConfig config, List<String> args, Function<ClientConfig, ServerApi> apiFactory,
                   PrintStream out, PrintStream err) {
        return run(config, args, apiFactory, ZoneId.systemDefault(), out, err);
    }

    static int run(ClientConfig config, List<String> args, Function<ClientConfig, ServerApi> apiFactory, ZoneId zone,
                   PrintStream out, PrintStream err) {
        ListCommand command = new ListCommand(apiFactory.apply(config), zone, out);
        try {
            switch (args.size()) {
                case 0 -> command.listMachines();
                case 1 -> command.listBackups(args.get(0));
                default -> command.listDirectory(args.get(0), args.get(1), args.size() > 2 ? args.get(2) : "");
            }
            return ClientCli.OK;
        } catch (ListFailedException e) {
            err.println("List failed: " + e.getMessage());
        } catch (ServerApiException e) {
            err.println("List failed: the server answered " + e.getMessage());
        } catch (IOException e) {
            err.println("List failed: " + IoMessages.describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("List failed: interrupted");
        }
        return ClientCli.FAILED;
    }

    /**
     * The request can't be answered; the message says why.
     */
    static final class ListFailedException extends Exception {
        ListFailedException(String message) {
            super(message);
        }
    }

    void listMachines() throws IOException, InterruptedException {
        for (String machine : api.listMachines()) out.println(machine);
    }

    void listBackups(String machine) throws IOException, InterruptedException {
        for (BackupInfo backup : api.listBackups(machine)) {
            out.println(backup.name() + "  " + formatTime(backup.time()));
        }
    }

    void listDirectory(String machine, String backup, String path)
            throws IOException, InterruptedException, ListFailedException {
        String backupName = resolveBackup(api, machine, backup);
        List<String> pieces = pathPieces(path);
        List<DirectoryListingEntry> entries;
        try {
            entries = api.listDirectory(machine, backupName, String.join("/", pieces));
        } catch (ServerApiException e) {
            if (e.getStatus() != 404 || pieces.isEmpty() || !"No such path".equals(e.getServerMessage())) throw e;
            // Maybe the path is a file or symlink: look it up in its parent directory.
            entries = List.of(findEntry(machine, backupName, pieces, e));
        }
        for (DirectoryListingEntry entry : entries) out.println(formatEntry(entry));
    }

    private DirectoryListingEntry findEntry(String machine, String backup, List<String> pieces,
                                            ServerApiException notFound)
            throws IOException, InterruptedException, ServerApiException {
        String name = pieces.get(pieces.size() - 1);
        List<DirectoryListingEntry> parent;
        try {
            parent = api.listDirectory(machine, backup, String.join("/", pieces.subList(0, pieces.size() - 1)));
        } catch (ServerApiException e) {
            if (e.getStatus() == 404) throw notFound;
            throw e;
        }
        for (DirectoryListingEntry entry : parent) {
            if (entry.name().equals(name) && entry.type() != DirectoryListingEntry.Type.DIRECTORY) return entry;
        }
        throw notFound;
    }

    /**
     * Turns {@code latest} into the name of the machine's newest backup; any other name is returned unchanged.
     *
     * @throws ListFailedException if {@code latest} is asked for and the machine has no backups
     */
    static String resolveBackup(ServerApi api, String machine, String backup)
            throws IOException, InterruptedException, ListFailedException {
        if (!backup.equals(LATEST)) return backup;
        BackupInfo newest = null;
        for (BackupInfo info : api.listBackups(machine)) {
            if (newest == null || info.time() > newest.time()
                    || (info.time() == newest.time() && info.name().compareTo(newest.name()) > 0)) {
                newest = info;
            }
        }
        if (newest == null) throw new ListFailedException("Machine " + machine + " has no backups yet.");
        return newest.name();
    }

    /**
     * Splits a path inside a backup on {@code /}, skipping empty pieces.
     */
    static List<String> pathPieces(String path) {
        List<String> pieces = new ArrayList<>();
        for (String piece : path.split("/")) {
            if (!piece.isEmpty()) pieces.add(piece);
        }
        return pieces;
    }

    String formatTime(long millis) {
        return TIME_FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    /**
     * One listing line: type letter ({@code d}, {@code f}, {@code l} or {@code ?}), permissions (or {@code -}),
     * size (or {@code -}), modified time (or {@code -}), then the name ({@code name/} for directories,
     * {@code name -> target} for symlinks).
     */
    String formatEntry(DirectoryListingEntry entry) {
        String type;
        String name = entry.name();
        switch (entry.type()) {
            case FILE -> type = "f";
            case DIRECTORY -> {
                type = "d";
                name += "/";
            }
            case SYMLINK -> {
                type = "l";
                if (entry.target() != null) name += " -> " + entry.target();
            }
            default -> type = "?";
        }
        String permissions = entry.permissions() != null && entry.permissions().string() != null
                ? entry.permissions().string() : "-";
        String size = entry.size() != null ? Long.toString(entry.size()) : "-";
        String modified = entry.modified() != null ? formatTime(entry.modified()) : "-";
        return String.format(Locale.ROOT, "%s %-9s %12s  %-25s  %s", type, permissions, size, modified, name);
    }
}
