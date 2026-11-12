package io.siggi.databackup.client;

import io.siggi.databackup.client.ManifestBuilder.ScanSource;
import io.siggi.databackup.client.api.BackupInfo;
import io.siggi.databackup.client.api.ContentSource;
import io.siggi.databackup.client.api.ContentUploadResult;
import io.siggi.databackup.client.api.ManifestUploadResult;
import io.siggi.databackup.client.api.MissingContentException;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.client.api.ServerApiException;
import io.siggi.databackup.client.snapshot.IoMessages;
import io.siggi.databackup.client.snapshot.SnapshotPlatform;
import io.siggi.databackup.client.snapshot.SourceSnapshot;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.util.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The {@code backup} command: cleans up stale snapshots, snapshots the sources, builds the manifest, uploads it and
 * the content the server is missing (read from the snapshots, each content ID once), commits, moves
 * {@code pending-manifest} to {@code last-manifest}, and deletes the snapshots. The snapshots stay until the commit
 * is done.
 * <p>
 * Content whose bytes no longer match the hash in the manifest (the file changed or vanished after it was hashed)
 * fails the backup: it is reported and the backup isn't committed.
 */
final class BackupCommand {

    /**
     * The most content uploaded between two progress lines (the API batches it further).
     */
    static final long PROGRESS_CHUNK_BYTES = 256L * 1024 * 1024;
    static final int PROGRESS_CHUNK_COUNT = 5000;

    /**
     * A finished backup.
     *
     * @param backupName     the committed backup's name on the server
     * @param snapshotId     the backup's ID
     * @param files          files in the backup
     * @param bytes          their total size
     * @param uploadedIds    the content IDs uploaded in this run, in upload order
     * @param uploadedBytes  the bytes of content uploaded
     */
    record Outcome(String backupName, UUID snapshotId, long files, long bytes, List<String> uploadedIds,
                   long uploadedBytes) {
    }

    /**
     * The backup can't be completed; the message says why.
     */
    static final class BackupFailedException extends Exception {
        BackupFailedException(String message) {
            super(message);
        }
    }

    /**
     * Where one piece of content is read from.
     */
    record ContentLocation(String sourceName, String scanPath, String path, long offset, long length) {
        String displayPath() {
            return sourceName + "/" + path;
        }
    }

    private final ServerApi api;
    private final String machine;
    private final Path stateDir;
    private final PrintStream out;

    BackupCommand(ServerApi api, String machine, Path stateDir, PrintStream out) {
        this.api = api;
        this.machine = machine;
        this.stateDir = stateDir;
        this.out = out;
    }

    /**
     * Runs {@code backup} for the CLI and prints the one-line summary. Returns the exit code.
     */
    static int run(ClientConfig config, SnapshotPlatform platform, Function<ClientConfig, ServerApi> apiFactory,
                   PrintStream out, PrintStream err) {
        long start = System.nanoTime();
        Outcome[] outcome = new Outcome[1];
        int code = SnapshotCommands.backup(config, platform, snapshots -> {
            List<ScanSource> sources = new ArrayList<>();
            for (SourceSnapshot snapshot : snapshots) sources.add(ManifestBuilder.scanSourceFor(snapshot));
            BackupCommand command = new BackupCommand(apiFactory.apply(config), config.getMachineName(),
                    config.getStateDir(), out);
            try {
                outcome[0] = command.backup(sources);
                return ClientCli.OK;
            } catch (BackupFailedException e) {
                err.println("Backup failed: " + e.getMessage());
            } catch (ServerApiException e) {
                err.println("Backup failed: the server answered " + e.getMessage());
            } catch (IOException e) {
                err.println("Backup failed: " + IoMessages.describe(e));
            }
            return ClientCli.FAILED;
        }, out, err);
        if (code == ClientCli.OK && outcome[0] != null) {
            out.println(summary(outcome[0], System.nanoTime() - start));
        }
        return code;
    }

    static String summary(Outcome outcome, long nanos) {
        return String.format(Locale.ROOT, "Backup %s complete: %d files (%s), %s uploaded, took %.1fs.",
                outcome.backupName(), outcome.files(), ManifestBuilder.formatBytes(outcome.bytes()),
                ManifestBuilder.formatBytes(outcome.uploadedBytes()), nanos / 1e9);
    }

    /**
     * Builds the manifest from {@code sources}, then uploads and commits it. Tests call this with plain folders.
     */
    Outcome backup(List<ScanSource> sources) throws IOException, InterruptedException, BackupFailedException {
        ManifestBuilder.Result manifest = new ManifestBuilder(stateDir, out).build(sources);
        Map<String, String> scanPaths = new HashMap<>();
        for (ScanSource source : sources) scanPaths.put(source.source().name(), source.scanPath());
        return uploadAndCommit(manifest, scanPaths);
    }

    /**
     * Uploads a built manifest and its missing content, commits it, and moves {@code pending-manifest} to
     * {@code last-manifest}.
     *
     * @param scanPaths where each source's content is read from, by source name
     */
    Outcome uploadAndCommit(ManifestBuilder.Result manifest, Map<String, String> scanPaths)
            throws IOException, InterruptedException, BackupFailedException {
        out.println("Uploading the manifest...");
        ManifestUploadResult upload = api.uploadManifest(machine, manifest.manifest());
        Map<String, ContentLocation> locations = locate(manifest.manifest(), scanPaths, upload.missing());
        List<String> uploadedIds = new ArrayList<>();
        long uploadedBytes = uploadContent(upload.missing(), locations, uploadedIds);

        String backupName;
        try {
            backupName = commit(upload.pendingId(), manifest.snapshotId());
        } catch (MissingContentException e) {
            // Shouldn't happen (another client's garbage collection, say): upload what's missing once more.
            out.println("The server is still missing " + e.getMissing().size() + " pieces of content; uploading them.");
            locations = locate(manifest.manifest(), scanPaths, e.getMissing());
            uploadedBytes += uploadContent(e.getMissing(), locations, uploadedIds);
            backupName = commit(upload.pendingId(), manifest.snapshotId());
        }

        Files.move(manifest.manifest(), stateDir.resolve(ManifestBuilder.LAST_MANIFEST),
                StandardCopyOption.REPLACE_EXISTING);
        return new Outcome(backupName, manifest.snapshotId(), manifest.files(), manifest.bytes(),
                List.copyOf(uploadedIds), uploadedBytes);
    }

    /**
     * Commits the pending backup. A retried commit whose first answer was lost can be refused ({@code 404} or
     * {@code 409}) even though it went through, so in that case the backup is looked up by its snapshot ID.
     */
    private String commit(String pendingId, UUID snapshotId) throws IOException, InterruptedException {
        out.println("Committing...");
        try {
            return api.commit(machine, pendingId);
        } catch (MissingContentException e) {
            throw e;
        } catch (ServerApiException e) {
            if (e.getStatus() != 404 && e.getStatus() != 409) throw e;
            for (BackupInfo info : api.listBackups(machine)) {
                if (snapshotId.equals(info.snapshotId())) return info.name();
            }
            throw e;
        }
    }

    /**
     * Uploads the content IDs in {@code ids}, reading each from its location.
     *
     * @return the bytes uploaded
     */
    private long uploadContent(Collection<String> ids, Map<String, ContentLocation> locations,
                               List<String> uploadedIds)
            throws IOException, InterruptedException, BackupFailedException {
        Set<String> wanted = new LinkedHashSet<>(ids);
        List<String> unknown = new ArrayList<>();
        for (String id : wanted) {
            if (!locations.containsKey(id)) unknown.add(id);
        }
        if (!unknown.isEmpty()) {
            throw new BackupFailedException("the server asked for " + unknown.size()
                    + " pieces of content that aren't in the manifest, for example " + unknown.get(0));
        }
        if (wanted.isEmpty()) {
            out.println("The server already has all the content.");
            return 0L;
        }
        long totalBytes = 0L;
        for (String id : wanted) totalBytes += locations.get(id).length();
        out.println("Uploading " + wanted.size() + " pieces of content (" + ManifestBuilder.formatBytes(totalBytes)
                + ")...");

        Map<String, String> problems = new ConcurrentHashMap<>();
        List<String> notReceived = new ArrayList<>();
        List<ContentSource> chunk = new ArrayList<>();
        long chunkBytes = 0L;
        long doneBytes = 0L;
        int doneCount = 0;
        List<String> order = new ArrayList<>(wanted);
        for (int i = 0; i < order.size(); i++) {
            String id = order.get(i);
            ContentLocation location = locations.get(id);
            chunk.add(contentSource(id, location, problems));
            chunkBytes += location.length();
            boolean last = i == order.size() - 1;
            if (last || chunk.size() >= PROGRESS_CHUNK_COUNT || chunkBytes >= PROGRESS_CHUNK_BYTES) {
                ContentUploadResult result = api.uploadContent(chunk);
                notReceived.addAll(result.notReceived());
                Set<String> failed = new HashSet<>(result.notReceived());
                for (ContentSource source : chunk) {
                    if (!failed.contains(source.contentId())) uploadedIds.add(source.contentId());
                }
                doneBytes += chunkBytes;
                doneCount += chunk.size();
                if (!last) {
                    out.println("Uploaded " + doneCount + " of " + order.size() + " ("
                            + ManifestBuilder.formatBytes(doneBytes) + " of "
                            + ManifestBuilder.formatBytes(totalBytes) + ")");
                }
                chunk = new ArrayList<>();
                chunkBytes = 0L;
            }
        }
        if (!notReceived.isEmpty()) {
            StringBuilder message = new StringBuilder();
            message.append(notReceived.size()).append(notReceived.size() == 1 ? " file" : " files")
                    .append(" changed or vanished after hashing, so the backup wasn't committed:");
            int shown = 0;
            for (String id : notReceived) {
                if (shown++ == ManifestBuilder.SUMMARY_LIMIT) {
                    message.append("\n  ... and ").append(notReceived.size() - ManifestBuilder.SUMMARY_LIMIT)
                            .append(" more");
                    break;
                }
                ContentLocation location = locations.get(id);
                String problem = problems.get(id);
                message.append("\n  ").append(location.displayPath()).append(": ")
                        .append(problem == null ? "its content no longer matches its hash" : problem);
            }
            throw new BackupFailedException(message.toString());
        }
        return totalBytes;
    }

    /**
     * Reads a piece of content from the snapshot. The stream always gives exactly {@code length} bytes: a file that
     * can't be opened or got shorter is padded with zeros, so the server computes a different hash and reports the
     * content as not received, instead of the request failing and being retried. {@code problems} records why.
     */
    static ContentSource contentSource(String id, ContentLocation location, Map<String, String> problems) {
        String filename = location.path();
        int slash = filename.lastIndexOf('/');
        if (slash >= 0) filename = filename.substring(slash + 1);
        return ContentSource.ofStream(id, filename, location.length(), () -> {
            InputStream in;
            try {
                in = new FileInputStream(new File(location.scanPath(), location.path()));
            } catch (IOException e) {
                problems.put(id, "can't be read (" + IoMessages.describe(e) + ")");
                in = InputStream.nullInputStream();
            }
            return new ExactLengthInputStream(in, location.offset(), location.length(),
                    () -> problems.putIfAbsent(id, "it got shorter after it was hashed"));
        });
    }

    /**
     * Finds where each of {@code ids} can be read, walking the manifest. A content ID can be in many files; the
     * first one found is used.
     */
    static Map<String, ContentLocation> locate(Path manifest, Map<String, String> scanPaths, Collection<String> ids)
            throws IOException {
        Set<String> wanted = new LinkedHashSet<>(ids);
        Map<String, ContentLocation> found = new LinkedHashMap<>();
        if (wanted.isEmpty()) return found;
        try (BackupFile file = BackupFile.open(manifest.toFile())) {
            for (DirectoryEntry sourceEntry : file.getRootDirectory()) {
                if (!sourceEntry.isDirectory()) continue;
                String scanPath = scanPaths.get(sourceEntry.getName());
                if (scanPath == null) continue;
                locate(sourceEntry.asDirectory(), sourceEntry.getName(), scanPath, "", wanted, found);
                if (found.size() == wanted.size()) break;
            }
        }
        return found;
    }

    private static void locate(DirectoryEntryDirectory directory, String sourceName, String scanPath, String path,
                               Set<String> wanted, Map<String, ContentLocation> found) {
        for (DirectoryEntry entry : directory) {
            if (found.size() == wanted.size()) return;
            if (entry.isFile()) {
                for (FileContent content : entry.asFile().getFileContents()) {
                    if (!(content instanceof ContentIDFileContent idContent)) continue;
                    String id = Util.bytesToHex(idContent.getHash());
                    if (wanted.contains(id) && !found.containsKey(id)) {
                        found.put(id, new ContentLocation(sourceName, scanPath, path + entry.getName(),
                                content.getOffset(), content.getLength()));
                    }
                }
            } else if (entry.isDirectory()) {
                locate(entry.asDirectory(), sourceName, scanPath, path + entry.getName() + "/", wanted, found);
            }
        }
    }

    /**
     * Gives exactly {@code length} bytes of {@code in} starting at {@code offset}, padding with zeros (and calling
     * {@code onShort}) if it ends early.
     */
    static final class ExactLengthInputStream extends InputStream {
        private final InputStream in;
        private final long offset;
        private final Runnable onShort;
        private long remaining;
        private boolean skipped;
        private boolean ended;

        ExactLengthInputStream(InputStream in, long offset, long length, Runnable onShort) {
            this.in = in;
            this.offset = offset;
            this.remaining = length;
            this.onShort = onShort;
        }

        private void skipToOffset() throws IOException {
            if (skipped) return;
            skipped = true;
            long toSkip = offset;
            while (toSkip > 0) {
                long n = in.skip(toSkip);
                if (n <= 0) {
                    if (in.read() < 0) {
                        markEnded();
                        return;
                    }
                    n = 1;
                }
                toSkip -= n;
            }
        }

        private void markEnded() {
            if (!ended) {
                ended = true;
                if (remaining > 0) onShort.run();
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (remaining <= 0) return -1;
            skipToOffset();
            int want = (int) Math.min(len, remaining);
            int n = -1;
            if (!ended) {
                n = in.read(b, off, want);
                if (n < 0) markEnded();
            }
            if (n < 0) {
                Arrays.fill(b, off, off + want, (byte) 0);
                n = want;
            }
            remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
