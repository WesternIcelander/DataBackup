package io.siggi.databackup.auth.simple;

import io.siggi.databackup.auth.Authorization;

import java.util.Collection;

public class SimpleAuthorization implements Authorization {

    private final String userId;
    private final String name;
    private final Collection<String> permissions;

    SimpleAuthorization(String userId, String name, Collection<String> permissions) {
        this.userId = userId;
        this.name = name;
        this.permissions = permissions;
    }

    @Override
    public String getUserId() {
        return userId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean checkPermission(String permission) {
        if (permission == null) return false;
        if (permissions.contains(permission)) return true;
        // "prefix:*" grants every permission of the form "prefix:<something>".
        int colonPos = permission.indexOf(':');
        if (colonPos == -1) return false;
        return permissions.contains(permission.substring(0, colonPos + 1) + "*");
    }
}
