package io.siggi.databackup.data;

import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.util.RandomAccessData;
import io.siggi.databackup.util.Serialization;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class DirectoryEntryFile extends DirectoryEntry {
    private List<FileContent> fileContents;
    private Reference<List<FileContent>> fileContentsReference;
    private final long contentOffset;
    private final long contentOffsetOffset;
    private final RandomAccessData data;
    private final long lastModified;
    private final long size;

    public DirectoryEntryFile(String name, long lastModified, long size) {
        this(name, new ArrayList<>(), 0L, 0L, null, lastModified, size);
    }

    public DirectoryEntryFile(String name, List<FileContent> fileContents, long contentOffset, long contentOffsetOffset, RandomAccessData data, long lastModified, long size) {
        super(name);
        this.fileContents = fileContents;
        this.contentOffset = contentOffset;
        this.contentOffsetOffset = contentOffsetOffset;
        this.data = data;
        this.lastModified = lastModified;
        this.size = size;
    }

    public final List<FileContent> getFileContents() {
        if (fileContents == null) {
            List<FileContent> weakContents = fileContentsReference == null ? null : fileContentsReference.get();
            if (weakContents == null) {
                weakContents = readFileContents();
                if (weakContents != null) {
                    fileContentsReference = new WeakReference<>(weakContents);
                }
            }
            return weakContents;
        }
        return fileContents;
    }

    public final List<FileContent> getFileContents(boolean keepInMemory) {
        if (fileContents != null) return fileContents;
        if (keepInMemory) {
            return fileContents = getFileContents();
        } else {
            return getFileContents();
        }
    }

    private List<FileContent> readFileContents() {
        if (contentOffset == 0L) return new ArrayList<>();
        try (InputStream in = data.getInputStream(contentOffset)) {
            return Serialization.deserializeFileContents(in);
        } catch (IOException e) {
            return null;
        }
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
        return Objects.equals(getFileContents(), o.getFileContents()) && lastModified == o.lastModified && size == o.size;
    }

    public boolean equalsIgnoreHash(DirectoryEntryFile other) {
        if (!super.equals(other)) return false;
        return lastModified == other.lastModified && size == other.size;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), getFileContents(), lastModified, size);
    }
}
