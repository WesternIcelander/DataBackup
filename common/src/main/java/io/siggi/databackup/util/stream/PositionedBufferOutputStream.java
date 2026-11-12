package io.siggi.databackup.util.stream;

import io.siggi.tools.io.FilePointer;
import io.siggi.tools.io.RandomAccessData;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Buffers bytes destined for a known position in a {@link RandomAccessData}, reporting the file pointer the bytes
 * would have once written. Call {@link #writeTo(RandomAccessData)} to write them in one go.
 * <p>
 * {@code RandomAccessDataFile} reads and writes through one shared {@code RandomAccessFile}, so a stream returned by
 * {@code writeTo(offset)} writes wherever the last read left the file pointer. Buffering and writing each chunk through
 * a fresh {@code writeTo(position)} call keeps writes in the right place even when reads happen in between.
 */
public final class PositionedBufferOutputStream extends ByteArrayOutputStream implements FilePointer {
    private final long startPosition;

    public PositionedBufferOutputStream(long startPosition) {
        this.startPosition = startPosition;
    }

    @Override
    public long getFilePointer() {
        return startPosition + size();
    }

    /**
     * Writes the buffered bytes at the start position and returns the position just after them.
     */
    public long writeTo(RandomAccessData data) throws IOException {
        try (OutputStream out = data.writeTo(startPosition)) {
            writeTo(out);
        }
        return getFilePointer();
    }
}
