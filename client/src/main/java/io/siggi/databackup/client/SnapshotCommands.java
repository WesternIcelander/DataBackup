package io.siggi.databackup.client;

import io.siggi.databackup.client.snapshot.IoMessages;
import io.siggi.databackup.client.snapshot.SnapshotCleanupException;
import io.siggi.databackup.client.snapshot.SnapshotManager;
import io.siggi.databackup.client.snapshot.SnapshotPlatform;
import io.siggi.databackup.client.snapshot.SnapshotSession;
import io.siggi.databackup.client.snapshot.SnapshotSetupException;
import io.siggi.databackup.client.snapshot.SnapshotVolume;
import io.siggi.databackup.client.snapshot.SourceSnapshot;
import io.siggi.databackup.client.snapshot.StateLock;
import io.siggi.databackup.client.snapshot.VolumeResolver;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code backup} and {@code snapshots cleanup} commands' snapshot handling.
 */
final class SnapshotCommands {

    /**
     * What a backup run does with its mounted snapshots. Returns the exit code.
     */
    interface SnapshotWork {
        int run(List<SourceSnapshot> snapshots) throws Exception;
    }

    private SnapshotCommands() {
    }

    /**
     * {@code snapshots cleanup}: deletes snapshots left in {@code snapshots.json} by an interrupted run.
     */
    static int cleanup(ClientConfig config, SnapshotPlatform platform, PrintStream out, PrintStream err) {
        Path stateDir = config.getStateDir();
        try (StateLock lock = StateLock.acquire(stateDir)) {
            SnapshotManager manager = new SnapshotManager(stateDir, platform, out);
            int cleaned = manager.cleanupLeftovers();
            out.println(cleaned == 0 ? "No leftover snapshots." : "Cleaned up " + cleaned + " leftover snapshot"
                    + (cleaned == 1 ? "" : "s") + ".");
            return ClientCli.OK;
        } catch (SnapshotCleanupException e) {
            printProblems(err, "Some leftover snapshots couldn't be deleted:", e.problems());
            return ClientCli.FAILED;
        } catch (IOException e) {
            err.println("Error: " + IoMessages.describe(e));
            return ClientCli.FAILED;
        }
    }

    /**
     * {@code backup}: cleans up leftovers, snapshots every source, hands the snapshots to {@code work}, and deletes
     * them again whatever happens.
     */
    static int backup(ClientConfig config, SnapshotPlatform platform, SnapshotWork work, PrintStream out,
                      PrintStream err) {
        Path stateDir = config.getStateDir();
        try (StateLock lock = StateLock.acquire(stateDir)) {
            SnapshotManager manager = new SnapshotManager(stateDir, platform, out);
            try {
                manager.cleanupLeftovers();
            } catch (SnapshotCleanupException e) {
                // Don't block backups over an old snapshot; it stays recorded for the next attempt.
                printProblems(err, "Warning: some leftover snapshots couldn't be deleted:", e.problems());
            }
            List<SnapshotVolume> volumes;
            try {
                volumes = new VolumeResolver(platform).resolve(config.getSources());
            } catch (SnapshotSetupException e) {
                printProblems(err, "These sources can't be snapshotted:", e.problems());
                return ClientCli.FAILED;
            }
            SnapshotSession session;
            try {
                session = manager.takeSnapshots(volumes);
            } catch (SnapshotSetupException e) {
                printProblems(err, "Can't take snapshots:", e.problems());
                return ClientCli.FAILED;
            }
            int result = ClientCli.FAILED;
            try {
                result = work.run(session.snapshots());
            } catch (Exception e) {
                err.println("Error: " + e);
                e.printStackTrace(err);
            } finally {
                try {
                    session.close();
                } catch (SnapshotCleanupException e) {
                    printProblems(err, "Some snapshots couldn't be deleted:", e.problems());
                    result = ClientCli.FAILED;
                }
            }
            return result;
        } catch (IOException e) {
            err.println("Error: " + IoMessages.describe(e));
            return ClientCli.FAILED;
        }
    }

    private static void printProblems(PrintStream err, String heading, List<String> problems) {
        err.println(heading);
        for (String problem : problems) {
            err.println("  " + problem.replace("\n", "\n    "));
        }
    }
}
