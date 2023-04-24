package io.siggi.databackup.data;

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
}
