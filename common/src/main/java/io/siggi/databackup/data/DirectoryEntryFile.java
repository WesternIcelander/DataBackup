package io.siggi.databackup.data;

import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.util.stream.IO;
import io.siggi.databackup.util.ObjectWriter;
import io.siggi.databackup.util.data.RandomAccessData;
import io.siggi.databackup.util.ReadingIterator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private long contentOffset;
    private long contentOffsetOffset;
    private final RandomAccessData data;
    private final long lastModified;
    private final long size;

    public DirectoryEntryFile(String name, long lastModified, long size) {
        this(name, new LinkedList<>(), 0L, 0L, null, lastModified, size);
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

    @Override
    public DirectoryEntryFile copy() {
        DirectoryEntryFile entry = new DirectoryEntryFile(getName(), fileContents, contentOffset, contentOffsetOffset, data, lastModified, size);
        entry.fileContentsReference = fileContentsReference;
        entry.setParent(getParent());
        entry.getExtra().addAll(getExtra());
        entry.setOffset(getOffset());
        return entry;
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

    public boolean hasFileContents() {
        if (fileContents != null) return !fileContents.isEmpty();
        return contentOffset != 0L;
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

    public ObjectWriter<FileContent> updateFileContents() throws IOException {
        if (data == null) {
            fileContents = new LinkedList<>();
            return new ObjectWriter<>() {
                boolean closed = false;
                @Override
                public void write(FileContent value) throws IOException {
                    if (closed) throw new IOException("Already closed.");
                    fileContents.add(value);
                }

                @Override
                public void close() throws IOException {
                    closed = true;
                }
            };
        }
        fileContents = null;
        fileContentsReference = null;
        long newOffset = data.getLength();
        OutputStream out = data.writeTo(newOffset);
        IO.writeInt(out, 0); // placeholder
        return new ObjectWriter<>() {
            boolean closed = false;
            int count = 0;
            @Override
            public void write(FileContent value) throws IOException {
                if (closed) throw new IOException("Already closed.");
                IO.writeShort(out, value.getTypeId());
                value.write(out);
                count += 1;
            }

            @Override
            public void close() throws IOException {
                if (closed) return;
                closed = true;
                out.close();
                try (OutputStream countStream = data.writeTo(newOffset)) {
                    IO.writeInt(countStream, count);
                }
                long newOffsetToWrite = newOffset - contentOffsetOffset;
                contentOffset = newOffset;
                try (OutputStream offsetStream = data.writeTo(contentOffsetOffset)) {
                    IO.writeLong(offsetStream, newOffsetToWrite);
                }
            }
        };
    }

    public long getFileContentOffset() {
        return contentOffset;
    }

    public void setFileContentOffset(long newOffset) {
        this.contentOffset = newOffset;
    }

    public long getFileContentOffsetOffset() {
        return contentOffsetOffset;
    }

    public void setFileContentOffsetOffset(long newOffset) {
        this.contentOffsetOffset = newOffset;
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
