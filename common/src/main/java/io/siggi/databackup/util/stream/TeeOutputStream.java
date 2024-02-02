package io.siggi.databackup.util.stream;

import java.io.IOException;
import java.io.OutputStream;

public class TeeOutputStream extends OutputStream {
    private final OutputStream[] outs;

    public TeeOutputStream(OutputStream... outs) {
        this.outs = outs;
    }

    @Override
    public void write(int b) throws IOException {
        for (OutputStream out : outs) {
            out.write(b);
        }
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
        for (OutputStream out : outs) {
            out.write(b, offset, length);
        }
    }

    @Override
    public void flush() throws IOException {
        for (OutputStream out : outs) {
            out.flush();
        }
    }
}
