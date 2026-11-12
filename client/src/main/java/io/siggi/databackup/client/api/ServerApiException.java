package io.siggi.databackup.client.api;

import java.io.IOException;

/**
 * The server answered a request with an error status. {@link #getStatus()} is the HTTP status and
 * {@link #getServerMessage()} the server's one-line message (see {@code docs/server-api.md}).
 * <p>
 * Connection failures aren't wrapped in this: they surface as plain {@link IOException}s once retries run out.
 */
public class ServerApiException extends IOException {

    private final int status;
    private final String serverMessage;

    public ServerApiException(int status, String serverMessage) {
        super("HTTP " + status + ": " + serverMessage);
        this.status = status;
        this.serverMessage = serverMessage;
    }

    public int getStatus() {
        return status;
    }

    public String getServerMessage() {
        return serverMessage;
    }

    /**
     * Whether retrying the same request might succeed: true for {@code 5xx} statuses.
     */
    public boolean isTransient() {
        return status >= 500 && status <= 599;
    }
}
