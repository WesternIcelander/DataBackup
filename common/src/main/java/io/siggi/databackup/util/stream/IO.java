package io.siggi.databackup.util.stream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class IO {
    private IO() {
    }

    public static int readByte(InputStream in) throws IOException {
        int read = in.read();
        if (read == -1) throw new EOFException();
        return read;
    }

    public static int readShort(InputStream in) throws IOException {
        return (readByte(in) << 8) | readByte(in);
    }

    public static void writeShort(OutputStream out, int value) throws IOException {
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    public static int readInt(InputStream in) throws IOException {
        return (readByte(in) << 24) | (readByte(in) << 16) | (readByte(in) << 8) | readByte(in);
    }

    public static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >> 24) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 8) & 0xff);
        out.write(value & 0xff);
    }

    public static long readLong(InputStream in) throws IOException {
        return (((long) readByte(in)) << 56) | (((long) readByte(in)) << 48) | (((long) readByte(in)) << 40) | (((long) readByte(in)) << 32)
            | (((long) readByte(in)) << 24) | (((long) readByte(in)) << 16) | (((long) readByte(in)) << 8) | ((long) readByte(in));
    }

    public static void writeLong(OutputStream out, long value) throws IOException {
        out.write((int) ((value >> 56) & 0xffL));
        out.write((int) ((value >> 48) & 0xffL));
        out.write((int) ((value >> 40) & 0xffL));
        out.write((int) ((value >> 32) & 0xffL));
        out.write((int) ((value >> 24) & 0xffL));
        out.write((int) ((value >> 16) & 0xffL));
        out.write((int) ((value >> 8) & 0xffL));
        out.write((int) (value & 0xffL));
    }

    public static byte[] readBytes(InputStream in, int count) throws IOException {
        byte[] b = new byte[count];
        readFully(in, b);
        return b;
    }

    public static String readString(InputStream in) throws IOException {
        return new String(readBytes(in, readShort(in)), StandardCharsets.UTF_8);
    }

    public static void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) throw new IOException("String too long");
        writeShort(out, bytes.length);
        out.write(bytes);
    }

    public static void readFully(InputStream in, byte[] bytes) throws IOException {
        int read = 0;
        int c;
        while (read < bytes.length) {
            c = in.read(bytes, read, bytes.length - read);
            if (c == -1) throw new EOFException();
            read += c;
        }
    }

    public static void copy(InputStream in, OutputStream out, Consumer<Integer> progress) throws IOException {
        byte[] b = new byte[65536];
        int c;
        while ((c = in.read(b, 0, b.length)) != -1) {
            out.write(b, 0, c);
            if (progress != null) {
                progress.accept(c);
            }
        }
    }

    public static void copyInterruptible(InputStream in, OutputStream out, Consumer<Integer> progress) throws IOException, InterruptedException {
        byte[] b = new byte[65536];
        int c;
        while ((c = in.read(b, 0, b.length)) != -1) {
            out.write(b, 0, c);
            if (progress != null) {
                progress.accept(c);
            }
            if (Thread.interrupted()) throw new InterruptedException();
        }
    }
}
