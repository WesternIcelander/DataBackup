package io.siggi.databackup.data;

import io.siggi.databackup.data.extra.ExtraData;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class DirectoryEntry {
    private final String name;
    private final List<ExtraData> extraData = new ArrayList<>();
    private transient DirectoryEntryDirectory parent;
    private transient long offset;

    protected DirectoryEntry(String name) {
        if (name == null) {
            throw new NullPointerException();
        }
        this.name = name;
    }

    public boolean isDirectory() {
        return false;
    }

    public boolean isFile() {
        return false;
    }

    public boolean isSymlink() {
        return false;
    }

    public boolean isNull() {
        return false;
    }

    public abstract DirectoryEntryType getType();

    public abstract DirectoryEntry copy();

    public final String getName() {
        return name;
    }

    public final List<ExtraData> getExtra() {
        return extraData;
    }

    public final <T extends ExtraData> T getExtra(Class<T> clazz) {
        for (ExtraData extra : getExtra()) {
            if (extra.getClass() == clazz) {
                return clazz.cast(extra);
            }
        }
        return null;
    }

    public final DirectoryEntryDirectory getParent() {
        return parent;
    }

    public final void setParent(DirectoryEntryDirectory parent) {
        this.parent = parent;
    }

    public final long getOffset() {
        return offset;
    }

    public final void setOffset(long offset) {
        this.offset = offset;
    }

    public final String getPathOnDisk() {
        ExtraDataFilePath filePath = getExtra(ExtraDataFilePath.class);
        if (filePath != null) return filePath.path;
        DirectoryEntryDirectory parent = getParent();
        if (parent == null) return null;
        String parentPath = parent.getPathOnDisk();
        if (parentPath == null) return null;
        return parentPath + "/" + getName();
    }

    public DirectoryEntryDirectory asDirectory() {
        throw new UnsupportedOperationException();
    }

    public DirectoryEntryFile asFile() {
        throw new UnsupportedOperationException();
    }

    public DirectoryEntrySymlink asSymlink() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof DirectoryEntry o)) return false;
        return name.equals(o.name) && extraData.equals(o.extraData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, extraData);
    }
}
