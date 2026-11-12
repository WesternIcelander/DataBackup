package io.siggi.databackup.server.gc;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.datarepository.StoredDataStream;
import io.siggi.databackup.util.TimeUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Mark-and-sweep garbage collection for the server's data folder.
 * <ol>
 *     <li>Deletes files in {@code tmp/} and pending manifests ({@code backups/<machine>/.pending/*}) that are older
 *     than the grace period.</li>
 *     <li>Marks every content ID referenced by a committed manifest (a file in {@code backups/<machine>/} whose name
 *     is a backup timestamp) or by a remaining pending manifest, of every machine.</li>
 *     <li>Deletes stored content that isn't marked and whose last touch is older than the grace period, so content
 *     uploaded for a backup that hasn't uploaded its manifest yet is kept.</li>
 * </ol>
 * If a committed manifest can't be read, no content is deleted, since what it references is unknown.
 * <p>
 * The caller must make sure no manifest is uploaded or committed while this runs (the server holds a lock shared
 * with those paths). Content uploads may run concurrently: each piece of content is re-checked and deleted while
 * holding {@code contentLock} for its ID, the same lock the upload path holds while it checks for and touches
 * existing content.
 */
public final class GarbageCollector {

    private final File backupsDir;
    private final File tmpDir;
    private final DataRepository repository;
    private final long gracePeriodMillis;
    private final Function<String, Object> contentLock;

    /**
     * @param backupsDir        the {@code backups} folder, holding one folder per machine
     * @param tmpDir            the {@code tmp} folder
     * @param repository        the content store
     * @param gracePeriodMillis how old unreferenced content, pending manifests and temp files must be to be deleted
     * @param contentLock       the lock object to hold while deleting a content ID
     */
    public GarbageCollector(File backupsDir, File tmpDir, DataRepository repository, long gracePeriodMillis,
                            Function<String, Object> contentLock) {
        this.backupsDir = backupsDir;
        this.tmpDir = tmpDir;
        this.repository = repository;
        this.gracePeriodMillis = gracePeriodMillis;
        this.contentLock = contentLock;
    }

    /**
     * What garbage collection did, or would do on a dry run.
     *
     * @param contentDeleted   content items deleted
     * @param contentBytes     bytes those content items took on disk (compressed size where compressed)
     * @param contentReferenced distinct content IDs referenced by the committed and pending manifests
     * @param contentKeptNew   unreferenced content kept because it was touched within the grace period
     * @param pendingDeleted   pending manifests deleted
     * @param pendingBytes     bytes of those pending manifests
     * @param tmpDeleted       temp files deleted
     * @param tmpBytes         bytes of those temp files
     * @param failed           paths or content IDs that should have been deleted but could not be
     */
    public record Result(int contentDeleted, long contentBytes, int contentReferenced, int contentKeptNew,
                         int pendingDeleted, long pendingBytes, int tmpDeleted, long tmpBytes, List<String> failed) {

        /**
         * Items deleted, of all kinds.
         */
        public int itemsDeleted() {
            return contentDeleted + pendingDeleted + tmpDeleted;
        }

        /**
         * Bytes freed, of all kinds.
         */
        public long bytesFreed() {
            return contentBytes + pendingBytes + tmpBytes;
        }

        /**
         * A one-line summary.
         */
        public String summary(boolean dryRun) {
            String verb = dryRun ? "would free " : "freed ";
            return verb + itemsDeleted() + " item(s), " + bytesFreed() + " bytes: "
                    + contentDeleted + " content item(s) (" + contentBytes + " bytes), "
                    + pendingDeleted + " pending manifest(s) (" + pendingBytes + " bytes), "
                    + tmpDeleted + " temp file(s) (" + tmpBytes + " bytes); "
                    + contentReferenced + " content item(s) referenced, "
                    + contentKeptNew + " unreferenced kept within the grace period"
                    + (failed.isEmpty() ? "" : "; " + failed.size() + " could not be deleted");
        }
    }

    /**
     * Thrown when a committed manifest can't be read. Temp files and old pending manifests have already been
     * handled by then, but no content was deleted.
     */
    public static final class ManifestUnreadableException extends IOException {
        public ManifestUnreadableException(File manifest, Throwable cause) {
            super("Could not read manifest " + manifest + ", so no content was deleted: " + cause, cause);
        }
    }

    /**
     * Runs garbage collection.
     *
     * @param dryRun if true, only work out what would be deleted
     * @param now    the current time, in milliseconds since the epoch
     */
    public Result run(boolean dryRun, long now) throws IOException {
        long cutoff = now - gracePeriodMillis;
        List<String> failed = new ArrayList<>();

        int tmpDeleted = 0;
        long tmpBytes = 0L;
        File[] tmpFiles = tmpDir.listFiles();
        if (tmpFiles != null) {
            for (File file : tmpFiles) {
                if (!file.isFile() || file.lastModified() >= cutoff) continue;
                long length = file.length();
                if (dryRun || file.delete()) {
                    tmpDeleted++;
                    tmpBytes += length;
                } else {
                    failed.add(file.getPath());
                }
            }
        }

        Set<String> marked = new HashSet<>();
        List<File> committed = new ArrayList<>();
        List<File> pendingKept = new ArrayList<>();
        int pendingDeleted = 0;
        long pendingBytes = 0L;
        File[] machines = backupsDir.listFiles();
        if (machines != null) {
            for (File machine : machines) {
                if (!machine.isDirectory()) continue;
                File[] files = machine.listFiles();
                if (files == null) throw new IOException("Could not list " + machine);
                for (File file : files) {
                    if (file.isFile() && !file.getName().startsWith(".")
                            && TimeUtil.fromString(file.getName()) != -1L) {
                        committed.add(file);
                    }
                }
                File[] pending = new File(machine, ".pending").listFiles();
                if (pending == null) continue;
                for (File file : pending) {
                    if (!file.isFile()) continue;
                    if (file.lastModified() >= cutoff) {
                        pendingKept.add(file);
                        continue;
                    }
                    long length = file.length();
                    if (dryRun || file.delete()) {
                        pendingDeleted++;
                        pendingBytes += length;
                    } else {
                        failed.add(file.getPath());
                        pendingKept.add(file);
                    }
                }
            }
        }

        for (File file : committed) {
            try {
                mark(file, marked);
            } catch (IOException | RuntimeException e) {
                throw new ManifestUnreadableException(file, e);
            }
        }
        for (File file : pendingKept) {
            try {
                mark(file, marked);
            } catch (IOException | RuntimeException e) {
                // An unreadable pending manifest can never be committed, so it protects nothing. It is deleted
                // once it's older than the grace period.
            }
        }

        int contentDeleted = 0;
        long contentBytes = 0L;
        int contentKeptNew = 0;
        if (repository.getRoot().isDirectory()) {
            List<String> candidates = new ArrayList<>();
            try (StoredDataStream stream = repository.openStream()) {
                for (StoredData storedData : stream) {
                    if (marked.contains(storedData.getContentId())) continue;
                    if (storedData.getLastTouch() >= cutoff) {
                        contentKeptNew++;
                        continue;
                    }
                    candidates.add(storedData.getContentId());
                }
            }
            for (String contentId : candidates) {
                synchronized (contentLock.apply(contentId)) {
                    // A fresh instance, so a touch from an upload since the scan above is seen.
                    StoredData storedData = repository.getStoredData(contentId);
                    if (storedData.getLastTouch() >= cutoff) {
                        contentKeptNew++;
                        continue;
                    }
                    File file = storedData.getFile();
                    long length = file.isFile() ? file.length() : 0L;
                    if (!dryRun) {
                        storedData.deleteFile();
                        if (file.exists() || storedData.getMetadataFile().exists()) {
                            failed.add(contentId);
                            continue;
                        }
                    }
                    contentDeleted++;
                    contentBytes += length;
                }
            }
        }

        return new Result(contentDeleted, contentBytes, marked.size(), contentKeptNew, pendingDeleted, pendingBytes,
                tmpDeleted, tmpBytes, List.copyOf(failed));
    }

    private void mark(File manifest, Set<String> marked) throws IOException {
        try (BackupFile file = BackupFile.open(manifest)) {
            mark(file.getRootDirectory(), marked);
        }
    }

    private void mark(DirectoryEntryDirectory directory, Set<String> marked) {
        for (DirectoryEntry entry : directory) {
            if (entry.isDirectory()) {
                mark(entry.asDirectory(), marked);
            } else if (entry.isFile()) {
                for (FileContent content : entry.asFile()) {
                    StoredData storedData = content.getStoredData(repository);
                    if (storedData != null) marked.add(storedData.getContentId());
                }
            }
        }
    }
}
