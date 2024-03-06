package io.siggi.databackup.util.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class NullCompressionAlgorithm implements CompressionAlgorithm {
    NullCompressionAlgorithm() {
    }

    @Override
    public String fileExtension() {
        return null;
    }

    @Override
    public boolean supportsCompression() {
        return true;
    }

    @Override
    public OutputStream compress(OutputStream out) throws IOException {
        return out;
    }

    @Override
    public boolean supportsDecompression() {
        return true;
    }

    @Override
    public InputStream decompress(InputStream in) throws IOException {
        return in;
    }
}
