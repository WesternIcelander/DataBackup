package io.siggi.databackup.data;

import static io.siggi.databackup.testutil.TreeAssert.assertTreeEquals;
import static io.siggi.databackup.testutil.TreeBuilder.contentId;
import static io.siggi.databackup.testutil.TreeBuilder.dir;
import static io.siggi.databackup.testutil.TreeBuilder.file;
import static io.siggi.databackup.testutil.TreeBuilder.inlined;
import static io.siggi.databackup.testutil.TreeBuilder.with;
import static io.siggi.databackup.testutil.TreeBuilder.zeros;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.content.ZeroFilledFileContent;
import io.siggi.databackup.data.extra.ExtraData;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.data.extra.ExtraDataUnsupported;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes trees with {@link BackupFile#writeToFile} and checks that {@link BackupFile#open} gives back the same tree.
 */
class BackupFormatRoundTripTest {
    @TempDir
    File tempDir;

    /**
     * A tree with every entry type, every file content type, nested and empty directories, and extra data.
     */
    static DirectoryEntryDirectoryMemory sampleTree() throws IOException {
        UUID snapshotId = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
        return with(dir("ROOT",
                with(file("hashed.bin", 1_700_000_000_123L, 4096L, contentId(1, 0L, 4096L)),
                    new ExtraDataPosixPermissions(0644, 501, 20),
                    new ExtraDataNanosecondModifiedDate(1_700_000_000L, 123_456_789)),
                file("inline.txt", 1_700_000_001_000L, 5L, inlined(0L, "hello")),
                file("sparse.img", 1_700_000_002_000L, 1L << 20, zeros(0L, 1L << 20)),
                file("mixed.dat", 1_700_000_003_000L, 2048L + 5L + 1024L,
                    contentId(2, 0L, 2048L), inlined(2048L, "héllo"), zeros(2053L, 1024L)),
                file("unhashed.txt", 1_700_000_004_000L, 99L),
                file("empty-file", 1_700_000_005_000L, 0L),
                with(new DirectoryEntrySymlink("link-to-hashed", "hashed.bin"),
                    new ExtraDataPosixPermissions(0755, 501, 20)),
                new DirectoryEntrySymlink("dangling", "/does/not/exist"),
                dir("empty-dir"),
                with(dir("a",
                        file("a1.txt", 10L, 3L, inlined(0L, "abc")),
                        with(dir("b",
                                with(dir("c",
                                        file("deep.txt", 20L, 7L, contentId(3, 0L, 7L)),
                                        new DirectoryEntrySymlink("up", "../../a1.txt"),
                                        dir("empty-deep")),
                                    new ExtraDataPosixPermissions(0700, 0, 0))),
                            new ExtraDataPosixPermissions(0750, 1000, 1000),
                            new ExtraDataFilePath("/elsewhere/b")),
                        dir("ünïcödé ñame", file("名前.txt", 30L, 1L, inlined(0L, "x"))))
                    , new ExtraDataPosixPermissions(0755, 501, 20))),
            new ExtraDataFilePath("/Users/someone/source"),
            new ExtraDataSnapshotId(snapshotId),
            new ExtraDataPosixPermissions(0755, 501, 20),
            new ExtraDataNanosecondModifiedDate(1_699_999_999L, 1));
    }

    private File write(DirectoryEntryDirectory tree) throws IOException {
        File file = new File(tempDir, "manifest-" + System.nanoTime());
        BackupFile.writeToFile(file, tree);
        return file;
    }

    @Test
    void roundTripsFullTree() throws IOException {
        File file = write(sampleTree());
        try (BackupFile backupFile = BackupFile.open(file)) {
            assertTreeEquals(sampleTree(), backupFile.getRootDirectory());
        }
        try (BackupFile backupFile = BackupFile.open(file, true)) {
            assertTreeEquals(sampleTree(), backupFile.getRootDirectory());
        }
    }

    @Test
    void contentTypesKeepTheirClassAndData() throws IOException {
        File file = write(sampleTree());
        try (BackupFile backupFile = BackupFile.open(file)) {
            List<FileContent> mixed = backupFile.getRootDirectory().getEntry("mixed.dat").asFile().getFileContents();
            assertEquals(3, mixed.size());
            ContentIDFileContent id = assertInstanceOf(ContentIDFileContent.class, mixed.get(0));
            assertArrayEquals(contentId(2, 0L, 2048L).getHash(), id.getHash());
            InlinedFileContent inline = assertInstanceOf(InlinedFileContent.class, mixed.get(1));
            assertEquals("héllo", new String(inline.getData(), StandardCharsets.UTF_8));
            assertEquals(2048L, inline.getOffset());
            assertEquals(6L, inline.getLength());
            ZeroFilledFileContent zero = assertInstanceOf(ZeroFilledFileContent.class, mixed.get(2));
            assertEquals(2053L, zero.getOffset());
            assertEquals(1024L, zero.getLength());
        }
    }

    @Test
    void extraDataSurvives() throws IOException {
        File file = write(sampleTree());
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertEquals(new ExtraDataFilePath("/Users/someone/source"), root.getExtra(ExtraDataFilePath.class));
            assertEquals(UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e"),
                root.getExtra(ExtraDataSnapshotId.class).uuid);
            assertEquals("/Users/someone/source", root.getPathOnDisk());

            DirectoryEntryFile hashed = root.getEntry("hashed.bin").asFile();
            assertEquals(new ExtraDataPosixPermissions(0644, 501, 20), hashed.getExtra(ExtraDataPosixPermissions.class));
            assertEquals("rw-r--r--", hashed.getExtra(ExtraDataPosixPermissions.class).getPermissionsAsString());
            ExtraDataNanosecondModifiedDate nanos = hashed.getExtra(ExtraDataNanosecondModifiedDate.class);
            assertEquals(1_700_000_000L, nanos.seconds);
            assertEquals(123_456_789, nanos.nanos);
            assertEquals("/Users/someone/source/hashed.bin", hashed.getPathOnDisk());

            DirectoryEntryDirectory b = root.getEntry("a").asDirectory().getEntry("b").asDirectory();
            assertEquals("/elsewhere/b", b.getPathOnDisk());
            assertEquals("/elsewhere/b/c/deep.txt",
                b.getEntry("c").asDirectory().getEntry("deep.txt").getPathOnDisk());
        }
    }

    @Test
    void emptyEntriesRoundTrip() throws IOException {
        File file = write(sampleTree());
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntryDirectory root = backupFile.getRootDirectory();
            assertTrue(root.getEntry("empty-dir").asDirectory().getEntries().isEmpty());
            assertFalse(root.getEntry("unhashed.txt").asFile().hasFileContents());
            assertFalse(root.getEntry("empty-file").asFile().hasFileContents());
            assertEquals(0L, root.getEntry("empty-file").asFile().getSize());
        }
    }

    @Test
    void emptyRootRoundTrips() throws IOException {
        DirectoryEntryDirectoryMemory empty = with(dir("ROOT"), new ExtraDataFilePath("/empty"));
        File file = write(empty);
        try (BackupFile backupFile = BackupFile.open(file)) {
            assertTreeEquals(empty, backupFile.getRootDirectory());
        }
    }

    @Test
    void rewritingAnOpenedFileGivesTheSameTree() throws IOException {
        File first = write(sampleTree());
        File second;
        try (BackupFile backupFile = BackupFile.open(first)) {
            second = write(backupFile.getRootDirectory());
        }
        try (BackupFile backupFile = BackupFile.open(second)) {
            assertTreeEquals(sampleTree(), backupFile.getRootDirectory());
        }
    }

    @Test
    void unknownExtraDataIsKept() throws IOException {
        DirectoryEntryDirectoryMemory tree = dir("ROOT",
            with(file("f", 1L, 1L, inlined(0L, "f")), new ExtraDataUnsupported(999, new byte[]{1, 2, 3})));
        File file = write(tree);
        try (BackupFile backupFile = BackupFile.open(file)) {
            DirectoryEntry f = backupFile.getRootDirectory().getEntry("f");
            assertEquals(1, f.getExtra().size());
            ExtraData extra = f.getExtra().get(0);
            ExtraDataUnsupported unsupported = assertInstanceOf(ExtraDataUnsupported.class, extra);
            assertEquals(999, unsupported.getTypeId());
            assertArrayEquals(new byte[]{1, 2, 3}, unsupported.serialize());
            // and it is written back out unchanged
            File second = write(backupFile.getRootDirectory());
            try (BackupFile reopened = BackupFile.open(second)) {
                assertEquals(new ExtraDataUnsupported(999, new byte[]{1, 2, 3}),
                    reopened.getRootDirectory().getEntry("f").getExtra().get(0));
            }
        }
    }
}
