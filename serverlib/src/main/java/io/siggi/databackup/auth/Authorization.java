package io.siggi.databackup.auth;

public interface Authorization {
    String getUserId();

    String getName();

    boolean checkPermission(String permission);
}
