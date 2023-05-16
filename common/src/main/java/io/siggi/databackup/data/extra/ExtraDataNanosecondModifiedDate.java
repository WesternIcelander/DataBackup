package io.siggi.databackup.data.extra;

import io.siggi.databackup.util.IO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public final class ExtraDataNanosecondModifiedDate extends ExtraData {

    public ExtraDataNanosecondModifiedDate(long seconds, int nanos) {
        this.seconds = seconds;
        this.nanos = nanos;
    }

    public final long seconds;
    public final int nanos;

    public static ExtraDataNanosecondModifiedDate deserialize(byte[] data) {
        ByteArrayInputStream in = new ByteArrayInputStream(data);
        try {
            long seconds = IO.readLong(in);
            int nanos = IO.readInt(in);
            return new ExtraDataNanosecondModifiedDate(seconds, nanos);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    @Override
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        try {
            IO.writeLong(out, seconds);
            IO.writeInt(out, nanos);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataNanosecondModifiedDate o)) return false;
        return seconds == o.seconds && nanos == o.nanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(seconds, nanos);
    }
}
