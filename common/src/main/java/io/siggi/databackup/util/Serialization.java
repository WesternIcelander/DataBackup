package io.siggi.databackup.util;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryDisk;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntryNull;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.extra.ExtraData;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
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
        List<DirectoryInfo> directories = new LinkedList<>();
        for (String name : names) {
            DirectoryEntry entry = entries.get(name);
            boolean skipExtra = false;
            if (entry instanceof DirectoryEntryNull) {
                out.write(DIRECTORY_ENTRY_NULL);
                IO.writeString(out, name);
                skipExtra = true;
            } else if (entry instanceof DirectoryEntryFile file) {
                out.write(DIRECTORY_ENTRY_FILE);
                IO.writeString(out, name);
                out.write(file.getSha256());
                IO.writeLong(out, file.getLastModified());
                IO.writeLong(out, file.getSize());
            } else if (entry instanceof DirectoryEntryDirectory dir) {
                out.write(DIRECTORY_ENTRY_DIRECTORY);
                IO.writeString(out, name);
                long locationOfOffset = raf.getFilePointer();
                IO.writeLong(out, 0L); // placeholder
                directories.add(new DirectoryInfo(locationOfOffset, dir));
            } else if (entry instanceof DirectoryEntrySymlink symlink) {
                out.write(DIRECTORY_ENTRY_SYMLINK);
                IO.writeString(out, name);
                IO.writeString(out, symlink.getTarget());
            } else {
                throw new IllegalArgumentException("Unhandled entry " + entry.getClass().getName());
            }
            if (!skipExtra) {
                writeExtra(out, entry.getExtra());
            }
        }
        out.write(DIRECTORY_ENTRY_END);
        for (DirectoryInfo directoryInfo : directories) {
            long filePointer = raf.getFilePointer();
            raf.seek(directoryInfo.locationOfOffset);
            IO.writeLong(out, filePointer);
            raf.seek(filePointer);
            serializeDirectory(raf, out, directoryInfo.directory);
        }
    }

    public static DirectoryEntryFile deserializeFile(InputStream in, String name) throws IOException {
        byte[] sha256 = IO.readBytes(in, 32);
        long lastModified = IO.readLong(in);
        long size = IO.readLong(in);
        return readExtraAndReturn(in, new DirectoryEntryFile(name, sha256, lastModified, size));
    }

    public static DirectoryEntryDirectoryDisk deserializeDirectory(InputStream in, RandomAccessData data, String name) throws IOException {
        long offset = IO.readLong(in);
        return readExtraAndReturn(in, new DirectoryEntryDirectoryDisk(data, name, offset));
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

    private record DirectoryInfo(long locationOfOffset, DirectoryEntryDirectory directory) {
    }
}
