package io.siggi.databackup.util.data;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class RandomAccessData implements Closeable {
    public abstract InputStream getInputStream(long startAt);

    public OutputStream writeTo(long filePointer) throws IOException {
        throw new IOException("File is open in read-only mode.");
    }

    public boolean isWritable() {
        return false;
    }

    public abstract long getLength();
    public static abstract class In extends InputStream {
        public abstract long getFilePointer();
    }
}
