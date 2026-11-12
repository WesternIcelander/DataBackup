package io.siggi.databackup.client;

import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.client.snapshot.SourceSnapshot;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.extra.ExtraData;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.data.extra.ExtraDataSnapshotInfoApfs;
import io.siggi.databackup.data.extra.ExtraDataSnapshotInfoBtrfs;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.apfs.ApfsSnapshot;
import io.siggi.databackup.diskutil.btrfs.BtrfsSnapshot;
import io.siggi.databackup.scanner.FileMetadataScanner;
import io.siggi.databackup.util.ObjectWriter;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Scans every source's snapshot and writes the backup manifest to {@code stateDir/pending-manifest}.
 * <p>
 * Layout: the root directory (named {@code ""}) carries a new random {@link ExtraDataSnapshotId}; each source is a
 * top-level directory named by its configured name, carrying {@link ExtraDataFilePath} with the live path (not the
 * snapshot path) and, for APFS and btrfs, the snapshot's info. Files whose size and modified time match the same
 * source in {@code stateDir/last-manifest} reuse its hashes instead of being hashed again.
 */
final class ManifestBuilder {

    static final String PENDING_MANIFEST = "pending-manifest";
    static final String LAST_MANIFEST = "last-manifest";

    /**
     * How many failed or ignored paths the summary lists before saying "and N more".
     */
    static final int SUMMARY_LIMIT = 50;

    /**
     * One source to scan.
     *
     * @param source           the configured source
     * @param scanPath         where its contents are read from (a snapshot, or a plain folder in tests)
     * @param rootListingPath  where its top level is listed from instead of {@code scanPath}, or null (Windows shadow
     *                         copies, whose root can't be listed)
     * @param extra            extra data for the source's directory, besides {@link ExtraDataFilePath}
     */
    record ScanSource(Source source, String scanPath, String rootListingPath, List<ExtraData> extra) {
    }

    /**
     * What a build produced.
     *
     * @param manifest      the written manifest
     * @param snapshotId    the backup's ID, also in the root's {@link ExtraDataSnapshotId}
     * @param files         files in the manifest
     * @param bytes         their total size
     * @param hashedFiles   files hashed in this run
     * @param hashedBytes   their total size
     * @param reusedFiles   files whose hashes were reused from {@code last-manifest}
     * @param failedItems   paths that couldn't be read, prefixed with the source name
     * @param ignoredItems  paths left out by excludes or {@code .nobackup}, prefixed with the source name
     */
    record Result(Path manifest, UUID snapshotId, long files, long bytes, long hashedFiles, long hashedBytes,
                  long reusedFiles, List<String> failedItems, List<String> ignoredItems) {
    }

    private final Path stateDir;
    private final PrintStream out;
    private final long progressIntervalMillis;

    ManifestBuilder(Path stateDir, PrintStream out) {
        this(stateDir, out, 1000L);
    }

    ManifestBuilder(Path stateDir, PrintStream out, long progressIntervalMillis) {
        this.stateDir = stateDir;
        this.out = out;
        this.progressIntervalMillis = progressIntervalMillis;
    }

    /**
     * Builds the manifest from the snapshots of a backup run.
     */
    Result buildFromSnapshots(List<SourceSnapshot> snapshots) throws IOException, InterruptedException {
        List<ScanSource> sources = new ArrayList<>();
        for (SourceSnapshot snapshot : snapshots) {
            sources.add(scanSourceFor(snapshot));
        }
        return build(sources);
    }

    static ScanSource scanSourceFor(SourceSnapshot snapshot) {
        List<ExtraData> extra = new ArrayList<>();
        ExtraData info = snapshotInfo(snapshot.snapshot());
        if (info != null) extra.add(info);
        String rootListing = null;
        if (snapshot.volume().diskUtil().filesystem().toLowerCase(Locale.ROOT).equals("ntfs")) {
            // The root of a shadow copy can't be listed; list it from the live drive (C:\) instead.
            rootListing = snapshot.volume().volume();
        }
        return new ScanSource(snapshot.source(), snapshot.scanPath(), rootListing, extra);
    }

    static ExtraData snapshotInfo(Snapshot snapshot) {
        if (snapshot instanceof ApfsSnapshot apfs) return new ExtraDataSnapshotInfoApfs(apfs.name(), apfs.xid());
        if (snapshot instanceof BtrfsSnapshot btrfs) return new ExtraDataSnapshotInfoBtrfs(btrfs.path());
        return null;
    }

    /**
     * The live path as stored in {@link ExtraDataFilePath}: {@code /} separators, no trailing separator except on a
     * root ({@code /} or {@code C:/}).
     */
    static String livePath(Path path) {
        String s = path.toString().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/") && !(s.length() == 3 && s.charAt(1) == ':')) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Scans the sources and writes {@code stateDir/pending-manifest}. Tests call this directly with plain folders;
     * the CLI only reaches it through {@link #buildFromSnapshots}.
     */
    Result build(List<ScanSource> sources) throws IOException, InterruptedException {
        UUID snapshotId = UUID.randomUUID();
        DirectoryEntryDirectoryMemory root = new DirectoryEntryDirectoryMemory("");
        root.getExtra().add(new ExtraDataSnapshotId(snapshotId));

        List<FileMetadataScanner> scanners = new ArrayList<>();
        for (ScanSource scanSource : sources) {
            Source source = scanSource.source();
            out.println("Scanning " + source.name() + " (" + source.path() + ")...");
            DirectoryEntryDirectoryMemory directory = new DirectoryEntryDirectoryMemory(source.name());
            directory.setParent(root);
            directory.getExtra().add(new ExtraDataFilePath(livePath(source.path())));
            directory.getExtra().addAll(scanSource.extra());
            FileMetadataScanner scanner = new FileMetadataScanner(directory, new File(scanSource.scanPath()));
            scanner.setPathPredicate(path -> !source.matcher().matches(path));
            if (scanSource.rootListingPath() != null) {
                scanner.setRootListingDirectory(new File(scanSource.rootListingPath()));
            }
            scanner.grabFiles();
            scanners.add(scanner);
        }
        try (ObjectWriter<DirectoryEntry> writer = root.updateEntries()) {
            for (FileMetadataScanner scanner : scanners) {
                writer.write(scanner.rootDirectory);
            }
        }

        long files = 0L;
        long bytes = 0L;
        for (FileMetadataScanner scanner : scanners) {
            files += scanner.fileCount;
            bytes += scanner.totalFileSize;
        }

        Path pending = stateDir.resolve(PENDING_MANIFEST);
        Files.createDirectories(stateDir);
        long reusedFiles;
        long[] hashed;
        BackupFile last = openLastManifest();
        try {
            long beforeCopy = unhashedFiles(scanners);
            if (last != null) {
                DirectoryEntryDirectory lastRoot = last.getRootDirectory();
                for (int i = 0; i < sources.size(); i++) {
                    DirectoryEntry previous = lastRoot.getEntry(sources.get(i).source().name());
                    if (previous != null && previous.isDirectory()) {
                        scanners.get(i).copyHashes(previous.asDirectory());
                    }
                }
            }
            reusedFiles = beforeCopy - unhashedFiles(scanners);
            out.println("Found " + files + " files (" + formatBytes(bytes) + "); " + unhashedFiles(scanners) + " ("
                    + formatBytes(unhashedBytes(scanners)) + ") need hashing.");
            hashed = hashAll(scanners);
            // A file that couldn't be read has no hash; leave it out rather than store an entry without content.
            for (FileMetadataScanner scanner : scanners) {
                removeUnhashedFiles(scanner.rootDirectory);
            }

            Path temp = stateDir.resolve(PENDING_MANIFEST + ".tmp");
            BackupFile.writeToFile(temp.toFile(), root);
            Files.move(temp, pending, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (last != null) last.close();
        }

        List<String> failed = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        long failedFiles = 0L;
        long failedBytes = 0L;
        for (int i = 0; i < scanners.size(); i++) {
            FileMetadataScanner scanner = scanners.get(i);
            String name = sources.get(i).source().name();
            for (String item : scanner.failedItems) failed.add(name + "/" + item);
            for (String item : scanner.ignoredItems) ignored.add(name + "/" + item);
            failedFiles += scanner.unhashedFiles;
            failedBytes += scanner.unhashedSize;
        }
        Result result = new Result(pending, snapshotId, files - failedFiles, bytes - failedBytes, hashed[0],
                hashed[1], reusedFiles, List.copyOf(failed), List.copyOf(ignored));
        printSummary(result);
        return result;
    }

    private static long unhashedFiles(List<FileMetadataScanner> scanners) {
        long total = 0L;
        for (FileMetadataScanner scanner : scanners) total += scanner.unhashedFiles;
        return total;
    }

    private static long unhashedBytes(List<FileMetadataScanner> scanners) {
        long total = 0L;
        for (FileMetadataScanner scanner : scanners) total += scanner.unhashedSize;
        return total;
    }

    private static void removeUnhashedFiles(DirectoryEntryDirectory directory) {
        directory.getEntries().values().removeIf(entry -> entry.isFile() && !entry.asFile().hasFileContents());
        for (DirectoryEntry entry : directory) {
            if (entry.isDirectory()) removeUnhashedFiles(entry.asDirectory());
        }
    }

    /**
     * Hashes every unhashed file, printing what's left at most once per progress interval.
     *
     * @return the files and bytes hashed
     */
    private long[] hashAll(List<FileMetadataScanner> scanners) throws InterruptedException {
        long startFiles = unhashedFiles(scanners);
        long startBytes = unhashedBytes(scanners);
        if (startFiles == 0L) return new long[]{0L, 0L};
        Thread progress = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(progressIntervalMillis);
                    out.println("Hashing: " + unhashedFiles(scanners) + " files ("
                            + formatBytes(unhashedBytes(scanners)) + ") left");
                }
            } catch (InterruptedException ignored) {
            }
        }, "manifest-progress");
        progress.setDaemon(true);
        progress.start();
        try {
            for (FileMetadataScanner scanner : scanners) {
                scanner.fillInHashes(null);
            }
        } finally {
            progress.interrupt();
            try {
                progress.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Files that failed to hash stay counted as unhashed.
        return new long[]{startFiles - unhashedFiles(scanners), startBytes - unhashedBytes(scanners)};
    }

    private BackupFile openLastManifest() {
        Path lastManifest = stateDir.resolve(LAST_MANIFEST);
        if (!Files.isRegularFile(lastManifest)) return null;
        try {
            BackupFile file = BackupFile.open(lastManifest.toFile());
            file.getRootDirectory().getEntries(); // fail now, not halfway through, if it's unreadable
            return file;
        } catch (Exception e) {
            out.println("Warning: can't read " + lastManifest + " (" + e + "); hashing every file.");
            return null;
        }
    }

    private void printSummary(Result result) {
        out.println("Manifest: " + result.files() + " files (" + formatBytes(result.bytes()) + "), "
                + result.hashedFiles() + " hashed (" + formatBytes(result.hashedBytes()) + "), "
                + result.reusedFiles() + " reused from the last backup.");
        printList("Ignored (excluded or .nobackup)", result.ignoredItems());
        printList("Failed (couldn't be read, left out of the backup)", result.failedItems());
    }

    private void printList(String heading, List<String> items) {
        if (items.isEmpty()) return;
        out.println(heading + ": " + items.size());
        int shown = Math.min(items.size(), SUMMARY_LIMIT);
        for (int i = 0; i < shown; i++) {
            out.println("  " + items.get(i));
        }
        if (items.size() > shown) out.println("  ... and " + (items.size() - shown) + " more");
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
