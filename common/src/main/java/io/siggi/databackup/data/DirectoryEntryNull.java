package io.siggi.databackup.data;

public class DirectoryEntryNull extends DirectoryEntry {
    public DirectoryEntryNull(String name) {
        super(name);
    }

    @Override
    public DirectoryEntryNull copy() {
        DirectoryEntryNull entry = new DirectoryEntryNull(getName());
        entry.setParent(getParent());
        entry.getExtra().addAll(getExtra());
        entry.setOffset(getOffset());
        return entry;
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
