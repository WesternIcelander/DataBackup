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
                DirectoryEntry entry;
                if (id == 0) {
                    String name = IO.readString(in);
                    entry = new DirectoryEntryNull(name);
                    entries.put(name, entry);
                } else if (id == 1) {
                    String name = IO.readString(in);
                    entry = Serialization.deserializeFile(in, name);
                    entries.put(name, entry);
                } else if (id == 2) {
                    String name = IO.readString(in);
                    entry = Serialization.deserializeDirectory(in, data, name);
                    entries.put(name, entry);
                } else if (id == 3) {
                    String name = IO.readString(in);
                    entry = Serialization.deserializeSymlink(in, name);
                    entries.put(name, entry);
                } else {
                    throw new DirectoryEntryException("Unknown file type " + id);
                }
                entry.setParent(this);
            }
        } catch (Exception e) {
            throw new DirectoryEntryException("Unable to read directory", e);
        }
        return entries;
    }
}
