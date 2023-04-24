package io.siggi.databackup.data;

import java.util.Objects;

public class DirectoryEntrySymlink extends DirectoryEntry {
    private final String target;

    public DirectoryEntrySymlink(String name, String target) {
        super(name);
        this.target = target;
        if (target == null) throw new NullPointerException();
    }

    @Override
    public final boolean isSymlink() {
        return true;
    }

    @Override
    public final DirectoryEntryType getType() {
        return DirectoryEntryType.SYMLINK;
    }

    public String getTarget() {
        return target;
    }

    @Override
    public DirectoryEntrySymlink asSymlink() {
        return this;
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) return false;
        if (!(other instanceof DirectoryEntrySymlink o)) return false;
        return target.equals(o.target);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), target);
    }
}
