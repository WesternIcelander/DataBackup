package io.siggi.databackup.auth.nullimpl;

import io.siggi.databackup.auth.Authorizer;

public class NullAuthorizer implements Authorizer {
    private final NullAuthorization authorization = new NullAuthorization();

    @Override
    public NullAuthorization getAuthorization(String authToken) {
        return authorization;
    }
}
