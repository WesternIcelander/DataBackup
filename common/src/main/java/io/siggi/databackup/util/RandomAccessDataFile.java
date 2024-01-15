package io.siggi.databackup.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public final class RandomAccessDataFile extends RandomAccessData {
    private final RandomAccessFile raf;

    public RandomAccessDataFile(RandomAccessFile raf) {
        this.raf = raf;
    }

    @Override
    public InputStream getInputStream(long startAt) {
        CountingInputStream stream = new CountingInputStream(new BufferedInputStream(new RafWrapper(startAt)));
        stream.setFilePointer(startAt);
        return stream;
    }

    @Override
    public long getLength() {
        try {
            return raf.length();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException ignored) {
        }
    }

    private class RafWrapper extends In {
        private RafWrapper(long pointer) {
            this.pointer = pointer;
            if (this.pointer < 0L) {
                throw new IllegalArgumentException("negative pointer");
            }
        }

        private long pointer;
        private long markPosition = -1L;

        @Override
        public long getFilePointer() {
            return pointer;
        }

        @Override
        public int read() throws IOException {
            raf.seek(pointer);
            int result = raf.read();
            if (result != -1) pointer += 1;
            return result;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            return read(buffer, 0, buffer.length);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            raf.seek(pointer);
            int result = raf.read(buffer, offset, length);
            if (result != -1) {
                pointer += result;
            }
            return result;
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int limit) {
            markPosition = pointer;
        }

        @Override
        public void reset() throws IOException {
            if (markPosition == -1L) {
                throw new IOException("Never marked");
            }
            pointer = markPosition;
        }
    }
}
