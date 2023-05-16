package io.siggi.databackup.data.extra;

import io.siggi.databackup.util.IO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public final class ExtraDataSnapshotInfoApfs extends ExtraData {

    public ExtraDataSnapshotInfoApfs(String name, long xid) {
        if (name == null) throw new NullPointerException();
        this.name = name;
        this.xid = xid;
    }

    public final String name;
    public final long xid;

    public static ExtraDataSnapshotInfoApfs deserialize(byte[] data) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            String name = IO.readString(in);
            long xid = IO.readLong(in);
            return new ExtraDataSnapshotInfoApfs(name, xid);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        try {
            IO.writeString(out, name);
            IO.writeLong(out, xid);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataSnapshotInfoApfs o)) return false;
        return name.equals(o.name) && xid == o.xid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, xid);
    }
}
