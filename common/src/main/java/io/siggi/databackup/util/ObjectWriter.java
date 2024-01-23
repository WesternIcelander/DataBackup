package io.siggi.databackup.util;

import java.io.IOException;

public interface ObjectWriter<T> extends AutoCloseable {
    void write(T value) throws IOException;
    void close() throws IOException;
}
