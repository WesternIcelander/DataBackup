package io.siggi.databackup.data;

public class DirectoryEntryNull extends DirectoryEntry {
    public DirectoryEntryNull(String name) {
        super(name);
    }

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public final DirectoryEntryType getType() {
        return DirectoryEntryType.NULL;
    }
}
