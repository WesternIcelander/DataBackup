package io.siggi.databackup.util;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryDisk;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntryNull;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraData;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public final class Serialization {

    public static final int DIRECTORY_ENTRY_NULL = 0;
    public static final int DIRECTORY_ENTRY_FILE = 1;
    public static final int DIRECTORY_ENTRY_DIRECTORY = 2;
    public static final int DIRECTORY_ENTRY_SYMLINK = 3;
    public static final int DIRECTORY_ENTRY_END = 255;

    private Serialization() {
    }

    public static void serializeDirectory(RandomAccessFile out, DirectoryEntryDirectory directory) throws IOException {
        serializeDirectory(out, new RafOutputStream(out), directory);
    }

    private static void serializeDirectory(RandomAccessFile raf, RafOutputStream out, DirectoryEntryDirectory directory) throws IOException {
        Map<String, DirectoryEntry> entries = directory.getEntries();
        List<String> names = Util.sortedKeys(entries);
        for (String name : names) {
            DirectoryEntry entry = entries.get(name);
            serializeDirectoryEntry(out, entry);
        }
        out.write(DIRECTORY_ENTRY_END);
        for (String name : names) {
            DirectoryEntry entry = entries.get(name);
            if (!(entry instanceof DirectoryEntryFile file)) continue;
            List<FileContent> fileContents = file.getFileContents();
            if (fileContents.isEmpty()) {
                file.setFileContentOffset(0L);
                continue;
            }
            long filePointer = raf.getFilePointer();
            file.setFileContentOffset(filePointer);
            raf.seek(file.getFileContentOffsetOffset());
            IO.writeLong(out, filePointer - file.getFileContentOffsetOffset());
            raf.seek(filePointer);
            serializeFileContents(out, fileContents);
        }
        for (String name : names) {
            DirectoryEntry entry = entries.get(name);
            if (!(entry instanceof DirectoryEntryDirectory dir)) continue;
            long filePointer = raf.getFilePointer();
            raf.seek(dir.getDirectoryOffsetOffset());
            IO.writeLong(out, filePointer - dir.getDirectoryOffsetOffset());
            raf.seek(filePointer);
            serializeDirectory(raf, out, dir);
        }
    }

    public static void serializeDirectoryEntry(OutputStream out, DirectoryEntry entry) throws IOException {
        FilePointer filePointer = (FilePointer) out;
        boolean skipExtra = false;
        if (entry instanceof DirectoryEntryNull) {
            out.write(DIRECTORY_ENTRY_NULL);
            IO.writeString(out, entry.getName());
            skipExtra = true;
        } else if (entry instanceof DirectoryEntryFile file) {
            out.write(DIRECTORY_ENTRY_FILE);
            IO.writeString(out, file.getName());
            file.setFileContentOffsetOffset(filePointer.getFilePointer());
            IO.writeLong(out, 0L); // placeholder
            IO.writeLong(out, file.getLastModified());
            IO.writeLong(out, file.getSize());
        } else if (entry instanceof DirectoryEntryDirectory dir) {
            out.write(DIRECTORY_ENTRY_DIRECTORY);
            IO.writeString(out, dir.getName());
            dir.setDirectoryOffsetOffset(filePointer.getFilePointer());
            IO.writeLong(out, 0L); // placeholder
        } else if (entry instanceof DirectoryEntrySymlink symlink) {
            out.write(DIRECTORY_ENTRY_SYMLINK);
            IO.writeString(out, symlink.getName());
            IO.writeString(out, symlink.getTarget());
        } else {
            throw new IllegalArgumentException("Unhandled entry " + entry.getClass().getName());
        }
        if (!skipExtra) {
            writeExtra(out, entry.getExtra());
        }
    }

    public static DirectoryEntryFile deserializeFile(InputStream in, RandomAccessData data, String name) throws IOException {
        long contentOffsetOffset = ((FilePointer) in).getFilePointer();
        long contentOffset = IO.readLong(in);
        if (contentOffset != 0L) {
            contentOffset += contentOffsetOffset;
        }
        long lastModified = IO.readLong(in);
        long size = IO.readLong(in);
        return readExtraAndReturn(in, new DirectoryEntryFile(name, null, contentOffset, contentOffsetOffset, data, lastModified, size));
    }

    public static DirectoryEntryDirectoryDisk deserializeDirectory(InputStream in, RandomAccessData data, String name) throws IOException {
        long offsetOffset = ((FilePointer) in).getFilePointer();
        long offset = IO.readLong(in);
        if (offset != 0L) {
            offset += offsetOffset;
        }
        return readExtraAndReturn(in, new DirectoryEntryDirectoryDisk(data, name, offset, offsetOffset));
    }

    public static DirectoryEntrySymlink deserializeSymlink(InputStream in, String name) throws IOException {
        String target = IO.readString(in);
        return readExtraAndReturn(in, new DirectoryEntrySymlink(name, target));
    }

    public static <T extends DirectoryEntry> T readExtraAndReturn(InputStream in, T entry) throws IOException {
        List<ExtraData> extra = entry.getExtra();
        int extraType;
        while ((extraType = IO.readShort(in)) != 0) {
            byte[] data = IO.readBytes(in, IO.readShort(in));
            ExtraData extraData = ExtraData.deserialize(extraType, data);
            if (extraData != null) {
                extra.add(extraData);
            }
        }
        return entry;
    }

    public static void writeExtra(OutputStream out, List<ExtraData> extraData) throws IOException {
        for (ExtraData extra : extraData) {
            if (extra == null) continue;
            int typeId = extra.getTypeId();
            byte[] data = extra.serialize();
            IO.writeShort(out, typeId);
            IO.writeShort(out, data.length);
            out.write(data);
        }
        IO.writeShort(out, 0);
    }

    public static void serializeFileContents(OutputStream out, List<FileContent> fileContents) throws IOException {
        IO.writeInt(out, fileContents.size());
        for (FileContent content : fileContents) {
            IO.writeShort(out, content.getTypeId());
            content.write(out);
        }
    }

    public static List<FileContent> deserializeFileContents(InputStream in) throws IOException {
        int count = IO.readInt(in);
        List<FileContent> contents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int typeId = IO.readShort(in);
            FileContent content = FileContent.create(typeId);
            content.read(in);
            contents.add(content);
        }
        return contents;
    }
}
