package io.siggi.databackup.util.stream;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class RafOutputStream extends OutputStream implements FilePointer {
    private final RandomAccessFile raf;

    public RafOutputStream(RandomAccessFile raf) {
        this.raf = raf;
    }

    @Override
    public void write(int b) throws IOException {
        raf.write(b);
    }

    @Override
    public void write(byte[] buffer) throws IOException {
        raf.write(buffer);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        raf.write(buffer, offset, length);
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
