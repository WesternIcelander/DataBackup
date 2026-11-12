package io.siggi.databackup.client.api;

/**
 * One entry of a directory listing inside a backup. Fields the server didn't send are null.
 *
 * @param name          the entry's name
 * @param type          what it is
 * @param size          the size in bytes (files)
 * @param modified      modification time in milliseconds since the epoch, when known
 * @param modifiedNanos modification time in nanoseconds since the epoch, when a nanosecond date is stored
 * @param target        the symlink target (symlinks)
 * @param permissions   the POSIX permissions, when recorded
 */
public record DirectoryListingEntry(String name, Type type, Long size, Long modified, Long modifiedNanos,
                                    String target, Permissions permissions) {

    public enum Type {
        FILE, DIRECTORY, SYMLINK,
        /**
         * A type this client doesn't know.
         */
        OTHER;

        static Type parse(String value) {
            if (value == null) return OTHER;
            return switch (value) {
                case "file" -> FILE;
                case "directory" -> DIRECTORY;
                case "symlink" -> SYMLINK;
                default -> OTHER;
            };
        }
    }

    /**
     * @param mode   the permission bits ({@code 0644} is {@code 420})
     * @param string the bits as {@code rw-r--r--}
     * @param owner  the numeric owner ID
     * @param group  the numeric group ID
     */
    public record Permissions(int mode, String string, long owner, long group) {
    }
}
