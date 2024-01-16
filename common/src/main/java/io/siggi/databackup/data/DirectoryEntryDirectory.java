package io.siggi.databackup.data;

import java.util.Iterator;
import java.util.Map;

public abstract class DirectoryEntryDirectory extends DirectoryEntry implements Iterable<DirectoryEntry> {
    protected DirectoryEntryDirectory(String name) {
        super(name);
    }

    @Override
    public final boolean isDirectory() {
        return true;
    }

    @Override
    public final DirectoryEntryType getType() {
        return DirectoryEntryType.DIRECTORY;
    }

    @Override
    public DirectoryEntryDirectory asDirectory() {
        return this;
    }

    public abstract DirectoryEntry getEntry(String name);

    public abstract Map<String,DirectoryEntry> getEntries();

    public Iterator<DirectoryEntry> iterator() {
        return getEntries().values().iterator();
    }
}
