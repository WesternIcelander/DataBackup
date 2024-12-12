package io.siggi.databackup.auth;

public interface Authorizer {
    Authorization getAuthorization(String authToken);
}
