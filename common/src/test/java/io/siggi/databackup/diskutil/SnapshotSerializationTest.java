package io.siggi.databackup.diskutil;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.siggi.databackup.diskutil.apfs.ApfsSnapshot;
import io.siggi.databackup.diskutil.btrfs.BtrfsSnapshot;
import io.siggi.databackup.diskutil.ntfs.NtfsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotSerializationTest {

    @Test
    void apfsRoundTrip() {
        UUID volume = UUID.randomUUID();
        UUID snap = UUID.randomUUID();
        ApfsSnapshot original = new ApfsSnapshot(volume, snap, "com.apple.TimeMachine.2026-11-12-120000.local", 1234L);
        JsonObject json = SnapshotSerialization.serialize(original);
        assertEquals("apfs", json.get("type").getAsString());
        ApfsSnapshot copy = assertInstanceOf(ApfsSnapshot.class, SnapshotSerialization.deserialize(json));
        assertEquals(volume, copy.volumeUuid());
        assertEquals(snap, copy.snapshotUuid());
        assertEquals(original.name(), copy.name());
        assertEquals(1234L, copy.xid());
    }

    @Test
    void btrfsRoundTrip() {
        JsonObject json = SnapshotSerialization.serialize(new BtrfsSnapshot("/var/lib/x/snapshots/Data-1"));
        assertEquals("btrfs", json.get("type").getAsString());
        BtrfsSnapshot copy = assertInstanceOf(BtrfsSnapshot.class, SnapshotSerialization.deserialize(json));
        assertEquals("/var/lib/x/snapshots/Data-1", copy.path());
    }

    @Test
    void ntfsRoundTrip() {
        UUID volume = UUID.randomUUID();
        UUID shadow = UUID.randomUUID();
        JsonObject json = SnapshotSerialization.serialize(new NtfsSnapshot(volume, shadow));
        assertEquals("ntfs", json.get("type").getAsString());
        // Through a JSON string, as when read back from a file.
        JsonObject reparsed = JsonParser.parseString(json.toString()).getAsJsonObject();
        NtfsSnapshot copy = assertInstanceOf(NtfsSnapshot.class, SnapshotSerialization.deserialize(reparsed));
        assertEquals(volume, copy.volumeId());
        assertEquals(shadow, copy.shadowCopyId());
    }

    @Test
    void gsonAdaptersRoundTrip() {
        Gson gson = SnapshotSerialization.registerTypeAdapters(new GsonBuilder()).create();
        UUID volume = UUID.randomUUID();
        UUID shadow = UUID.randomUUID();
        String json = gson.toJson(new NtfsSnapshot(volume, shadow), Snapshot.class);
        NtfsSnapshot copy = assertInstanceOf(NtfsSnapshot.class, gson.fromJson(json, Snapshot.class));
        assertEquals(shadow, copy.shadowCopyId());
    }

    @Test
    void unknownOrMissingTypeIsRejected() {
        JsonObject unknown = new JsonObject();
        unknown.addProperty("type", "zfs");
        assertThrows(IllegalArgumentException.class, () -> SnapshotSerialization.deserialize(unknown));
        assertThrows(IllegalArgumentException.class, () -> SnapshotSerialization.deserialize(new JsonObject()));
        JsonObject broken = new JsonObject();
        broken.addProperty("type", "ntfs");
        broken.addProperty("volumeId", "not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> SnapshotSerialization.deserialize(broken));
    }
}
