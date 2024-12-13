package io.siggi.databackup.server.repository;

import java.io.File;
import java.util.UUID;

public class BackupItem {
    private final BackupRepository repository;
    private final File file;
    private final String name;
    private final UUID uuid;
    private final long time;

    BackupItem(BackupRepository repository, File file, String name, UUID uuid, long time) {
        this.repository = repository;
        this.file = file;
        this.name = name;
        this.uuid = uuid;
        this.time = time;
    }

    public BackupRepository getRepository() {
        return repository;
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public long getTime() {
        return time;
    }
}
