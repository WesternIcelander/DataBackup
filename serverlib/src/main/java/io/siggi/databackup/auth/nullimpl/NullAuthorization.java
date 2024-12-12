package io.siggi.databackup.auth.nullimpl;

import io.siggi.databackup.auth.Authorization;

public class NullAuthorization implements Authorization {
    @Override
    public String getUserId() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public boolean checkPermission(String permission) {
        return true;
    }
}
