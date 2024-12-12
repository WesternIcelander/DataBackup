package io.siggi.databackup.diskutil.ntfs;

import com.google.gson.JsonObject;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotSerializer;

import java.util.UUID;

public class NtfsSnapshotSerializer implements SnapshotSerializer {
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
    public JsonObject serialize(Snapshot snapshot) {
        if (!(snapshot instanceof NtfsSnapshot ntfsSnapshot)) throw new IllegalArgumentException("Snapshot to serialize is not an NtfsSnapshot.");
        JsonObject object = new JsonObject();
        object.addProperty("volumeId", ntfsSnapshot.volumeId().toString());
        object.addProperty("shadowCopyId", ntfsSnapshot.shadowCopyId().toString());
        return object;
    }
}
