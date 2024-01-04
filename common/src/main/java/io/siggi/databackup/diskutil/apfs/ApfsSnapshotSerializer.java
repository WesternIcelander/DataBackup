package io.siggi.databackup.diskutil.apfs;

import com.google.gson.JsonObject;
import io.siggi.databackup.diskutil.SnapshotSerializer;

import java.util.UUID;

public class ApfsSnapshotSerializer implements SnapshotSerializer<ApfsSnapshot> {
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
    public JsonObject serialize(ApfsSnapshot snapshot) {
        JsonObject object = new JsonObject();
        object.addProperty("volumeUuid", snapshot.volumeUuid().toString());
        object.addProperty("snapshotUuid", snapshot.snapshotUuid().toString());
        object.addProperty("name", snapshot.name());
        object.addProperty("xid", snapshot.xid());
        return object;
    }
}
