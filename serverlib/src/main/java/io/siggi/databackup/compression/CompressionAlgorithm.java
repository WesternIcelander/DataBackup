package io.siggi.databackup.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface CompressionAlgorithm {
    InputStream decompress(InputStream in) throws IOException;

    OutputStream compress(OutputStream out) throws IOException;
}
