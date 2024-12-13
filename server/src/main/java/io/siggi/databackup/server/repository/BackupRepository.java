package io.siggi.databackup.server.repository;

import io.siggi.databackup.data.BackupFile;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.extra.ExtraDataSnapshotId;
import io.siggi.databackup.util.TimeUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BackupRepository {
    private final String name;
    private final File directory;
    private final Map<String, BackupItem> byName = new HashMap<>();
    private final Map<UUID, BackupItem> byUuid = new HashMap<>();
    private final List<BackupItem> allItems = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public BackupRepository(String name, File directory) {
        this.name = name;
        this.directory = directory;
        reload();
    }

    public BackupItem get(String name) {
        readLock.lock();
        try {
            return byName.get(name);
        } finally {
            readLock.unlock();
        }
    }

    public BackupItem get(UUID uuid) {
        readLock.lock();
        try {
            return byUuid.get(uuid);
        } finally {
            readLock.unlock();
        }
    }

    public BackupItem getMostRecent() {
        readLock.lock();
        try {
            if (allItems.isEmpty()) return null;
            return allItems.get(allItems.size() - 1);
        } finally {
            readLock.unlock();
        }
    }

    public List<BackupItem> getAllItems() {
        readLock.lock();
        try {
            return new ArrayList<>(allItems);
        } finally {
            readLock.unlock();
        }
    }

    public BackupItem add(File file, long time) {
        String name = TimeUtil.toString(time);
        File target = new File(directory, name);
        writeLock.lock();
        try {
            if (target.exists()) return null;
            UUID uuid = getSnapshotUuid(file);
            if (uuid == null || byUuid.containsKey(uuid) || byName.containsKey(name)) return null;
            if (!directory.exists()) directory.mkdirs();
            if (!file.renameTo(target)) return null;
            BackupItem item = new BackupItem(this, target, name, uuid, time);
            add(item);
            sort();
            return item;
        } finally {
            writeLock.unlock();
        }
    }

    private void add(BackupItem item) {
        byName.put(item.getName(), item);
        byUuid.put(item.getUuid(), item);
        allItems.add(item);
    }

    private void sort() {
        allItems.sort(Comparator.comparingLong(BackupItem::getTime));
    }

    private UUID getSnapshotUuid(File file) {
        try (BackupFile f = BackupFile.open(file)) {
            DirectoryEntryDirectory rootDirectory = f.getRootDirectory();
            ExtraDataSnapshotId snapshotId = rootDirectory.getExtra(ExtraDataSnapshotId.class);
            if (snapshotId == null) return null;
            return snapshotId.uuid;
        } catch (Exception e) {
            return null;
        }
    }

    public void reload() {
        writeLock.lock();
        try {
            byName.clear();
            byUuid.clear();
            allItems.clear();
            File[] allFiles = directory.listFiles();
            if (allFiles == null) return;
            for (File file : allFiles) {
                String name = file.getName();
                if (name.startsWith(".")) continue;
                long time = TimeUtil.fromString(name);
                if (time == -1L) continue;
                UUID uuid = getSnapshotUuid(file);
                if (uuid == null) continue;
                add(new BackupItem(this, file, name, uuid, time));
            }
            sort();
        } finally {
            writeLock.unlock();
        }
    }
}
