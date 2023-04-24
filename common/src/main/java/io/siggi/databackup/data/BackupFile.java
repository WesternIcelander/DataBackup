package io.siggi.databackup.data;

import io.siggi.databackup.util.IO;
import io.siggi.databackup.util.RafOutputStream;
import io.siggi.databackup.util.RandomAccessData;
import io.siggi.databackup.util.RandomAccessDataFile;
import io.siggi.databackup.util.Serialization;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;

public class BackupFile implements Closeable {
    private static final byte[] magicHeader = new byte[]{0x44, 0x42, 0x61, 0x6b};
    private static final int version = 0;

    private final RandomAccessFile raf;
    private final RandomAccessData data;
    private final DirectoryEntryDirectoryDisk rootDirectory;

    public static BackupFile open(File file) throws IOException {
        boolean success = false;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            BackupFile backupFile = new BackupFile(raf, new RandomAccessDataFile(raf));
            success = true;
            return backupFile;
        } finally {
            if (!success && raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static BackupFile open(RandomAccessData data) throws IOException {
        return new BackupFile(null, data);
    }

    private BackupFile(RandomAccessFile raf, RandomAccessData data) throws IOException {
        this.raf = raf;
        this.data = data;
        RandomAccessData.In in = data.getInputStream(0L);
        if (!Arrays.equals(IO.readBytes(in, magicHeader.length), magicHeader)) {
            throw new IllegalArgumentException("Not a backup file");
        }
        long fileVersion = IO.readInt(in);
        if (fileVersion > version) {
            throw new IllegalArgumentException("File version is newer than supported");
        }
        String rootName = IO.readString(in);
        long startOfDirectory = IO.readLong(in);
        rootDirectory = Serialization.readExtraAndReturn(in, new DirectoryEntryDirectoryDisk(data, rootName, startOfDirectory));
    }

    public DirectoryEntryDirectory getRootDirectory() {
        return rootDirectory;
    }

    @Override
    public void close() throws IOException {
        if (raf != null) {
            raf.close();
        }
    }

    public static void writeToFile(File file, DirectoryEntryDirectory rootDirectory) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(0);
            raf.seek(0);
            OutputStream out = new RafOutputStream(raf);
            out.write(magicHeader);
            IO.writeInt(out, version);

            IO.writeString(out, rootDirectory.getName());
            long locationOfOffset = raf.getFilePointer();
            IO.writeLong(out, 0L); // placeholder
            Serialization.writeExtra(out, rootDirectory.getExtra());

            long startOfDirectory = raf.getFilePointer();
            raf.seek(locationOfOffset);
            IO.writeLong(out, startOfDirectory);
            raf.seek(startOfDirectory);

            Serialization.serializeDirectory(raf, rootDirectory);
        }
    }
}
