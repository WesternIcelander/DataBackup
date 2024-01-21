package io.siggi.databackup.data;

import io.siggi.databackup.util.ObjectWriter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class DirectoryEntryDirectoryMemory extends DirectoryEntryDirectory {

    private final Map<String, DirectoryEntry> entries;

    public DirectoryEntryDirectoryMemory(String name) {
        super(name);
        this.entries = new HashMap<>();
    }

    @Override
    public DirectoryEntry getEntry(String name) {
        return getEntries().get(name);
    }

    @Override
    public Map<String, DirectoryEntry> getEntries() {
        return entries;
    }

    public ObjectWriter<DirectoryEntry> updateEntries() {
        entries.clear();
        return new ObjectWriter<>() {
            boolean closed = false;
            @Override
            public void write(DirectoryEntry value) throws IOException {
                if (closed) throw new IOException("Already closed.");
                entries.put(value.getName(), value);
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }
}
