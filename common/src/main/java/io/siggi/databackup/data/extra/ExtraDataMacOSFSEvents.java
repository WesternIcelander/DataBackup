package io.siggi.databackup.data.extra;

import io.siggi.databackup.util.IO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

public class ExtraDataMacOSFSEvents extends ExtraData {

    public ExtraDataMacOSFSEvents(long lastEventId, UUID fsEventsUuid) {
        if (fsEventsUuid == null) throw new NullPointerException();
        this.lastEventId = lastEventId;
        this.fsEventsUuid = fsEventsUuid;
    }

    public final long lastEventId;
    public final UUID fsEventsUuid;

    public static ExtraDataMacOSFSEvents deserialize(byte[] data) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            long lastEventId = IO.readLong(in);
            long uuidMost = IO.readLong(in);
            long uuidLeast = IO.readLong(in);
            return new ExtraDataMacOSFSEvents(lastEventId, new UUID(uuidMost, uuidLeast));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        try {
            IO.writeLong(out, lastEventId);
            IO.writeLong(out, fsEventsUuid.getMostSignificantBits());
            IO.writeLong(out, fsEventsUuid.getLeastSignificantBits());
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataMacOSFSEvents o)) return false;
        return lastEventId == o.lastEventId && fsEventsUuid.equals(o.fsEventsUuid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lastEventId, fsEventsUuid);
    }
}
