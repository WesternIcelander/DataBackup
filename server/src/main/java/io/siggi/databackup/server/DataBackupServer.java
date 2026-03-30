package io.siggi.databackup.server;

import io.siggi.databackup.auth.simple.SimpleAuthorizer;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.osutils.OS;
import io.siggi.http.HTTPServer;
import io.siggi.http.HTTPServerBuilder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class DataBackupServer {
    final File root;
    final File backupsDir;
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
    private boolean shutdown = false;

    public DataBackupServer(File root, InetSocketAddress listenerAddress) throws IOException {
        this.root = root;
        this.backupsDir = new File(root, "backups");
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
        this.serverSocket = new ServerSocket();
        serverSocket.bind(listenerAddress);
        this.requestHandler = new RequestHandler(this, new SimpleAuthorizer(new File(root, "tokens")));
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

    private void start() {
        if (started) return;
        started = true;
        (listenerThread = new Thread(this::listenerThread, "ListenerThread")).start();
        (cleanupThread = new Thread(this::cleanupThread, "CleanupThread")).start();
    }

    private void shutdown() {
        if (!started || shutdown) return;
        shutdown = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        listenerThread.interrupt();
        cleanupThread.interrupt();
    }

    private void listenerThread() {
        while (!shutdown) {
            try {
                Socket socket = serverSocket.accept();
                httpServer.handle(socket);
            } catch (Exception e) {
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
}
