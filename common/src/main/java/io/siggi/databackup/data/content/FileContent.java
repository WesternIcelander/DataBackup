package io.siggi.databackup.data.content;

import io.siggi.databackup.util.IO;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class FileContent {

    private static final List<Class<? extends FileContent>> types = new ArrayList<>();
    private static final List<Constructor<? extends FileContent>> constructors = new ArrayList<>();
    static final int TYPE_SHA256 = 0;
    static final int TYPE_ZERO_FILLED = 1;

    private static void addContentType(Class<? extends FileContent> type) {
        try {
            types.add(type);
            constructors.add(type.getDeclaredConstructor());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static {
        addContentType(Sha256FileContent.class);
        addContentType(ZeroFilledFileContent.class);
    }

    public static Class<? extends FileContent> getType(int typeId) {
        return types.get(typeId);
    }

    public static FileContent create(int typeId) {
        try {
            return constructors.get(typeId).newInstance();
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
