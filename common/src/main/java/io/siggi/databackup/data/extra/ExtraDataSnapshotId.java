package io.siggi.databackup.data.extra;

import io.siggi.databackup.util.IO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

public final class ExtraDataSnapshotId extends ExtraData {

    public ExtraDataSnapshotId(UUID uuid) {
        if (uuid == null) throw new NullPointerException();
        this.uuid = uuid;
    }

    public final UUID uuid;

    public static ExtraDataSnapshotId deserialize(byte[] data) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            return new ExtraDataSnapshotId(new UUID(IO.readLong(in), IO.readLong(in)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        try {
            IO.writeLong(out, uuid.getMostSignificantBits());
            IO.writeLong(out, uuid.getLeastSignificantBits());
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ExtraDataSnapshotId o)) return false;
        return uuid.equals(o.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
