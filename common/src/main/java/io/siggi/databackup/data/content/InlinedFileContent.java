package io.siggi.databackup.data.content;

import io.siggi.databackup.util.stream.IO;
import io.siggi.databackup.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class InlinedFileContent extends FileContent {
    private static final byte[] empty = new byte[0];
    private byte[] data = empty;

    @Override
    public int getTypeId() {
        return FileContent.TYPE_INLINED;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        if (data == null) {
            this.data = empty;
            return;
        }
        this.data = data;
    }

    @Override
    public void read(InputStream in) throws IOException {
        super.read(in);
        long length = getLength();
        if (length > Integer.MAX_VALUE) {
            throw new IOException("Data size does not fit in 32 bit integer.");
        }
        data = IO.readBytes(in, (int) length);
    }

    @Override
    public void write(OutputStream out) throws IOException {
        setLength(data.length);
        super.write(out);
        out.write(data);
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) return false;
        if (other == this) return true;
        if (!(other instanceof InlinedFileContent o)) return false;
        return Arrays.equals(data, o.data);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "inline:" + Util.bytesToHex(data);
    }
}
