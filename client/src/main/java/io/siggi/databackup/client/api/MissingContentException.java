package io.siggi.databackup.client.api;

import java.util.List;

/**
 * A commit was refused with {@code 409} because content the manifest references is still missing on the server.
 * Upload {@link #getMissing()} and commit again; the pending backup stays on the server.
 */
public class MissingContentException extends ServerApiException {

    private final List<String> missing;

    public MissingContentException(String serverMessage, List<String> missing) {
        super(409, serverMessage + " (" + missing.size() + " missing)");
        this.missing = List.copyOf(missing);
    }

    /**
     * The content IDs the server still lacks.
     */
    public List<String> getMissing() {
        return missing;
    }
}
