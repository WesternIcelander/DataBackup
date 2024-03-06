package io.siggi.databackup.util.compression;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipCompressionAlgorithm implements CompressionAlgorithm {
    GzipCompressionAlgorithm() {
    }

    @Override
    public String fileExtension() {
        return "gzip";
    }

    @Override
    public boolean supportsCompression() {
        return true;
    }

    @Override
    public OutputStream compress(OutputStream out) throws IOException {
        return new GZIPOutputStream(out);
    }

    @Override
    public boolean supportsDecompression() {
        return true;
    }

    @Override
    public InputStream decompress(InputStream in) throws IOException {
        return new GZIPInputStream(in);
    }
}
