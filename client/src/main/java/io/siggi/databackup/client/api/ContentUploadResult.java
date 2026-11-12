package io.siggi.databackup.client.api;

import java.util.List;

/**
 * The combined answer to the batches of a content upload.
 *
 * @param stored         content IDs the server stored as new
 * @param alreadyPresent content IDs the server already had
 * @param notReceived    content IDs the client expected but the server didn't report, in upload order: the bytes
 *                       sent didn't hash to the expected ID (the source changed after it was hashed). The server
 *                       may have stored those bytes under another ID, which is harmless.
 */
public record ContentUploadResult(List<String> stored, List<String> alreadyPresent, List<String> notReceived) {
    public ContentUploadResult {
        stored = List.copyOf(stored);
        alreadyPresent = List.copyOf(alreadyPresent);
        notReceived = List.copyOf(notReceived);
    }

    /**
     * Whether the server reported every expected content ID.
     */
    public boolean allReceived() {
        return notReceived.isEmpty();
    }
}
