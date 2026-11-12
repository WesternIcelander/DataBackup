package io.siggi.databackup.client.snapshot;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * An exclusive lock on a state folder ({@code stateDir/lock}), so two client runs never take, record or delete
 * snapshots at the same time. Released by {@link #close()} or when the process exits.
 */
public final class StateLock implements AutoCloseable {

    public static final String FILE_NAME = "lock";

    private final FileChannel channel;
    private final FileLock lock;

    private StateLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the lock without waiting.
     *
     * @throws IOException if the state folder can't be created or another run holds the lock
     */
    public static StateLock acquire(Path stateDir) throws IOException {
        Files.createDirectories(stateDir);
        Path file = stateDir.resolve(FILE_NAME);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        if (lock == null) {
            channel.close();
            throw new IOException("Another DataBackup client run is using " + stateDir
                    + " (it holds " + file + "). Wait for it to finish.");
        }
        return new StateLock(channel, lock);
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
