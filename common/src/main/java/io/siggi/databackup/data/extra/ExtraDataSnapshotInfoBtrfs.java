package io.siggi.databackup.data.extra;

import io.siggi.databackup.util.IO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public final class ExtraDataSnapshotInfoBtrfs extends ExtraData {

    public ExtraDataSnapshotInfoBtrfs(String path) {
        if (path == null) throw new NullPointerException();
        this.path = path;
    }

    public final String path;

    public static ExtraDataSnapshotInfoBtrfs deserialize(byte[] data) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            String path = IO.readString(in);
            return new ExtraDataSnapshotInfoBtrfs(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        try {
            IO.writeString(out, path);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataSnapshotInfoBtrfs o)) return false;
        return path.equals(o.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}
