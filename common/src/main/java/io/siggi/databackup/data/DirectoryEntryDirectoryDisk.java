package io.siggi.databackup.data;

import io.siggi.databackup.util.IO;
import io.siggi.databackup.util.RandomAccessData;
import io.siggi.databackup.util.ReadingIterator;
import io.siggi.databackup.util.Serialization;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class DirectoryEntryDirectoryDisk extends DirectoryEntryDirectory {
    private final RandomAccessData data;
    private final long offset;
    private final long offsetOffset;

    public DirectoryEntryDirectoryDisk(RandomAccessData data, String name, long offset, long offsetOffset) {
        super(name);
        this.data = data;
        this.offset = offset;
        this.offsetOffset = offsetOffset;
    }

    @Override
    public DirectoryEntry getEntry(String name) {
        return getEntries().get(name);
    }

    @Override
    public Iterator<DirectoryEntry> iterator() {
        InputStream in = data.getInputStream(offset);
        return new ReadingIterator<>() {
            @Override
            protected DirectoryEntry read() {
                DirectoryEntry entry;
                try {
                    int id = in.read();
                    switch (id) {
                        case Serialization.DIRECTORY_ENTRY_END: {
                            in.close();
                            return null;
                        }
                        case Serialization.DIRECTORY_ENTRY_NULL: {
                            String name = IO.readString(in);
                            entry = new DirectoryEntryNull(name);
                        }
                        break;
                        case Serialization.DIRECTORY_ENTRY_FILE: {
                            String name = IO.readString(in);
                            entry = Serialization.deserializeFile(in, data, name);
                        }
                        break;
                        case Serialization.DIRECTORY_ENTRY_DIRECTORY: {
                            String name = IO.readString(in);
                            entry = Serialization.deserializeDirectory(in, data, name);
                        }
                        break;
                        case Serialization.DIRECTORY_ENTRY_SYMLINK: {
                            String name = IO.readString(in);
                            entry = Serialization.deserializeSymlink(in, name);
                        }
                        break;
                        default:
                            throw new DirectoryEntryException("Unknown file type " + id);
                    }
                } catch (IOException e) {
                    throw new DirectoryEntryException("IOException occurred", e);
                }
                entry.setParent(DirectoryEntryDirectoryDisk.this);
                return entry;
            }
        };
    }

    @Override
    public Map<String, DirectoryEntry> getEntries() {
        Map<String, DirectoryEntry> entries = new HashMap<>();
        for (DirectoryEntry entry : this) {
            entries.put(entry.getName(), entry);
        }
        return entries;
    }
}
