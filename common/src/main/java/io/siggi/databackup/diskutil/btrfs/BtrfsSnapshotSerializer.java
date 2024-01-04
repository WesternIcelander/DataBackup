package io.siggi.databackup.diskutil.btrfs;

import com.google.gson.JsonObject;
import io.siggi.databackup.diskutil.SnapshotSerializer;

public class BtrfsSnapshotSerializer implements SnapshotSerializer<BtrfsSnapshot> {
    private BtrfsSnapshotSerializer() {
    }

    private static final BtrfsSnapshotSerializer instance = new BtrfsSnapshotSerializer();

    public static BtrfsSnapshotSerializer get() {
        return instance;
    }

    @Override
    public BtrfsSnapshot deserialize(JsonObject object) {
        return new BtrfsSnapshot(
                object.get("path").getAsString()
        );
    }

    @Override
    public JsonObject serialize(BtrfsSnapshot snapshot) {
        JsonObject object = new JsonObject();
        object.addProperty("path", snapshot.path());
        return object;
    }
}
