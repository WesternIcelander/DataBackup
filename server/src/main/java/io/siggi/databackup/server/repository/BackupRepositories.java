package io.siggi.databackup.server.repository;

import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class BackupRepositories {
    private final File directory;
    private final Map<String, Reference<BackupRepository>> repositoryMap = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    public BackupRepositories(File directory) {
        this.directory = directory;
    }

    public BackupRepository get(String name) {
        readLock.lock();
        try {
            Reference<BackupRepository> ref = repositoryMap.get(name);
            if (ref != null) {
                BackupRepository repository = ref.get();
                if (repository != null) return repository;
            }
        } finally {
            readLock.unlock();
        }
        writeLock.lock();
        try {
            Reference<BackupRepository> ref = repositoryMap.get(name);
            if (ref != null) {
                BackupRepository repository = ref.get();
                if (repository != null) return repository;
            }
            clean();
            BackupRepository repository = new BackupRepository(name, new File(directory, name));
            repositoryMap.put(name, new WeakReference<>(repository));
            return repository;
        } finally {
            writeLock.unlock();
        }
    }

    private void clean() {
        for (Iterator<Map.Entry<String, Reference<BackupRepository>>> it = repositoryMap.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Reference<BackupRepository>> entry = it.next();
            Reference<BackupRepository> value = entry.getValue();
            if (value == null || value.get() == null) it.remove();
        }
    }
}
