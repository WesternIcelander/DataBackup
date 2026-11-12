package io.siggi.databackup.server;

import io.siggi.databackup.auth.Authorizer;
import io.siggi.databackup.auth.simple.SimpleAuthorizer;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.osutils.OS;
import io.siggi.databackup.server.repository.BackupRepositories;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class DataBackupServer implements AutoCloseable {
    final File root;
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
    private boolean started = false;
    private volatile boolean shutdown = false;

    /**
     * Creates a server that reads its tokens from the {@code tokens} folder in {@code root}.
     *
     * @param root            the data folder
     * @param listenerAddress the address to listen on, or {@code null} for port 8080 on all interfaces. Port 0 picks
     *                        a free port; see {@link #getPort()}.
     */
    public DataBackupServer(File root, InetSocketAddress listenerAddress) throws IOException {
        this(root, listenerAddress, null);
    }

    /**
     * Creates a server.
     *
     * @param root            the data folder
     * @param listenerAddress the address to listen on, or {@code null} for port 8080 on all interfaces. Port 0 picks
     *                        a free port; see {@link #getPort()}.
     * @param authorizer      the authorizer to check tokens with, or {@code null} to use a {@link SimpleAuthorizer}
     *                        on the {@code tokens} folder in {@code root}
     */
    public DataBackupServer(File root, InetSocketAddress listenerAddress, Authorizer authorizer) throws IOException {
        this.root = root;
        this.backupRepositories = new BackupRepositories(new File(root, "backups"));
        this.tmpDir = new File(root, "tmp");
        if (!tmpDir.exists()) tmpDir.mkdirs();
        for (File file : tmpDir.listFiles()) {
            file.delete();
        }
        File dataRepositoryRoot = new File(root, "repository");
        this.dataRepository = new DataRepository(dataRepositoryRoot);
        this.contentReceiver = new ContentReceiver(dataRepository, tmpDir);
        if (listenerAddress == null) {
            listenerAddress = new InetSocketAddress(8080);
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
        String dataBackupDir = System.getProperty("databackupdir");
        File dataRoot;
        if (dataBackupDir != null) {
            dataRoot = new File(dataBackupDir);
        } else {
            dataRoot = new File("databackup");
            if (!dataRoot.exists()) {
                dataRoot = switch (OS.get()) {
                    case WINDOWS -> new File("C:\\ProgramData\\DataBackup");
                    case MACOS -> new File("/Library/Application Support/DataBackup");
                    default -> new File("/var/lib/databackup");
                };
            }
        }
        if (!dataRoot.exists() && !dataRoot.mkdirs()) {
            System.err.println("Required directory does not exist: " + dataRoot.getPath());
            System.exit(1);
            return;
        }
        new DataBackupServer(dataRoot, null).start();
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
        started = true;
        (listenerThread = new Thread(this::listenerThread, "ListenerThread")).start();
        (cleanupThread = new Thread(this::cleanupThread, "CleanupThread")).start();
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

    private void cleanup() {
    }

    public DataRepository getDataRepository() {
        return dataRepository;
    }

    public BackupRepositories getBackupRepositories() {
        return backupRepositories;
    }
}
