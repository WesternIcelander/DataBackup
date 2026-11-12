package io.siggi.databackup.client.snapshot;

import com.google.gson.JsonObject;
import io.siggi.databackup.client.ClientConfig.Source;
import io.siggi.databackup.client.ExcludeMatcher;
import io.siggi.databackup.diskutil.DiskUtil;
import io.siggi.databackup.diskutil.MountedDisk;
import io.siggi.databackup.diskutil.Snapshot;
import io.siggi.databackup.diskutil.SnapshotException;
import io.siggi.databackup.diskutil.SnapshotSerializer;
import io.siggi.databackup.osutils.OS;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A scripted system for snapshot tests: a chosen OS, mount table, btrfs subvolumes, Windows drives, and fake
 * {@link DiskUtil}s whose snapshots live in memory.
 */
final class FakeSnapshotPlatform implements SnapshotPlatform {

    OS os = OS.LINUX;
    final List<MountedDisk> mounts = new ArrayList<>();
    final Set<String> directories = new HashSet<>();
    final Map<String, String> symlinks = new HashMap<>();
    final Set<String> btrfsSubvolumes = new HashSet<>();
    final Map<String, String> windowsDrives = new HashMap<>();
    final Map<String, FakeDiskUtil> diskUtils = new HashMap<>();
    boolean mountsFail;

    FakeSnapshotPlatform() {
        for (String type : List.of("apfs", "btrfs", "ntfs")) {
            diskUtils.put(type, new FakeDiskUtil(type, type.equals("btrfs")));
        }
    }

    FakeDiskUtil fake(String type) {
        return diskUtils.get(type);
    }

    void mount(String dev, String path, String type, String... options) {
        mounts.add(new MountedDisk(dev, path, type, List.of(options)));
        directories.add(path);
    }

    static Source source(String name, String path) {
        return new Source(name, Path.of(path), List.of(), ExcludeMatcher.none());
    }

    @Override
    public OS os() {
        return os;
    }

    @Override
    public List<MountedDisk> mounts() throws IOException {
        if (mountsFail) throw new IOException("mount failed");
        return mounts;
    }

    @Override
    public Path realPath(Path path) throws IOException {
        String target = symlinks.get(path.toString());
        if (target != null) return Path.of(target);
        if (!directories.contains(path.toString())) throw new java.nio.file.NoSuchFileException(path.toString());
        return path;
    }

    @Override
    public boolean isDirectory(Path path) {
        return directories.contains(path.toString()) || windowsDrives.containsKey(drive(path));
    }

    @Override
    public String checkBtrfsSubvolume(Path path) {
        return btrfsSubvolumes.contains(path.toString()) ? null : "ERROR: Not a Btrfs subvolume";
    }

    @Override
    public String windowsFileSystemType(Path driveRoot) throws IOException {
        String type = windowsDrives.get(drive(driveRoot));
        if (type == null) throw new IOException("no drive");
        return type;
    }

    private static String drive(Path path) {
        String s = path.toString();
        return s.isEmpty() ? "" : s.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    @Override
    public DiskUtil diskUtil(String fileSystemType) {
        return diskUtils.get(fileSystemType.toLowerCase(Locale.ROOT));
    }

    /**
     * A {@link DiskUtil} that keeps its snapshots in memory. {@link #existing} is the set of snapshot IDs that
     * currently exist, shared with every {@link FakeSnapshot} read back from JSON.
     */
    static final class FakeDiskUtil implements DiskUtil {
        final String type;
        final boolean requiresDestination;
        boolean supportsSnapshots = true;
        final Set<String> existing = new HashSet<>();
        final Set<String> mounted = new HashSet<>();
        final List<String> deleted = new ArrayList<>();
        final List<List<String>> createCalls = new ArrayList<>();
        final Map<String, String> destinations = new LinkedHashMap<>();
        final Set<String> failCreate = new HashSet<>();
        final Set<String> skipCreate = new HashSet<>();
        final Set<String> extraCreated = new HashSet<>();
        final Set<String> failDelete = new HashSet<>();
        final Set<String> failMount = new HashSet<>();
        private int counter;

        FakeDiskUtil(String type, boolean requiresDestination) {
            this.type = type;
            this.requiresDestination = requiresDestination;
        }

        @Override
        public String filesystem() {
            return type;
        }

        @Override
        public SnapshotSerializer serializer() {
            return new SnapshotSerializer() {
                @Override
                public Snapshot deserialize(JsonObject object) {
                    return new FakeSnapshot(FakeDiskUtil.this, object.get("id").getAsString(),
                            object.get("volume").getAsString());
                }

                @Override
                public JsonObject serialize(Snapshot snapshot) {
                    FakeSnapshot fake = (FakeSnapshot) snapshot;
                    JsonObject object = new JsonObject();
                    object.addProperty("id", fake.id);
                    object.addProperty("volume", fake.volume);
                    return object;
                }
            };
        }

        @Override
        public boolean supportsSnapshots() {
            return supportsSnapshots;
        }

        @Override
        public boolean snapshotsRequireDestination() {
            return requiresDestination;
        }

        @Override
        public Map<String, Snapshot> createSnapshots(Collection<String> volumes, Function<String, String> destination)
                throws SnapshotException {
            createCalls.add(List.copyOf(volumes));
            Map<String, Snapshot> result = new HashMap<>();
            for (String volume : volumes) {
                if (failCreate.contains(volume)) throw new SnapshotException("create failed for " + volume);
                if (requiresDestination) destinations.put(volume, destination.apply(volume));
                if (skipCreate.contains(volume)) continue;
                result.put(volume, newSnapshot(volume));
            }
            for (String extra : extraCreated) result.put(extra, newSnapshot(extra));
            return result;
        }

        private FakeSnapshot newSnapshot(String volume) {
            String id = type + "-" + (++counter);
            existing.add(id);
            return new FakeSnapshot(this, id, volume);
        }
    }

    static final class FakeSnapshot implements Snapshot {
        final FakeDiskUtil diskUtil;
        final String id;
        final String volume;

        FakeSnapshot(FakeDiskUtil diskUtil, String id, String volume) {
            this.diskUtil = diskUtil;
            this.id = id;
            this.volume = volume;
        }

        @Override
        public DiskUtil diskUtil() {
            return diskUtil;
        }

        @Override
        public String mountedPath() {
            return diskUtil.mounted.contains(id) ? "/mnt/" + id : null;
        }

        @Override
        public boolean isMounted() {
            return diskUtil.mounted.contains(id);
        }

        @Override
        public String mount() throws SnapshotException {
            if (diskUtil.failMount.contains(volume)) throw new SnapshotException("mount failed");
            diskUtil.mounted.add(id);
            return mountedPath();
        }

        @Override
        public void unmount() {
            diskUtil.mounted.remove(id);
        }

        @Override
        public void delete() throws SnapshotException {
            if (diskUtil.failDelete.contains(volume)) throw new SnapshotException("delete failed");
            if (!diskUtil.existing.remove(id)) throw new SnapshotException("no such snapshot " + id);
            diskUtil.deleted.add(id);
        }

        @Override
        public boolean isValid() {
            return diskUtil.existing.contains(id);
        }

        @Override
        public String toString() {
            return "FakeSnapshot{" + id + "}";
        }
    }
}
