package io.siggi.databackup.util.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface CompressionAlgorithm {
    String fileExtension();

    default boolean supportsCompression() {
        return false;
    }

    default OutputStream compress(OutputStream out) throws IOException {
        throw new UnsupportedOperationException();
    }

    default boolean supportsDecompression() {
        return false;
    }

    default InputStream decompress(InputStream in) throws IOException {
        throw new UnsupportedOperationException();
    }
}
