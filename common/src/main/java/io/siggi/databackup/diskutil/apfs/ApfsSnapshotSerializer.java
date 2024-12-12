package io.siggi.databackup.diskutil.apfs;

import com.google.gson.JsonObject;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotSerializer;

import java.util.UUID;

public class ApfsSnapshotSerializer implements SnapshotSerializer {
    private ApfsSnapshotSerializer() {
    }

    private static final ApfsSnapshotSerializer instance = new ApfsSnapshotSerializer();

    public static ApfsSnapshotSerializer get() {
        return instance;
    }

    @Override
    public ApfsSnapshot deserialize(JsonObject object) {
        return new ApfsSnapshot(
                UUID.fromString(object.get("volumeUuid").getAsString()),
                UUID.fromString(object.get("snapshotUuid").getAsString()),
                object.get("name").getAsString(),
                object.get("xid").getAsLong()
        );
    }

    @Override
    public JsonObject serialize(Snapshot snapshot) {
        if (!(snapshot instanceof ApfsSnapshot apfsSnapshot)) throw new IllegalArgumentException("Snapshot to serialize is not an ApfsSnapshot.");
        JsonObject object = new JsonObject();
        object.addProperty("volumeUuid", apfsSnapshot.volumeUuid().toString());
        object.addProperty("snapshotUuid", apfsSnapshot.snapshotUuid().toString());
        object.addProperty("name", apfsSnapshot.name());
        object.addProperty("xid", apfsSnapshot.xid());
        return object;
    }
}
