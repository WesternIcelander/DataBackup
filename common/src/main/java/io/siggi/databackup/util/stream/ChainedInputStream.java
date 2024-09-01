package io.siggi.databackup.util.stream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

public class ChainedInputStream extends InputStream {
    private InputStream currentStream;
    private Iterator<InputStream> iterator;

    public ChainedInputStream(Iterator<InputStream> iterator) throws IOException {
        this.iterator = iterator;
        nextStream();
    }

    private boolean nextStream() throws IOException {
        try {
            if (currentStream != null) {
                currentStream.close();
                currentStream = null;
            }
            if (iterator == null || !iterator.hasNext()) return false;
            currentStream = iterator.next();
            return true;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException ex) {
                throw ex;
            }
            throw e;
        }
    }

    @Override
    public int read() throws IOException {
        if (currentStream == null) return -1;
        while (true) {
            int value = currentStream.read();
            if (value != -1) return value;
            if (!nextStream()) return -1;
        }
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (currentStream == null) return -1;
        while (true) {
            int count = currentStream.read(buffer, offset, length);
            if (count != -1) return count;
            if (!nextStream()) return -1;
        }
    }

    @Override
    public int available() throws IOException {
        if (currentStream == null) return 0;
        return currentStream.available();
    }

    @Override
    public void close() throws IOException {
        try {
            if (currentStream != null) {
                currentStream.close();
            }
        } finally {
            currentStream = null;
            iterator = null;
        }
    }
}
