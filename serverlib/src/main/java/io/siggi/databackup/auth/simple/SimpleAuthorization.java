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
        return permissions.contains(permission);
    }
}
