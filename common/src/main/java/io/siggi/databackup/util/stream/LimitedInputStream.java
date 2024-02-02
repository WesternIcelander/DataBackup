package io.siggi.databackup.util.stream;

import java.io.IOException;
import java.io.InputStream;

public class LimitedInputStream extends InputStream {
    private final InputStream in;
    private final boolean relayClose;
    private long remainingBytes;

    public LimitedInputStream(InputStream in, long length, boolean relayClose) {
        this.in = in;
        this.relayClose = relayClose;
        this.remainingBytes = length;
    }

    @Override
    public int read() throws IOException {
        if (remainingBytes <= 0L) return -1;
        int read = in.read();
        if (read >= 0) remainingBytes -= 1L;
        return read;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remainingBytes <= 0L) return -1;
        int maxLength = remainingBytes > ((long) Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) remainingBytes;
        length = Math.min(maxLength, length);
        int read = in.read(buffer, offset, length);
        if (read >= 0) remainingBytes -= read;
        return read;
    }

    @Override
    public void close() throws IOException {
        if (relayClose) {
            in.close();
        }
    }
}
