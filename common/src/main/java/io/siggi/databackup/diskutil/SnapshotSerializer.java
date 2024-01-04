package io.siggi.databackup.diskutil;

import com.google.gson.JsonObject;

public interface SnapshotSerializer<S extends Snapshot> {
    S deserialize(JsonObject object);
    JsonObject serialize(S snapshot);
}
