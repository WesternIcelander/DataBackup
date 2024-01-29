package io.siggi.databackup.datarepository;

import java.io.File;
import java.io.IOException;

public class DataRepository {
    private final File root;

    public DataRepository(File root) {
        this.root = root;
    }

    public StoredDataStream openStream() throws IOException {
        return new StoredDataStream(this, root);
    }

    private String getPath(String contentId) {
        if (contentId.length() <= 3) {
            return "short/" + contentId;
        } else if (contentId.length() <= 6) {
            return contentId.substring(0, 3) + "/" + contentId;
        } else {
            return contentId.substring(0, 3) + "/" + contentId.substring(3, 6) + "/" + contentId;
        }
    }

    File getContentFile(String contentId, String compression) {
        String suffix = (compression == null || compression.equals("none")) ? "" : ("." + compression);
        return new File(root, getPath(contentId) + suffix);
    }

    File getMetadataFile(String contentId) {
        return new File(root, getPath(contentId) + ".metadata");
    }

    public StoredData getStoredData(String contentId) {
        return new StoredData(this, contentId);
    }
}
