package io.siggi.databackup.data;

import io.siggi.databackup.util.IO;
import io.siggi.databackup.util.RandomAccessData;
import io.siggi.databackup.util.Serialization;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class DirectoryEntryDirectoryDisk extends DirectoryEntryDirectory {
    private final RandomAccessData data;
    private final long offset;

    public DirectoryEntryDirectoryDisk(RandomAccessData data, String name, long offset) {
        super(name);
        this.data = data;
        this.offset = offset;
    }

    @Override
    public DirectoryEntry getEntry(String name) {
        return getEntries().get(name);
    }

    @Override
    public Map<String, DirectoryEntry> getEntries() {
        Map<String, DirectoryEntry> entries = new HashMap<>();
        try {
            InputStream in = new BufferedInputStream(data.getInputStream(offset));
            int id;
            while ((id = in.read()) != -1) {
                if (id == 255) break;
                if (id == 0) {
                    String name = IO.readString(in);
                    entries.put(name, new DirectoryEntryNull(name));
                } else if (id == 1) {
                    String name = IO.readString(in);
                    entries.put(name, Serialization.deserializeFile(in, name));
                } else if (id == 2) {
                    String name = IO.readString(in);
                    entries.put(name, Serialization.deserializeDirectory(in, data, name));
                } else if (id == 3) {
                    String name = IO.readString(in);
                    entries.put(name, Serialization.deserializeSymlink(in, name));
                }
            }
        } catch (Exception e) {
            throw new DirectoryEntryException("Unable to read directory", e);
        }
        return entries;
    }
}
