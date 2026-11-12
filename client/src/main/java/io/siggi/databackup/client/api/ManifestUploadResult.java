package io.siggi.databackup.client.api;

import java.util.List;

/**
 * The answer to a manifest upload.
 *
 * @param pendingId the pending backup's ID, to commit with {@link ServerApi#commit}
 * @param missing   the content IDs the manifest references that the server doesn't have, each once
 */
public record ManifestUploadResult(String pendingId, List<String> missing) {
    public ManifestUploadResult {
        missing = List.copyOf(missing);
    }
}
