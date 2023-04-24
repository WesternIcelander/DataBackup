package io.siggi.databackup.data.extra;

import java.nio.charset.StandardCharsets;

public class ExtraDataFilePath extends ExtraData {

    public ExtraDataFilePath(String path) {
        if (path == null) throw new NullPointerException();
        this.path = path;
    }

    public final String path;

    public static ExtraDataFilePath deserialize(byte[] data) {
        return new ExtraDataFilePath(new String(data, StandardCharsets.UTF_8));
    }

    @Override
    public byte[] serialize() {
        return path.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataFilePath o)) return false;
        return path.equals(o.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }
}
