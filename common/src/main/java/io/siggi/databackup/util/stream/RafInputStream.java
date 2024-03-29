package io.siggi.databackup.util.stream;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class RafInputStream extends InputStream implements FilePointer {
    private final RandomAccessFile raf;

    public RafInputStream(RandomAccessFile raf) {
        this.raf = raf;
    }

    @Override
    public int read() throws IOException {
        return raf.read();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return raf.read(buffer);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        return raf.read(buffer, offset, length);
    }

    @Override
    public long getFilePointer() {
        try {
            return raf.getFilePointer();
        } catch (IOException e) {
            return 0L;
        }
    }
}
