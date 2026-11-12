package io.siggi.databackup.auth;

public interface Authorization {
    String getUserId();

    String getName();

    boolean checkPermission(String permission);

    /**
     * Returns whether any permission of the form {@code <prefix>:<something>} is granted, for example
     * {@code upload:laptop} or {@code upload:*} for the prefix {@code upload}.
     */
    boolean checkAnyPermission(String prefix);
}
