package io.siggi.databackup.client.snapshot;

import java.util.List;

/**
 * Some snapshots couldn't be deleted. They stay in {@code snapshots.json}, so {@code databackup snapshots cleanup}
 * (or the next backup) tries again. The message lists every failure, one per line.
 */
public class SnapshotCleanupException extends Exception {

    private final List<String> problems;

    public SnapshotCleanupException(List<String> problems) {
        super(String.join("\n", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
