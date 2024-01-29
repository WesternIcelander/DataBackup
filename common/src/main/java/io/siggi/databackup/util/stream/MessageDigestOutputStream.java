package io.siggi.databackup.util.stream;

import java.io.OutputStream;
import java.security.MessageDigest;

public class MessageDigestOutputStream extends OutputStream {
    private final MessageDigest digest;
    public MessageDigestOutputStream(MessageDigest digest) {
        this.digest = digest;
    }

    @Override
    public void write(int b) {
        digest.update((byte) b);
    }

    @Override
    public void write(byte[] buffer) {
        digest.update(buffer);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) {
        digest.update(buffer, offset, length);
    }
}
