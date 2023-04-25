package io.siggi.databackup.data;

import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import java.util.Arrays;
import java.util.Objects;

public class DirectoryEntryFile extends DirectoryEntry {
    private final byte[] sha256;
    private final long lastModified;
    private final long size;

    public DirectoryEntryFile(String name, byte[] sha256, long lastModified, long size) {
        super(name);
        if (sha256.length != 32) {
            throw new IllegalArgumentException("sha256 must be exactly 32 bytes");
        }
        this.sha256 = sha256;
        this.lastModified = lastModified;
        this.size = size;
    }

    public final byte[] getSha256() {
        return sha256;
    }

    public final long getLastModified() {
        return lastModified;
    }

    public final long getSize() {
        return size;
    }

    @Override
    public final boolean isFile() {
        return true;
    }

    @Override
    public final DirectoryEntryType getType() {
        return DirectoryEntryType.FILE;
    }

    @Override
    public final DirectoryEntryFile asFile() {
        return this;
    }

    public boolean hasSameLastModified(DirectoryEntryFile other) {
        ExtraDataNanosecondModifiedDate myModifiedDate = getExtra(ExtraDataNanosecondModifiedDate.class);
        if (myModifiedDate != null) return myModifiedDate.equals(other.getExtra(ExtraDataNanosecondModifiedDate.class));
        return lastModified == other.lastModified;
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) return false;
        if (!(other instanceof DirectoryEntryFile o)) return false;
        return Arrays.equals(sha256, o.sha256) && lastModified == o.lastModified && size == o.size;
    }

    public boolean equalsIgnoreHash(DirectoryEntryFile other) {
        if (!super.equals(other)) return false;
        return lastModified == other.lastModified && size == other.size;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(super.hashCode(), lastModified, size);
        result = 31 * result + Arrays.hashCode(sha256);
        return result;
    }
}
