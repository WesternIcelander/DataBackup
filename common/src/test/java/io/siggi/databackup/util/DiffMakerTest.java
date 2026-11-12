package io.siggi.databackup.util;

import static io.siggi.databackup.testutil.TreeBuilder.contentId;
import static io.siggi.databackup.testutil.TreeBuilder.dir;
import static io.siggi.databackup.testutil.TreeBuilder.file;
import static io.siggi.databackup.testutil.TreeBuilder.inlined;
import static io.siggi.databackup.testutil.TreeBuilder.with;
import static io.siggi.databackup.testutil.TreeBuilder.zeros;
import static io.siggi.databackup.testutil.TreeAssert.assertTreeEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMerge;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.extra.ExtraDataDiffMetadata;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffMakerTest {
    @TempDir
    File tempDir;

    private static DirectoryEntryDirectoryMemory base() throws IOException {
        return with(dir("ROOT",
                file("unchanged.txt", 1L, 3L, inlined(0L, "abc")),
                file("modified.txt", 2L, 10L, contentId(1, 0L, 10L)),
                file("deleted.txt", 3L, 5L, contentId(2, 0L, 5L)),
                file("becomes-dir", 4L, 1L, inlined(0L, "x")),
                new DirectoryEntrySymlink("link", "old-target"),
                new DirectoryEntrySymlink("same-link", "target"),
                dir("deleted-dir",
                    file("inside.txt", 5L, 1L, inlined(0L, "i")),
                    dir("nested", file("deeper.txt", 6L, 1L, inlined(0L, "d")))),
                dir("becomes-link", file("gone.txt", 7L, 1L, inlined(0L, "g"))),
                with(dir("chmod-only", file("keep.txt", 8L, 1L, inlined(0L, "k"))),
                    new ExtraDataPosixPermissions(0755, 501, 20)),
                dir("untouched", dir("deep", file("still.txt", 9L, 1L, inlined(0L, "s")))),
                dir("changed",
                    file("same.txt", 10L, 2L, inlined(0L, "ss")),
                    file("removed.txt", 11L, 1L, inlined(0L, "r")),
                    dir("sub",
                        file("touched.txt", 12L, 4L, zeros(0L, 4L)),
                        dir("removed-sub", file("r.txt", 13L, 1L, inlined(0L, "r"))))),
                dir("emptied", file("only.txt", 14L, 1L, inlined(0L, "o")))),
            new ExtraDataFilePath("/source"));
    }

    private static DirectoryEntryDirectoryMemory target() throws IOException {
        return with(dir("ROOT",
                file("unchanged.txt", 1L, 3L, inlined(0L, "abc")),
                file("modified.txt", 20L, 12L, contentId(3, 0L, 12L)),
                dir("becomes-dir", file("new-inside.txt", 21L, 1L, inlined(0L, "n"))),
                new DirectoryEntrySymlink("link", "new-target"),
                new DirectoryEntrySymlink("same-link", "target"),
                new DirectoryEntrySymlink("becomes-link", "somewhere"),
                with(dir("chmod-only", file("keep.txt", 8L, 1L, inlined(0L, "k"))),
                    new ExtraDataPosixPermissions(0700, 501, 20)),
                dir("untouched", dir("deep", file("still.txt", 9L, 1L, inlined(0L, "s")))),
                dir("changed",
                    file("same.txt", 10L, 2L, inlined(0L, "ss")),
                    file("added.txt", 22L, 1L, inlined(0L, "a")),
                    dir("sub",
                        file("touched.txt", 23L, 4L, contentId(4, 0L, 4L)),
                        dir("new-sub", dir("newer", file("n.txt", 24L, 1L, inlined(0L, "n")))))),
                dir("emptied"),
                file("new.txt", 25L, 0L),
                dir("new-dir", dir("empty"))),
            new ExtraDataFilePath("/source"));
    }

    @Test
    void diffHoldsOnlyChanges() throws IOException {
        DirectoryEntryDirectoryMemory diff = DiffMaker.diff(base(), target());
        Map<String, DirectoryEntry> entries = diff.getEntries();
        assertEquals(List.of("becomes-dir", "becomes-link", "changed", "chmod-only", "deleted-dir", "deleted.txt",
                "emptied", "link", "modified.txt", "new-dir", "new.txt"),
            entries.keySet().stream().sorted().toList());
        assertTrue(entries.get("deleted.txt").isNull());
        assertTrue(entries.get("deleted-dir").isNull());
        assertTrue(entries.get("chmod-only").asDirectory().getEntries().isEmpty());
        Map<String, DirectoryEntry> changed = entries.get("changed").asDirectory().getEntries();
        assertEquals(List.of("added.txt", "removed.txt", "sub"), changed.keySet().stream().sorted().toList());
        assertTrue(changed.get("removed.txt").isNull());
        Map<String, DirectoryEntry> sub = changed.get("sub").asDirectory().getEntries();
        assertEquals(List.of("new-sub", "removed-sub", "touched.txt"), sub.keySet().stream().sorted().toList());
        assertTrue(sub.get("removed-sub").isNull());
        assertTrue(entries.get("emptied").asDirectory().getEntry("only.txt").isNull());
        assertNull(entries.get("untouched"));
        assertNull(entries.get("unchanged.txt"));
        assertNull(entries.get("same-link"));
    }

    @Test
    void identicalTreesGiveEmptyDiff() throws IOException {
        DirectoryEntryDirectoryMemory diff = DiffMaker.diff(base(), base());
        assertTrue(diff.getEntries().isEmpty());
        assertTreeEquals(base(), new DirectoryEntryDirectoryMerge(base(), diff));
    }

    @Test
    void mergingDiffOntoBaseGivesTarget() throws IOException {
        DirectoryEntryDirectoryMemory diff = DiffMaker.diff(base(), target());
        assertTreeEquals(target(), new DirectoryEntryDirectoryMerge(base(), diff));
    }

    @Test
    void mergeDropsDiffMetadata() throws IOException {
        DirectoryEntryDirectoryMemory diff = DiffMaker.diff(base(), target());
        diff.getExtra().add(new ExtraDataDiffMetadata("base-backup", "abcdef"));
        DirectoryEntryDirectoryMerge merged = new DirectoryEntryDirectoryMerge(base(), diff);
        assertNull(merged.getExtra(ExtraDataDiffMetadata.class));
        assertTreeEquals(target(), merged);
    }

    @Test
    void mergeWorksThroughBackupFiles() throws IOException {
        File baseFile = new File(tempDir, "base");
        File diffFile = new File(tempDir, "diff");
        File mergedFile = new File(tempDir, "merged");
        BackupFile.writeToFile(baseFile, base());
        try (BackupFile baseBackup = BackupFile.open(baseFile)) {
            // diff the on-disk base against the in-memory target, as a client would
            BackupFile.writeToFile(diffFile, DiffMaker.diff(baseBackup.getRootDirectory(), target()));
        }
        try (BackupFile baseBackup = BackupFile.open(baseFile); BackupFile diffBackup = BackupFile.open(diffFile)) {
            DirectoryEntryDirectory merged = new DirectoryEntryDirectoryMerge(
                baseBackup.getRootDirectory(), diffBackup.getRootDirectory());
            assertTreeEquals(target(), merged);
            BackupFile.writeToFile(mergedFile, merged);
        }
        try (BackupFile mergedBackup = BackupFile.open(mergedFile)) {
            assertTreeEquals(target(), mergedBackup.getRootDirectory());
        }
    }

    @Test
    void chainedDiffsMerge() throws IOException {
        DirectoryEntryDirectoryMemory third = with(dir("ROOT",
                file("unchanged.txt", 1L, 3L, inlined(0L, "abc")),
                dir("changed", file("same.txt", 10L, 2L, inlined(0L, "ss"))),
                new DirectoryEntrySymlink("deleted.txt", "back as a link")),
            new ExtraDataFilePath("/source"));
        DirectoryEntryDirectoryMemory diff1 = DiffMaker.diff(base(), target());
        DirectoryEntryDirectoryMemory diff2 = DiffMaker.diff(target(), third);
        DirectoryEntryDirectory merged = new DirectoryEntryDirectoryMerge(
            new DirectoryEntryDirectoryMerge(base(), diff1), diff2);
        assertTreeEquals(third, merged);
    }
}
