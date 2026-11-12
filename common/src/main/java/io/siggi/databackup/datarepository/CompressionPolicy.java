package io.siggi.databackup.datarepository;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether content stored in a {@link DataRepository} is worth compressing. Content is compressed with gzip
 * unless its filename shows it is already compressed, and the compressed copy is kept only when it saves at least
 * {@link #getMinSavings()} of the original size.
 */
public final class CompressionPolicy {
    /**
     * The compression algorithm used for stored content, as a {@code DataCompression} identifier.
     */
    public static final String ALGORITHM = "gzip";

    /**
     * The default minimum saving: the compressed copy must be at least 5% smaller than the original.
     */
    public static final double DEFAULT_MIN_SAVINGS = 0.05;

    /**
     * Filename extensions (lower case, without the dot) of formats that are already compressed, so trying gzip on
     * them is wasted work.
     */
    public static final Set<String> DEFAULT_SKIP_EXTENSIONS = Set.of(
            // images
            "jpg", "jpeg", "jpe", "jfif", "png", "gif", "webp", "heic", "heif", "avif", "jxl", "jp2",
            // audio
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wma",
            // video
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv", "flv", "3gp", "mpg", "mpeg", "ts", "m2ts",
            // archives and compressed streams
            "zip", "gz", "tgz", "gzip", "bz2", "tbz2", "xz", "txz", "lz", "lzma", "lz4", "zst", "zstd", "7z", "rar",
            "cab", "br", "z",
            // packages and containers that are zip or otherwise compressed inside
            "jar", "war", "ear", "apk", "aab", "ipa", "xpi", "crx", "whl", "nupkg", "deb", "rpm", "dmg", "pkg", "msi",
            "appx", "msix", "epub", "docx", "xlsx", "pptx", "odt", "ods", "odp", "pdf",
            // fonts
            "woff", "woff2");

    private static final CompressionPolicy DEFAULT = new CompressionPolicy(DEFAULT_MIN_SAVINGS,
            DEFAULT_SKIP_EXTENSIONS);
    private static final CompressionPolicy NEVER = new CompressionPolicy(1.0, Set.of());

    private final double minSavings;
    private final Set<String> skipExtensions;

    /**
     * @param minSavings     the fraction of the original size the compressed copy must save to be kept, from 0
     *                       (keep it whenever it isn't bigger) to 1 (never compress)
     * @param skipExtensions filename extensions, without the dot, to store without trying to compress; matched
     *                       case-insensitively
     */
    public CompressionPolicy(double minSavings, Collection<String> skipExtensions) {
        if (!(minSavings >= 0.0 && minSavings <= 1.0)) {
            throw new IllegalArgumentException("minSavings must be between 0 and 1, was " + minSavings);
        }
        this.minSavings = minSavings;
        this.skipExtensions = skipExtensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Gzip, kept when it saves at least 5%, skipping {@link #DEFAULT_SKIP_EXTENSIONS}.
     */
    public static CompressionPolicy defaults() {
        return DEFAULT;
    }

    /**
     * Stores everything uncompressed.
     */
    public static CompressionPolicy never() {
        return NEVER;
    }

    public double getMinSavings() {
        return minSavings;
    }

    public Set<String> getSkipExtensions() {
        return skipExtensions;
    }

    /**
     * Returns a copy of this policy with a different minimum saving.
     */
    public CompressionPolicy withMinSavings(double minSavings) {
        return new CompressionPolicy(minSavings, skipExtensions);
    }

    /**
     * Whether compressing is worth trying for content with this filename (null when unknown).
     */
    public boolean shouldTry(String filename) {
        if (minSavings >= 1.0) return false;
        if (filename == null) return true;
        int lastDot = filename.lastIndexOf('.');
        if (lastDot == -1 || lastDot == filename.length() - 1) return true;
        return !skipExtensions.contains(filename.substring(lastDot + 1).toLowerCase(Locale.ROOT));
    }

    /**
     * Whether a compressed copy of {@code compressedSize} bytes is worth keeping for {@code originalSize} bytes of
     * content.
     */
    public boolean isWorthKeeping(long originalSize, long compressedSize) {
        if (originalSize <= 0L || minSavings >= 1.0) return false;
        return (double) (originalSize - compressedSize) >= minSavings * originalSize;
    }
}
