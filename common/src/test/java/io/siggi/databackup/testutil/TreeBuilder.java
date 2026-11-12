package io.siggi.databackup.testutil;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.ContentIDFileContent;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.content.InlinedFileContent;
import io.siggi.databackup.data.content.ZeroFilledFileContent;
import io.siggi.databackup.data.extra.ExtraData;
import io.siggi.databackup.util.ObjectWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shorthand for building in-memory directory trees in tests.
 */
public final class TreeBuilder {
    private TreeBuilder() {
    }

    public static ContentIDFileContent contentId(int seed, long offset, long length) {
        byte[] hash = new byte[32];
        for (int i = 0; i < hash.length; i++) {
            hash[i] = (byte) (seed * 37 + i);
        }
        ContentIDFileContent content = new ContentIDFileContent();
        content.setOffset(offset);
        content.setLength(length);
        content.setHash(hash);
        return content;
    }

    public static InlinedFileContent inlined(long offset, String text) {
        InlinedFileContent content = new InlinedFileContent();
        content.setOffset(offset);
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        content.setData(data);
        content.setLength(data.length);
        return content;
    }

    public static ZeroFilledFileContent zeros(long offset, long length) {
        ZeroFilledFileContent content = new ZeroFilledFileContent();
        content.setOffset(offset);
        content.setLength(length);
        return content;
    }

    public static DirectoryEntryFile file(String name, long lastModified, long size, FileContent... contents) {
        DirectoryEntryFile file = new DirectoryEntryFile(name, lastModified, size);
        file.getFileContents().addAll(List.of(contents));
        return file;
    }

    public static DirectoryEntryDirectoryMemory dir(String name, DirectoryEntry... entries) throws IOException {
        DirectoryEntryDirectoryMemory dir = new DirectoryEntryDirectoryMemory(name);
        try (ObjectWriter<DirectoryEntry> writer = dir.updateEntries()) {
            for (DirectoryEntry entry : entries) {
                writer.write(entry);
            }
        }
        return dir;
    }

    public static <T extends DirectoryEntry> T with(T entry, ExtraData... extra) {
        entry.getExtra().addAll(List.of(extra));
        return entry;
    }
}
