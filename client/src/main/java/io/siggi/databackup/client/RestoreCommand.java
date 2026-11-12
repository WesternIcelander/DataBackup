package io.siggi.databackup.client;

import io.siggi.databackup.client.ListCommand.ListFailedException;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.client.api.ServerApiException;
import io.siggi.databackup.client.snapshot.IoMessages;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.content.ZeroFilledFileContent;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.util.Util;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The {@code restore} command: {@code restore <machine> <backup> <path-in-backup> <destination> [--overwrite]}.
 * <p>
 * The backup's manifest is downloaded once. A directory's contents are restored into {@code destination} (created
 * if missing, merged into if it exists). A file or symlink is restored to {@code destination}, or into it when it is
 * an existing directory. An empty path (or {@code /}) restores the whole backup, one folder per source.
 * <p>
 * Content is downloaded through the per-machine file route ({@code read:<machine>}), each distinct file content
 * once; further files with the same content are copied locally. Files made only of inlined or zero-filled content
 * are written without downloading. Every restored file's SHA-256 is checked against its content IDs.
 * <p>
 * Existing files, symlinks and non-directories in the way are only replaced with {@code --overwrite}; existing
 * directories are merged into, and a non-empty directory is never replaced. Paths are checked without following
 * links, so a symlink inside the destination (restored or already there) is never written through.
 * <p>
 * After all content is written: owners (only when running as root), POSIX modes and modified times are restored,
 * directories last and deepest first. POSIX metadata is skipped where the filesystem has none (Windows).
 */
final class RestoreCommand {

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    /**
     * What a restore did.
     */
    record Outcome(long files, long directories, long symlinks, long bytes, long downloadedBytes, long downloads,
                   List<String> failures) {
    }

    /**
     * The restore can't start; the message says why.
     */
    static final class RestoreFailedException extends Exception {
        RestoreFailedException(String message) {
            super(message);
        }
    }

    private record Item(DirectoryEntry entry, Path target, String backupPath) {
    }

    private final ServerApi api;
    private final PrintStream out;
    private final boolean overwrite;
    private final boolean posix;
    private final boolean restoreOwners;

    private final List<String> failures = new ArrayList<>();
    private final Map<String, Path> restoredContent = new HashMap<>();
    private long files;
    private long directories;
    private long symlinks;
    private long bytes;
    private long downloadedBytes;
    private long downloads;

    /**
     * @param restoreOwners whether to restore uid and gid (only works as root)
     */
    RestoreCommand(ServerApi api, PrintStream out, boolean overwrite, boolean restoreOwners) {
        this.api = api;
        this.out = out;
        this.overwrite = overwrite;
        this.posix = FileSystems.getDefault().supportedFileAttributeViews().contains("unix");
        this.restoreOwners = posix && restoreOwners;
    }

    static boolean runningAsRoot() {
        return "root".equals(System.getProperty("user.name"));
    }

    static int run(ClientConfig config, List<String> args, boolean overwrite,
                   Function<ClientConfig, ServerApi> apiFactory, PrintStream out, PrintStream err) {
        Path destination;
        try {
            destination = Paths.get(args.get(3));
        } catch (InvalidPathException e) {
            err.println("Restore failed: not a valid destination path: " + args.get(3));
            return ClientCli.CONFIG_ERROR;
        }
        long start = System.nanoTime();
        RestoreCommand command = new RestoreCommand(apiFactory.apply(config), out, overwrite, runningAsRoot());
        try {
            Outcome outcome = command.restore(args.get(0), args.get(1), args.get(2), destination);
            out.println(summary(outcome, destination, System.nanoTime() - start));
            if (outcome.failures().isEmpty()) return ClientCli.OK;
            err.println("Restore incomplete: " + outcome.failures().size() + " items failed:");
            int shown = 0;
            for (String failure : outcome.failures()) {
                if (shown++ == ManifestBuilder.SUMMARY_LIMIT) {
                    err.println("  ... and " + (outcome.failures().size() - ManifestBuilder.SUMMARY_LIMIT) + " more");
                    break;
                }
                err.println("  " + failure);
            }
        } catch (RestoreFailedException | ListFailedException e) {
            err.println("Restore failed: " + e.getMessage());
        } catch (ServerApiException e) {
            err.println("Restore failed: the server answered " + e.getMessage());
        } catch (IOException e) {
            err.println("Restore failed: " + IoMessages.describe(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Restore failed: interrupted");
        }
        return ClientCli.FAILED;
    }

    static String summary(Outcome outcome, Path destination, long nanos) {
        return String.format(Locale.ROOT,
                "Restored %d files (%s), %d directories and %d symlinks to %s; downloaded %s in %d requests, "
                        + "took %.1fs.",
                outcome.files(), ManifestBuilder.formatBytes(outcome.bytes()), outcome.directories(),
                outcome.symlinks(), destination, ManifestBuilder.formatBytes(outcome.downloadedBytes()),
                outcome.downloads(), nanos / 1e9);
    }

    /**
     * Restores {@code pathInBackup} of a backup ({@code latest} works) to {@code destination}. Problems with single
     * items are collected in {@link Outcome#failures()}; problems that stop the whole restore are thrown.
     */
    Outcome restore(String machine, String backup, String pathInBackup, Path destination)
            throws IOException, InterruptedException, RestoreFailedException, ListFailedException {
        String backupName = ListCommand.resolveBackup(api, machine, backup);
        List<String> pieces = ListCommand.pathPieces(pathInBackup);
        Path manifest = Files.createTempFile("databackup-restore", ".manifest");
        try {
            out.println("Downloading the manifest of " + machine + "/" + backupName + "...");
            api.downloadManifest(machine, backupName, manifest);
            try (BackupFile file = BackupFile.open(manifest.toFile())) {
                DirectoryEntry entry = file.getRootDirectory();
                for (String piece : pieces) {
                    DirectoryEntry child = entry.isDirectory() ? entry.asDirectory().getEntry(piece) : null;
                    if (child == null || child.isNull()) {
                        throw new RestoreFailedException("No such path in backup " + backupName + ": "
                                + String.join("/", pieces));
                    }
                    entry = child;
                }
                Path target = destination.toAbsolutePath().normalize();
                // The destination itself may be (or be inside) a symlink the user chose; links inside it are never
                // followed.
                if (Files.isDirectory(target)) target = target.toRealPath();
                if (!entry.isDirectory() && Files.isDirectory(target)) {
                    target = child(target, entry.getName());
                    if (target == null) {
                        throw new RestoreFailedException("Can't restore a file named " + entry.getName()
                                + " on this system.");
                    }
                }
                restoreTree(machine, backupName, entry, String.join("/", pieces), target);
            }
        } finally {
            Files.deleteIfExists(manifest);
        }
        return new Outcome(files, directories, symlinks, bytes, downloadedBytes, downloads, List.copyOf(failures));
    }

    private void restoreTree(String machine, String backup, DirectoryEntry root, String rootPath, Path target)
            throws IOException, InterruptedException, RestoreFailedException {
        List<Item> items = new ArrayList<>();
        collect(root, target, rootPath, items);

        List<String> conflicts = new ArrayList<>();
        for (Item item : items) {
            String conflict = conflict(item);
            if (conflict != null) conflicts.add(conflict);
        }
        if (!conflicts.isEmpty() && (!overwrite || conflicts.stream().anyMatch(c -> c.endsWith(NOT_EMPTY)))) {
            StringBuilder message = new StringBuilder();
            message.append(conflicts.size()).append(conflicts.size() == 1 ? " path" : " paths")
                    .append(overwrite ? " can't be replaced" : " already exist (use --overwrite to replace them)")
                    .append(", nothing was restored:");
            int shown = 0;
            for (String conflict : conflicts) {
                if (shown++ == ManifestBuilder.SUMMARY_LIMIT) {
                    message.append("\n  ... and ").append(conflicts.size() - ManifestBuilder.SUMMARY_LIMIT)
                            .append(" more");
                    break;
                }
                message.append("\n  ").append(conflict);
            }
            throw new RestoreFailedException(message.toString());
        }

        long totalFiles = 0;
        long totalBytes = 0;
        for (Item item : items) {
            if (item.entry().isFile()) {
                totalFiles++;
                totalBytes += item.entry().asFile().getSize();
            }
        }
        out.println("Restoring " + totalFiles + " files (" + ManifestBuilder.formatBytes(totalBytes) + ") to "
                + target + "...");

        // Content first. A directory that can't be created takes its subtree with it. Symlinks come after everything
        // else: Windows makes a directory symlink only when its target is an existing directory at creation time.
        List<Item> ordered = new ArrayList<>(items.size());
        for (Item item : items) {
            if (!item.entry().isSymlink()) ordered.add(item);
        }
        for (Item item : items) {
            if (item.entry().isSymlink()) ordered.add(item);
        }
        List<Item> written = new ArrayList<>();
        List<Path> failedDirectories = new ArrayList<>();
        for (Item item : ordered) {
            if (Thread.interrupted()) throw new InterruptedException();
            if (insideAny(item.target(), failedDirectories)) continue;
            try {
                if (item.entry().isDirectory()) {
                    restoreDirectory(item);
                    directories++;
                } else if (item.entry().isSymlink()) {
                    restoreSymlink(item);
                    symlinks++;
                } else if (item.entry().isFile()) {
                    restoreFile(machine, backup, item);
                    files++;
                    bytes += item.entry().asFile().getSize();
                } else {
                    continue;
                }
                written.add(item);
            } catch (ItemFailedException e) {
                fail(item, e.getMessage());
                if (item.entry().isDirectory()) failedDirectories.add(item.target());
            } catch (ServerApiException e) {
                if (e.getStatus() == 401 || e.getStatus() == 403) throw e;
                fail(item, "the server answered " + e.getMessage());
                if (item.entry().isDirectory()) failedDirectories.add(item.target());
            } catch (IOException e) {
                fail(item, IoMessages.describe(e));
                if (item.entry().isDirectory()) failedDirectories.add(item.target());
            }
        }

        // Then metadata: files and symlinks, then directories deepest first (children change a directory's time).
        for (Item item : written) {
            if (!item.entry().isDirectory()) applyMetadata(item);
        }
        for (int i = written.size() - 1; i >= 0; i--) {
            Item item = written.get(i);
            if (item.entry().isDirectory()) applyMetadata(item);
        }
    }

    private static boolean insideAny(Path path, List<Path> directories) {
        for (Path directory : directories) {
            if (path.startsWith(directory)) return true;
        }
        return false;
    }

    private void fail(Item item, String message) {
        failures.add(item.target() + ": " + message);
    }

    /**
     * Walks the tree in pre-order (a directory before its contents). Entries whose names can't be a single path
     * element here (such as {@code ..} or a name with a separator) are reported as failures and skipped.
     */
    private void collect(DirectoryEntry entry, Path target, String backupPath, List<Item> items) {
        if (entry.isNull()) return;
        items.add(new Item(entry, target, backupPath));
        if (!entry.isDirectory()) return;
        for (DirectoryEntry childEntry : entry.asDirectory()) {
            if (childEntry.isNull()) continue;
            String childBackupPath = backupPath.isEmpty() ? childEntry.getName()
                    : backupPath + "/" + childEntry.getName();
            Path childTarget = child(target, childEntry.getName());
            if (childTarget == null) {
                failures.add(target + ": can't restore an entry named \"" + childEntry.getName()
                        + "\" on this system (" + childBackupPath + ")");
                continue;
            }
            collect(childEntry, childTarget, childBackupPath, items);
        }
    }

    /**
     * {@code parent/name}, or null if {@code name} isn't exactly one ordinary path element on this system.
     */
    static Path child(Path parent, String name) {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                || name.indexOf('\0') >= 0) {
            return null;
        }
        try {
            Path child = parent.resolve(name);
            if (!parent.equals(child.getParent()) || child.getFileName() == null
                    || !child.getFileName().toString().equals(name)) {
                return null;
            }
            return child;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private static final String NOT_EMPTY = "is a directory that isn't empty";

    /**
     * Why restoring {@code item} would replace something, or null if it wouldn't.
     */
    private static String conflict(Item item) throws IOException {
        Path target = item.target();
        if (!Files.exists(target, NOFOLLOW)) return null;
        boolean isDirectory = Files.isDirectory(target, NOFOLLOW);
        if (item.entry().isDirectory()) {
            return isDirectory ? null : target + " exists and isn't a directory";
        }
        if (isDirectory) {
            try (var stream = Files.list(target)) {
                if (stream.findAny().isPresent()) return target + " " + NOT_EMPTY;
            }
        }
        return target + " exists";
    }

    /**
     * Something in the way of one item; the message says what.
     */
    private static final class ItemFailedException extends Exception {
        ItemFailedException(String message) {
            super(message);
        }
    }

    /**
     * Removes whatever is at {@code target} (a file, a symlink, which is removed itself, or an empty directory) so
     * something new can be created there, if overwriting is allowed.
     */
    private void clear(Path target) throws IOException, ItemFailedException {
        if (!Files.exists(target, NOFOLLOW)) return;
        if (!overwrite) throw new ItemFailedException("already exists (use --overwrite to replace it)");
        if (Files.isDirectory(target, NOFOLLOW)) {
            try (var stream = Files.list(target)) {
                if (stream.findAny().isPresent()) throw new ItemFailedException(NOT_EMPTY);
            }
        }
        Files.delete(target);
    }

    private void restoreDirectory(Item item) throws IOException, ItemFailedException {
        Path target = item.target();
        if (Files.isDirectory(target, NOFOLLOW)) return;
        clear(target);
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
        Files.createDirectory(target);
    }

    private void restoreSymlink(Item item) throws IOException, ItemFailedException {
        Path target = item.target();
        clear(target);
        createParent(target);
        String linkTarget = item.entry().asSymlink().getTarget();
        Path link;
        try {
            link = Paths.get(linkTarget);
        } catch (InvalidPathException e) {
            throw new ItemFailedException("its target can't be a path on this system: " + linkTarget);
        }
        Files.createSymbolicLink(target, link);
    }

    /**
     * Creates the parent folder when restoring a single file or symlink to a destination that doesn't exist yet.
     * Inside a restored tree, the parent is always a directory restored before.
     */
    private static void createParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
    }

    private void restoreFile(String machine, String backup, Item item)
            throws IOException, InterruptedException, ItemFailedException {
        DirectoryEntryFile file = item.entry().asFile();
        Path target = item.target();
        List<FileContent> contents = file.getFileContents();
        boolean needsDownload = false;
        for (FileContent content : contents) {
            // Empty content needs no download; its hash is still checked.
            if (content instanceof ContentIDFileContent) needsDownload |= content.getLength() > 0;
            else if (!(content instanceof InlinedFileContent) && !(content instanceof ZeroFilledFileContent)) {
                throw new ItemFailedException("unsupported content type " + content.getClass().getSimpleName());
            }
            if (content.getOffset() < 0 || content.getLength() < 0
                    || content.getOffset() + content.getLength() > file.getSize()) {
                throw new ItemFailedException("its content lies outside the file's size in the backup");
            }
        }
        if (contents.isEmpty() && file.getSize() > 0) {
            throw new ItemFailedException("the backup has no content for it");
        }

        clear(target);
        createParent(target);
        String key = needsDownload ? contentKey(file, contents) : null;
        Path copyFrom = key == null ? null : restoredContent.get(key);
        if (copyFrom != null && Files.isRegularFile(copyFrom, NOFOLLOW)) {
            Files.copy(copyFrom, target, NOFOLLOW);
        } else if (needsDownload) {
            String path = item.backupPath();
            api.downloadFile(machine, backup, path, target);
            downloads++;
            downloadedBytes += Files.size(target);
        } else {
            writeLocally(target, file.getSize(), contents);
        }

        String problem = verify(target, file.getSize(), contents);
        if (problem != null) {
            Files.deleteIfExists(target);
            throw new ItemFailedException(problem);
        }
        if (key != null && copyFrom == null) restoredContent.put(key, target);
    }

    /**
     * Identifies a file's content, so files with the same content are downloaded once.
     */
    private static String contentKey(DirectoryEntryFile file, List<FileContent> contents) {
        StringBuilder key = new StringBuilder().append(file.getSize());
        for (FileContent content : contents) {
            key.append('|').append(content.getTypeId()).append(':').append(content.getOffset()).append(':')
                    .append(content.getLength()).append(':');
            if (content instanceof ContentIDFileContent id) key.append(Util.bytesToHex(id.getHash()));
            else if (content instanceof InlinedFileContent inlined) key.append(Util.bytesToHex(inlined.getData()));
        }
        return key.toString();
    }

    private static void writeLocally(Path target, long size, List<FileContent> contents) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            for (FileContent content : contents) {
                if (content instanceof InlinedFileContent inlined) {
                    ByteBuffer buffer = ByteBuffer.wrap(inlined.getData(), 0,
                            (int) Math.min(inlined.getData().length, content.getLength()));
                    long position = content.getOffset();
                    while (buffer.hasRemaining()) position += channel.write(buffer, position);
                }
            }
            // Zero-filled content and gaps: extend the file (reads back as zeros) up to its size.
            if (channel.size() < size) {
                channel.write(ByteBuffer.wrap(new byte[1]), size - 1);
            }
        }
    }

    /**
     * Checks the restored file's size and the SHA-256 of each content ID's range. Returns the problem, or null.
     */
    static String verify(Path target, long size, List<FileContent> contents) throws IOException {
        long actualSize = Files.size(target);
        if (actualSize != size) {
            return "restored " + actualSize + " bytes, but the backup says " + size;
        }
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            for (FileContent content : contents) {
                if (!(content instanceof ContentIDFileContent id)) continue;
                MessageDigest sha256 = Util.sha256();
                long position = content.getOffset();
                long remaining = content.getLength();
                while (remaining > 0) {
                    buffer.clear();
                    if (remaining < buffer.capacity()) buffer.limit((int) remaining);
                    int n = channel.read(buffer, position);
                    if (n < 0) break;
                    buffer.flip();
                    sha256.update(buffer);
                    position += n;
                    remaining -= n;
                }
                if (remaining > 0 || !MessageDigest.isEqual(sha256.digest(), id.getHash())) {
                    return "its content doesn't match its SHA-256 " + Util.bytesToHex(id.getHash());
                }
            }
        }
        return null;
    }

    /**
     * Restores the modified time, the owner (as root) and the POSIX mode. Failures are recorded, not thrown.
     */
    private void applyMetadata(Item item) {
        DirectoryEntry entry = item.entry();
        Path target = item.target();
        boolean symlink = entry.isSymlink();
        // Time first: setting it opens the file, which the restored mode may not allow.
        FileTime modified = modifiedTime(entry);
        if (modified != null) {
            try {
                Files.getFileAttributeView(target, BasicFileAttributeView.class, NOFOLLOW)
                        .setTimes(modified, null, null);
            } catch (IOException | RuntimeException e) {
                // Not every system can set a symlink's own time; that isn't worth failing the restore for.
                if (!symlink) failures.add(target + ": can't set the modified time: " + describe(e));
            }
        }

        ExtraDataPosixPermissions permissions = entry.getExtra(ExtraDataPosixPermissions.class);
        if (posix && permissions != null) {
            if (restoreOwners) {
                try {
                    // Owner first: changing it can clear the setuid and setgid bits.
                    Files.setAttribute(target, "unix:uid", (int) permissions.owner, NOFOLLOW);
                    Files.setAttribute(target, "unix:gid", (int) permissions.group, NOFOLLOW);
                } catch (IOException | RuntimeException e) {
                    failures.add(target + ": can't set the owner: " + describe(e));
                }
            }
            if (!symlink) {
                try {
                    Files.setAttribute(target, "unix:mode", permissions.permissions & 07777, NOFOLLOW);
                } catch (IOException | RuntimeException e) {
                    failures.add(target + ": can't set the permissions: " + describe(e));
                }
            }
        }
    }

    private static String describe(Exception e) {
        return e instanceof IOException io ? IoMessages.describe(io) : String.valueOf(e.getMessage());
    }

    /**
     * The entry's modified time: nanosecond precision when recorded, otherwise a file's millisecond time.
     * Directories and symlinks only have a time when the nanosecond one was recorded.
     */
    static FileTime modifiedTime(DirectoryEntry entry) {
        ExtraDataNanosecondModifiedDate nanos = entry.getExtra(ExtraDataNanosecondModifiedDate.class);
        if (nanos != null) return FileTime.from(Instant.ofEpochSecond(nanos.seconds, nanos.nanos));
        if (entry.isFile()) return FileTime.fromMillis(entry.asFile().getLastModified());
        return null;
    }
}
