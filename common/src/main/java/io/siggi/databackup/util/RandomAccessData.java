package io.siggi.databackup.util;

import java.io.Closeable;
import java.io.InputStream;

public abstract class RandomAccessData implements Closeable {
    public abstract InputStream getInputStream(long startAt);

    public abstract long getLength();
    public static abstract class In extends InputStream {
        public abstract long getFilePointer();
    }
}
