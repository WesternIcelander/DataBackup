package io.siggi.databackup.util;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;

public class RandomAccessDataMemoryBuilder extends OutputStream {
    static final int chunkSize = 67108864;
    private final List<byte[]> chunks = new LinkedList<>();
    private final ByteArrayOutputStream outStream = new ByteArrayOutputStream(chunkSize);

    @Override
    public void write(int b) {
        outStream.write(b);
        postWrite();
    }

    @Override
    public void write(byte[] buffer) {
        write(buffer, 0, buffer.length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        while (length > 0) {
            int maxLength = chunkSize - outStream.size();
            int toCopy = Math.min(length, maxLength);
            copy(buffer, offset, toCopy);
            offset += toCopy;
            length -= toCopy;
        }
    }

    public RandomAccessDataMemory toRandomAccessData() {
        int extra = 0;
        if (outStream.size() > 0) {
            extra = 1;
        }
        byte[][] chunksArray = new byte[chunks.size() + extra][];
        int i = 0;
        for (byte[] chunk : chunks) {
            chunksArray[i++] = chunk;
        }
        if (extra == 1) {
            chunksArray[chunksArray.length - 1] = outStream.toByteArray();
        }
        return new RandomAccessDataMemory(chunksArray);
    }

    private void copy(byte[] buffer, int offset, int length) {
        outStream.write(buffer, offset, length);
        postWrite();
    }

    private void postWrite() {
        if (outStream.size() >= chunkSize) {
            chunks.add(outStream.toByteArray());
            outStream.reset();
        }
    }
}
