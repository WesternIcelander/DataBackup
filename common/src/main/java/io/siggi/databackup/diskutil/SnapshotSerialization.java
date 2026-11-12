package io.siggi.databackup.diskutil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
import java.util.function.Function;

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

    /**
     * The serializer for one of the built-in snapshot types ({@code apfs}, {@code btrfs}, {@code ntfs}), or null.
     */
    public static SnapshotSerializer serializerFor(String type) {
        if (type == null) return null;
        return switch (type) {
            case "apfs" -> ApfsSnapshotSerializer.get();
            case "btrfs" -> BtrfsSnapshotSerializer.get();
            case "ntfs" -> NtfsSnapshotSerializer.get();
            default -> null;
        };
    }

    /**
     * Serializes a snapshot to JSON, adding a {@code type} property naming its filesystem.
     */
    public static JsonObject serialize(Snapshot snapshot) {
        JsonObject object = snapshot.diskUtil().serializer().serialize(snapshot);
        object.addProperty("type", snapshot.diskUtil().filesystem());
        return object;
    }

    /**
     * Reads a snapshot written by {@link #serialize(Snapshot)}, for one of the built-in types.
     *
     * @throws IllegalArgumentException if the type is unknown or the object is malformed
     */
    public static Snapshot deserialize(JsonObject object) {
        return deserialize(object, SnapshotSerialization::serializerFor);
    }

    /**
     * Reads a snapshot written by {@link #serialize(Snapshot)}, looking up its serializer by type.
     *
     * @param serializers returns the serializer for a type, or null if the type is unknown
     * @throws IllegalArgumentException if the type is unknown or the object is malformed
     */
    public static Snapshot deserialize(JsonObject object, Function<String, SnapshotSerializer> serializers) {
        JsonElement typeElement = object.get("type");
        if (typeElement == null || !typeElement.isJsonPrimitive()) {
            throw new IllegalArgumentException("Snapshot has no type");
        }
        String snapshotType = typeElement.getAsString();
        SnapshotSerializer serializer = serializers.apply(snapshotType);
        if (serializer == null) throw new IllegalArgumentException("Unknown snapshot type: " + snapshotType);
        try {
            return serializer.deserialize(object);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid " + snapshotType + " snapshot: " + e.getMessage(), e);
        }
    }

    private static final TypeAdapter<Snapshot> snapshotAdapter = new TypeAdapter<>() {
        @Override
        public Snapshot read(JsonReader reader) throws IOException {
            return deserialize(JsonParser.parseReader(reader).getAsJsonObject());
        }

        @Override
        public void write(JsonWriter writer, Snapshot snapshot) throws IOException {
            gson.toJson(serialize(snapshot), writer);
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
