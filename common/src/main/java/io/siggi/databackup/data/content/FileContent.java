package io.siggi.databackup.data.content;

import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.util.stream.IO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public abstract class FileContent {

    private static final List<Class<? extends FileContent>> types = new ArrayList<>();
    private static final List<Supplier<? extends FileContent>> suppliers = new ArrayList<>();
    static final int TYPE_CONTENT_ID = 0;
    static final int TYPE_ZERO_FILLED = 1;
    static final int TYPE_INLINED = 2;

    private static <T extends FileContent> void addContentType(Class<T> type, Supplier<T> supplier) {
        try {
            types.add(type);
            suppliers.add(supplier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static {
        addContentType(ContentIDFileContent.class, ContentIDFileContent::new);
        addContentType(ZeroFilledFileContent.class, ZeroFilledFileContent::new);
        addContentType(InlinedFileContent.class, InlinedFileContent::new);
    }

    public static Class<? extends FileContent> getType(int typeId) {
        return types.get(typeId);
    }

    public static FileContent create(int typeId) {
        try {
            return suppliers.get(typeId).get();
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException(e);
        }
    }

    private long offset;
    private long length;

    public FileContent() {
    }

    public abstract int getTypeId();

    public long getOffset() {
        return offset;
    }

    public long getLength() {
        return length;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public void read(InputStream in) throws IOException {
        setOffset(IO.readLong(in));
        setLength(IO.readLong(in));
    }

    public void write(OutputStream out) throws IOException {
        IO.writeLong(out, getOffset());
        IO.writeLong(out, getLength());
    }

    public boolean isDataAvailable(DataRepository repository) {
        return true;
    }

    public abstract InputStream getInputStream(DataRepository repository) throws IOException;

    public StoredData getStoredData(DataRepository repository) {
        return null;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof FileContent o)) return false;
        return o.offset == offset && o.length == length;
    }

    @Override
    public int hashCode() {
        return Objects.hash(offset, length);
    }
}
