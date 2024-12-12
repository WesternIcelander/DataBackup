package io.siggi.databackup.diskutil.btrfs;

import com.google.gson.JsonObject;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotSerializer;

public class BtrfsSnapshotSerializer implements SnapshotSerializer {
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
    public JsonObject serialize(Snapshot snapshot) {
        if (!(snapshot instanceof BtrfsSnapshot btrfsSnapshot)) throw new IllegalArgumentException("Snapshot to serialize is not a BtrfsSnapshot.");
        JsonObject object = new JsonObject();
        object.addProperty("path", btrfsSnapshot.path());
        return object;
    }
}
