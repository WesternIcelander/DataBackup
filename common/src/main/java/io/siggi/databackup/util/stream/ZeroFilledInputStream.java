package io.siggi.databackup.util.stream;

import java.io.IOException;
import java.io.InputStream;

public class ZeroFilledInputStream extends InputStream {
    private long remaining = 0L;

    public ZeroFilledInputStream(long count) {
        remaining = count;
    }

    @Override
    public int read() throws IOException {
        if (remaining > 0L) {
            remaining -= 1L;
            return 0;
        }
        return -1;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length > remaining) {
            length = (int) remaining;
        }
        remaining -= length;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            buffer[i] = (byte) 0;
        }
        return length;
    }

    @Override
    public int available() {
        if (remaining > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) remaining;
    }
}