package io.siggi.databackup.diskutil;

import com.google.gson.JsonObject;

public interface SnapshotSerializer {
    Snapshot deserialize(JsonObject object);
    JsonObject serialize(Snapshot snapshot);
}
