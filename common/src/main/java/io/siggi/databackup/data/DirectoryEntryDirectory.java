package io.siggi.databackup.data;

import io.siggi.databackup.util.ObjectWriter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

public abstract class DirectoryEntryDirectory extends DirectoryEntry implements Iterable<DirectoryEntry> {
    protected DirectoryEntryDirectory(String name) {
        super(name);
    }

    private long offsetOffset = 0L;

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

    public ObjectWriter<DirectoryEntry> updateEntries() throws IOException {
        throw new IOException("File open in read-only mode.");
    }

    public long getDirectoryOffsetOffset() {
        return offsetOffset;
    }

    public void setDirectoryOffsetOffset(long newOffset) {
        this.offsetOffset = newOffset;
    }
}
