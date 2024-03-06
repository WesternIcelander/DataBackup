package io.siggi.databackup.util.compression;

import java.util.HashMap;
import java.util.Map;

public class DataCompression {
    private static final Map<String, CompressionAlgorithm> algorithms = new HashMap<>();

    public static void registerCompressionAlgorithm(String identifier, CompressionAlgorithm algorithm) {
        if (algorithms.putIfAbsent(identifier, algorithm) != null) {
            throw new IllegalStateException("Algorithm '" + identifier + "' was already registered.");
        }
    }

    static {
        registerCompressionAlgorithm("none", new NullCompressionAlgorithm());
        registerCompressionAlgorithm("gzip", new GzipCompressionAlgorithm());
    }

    public static CompressionAlgorithm getCompressionAlgorithm(String identifier) {
        return algorithms.get(identifier);
    }
}
