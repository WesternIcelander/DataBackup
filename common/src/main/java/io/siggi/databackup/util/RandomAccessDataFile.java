package io.siggi.databackup.util;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public final class RandomAccessDataFile extends RandomAccessData {
    private final RandomAccessFile raf;
    private final RafOutputStream rafOutputStream;
    private final boolean writable;
    private boolean closed = false;
    private final List<CountingInputStream> recentStreams = new LinkedList<>();

    public RandomAccessDataFile(RandomAccessFile raf, boolean writable) {
        this.raf = raf;
        this.rafOutputStream = writable ? new RafOutputStream(raf) : null;
        this.writable = writable;
    }

    @Override
    public InputStream getInputStream(long startAt) {
        for (Iterator<CountingInputStream> it = recentStreams.iterator(); it.hasNext(); ) {
            CountingInputStream stream = it.next();
            long difference = startAt - stream.getFilePointer();
            if (difference < 0L || difference >= 8192L) continue;
            try {
                if (difference != 0L) stream.skipNBytes(difference);
                it.remove();
                return stream;
            } catch (IOException e) {
                it.remove();
            }
        }
        CountingInputStream stream = new CountingInputStream(new BufferedInputStream(new RafWrapper(startAt)));
        stream.setFilePointer(startAt);
        stream.setCloseHandler(this::closeStream);
        return stream;
    }

    private void closeStream(CountingInputStream stream) {
        if (closed) return;
        recentStreams.add(stream);
        while (recentStreams.size() > 4) {
            recentStreams.remove(recentStreams.size() - 1);
        }
    }

    @Override
    public OutputStream writeTo(long filePointer) throws IOException {
        if (!writable) {
            throw new IOException("File is open in read-only mode.");
        }
        raf.seek(filePointer);
        return rafOutputStream;
    }

    @Override
    public boolean isWritable() {
        return writable;
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
        closed = true;
        recentStreams.clear();
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
