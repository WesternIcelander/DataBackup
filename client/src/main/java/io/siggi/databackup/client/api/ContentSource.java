package io.siggi.databackup.client.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One piece of content to upload with {@link ServerApi#uploadContent}: where its bytes come from, the content ID the
 * client expects the server to compute for it (the SHA-256 of the bytes, 64 lowercase hex digits), and an optional
 * filename the server records for downloads.
 * <p>
 * The source must be readable more than once, since a failed upload is retried, and must give exactly
 * {@code size} bytes each time.
 */
public final class ContentSource {

    /**
     * Opens the content's bytes. Called once per upload attempt.
     */
    @FunctionalInterface
    public interface Opener {
        InputStream open() throws IOException;
    }

    private final String contentId;
    private final String filename;
    private final long size;
    private final Path path;
    private final Opener opener;

    private ContentSource(String contentId, String filename, long size, Path path, Opener opener) {
        this.contentId = Objects.requireNonNull(contentId, "contentId");
        if (size < 0) throw new IllegalArgumentException("size must not be negative");
        this.filename = filename;
        this.size = size;
        this.path = path;
        this.opener = opener;
    }

    /**
     * The content of a whole file, named after the file.
     */
    public static ContentSource ofFile(Path file, String contentId) throws IOException {
        Path name = file.getFileName();
        return new ContentSource(contentId, name == null ? null : name.toString(), Files.size(file), file,
                () -> Files.newInputStream(file));
    }

    /**
     * Content from a stream of known size, such as part of a file.
     */
    public static ContentSource ofStream(String contentId, String filename, long size, Opener opener) {
        return new ContentSource(contentId, filename, size, null, Objects.requireNonNull(opener, "opener"));
    }

    /**
     * Content held in memory.
     */
    public static ContentSource ofBytes(String contentId, String filename, byte[] data) {
        byte[] copy = data.clone();
        return new ContentSource(contentId, filename, copy.length, null, () -> new ByteArrayInputStream(copy));
    }

    public String contentId() {
        return contentId;
    }

    /**
     * The filename sent with the content, or null for none.
     */
    public String filename() {
        return filename;
    }

    public long size() {
        return size;
    }

    /**
     * The whole file this content comes from, or null when it isn't a whole file.
     */
    Path path() {
        return path;
    }

    InputStream open() throws IOException {
        return opener.open();
    }
}
