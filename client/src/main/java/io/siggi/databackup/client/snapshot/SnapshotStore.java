package io.siggi.databackup.client.snapshot;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotSerialization;
import io.siggi.databackup.diskutil.SnapshotSerializer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * The snapshots a backup run has created and not yet deleted, kept in {@code stateDir/snapshots.json} so a crashed
 * run's snapshots can be found and deleted later. The file is a JSON array of
 * {@code {"source": "<name>", "snapshot": {"type": "<filesystem>", ...}}}, the snapshot written with
 * {@link SnapshotSerialization}. Every change rewrites the whole file atomically.
 * <p>
 * Not safe for two processes at once; hold a {@link StateLock} while using it.
 */
public final class SnapshotStore {

    public static final String FILE_NAME = "snapshots.json";

    /**
     * One recorded snapshot.
     *
     * @param source   the name of the source it was taken for
     * @param snapshot the snapshot, or null if it couldn't be read back (unknown type or bad data)
     * @param json     the snapshot as stored
     * @param error    why {@code snapshot} is null, otherwise null
     */
    public record Entry(String source, Snapshot snapshot, JsonObject json, String error) {
    }

    private final Path file;
    private final Function<String, SnapshotSerializer> serializers;
    private final List<Entry> entries = new ArrayList<>();

    private SnapshotStore(Path file, Function<String, SnapshotSerializer> serializers) {
        this.file = file;
        this.serializers = serializers;
    }

    /**
     * Opens the store in a state folder, reading any existing file.
     *
     * @param diskUtils looks up the {@link DiskUtil} (and so the serializer) for a snapshot type
     * @throws IOException if the file exists but can't be read or isn't valid JSON
     */
    public static SnapshotStore open(Path stateDir, Function<String, DiskUtil> diskUtils) throws IOException {
        Function<String, SnapshotSerializer> serializers = type -> {
            DiskUtil diskUtil = diskUtils.apply(type);
            return diskUtil == null ? null : diskUtil.serializer();
        };
        SnapshotStore store = new SnapshotStore(stateDir.resolve(FILE_NAME), serializers);
        store.read();
        return store;
    }

    public Path file() {
        return file;
    }

    /**
     * The recorded snapshots, oldest first.
     */
    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /**
     * Records snapshots and writes the file.
     *
     * @return the new entries
     */
    public synchronized List<Entry> add(List<SourceSnapshotPair> snapshots) throws IOException {
        List<Entry> added = new ArrayList<>();
        for (SourceSnapshotPair pair : snapshots) {
            added.add(new Entry(pair.source(), pair.snapshot(), SnapshotSerialization.serialize(pair.snapshot()),
                    null));
        }
        List<Entry> previous = new ArrayList<>(entries);
        entries.addAll(added);
        try {
            write();
        } catch (IOException e) {
            entries.clear();
            entries.addAll(previous);
            throw e;
        }
        return added;
    }

    /**
     * A source name and its snapshot, to {@link #add}.
     */
    public record SourceSnapshotPair(String source, Snapshot snapshot) {
    }

    /**
     * Forgets an entry (matched by identity) and writes the file. The file is deleted once it is empty.
     */
    public synchronized void remove(Entry entry) throws IOException {
        int index = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == entry) {
                index = i;
                break;
            }
        }
        if (index == -1) return;
        entries.remove(index);
        try {
            write();
        } catch (IOException e) {
            entries.add(index, entry);
            throw e;
        }
    }

    private void read() throws IOException {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return;
        }
        if (json.isBlank()) return;
        JsonArray array;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) throw new IOException(file + " is not a JSON array");
            array = root.getAsJsonArray();
        } catch (JsonParseException e) {
            throw new IOException(file + " is not valid JSON: " + e.getMessage(), e);
        }
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IOException(file + " has an entry that isn't a JSON object");
            JsonObject object = element.getAsJsonObject();
            JsonElement sourceElement = object.get("source");
            String source = sourceElement != null && sourceElement.isJsonPrimitive()
                    ? sourceElement.getAsString() : "?";
            JsonElement snapshotElement = object.get("snapshot");
            if (snapshotElement == null || !snapshotElement.isJsonObject()) {
                throw new IOException(file + " has an entry without a snapshot");
            }
            JsonObject snapshotJson = snapshotElement.getAsJsonObject();
            Snapshot snapshot = null;
            String error = null;
            try {
                snapshot = SnapshotSerialization.deserialize(snapshotJson, serializers);
            } catch (IllegalArgumentException e) {
                error = e.getMessage();
            }
            entries.add(new Entry(source, snapshot, snapshotJson, error));
        }
    }

    private void write() throws IOException {
        if (entries.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        JsonArray array = new JsonArray();
        for (Entry entry : entries) {
            JsonObject object = new JsonObject();
            object.addProperty("source", entry.source());
            object.add("snapshot", entry.json());
            array.add(object);
        }
        String json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(array) + "\n";
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
