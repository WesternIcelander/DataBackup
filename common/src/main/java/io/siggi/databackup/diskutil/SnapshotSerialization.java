package io.siggi.databackup.diskutil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.siggi.databackup.diskutil.apfs.ApfsSnapshot;
import io.siggi.databackup.diskutil.apfs.ApfsSnapshotSerializer;
import io.siggi.databackup.diskutil.btrfs.BtrfsSnapshot;
import io.siggi.databackup.diskutil.btrfs.BtrfsSnapshotSerializer;
import io.siggi.databackup.diskutil.ntfs.NtfsSnapshot;
import io.siggi.databackup.diskutil.ntfs.NtfsSnapshotSerializer;

import java.io.IOException;

public class SnapshotSerialization {

    private SnapshotSerialization() {
    }

    private static final Gson gson = new Gson();

    public static GsonBuilder registerTypeAdapters(GsonBuilder gsonBuilder) {
        gsonBuilder.registerTypeAdapter(Snapshot.class, snapshotAdapter);
        gsonBuilder.registerTypeAdapter(ApfsSnapshot.class, apfsAdapter);
        gsonBuilder.registerTypeAdapter(BtrfsSnapshot.class, btrfsAdapter);
        gsonBuilder.registerTypeAdapter(NtfsSnapshot.class, ntfsAdapter);
        return gsonBuilder;
    }

    private static final TypeAdapter<ApfsSnapshot> apfsAdapter = new SnapshotAdapter<>(ApfsSnapshot.class, ApfsSnapshotSerializer.get());
    private static final TypeAdapter<BtrfsSnapshot> btrfsAdapter = new SnapshotAdapter<>(BtrfsSnapshot.class, BtrfsSnapshotSerializer.get());
    private static final TypeAdapter<NtfsSnapshot> ntfsAdapter = new SnapshotAdapter<>(NtfsSnapshot.class, NtfsSnapshotSerializer.get());

    private static final TypeAdapter<Snapshot> snapshotAdapter = new TypeAdapter<>() {
        @Override
        public Snapshot read(JsonReader reader) throws IOException {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            String snapshotType = object.get("type").getAsString();
            return switch (snapshotType) {
                case "apfs" -> ApfsSnapshotSerializer.get().deserialize(object);
                case "btrfs" -> BtrfsSnapshotSerializer.get().deserialize(object);
                case "ntfs" -> NtfsSnapshotSerializer.get().deserialize(object);
                default -> throw new IllegalArgumentException("Unknown snapshot type: " + snapshotType);
            };
        }

        @Override
        public void write(JsonWriter writer, Snapshot snapshot) throws IOException {
            JsonObject object = snapshot.diskUtil().serializer().serialize(snapshot);
            object.addProperty("type", snapshot.diskUtil().filesystem());
            gson.toJson(object, writer);
        }
    };

    private static class SnapshotAdapter<T extends Snapshot> extends TypeAdapter<T> {
        private final Class<T> snapshotClass;
        private final SnapshotSerializer serializer;
        SnapshotAdapter(Class<T> snapshotClass, SnapshotSerializer serializer) {
            this.snapshotClass = snapshotClass;
            this.serializer = serializer;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T read(JsonReader reader) throws IOException {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            return (T) serializer.deserialize(object);
        }

        @Override
        public void write(JsonWriter writer, T snapshot) throws IOException {
            snapshotAdapter.write(writer, snapshot);
        }
    }
}
