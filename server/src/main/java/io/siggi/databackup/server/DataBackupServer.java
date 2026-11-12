package io.siggi.databackup.server;

import io.siggi.databackup.auth.Authorizer;
import io.siggi.databackup.auth.simple.SimpleAuthorizer;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.osutils.OS;
import io.siggi.databackup.server.gc.GarbageCollector;
import io.siggi.databackup.server.repository.BackupRepositories;
import io.siggi.databackup.server.retention.Pruner;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class DataBackupServer implements AutoCloseable {
    final File root;
    private final ServerConfig config;
    private final BackupRepositories backupRepositories;
    final File tmpDir;
    private final DataRepository dataRepository;
    final ContentReceiver contentReceiver;
    private final InetSocketAddress listenerAddress;
    final HTTPServer httpServer;
    private final ServerSocket serverSocket;
    private final RequestHandler requestHandler;
    private Thread listenerThread;
    private Thread cleanupThread;
    private final ReentrantReadWriteLock maintenanceLock = new ReentrantReadWriteLock();
    private FileChannel dataLockChannel;
    private FileLock dataLock;
    private boolean started = false;
    private volatile boolean shutdown = false;

    /**
     * Creates a server that reads its settings from {@code server.json} and its tokens from the {@code tokens}
     * folder in {@code root}.
     *
     * @param root            the data folder
     * @param listenerAddress the address to listen on, or {@code null} for the bind address and port from
     *                        {@code server.json} (port 8080 on all interfaces by default). Port 0 picks a free port;
     *                        see {@link #getPort()}.
     */
    public DataBackupServer(File root, InetSocketAddress listenerAddress) throws IOException {
        this(root, listenerAddress, null);
    }

    /**
     * Creates a server that reads its settings from {@code server.json} in {@code root}.
     *
     * @param root            the data folder
     * @param listenerAddress the address to listen on, or {@code null} for the bind address and port from
     *                        {@code server.json} (port 8080 on all interfaces by default). Port 0 picks a free port;
     *                        see {@link #getPort()}.
     * @param authorizer      the authorizer to check tokens with, or {@code null} to use a {@link SimpleAuthorizer}
     *                        on the {@code tokens} folder in {@code root}
     */
    public DataBackupServer(File root, InetSocketAddress listenerAddress, Authorizer authorizer) throws IOException {
        this(root, listenerAddress, authorizer, ServerConfig.load(root));
    }

    /**
     * Creates a server with the given settings instead of reading {@code server.json}.
     *
     * @param root            the data folder
     * @param listenerAddress the address to listen on, or {@code null} for the config's bind address and port. Port 0
     *                        picks a free port; see {@link #getPort()}.
     * @param authorizer      the authorizer to check tokens with, or {@code null} to use a {@link SimpleAuthorizer}
     *                        on the {@code tokens} folder in {@code root}
     * @param config          the server settings, or {@code null} for the defaults
     */
    public DataBackupServer(File root, InetSocketAddress listenerAddress, Authorizer authorizer, ServerConfig config)
            throws IOException {
        if (config == null) config = ServerConfig.defaults();
        this.config = config;
        this.root = root;
        this.backupRepositories = new BackupRepositories(new File(root, "backups"));
        this.tmpDir = new File(root, "tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File dataRepositoryRoot = new File(root, "repository");
        this.dataRepository = new DataRepository(dataRepositoryRoot);
        this.dataRepository.setCompressionPolicy(config.getCompressionPolicy());
        this.contentReceiver = new ContentReceiver(dataRepository, tmpDir);
        if (listenerAddress == null) {
            listenerAddress = config.getListenerAddress();
        }
        this.listenerAddress = listenerAddress;
        this.httpServer = new HTTPServerBuilder().build();
        if (authorizer == null) {
            authorizer = new SimpleAuthorizer(new File(root, "tokens"));
        }
        this.serverSocket = new ServerSocket();
        try {
            serverSocket.bind(listenerAddress);
        } catch (IOException e) {
            serverSocket.close();
            throw e;
        }
        this.requestHandler = new RequestHandler(this, authorizer);
        this.httpServer.responderRegistry.register("/", this.requestHandler);
        this.httpServer.setIgnoringMultipartFormData(true);
    }

    public static void main(String[] args) throws Exception {
        System.exit(ServerCli.run(args, System.out, System.err));
    }

    /**
     * Returns the data folder: the {@code databackupdir} system property if set, else {@code ./databackup} if it
     * exists, else the OS default.
     */
    static File defaultDataRoot() {
        String dataBackupDir = System.getProperty("databackupdir");
        if (dataBackupDir != null) return new File(dataBackupDir);
        File dataRoot = new File("databackup");
        if (dataRoot.exists()) return dataRoot;
        return switch (OS.get()) {
            case WINDOWS -> new File("C:\\ProgramData\\DataBackup");
            case MACOS -> new File("/Library/Application Support/DataBackup");
            default -> new File("/var/lib/databackup");
        };
    }

    /**
     * Returns the port the server is bound to. This is the actual port when the server was created with port 0.
     */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * Starts accepting connections. Does nothing if the server was already started or shut down.
     */
    public synchronized void start() {
        if (started || shutdown) return;
        try {
            dataLockChannel = openDataLockChannel(root);
            dataLock = tryLock(dataLockChannel);
        } catch (IOException e) {
            closeQuietly(dataLockChannel);
            dataLockChannel = null;
            throw new IllegalStateException("Could not lock " + new File(root, DATA_LOCK_FILE) + ": " + e, e);
        }
        if (dataLock == null) {
            closeQuietly(dataLockChannel);
            dataLockChannel = null;
            throw new IllegalStateException("The data folder " + root + " is in use by another server or by"
                    + " \"server gc\"");
        }
        // Only now that this server holds the data folder: another server may be using these files.
        clearTmpDir();
        started = true;
        (listenerThread = new Thread(this::listenerThread, "ListenerThread")).start();
        (cleanupThread = new Thread(this::cleanupThread, "CleanupThread")).start();
    }

    /**
     * Deletes leftovers from an earlier run in {@code tmp/}. Called only while holding the data folder lock, so it
     * can't delete the in-progress uploads of another server on the same folder.
     */
    private void clearTmpDir() {
        if (!tmpDir.exists()) tmpDir.mkdirs();
        File[] files = tmpDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            file.delete();
        }
    }

    /**
     * Stops accepting connections and waits for the listener and cleanup threads to stop. Requests already being
     * handled may still finish afterwards. Calling this more than once, or before {@link #start()}, is allowed.
     */
    public void shutdown() throws InterruptedException {
        Thread listener;
        Thread cleanup;
        synchronized (this) {
            shutdown = true;
            listener = listenerThread;
            cleanup = cleanupThread;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        if (listener != null) {
            listener.interrupt();
            listener.join();
        }
        if (cleanup != null) {
            cleanup.interrupt();
            cleanup.join();
        }
        synchronized (this) {
            closeQuietly(dataLockChannel);
            dataLockChannel = null;
            dataLock = null;
        }
    }

    /**
     * The file in the data folder a running server holds a lock on, so that {@code server gc} can't run alongside
     * it (garbage collection must not overlap a manifest upload or commit).
     */
    static final String DATA_LOCK_FILE = "server.lock";

    static FileChannel openDataLockChannel(File root) throws IOException {
        return FileChannel.open(new File(root, DATA_LOCK_FILE).toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
    }

    /**
     * Tries to take an exclusive lock on the channel, returning null if another process or channel holds it.
     */
    static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException e) {
            return null;
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) return;
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Waits until the server has stopped accepting connections, after {@link #shutdown()} or a fatal error. Returns
     * at once if the server was never started.
     */
    public void awaitTermination() throws InterruptedException {
        Thread listener;
        synchronized (this) {
            listener = listenerThread;
        }
        if (listener != null) listener.join();
    }

    /**
     * Same as {@link #shutdown()}.
     */
    @Override
    public void close() throws InterruptedException {
        shutdown();
    }

    private void listenerThread() {
        while (!shutdown) {
            try {
                Socket socket = serverSocket.accept();
                httpServer.handle(socket);
            } catch (Exception e) {
                if (shutdown) break;
                e.printStackTrace();
            }
        }
    }

    private void cleanupThread() {
        while (!shutdown) {
            cleanup();
            try {
                Thread.sleep(3600000L);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * The hourly maintenance run: prunes every machine's backups by its retention policy, then collects garbage.
     */
    void cleanup() {
        try {
            logPruneResults(prune(false));
        } catch (RuntimeException e) {
            System.err.println("Pruning failed:");
            e.printStackTrace();
        }
        try {
            GarbageCollector.Result result = gc(false);
            if (result.itemsDeleted() > 0 || !result.failed().isEmpty()) {
                System.err.println("Garbage collection " + result.summary(false));
                for (String failed : result.failed()) System.err.println("  could not delete " + failed);
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("Garbage collection failed: " + e.getMessage());
            if (e instanceof RuntimeException) e.printStackTrace();
        }
    }

    /**
     * Collects garbage (see {@link GarbageCollector}): unreferenced content, pending manifests and temp files older
     * than the grace period from the config. Manifest uploads and commits wait while this runs.
     *
     * @param dryRun if true, only work out what would be deleted
     */
    public GarbageCollector.Result gc(boolean dryRun) throws IOException {
        maintenanceLock.writeLock().lock();
        try {
            return createGarbageCollector(root, dataRepository, config).run(dryRun, System.currentTimeMillis());
        } finally {
            maintenanceLock.writeLock().unlock();
        }
    }

    static GarbageCollector createGarbageCollector(File root, DataRepository dataRepository, ServerConfig config) {
        return new GarbageCollector(new File(root, "backups"), new File(root, "tmp"), dataRepository,
                config.getGcGracePeriodMillis(), ContentReceiver::lockFor);
    }

    /**
     * The lock that keeps garbage collection (write lock) apart from manifest uploads and commits (read lock).
     */
    ReentrantReadWriteLock getMaintenanceLock() {
        return maintenanceLock;
    }

    /**
     * Applies the retention policies from the config to every machine's backups, counting days, weeks and months in
     * the system's default time zone.
     *
     * @param dryRun if true, only work out what would be deleted
     */
    public List<Pruner.MachineResult> prune(boolean dryRun) {
        return createPruner(backupRepositories, config).pruneAll(dryRun);
    }

    static Pruner createPruner(BackupRepositories repositories, ServerConfig config) {
        return new Pruner(repositories, config, ZoneId.systemDefault(), RequestHandler::isValidName);
    }

    private static void logPruneResults(List<Pruner.MachineResult> results) {
        for (Pruner.MachineResult result : results) {
            if (result.deleted().isEmpty() && result.failed().isEmpty()) continue;
            System.err.println("Pruned " + result.machine() + ": deleted " + result.deleted().size()
                    + " backup(s), kept " + result.kept().size()
                    + (result.failed().isEmpty() ? "" : ", could not delete " + String.join(", ", result.failed())));
        }
    }

    public ServerConfig getConfig() {
        return config;
    }

    public DataRepository getDataRepository() {
        return dataRepository;
    }

    public BackupRepositories getBackupRepositories() {
        return backupRepositories;
    }
}
