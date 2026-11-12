package io.siggi.databackup.client;

import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.data.extra.ExtraDataSnapshotInfoApfs;
import io.siggi.databackup.util.Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the manifest dump that {@code docs/manual-checks/macos-apfs-smoke.sh} compares against {@code shasum}.
 */
class ApfsSmokeCheckTest {

    @TempDir
    Path dir;

    @Test
    void dumpsTheSmokeSource() throws Exception {
        Path folder = dir.resolve("files");
        Files.createDirectories(folder.resolve("sub dir"));
        Files.writeString(folder.resolve("a.txt"), "hello");
        Files.writeString(folder.resolve("sub dir/b.txt"), "bee");
        Files.write(folder.resolve("empty"), new byte[0]);

        Source source = new Source(ApfsSmokeCheck.SOURCE_NAME, folder, List.of(), ExcludeMatcher.none());
        ExtraDataSnapshotInfoApfs info = new ExtraDataSnapshotInfoApfs("com.apple.TimeMachine.x.local", 42L);
        ManifestBuilder.Result result = new ManifestBuilder(dir.resolve("state"), new PrintStream(
                new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .build(List.of(new ManifestBuilder.ScanSource(source, folder.toString(), null, List.of(info))));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertEquals(0, ApfsSmokeCheck.dump(result.manifest(), new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        String expected = "snapshotId\t" + result.snapshotId() + "\n"
                + "filePath\t" + ManifestBuilder.livePath(folder) + "\n"
                + "apfs\tcom.apple.TimeMachine.x.local\t42\n"
                + "file\ta.txt\t5\t" + sha256("hello") + "\n"
                + "file\tempty\t0\t" + sha256("") + "\n"
                + "file\tsub dir/b.txt\t3\t" + sha256("bee") + "\n";
        assertEquals(expected, bytes.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    private static String sha256(String text) {
        return HexFormat.of().formatHex(Util.sha256().digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
