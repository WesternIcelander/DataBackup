package io.siggi.databackup.compression.algorithms;

import io.siggi.databackup.compression.CompressionAlgorithm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class GzipCompressionAlgorithm implements CompressionAlgorithm {
    @Override
    public InputStream decompress(InputStream in) throws IOException {
        return new GZIPInputStream(in);
    }

    @Override
    public OutputStream compress(OutputStream out) throws IOException {
        return new GZIPOutputStream(out);
    }
}
