package io.siggi.databackup.client.snapshot;

import java.util.List;

/**
 * Sources can't be snapshotted, or snapshots couldn't be created or recorded. The message lists every problem, one
 * per line.
 */
public class SnapshotSetupException extends Exception {

    private final List<String> problems;

    public SnapshotSetupException(List<String> problems) {
        super(String.join("\n", problems));
        this.problems = List.copyOf(problems);
    }

    public SnapshotSetupException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of(message);
    }

    public List<String> problems() {
        return problems;
    }
}
