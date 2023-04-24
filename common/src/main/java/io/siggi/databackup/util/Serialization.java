package io.siggi.databackup.util;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryDisk;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntryNull;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.extra.ExtraData;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public final class Serialization {
    private Serialization() {
    }

    public static void serializeDirectory(RandomAccessFile out, DirectoryEntryDirectory directory, DirectoryEntryDirectory mergeIn) throws IOException {
        serializeDirectory(out, new RafOutputStream(out), directory, mergeIn);
    }

    private static void serializeDirectory(RandomAccessFile raf, RafOutputStream out, DirectoryEntryDirectory directory, DirectoryEntryDirectory mergeIn) throws IOException {
        Map<String, DirectoryEntry> entries = directory.getEntries();
        Map<String, DirectoryEntry> mergeEntries = null;
        if (mergeIn != null) {
            entries = directory instanceof DirectoryEntryDirectoryDisk ? entries : new HashMap<>(entries);
            mergeEntries = mergeIn instanceof DirectoryEntryDirectoryDisk ? mergeIn.getEntries() : new HashMap<>(mergeIn.getEntries());
            for (Iterator<Map.Entry<String,DirectoryEntry>> it = mergeEntries.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String,DirectoryEntry> entry = it.next();
                String name = entry.getKey();
                DirectoryEntry value = entry.getValue();
                if (value.isNull()) {
                    entries.remove(name);
                } else if (value.isDirectory()) {
                    DirectoryEntry directoryEntry = entries.get(name);
                    if (directoryEntry == null) {
                        entries.put(name, value);
                        it.remove();
                        continue;
                    }
                    DirectoryEntryDirectoryMemory mergeDirectory = new DirectoryEntryDirectoryMemory(name);
                    mergeDirectory.getExtra().addAll(value.getExtra());
                    mergeDirectory.getEntries().putAll(directoryEntry.asDirectory().getEntries());
                    entries.put(name, mergeDirectory);
                } else {
                    entries.put(name, entry.getValue());
                }
            }
        }
        List<String> names = Util.sortedKeys(entries);
        List<DirectoryInfo> directories = new LinkedList<>();
        for (String name : names) {
            DirectoryEntry entry = entries.get(name);
            boolean skipExtra = false;
            if (entry instanceof DirectoryEntryNull) {
                out.write(0);
                IO.writeString(out, name);
                skipExtra = true;
            } else if (entry instanceof DirectoryEntryFile file) {
                out.write(1);
                IO.writeString(out, name);
                out.write(file.getSha256());
                IO.writeLong(out, file.getLastModified());
                IO.writeLong(out, file.getSize());
            } else if (entry instanceof DirectoryEntryDirectory dir) {
                out.write(2);
                IO.writeString(out, name);
                long locationOfOffset = raf.getFilePointer();
                IO.writeLong(out, 0L); // placeholder
                DirectoryEntryDirectory mergeInDir = null;
                if (mergeEntries != null) {
                    DirectoryEntry possibleMergeInDir = mergeEntries.get(name);
                    if (possibleMergeInDir != null && possibleMergeInDir.isDirectory()) {
                        mergeInDir = possibleMergeInDir.asDirectory();
                    }
                }
                directories.add(new DirectoryInfo(locationOfOffset, dir, mergeInDir));
            } else if (entry instanceof DirectoryEntrySymlink symlink) {
                out.write(3);
                IO.writeString(out, name);
                IO.writeString(out, symlink.getTarget());
            } else {
                throw new IllegalArgumentException("Unhandled entry " + entry.getClass().getName());
            }
            if (!skipExtra) {
                writeExtra(out, entry.getExtra());
            }
        }
        out.write(255);
        for (DirectoryInfo directoryInfo : directories) {
            long filePointer = raf.getFilePointer();
            raf.seek(directoryInfo.locationOfOffset);
            IO.writeLong(out, filePointer);
            raf.seek(filePointer);
            serializeDirectory(raf, out, directoryInfo.directory, directoryInfo.mergeIn);
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

    private record DirectoryInfo(long locationOfOffset, DirectoryEntryDirectory directory, DirectoryEntryDirectory mergeIn) {
    }
}
