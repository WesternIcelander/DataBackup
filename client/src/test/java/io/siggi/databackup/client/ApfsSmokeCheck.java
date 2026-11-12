package io.siggi.databackup.client;

import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.client.snapshot.SnapshotPlatform;
import io.siggi.databackup.client.snapshot.SourceSnapshot;
import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.data.extra.ExtraDataSnapshotInfoApfs;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.apfs.ApfsSnapshot;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper for {@code docs/manual-checks/macos-apfs-smoke.sh}, run as root on a Mac. It isn't a JUnit test (the name
 * doesn't end in {@code Test}) and isn't in the client jar, so it can reach the test-only
 * {@link ManifestBuilder#build} path without making it reachable from the CLI.
 * <ul>
 *     <li>{@code backup <config> <testFolder> <fileToModify> <resultFile>}: runs the client's real snapshot
 *     orchestration ({@link SnapshotCommands#backup}) on the config's single source (the Data volume, {@code /}).
 *     While the snapshot is mounted it appends to {@code fileToModify} in the live folder, then scans
 *     {@code testFolder} inside the snapshot into {@code stateDir/pending-manifest}. The snapshot is deleted when
 *     the work returns. Writes what it saw to {@code resultFile} as {@code key=value} lines.</li>
 *     <li>{@code hold <config>}: the same orchestration, but once the snapshot is mounted it prints
 *     {@code SNAPSHOT_READY} and sleeps forever, so the script can {@code kill -9} it with a snapshot recorded.</li>
 *     <li>{@code dump <manifest>}: prints the manifest's {@code Smoke} source as tab-separated lines.</li>
 * </ul>
 * {@code snapshots cleanup} goes through the real CLI ({@link DataBackupClient}), not this class.
 */
public final class ApfsSmokeCheck {

    static final String SOURCE_NAME = "Smoke";

    private ApfsSmokeCheck() {
    }

    public static void main(String[] args) throws Exception {
        int code;
        if (args.length == 5 && args[0].equals("backup")) {
            code = backup(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]), Paths.get(args[4]));
        } else if (args.length == 2 && args[0].equals("hold")) {
            code = hold(Paths.get(args[1]));
        } else if (args.length == 2 && args[0].equals("dump")) {
            code = dump(Paths.get(args[1]), System.out);
        } else {
            System.err.println("Usage: ApfsSmokeCheck backup <config> <testFolder> <fileToModify> <resultFile>");
            System.err.println("       ApfsSmokeCheck hold <config>");
            System.err.println("       ApfsSmokeCheck dump <manifest>");
            code = 2;
        }
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }

    private static ClientConfig load(Path config) throws ClientConfig.ConfigException {
        ClientConfig clientConfig = ClientConfig.load(config, config.toAbsolutePath().getParent().resolve("state"));
        if (clientConfig.getSources().size() != 1) {
            throw new ClientConfig.ConfigException("the smoke-test config must have exactly one source");
        }
        return clientConfig;
    }

    static int backup(Path config, Path testFolder, Path fileToModify, Path resultFile) throws Exception {
        ClientConfig clientConfig = load(config);
        Map<String, String> result = new LinkedHashMap<>();
        PrintStream out = System.out;
        int code = SnapshotCommands.backup(clientConfig, SnapshotPlatform.system(), snapshots -> {
            SourceSnapshot snapshot = snapshots.get(0);
            String volume = snapshot.volume().volume();
            String scanPath = snapshot.scanPath();
            result.put("volume", volume);
            result.put("scanPath", scanPath);
            result.put("snapshot", String.valueOf(snapshot.snapshot()));
            if (snapshot.snapshot() instanceof ApfsSnapshot apfs) {
                result.put("snapshotName", apfs.name());
                result.put("snapshotXid", Long.toString(apfs.xid()));
                result.put("snapshotDevice", String.valueOf(apfs.device()));
            }
            Snapshot raw = snapshot.snapshot();
            result.put("mountedPathDuringScan", String.valueOf(raw.mountedPath()));

            String folder = testFolder.toRealPath().toString();
            String relative;
            if (folder.startsWith(volume + "/")) {
                relative = folder.substring(volume.length() + 1);
            } else if (volume.equals("/System/Volumes/Data") && folder.startsWith("/")) {
                // Firmlinked paths (/Users, /private, ...) are the same path inside the Data volume.
                relative = folder.substring(1);
            } else {
                throw new IllegalStateException(folder + " isn't on " + volume);
            }
            Path scanFolder = Paths.get(scanPath).resolve(relative);
            result.put("scanFolder", scanFolder.toString());
            result.put("scanFolderExists", Boolean.toString(Files.isDirectory(scanFolder)));
            result.put("scanFolderWritable", Boolean.toString(probeWritable(scanFolder)));

            // Change a live file after the snapshot was taken: the manifest must still hold the snapshot's version.
            Files.writeString(fileToModify, "changed after the snapshot\n", StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);

            Source live = clientConfig.getSources().get(0);
            Source smoke = new Source(SOURCE_NAME, testFolder.toAbsolutePath(), List.of(), live.matcher());
            ManifestBuilder.ScanSource scanSource = new ManifestBuilder.ScanSource(smoke, scanFolder.toString(), null,
                    ManifestBuilder.scanSourceFor(snapshot).extra());
            ManifestBuilder.Result manifest = new ManifestBuilder(clientConfig.getStateDir(), out)
                    .build(List.of(scanSource));
            result.put("manifest", manifest.manifest().toString());
            result.put("files", Long.toString(manifest.files()));
            result.put("failedItems", Integer.toString(manifest.failedItems().size()));
            return ClientCli.OK;
        }, out, System.err);
        result.put("exitCode", Integer.toString(code));
        writeResult(resultFile, result);
        return code;
    }

    /**
     * A read-only snapshot mount must refuse new files.
     */
    private static boolean probeWritable(Path folder) {
        Path probe = folder.resolve(".databackup-write-probe");
        try {
            Files.writeString(probe, "x");
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static int hold(Path config) throws Exception {
        ClientConfig clientConfig = load(config);
        return SnapshotCommands.backup(clientConfig, SnapshotPlatform.system(), snapshots -> {
            SourceSnapshot snapshot = snapshots.get(0);
            String name = snapshot.snapshot() instanceof ApfsSnapshot apfs ? apfs.name() : "?";
            System.out.println("SNAPSHOT_READY name=" + name + " mount=" + snapshot.scanPath());
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
            return ClientCli.FAILED;
        }, System.out, System.err);
    }

    private static void writeResult(Path resultFile, Map<String, String> result) throws Exception {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : result.entrySet()) {
            text.append(entry.getKey()).append('=').append(entry.getValue().replace('\n', ' ')).append('\n');
        }
        Files.writeString(resultFile, text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Prints, tab-separated:
     * <pre>
     * snapshotId  &lt;uuid&gt;
     * filePath    &lt;ExtraDataFilePath of the Smoke source&gt;
     * apfs        &lt;snapshot name&gt;  &lt;xid&gt;
     * file        &lt;relative path&gt;  &lt;size&gt;  &lt;sha256 hex, or "none"&gt;
     * </pre>
     */
    static int dump(Path manifestFile, PrintStream out) throws Exception {
        try (BackupFile manifest = BackupFile.open(manifestFile.toFile())) {
            DirectoryEntryDirectory root = manifest.getRootDirectory();
            ExtraDataSnapshotId id = root.getExtra(ExtraDataSnapshotId.class);
            out.println("snapshotId\t" + (id == null ? "none" : id.uuid.toString()));
            DirectoryEntry sourceEntry = root.getEntry(SOURCE_NAME);
            if (sourceEntry == null || !sourceEntry.isDirectory()) {
                out.println("error\tno " + SOURCE_NAME + " directory in the manifest");
                return 1;
            }
            DirectoryEntryDirectory source = sourceEntry.asDirectory();
            ExtraDataFilePath filePath = source.getExtra(ExtraDataFilePath.class);
            out.println("filePath\t" + (filePath == null ? "none" : filePath.path));
            ExtraDataSnapshotInfoApfs apfs = source.getExtra(ExtraDataSnapshotInfoApfs.class);
            out.println("apfs\t" + (apfs == null ? "none\tnone" : apfs.name + "\t" + apfs.xid));
            List<String> lines = new ArrayList<>();
            collect(source, "", lines);
            lines.sort(null);
            for (String line : lines) out.println(line);
        }
        return 0;
    }

    private static void collect(DirectoryEntryDirectory directory, String path, List<String> lines) {
        for (DirectoryEntry entry : directory) {
            if (entry.isFile()) {
                DirectoryEntryFile file = entry.asFile();
                String hash = "none";
                List<FileContent> contents = file.getFileContents();
                if (contents.size() == 1 && contents.get(0) instanceof ContentIDFileContent content) {
                    hash = HexFormat.of().formatHex(content.getHash());
                } else if (!contents.isEmpty()) {
                    hash = "unexpected-" + contents.size() + "-contents";
                }
                lines.add("file\t" + path + entry.getName() + "\t" + file.getSize() + "\t" + hash);
            } else if (entry.isDirectory()) {
                collect(entry.asDirectory(), path + entry.getName() + "/", lines);
            }
        }
    }
}
