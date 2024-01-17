package io.siggi.databackup.data;

import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.util.IO;
import io.siggi.databackup.util.RandomAccessData;
import io.siggi.databackup.util.ReadingIterator;
import io.siggi.databackup.util.Serialization;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public class DirectoryEntryFile extends DirectoryEntry implements Iterable<FileContent> {
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

    public Iterator<FileContent> iterator() {
        if (fileContents != null) return fileContents.iterator();
        if (fileContentsReference != null) {
            List<FileContent> contents = fileContentsReference.get();
            if (contents != null) return contents.iterator();
        }
        if (contentOffset == 0L) return new ReadingIterator<>() {
            @Override
            protected FileContent read() {
                return null;
            }
        };
        InputStream in = data.getInputStream(contentOffset);
        return new ReadingIterator<>() {
            int count = -1;
            @Override
            protected FileContent read() {
                try {
                    if (count == -1) count = IO.readInt(in);
                    if (count == 0) {
                        in.close();
                        return null;
                    }
                    count -= 1;
                    int typeId = IO.readShort(in);
                    FileContent content = FileContent.create(typeId);
                    content.read(in);
                    return content;
                } catch (IOException e) {
                    throw new DirectoryEntryException("IOException occurred", e);
                }
            }
        };
    }

    public final List<FileContent> getFileContents() {
        if (fileContents == null) {
            List<FileContent> weakContents = fileContentsReference == null ? null : fileContentsReference.get();
            if (weakContents == null) {
                weakContents = readFileContents();
                fileContentsReference = new WeakReference<>(weakContents);
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
        List<FileContent> contents = new LinkedList<>();
        for (FileContent content : this) {
            contents.add(content);
        }
        return contents;
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
