package io.siggi.databackup.diskutil.ntfs;

import com.google.gson.JsonObject;
import io.siggi.databackup.diskutil.SnapshotSerializer;

import java.util.UUID;

public class NtfsSnapshotSerializer implements SnapshotSerializer<NtfsSnapshot> {
    private NtfsSnapshotSerializer() {
    }

    private static final NtfsSnapshotSerializer instance = new NtfsSnapshotSerializer();

    public static NtfsSnapshotSerializer get() {
        return instance;
    }

    @Override
    public NtfsSnapshot deserialize(JsonObject object) {
        return new NtfsSnapshot(
                UUID.fromString(object.get("volumeId").toString()),
                UUID.fromString(object.get("shadowCopyId").toString())
        );
    }

    @Override
    public JsonObject serialize(NtfsSnapshot snapshot) {
        JsonObject object = new JsonObject();
        object.addProperty("volumeId", snapshot.volumeId().toString());
        object.addProperty("shadowCopyId", snapshot.shadowCopyId().toString());
        return object;
    }
}
