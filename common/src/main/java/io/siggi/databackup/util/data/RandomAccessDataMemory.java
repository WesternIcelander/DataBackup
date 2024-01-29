package io.siggi.databackup.util.data;

import io.siggi.databackup.util.stream.FilePointer;

import java.io.IOException;
import java.io.InputStream;

public final class RandomAccessDataMemory extends RandomAccessData {
    private final byte[][] data;
    private final long length;

    RandomAccessDataMemory(byte[][] data) {
        this.data = data;
        long length = (data.length - 1) * ((long) RandomAccessDataMemoryBuilder.chunkSize);
        length += data[data.length - 1].length;
        this.length = length;
    }

    @Override
    public InputStream getInputStream(long startAt) {
        return new DataWrapper(startAt);
    }

    @Override
    public long getLength() {
        return length;
    }

    @Override
    public void close() {
    }

    private class DataWrapper extends In implements FilePointer {
        private DataWrapper(long pointer) {
            this.pointer = pointer;
            if (pointer < 0L) {
                throw new IllegalArgumentException("negative pointer");
            }
        }

        private long pointer;
        private long markPosition = -1;

        @Override
        public long getFilePointer() {
            return pointer;
        }

        @Override
        public int read() {
            if (pointer >= length) {
                return -1;
            }
            int bigPointer = (int) (pointer / ((long) RandomAccessDataMemoryBuilder.chunkSize));
            int smallPointer = (int) (pointer % ((long) RandomAccessDataMemoryBuilder.chunkSize));
            pointer += 1L;
            return data[bigPointer][smallPointer] & 0xff;
        }

        @Override
        public int read(byte[] buffer) {
            return read(buffer, 0, buffer.length);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (pointer >= RandomAccessDataMemory.this.length)
                return -1;
            int bigPointer = (int) (pointer / ((long) RandomAccessDataMemoryBuilder.chunkSize));
            int smallPointer = (int) (pointer % ((long) RandomAccessDataMemoryBuilder.chunkSize));
            byte[] chunk = data[bigPointer];
            length = Math.min(length, chunk.length - smallPointer);
            System.arraycopy(chunk, smallPointer, buffer, offset, length);
            pointer += length;
            return length;
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
            if (markPosition == -1) {
                throw new IOException("Never marked");
            }
            pointer = markPosition;
        }
    }
}
