package io.siggi.databackup.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

public class CountingInputStream extends InputStream implements FilePointer {
    private final InputStream in;
    private long filePointer = 0L;
    private Consumer<CountingInputStream> closeHandler = null;

    public CountingInputStream(InputStream in) {
        this.in = in;
    }

    public InputStream getWrappedInputStream() {
        return in;
    }

    public void setCloseHandler(Consumer<CountingInputStream> closeHandler) {
        this.closeHandler = closeHandler;
    }

    public void setFilePointer(long filePointer) {
        this.filePointer = filePointer;
    }

    @Override
    public long getFilePointer() {
        return filePointer;
    }

    @Override
    public int read() throws IOException {
        int value = in.read();
        if (value >= 0) filePointer += 1L;
        return value;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int readCount = in.read(buffer, offset, length);
        if (readCount >= 0) {
            this.filePointer += readCount;
        }
        return readCount;
    }

    @Override
    public int available() throws IOException {
        return in.available();
    }

    @Override
    public void close() throws IOException {
        if (closeHandler != null) {
            closeHandler.accept(this);
        } else {
            in.close();
        }
    }
}
