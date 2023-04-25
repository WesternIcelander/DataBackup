package io.siggi.databackup.scanner;

public enum DiffAction {
    /**
     * Do not scan this directory.
     */
    DO_NOT_SCAN(0),
    /**
     * Scan only subdirectories in this directory, checking each subdirectory's diff action before scanning them.
     */
    SUBDIRS_ONLY(1),
    /**
     * Scan all files in this directory, checking each subdirectory's diff action before scanning them.
     */
    PARTIAL_SCAN(2),
    /**
     * Scan all files in this directory and assume all subdirectories have a FULL_SCAN action.
     */
    FULL_SCAN(3);

    public final int level;

    DiffAction(int level) {
        this.level = level;
    }
}
