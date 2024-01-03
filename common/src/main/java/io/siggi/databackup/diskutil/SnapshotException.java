package io.siggi.databackup.diskutil;

public class SnapshotException extends Exception {
    public SnapshotException() {
        super();
    }

    public SnapshotException(String message) {
        super(message);
    }

    public SnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
