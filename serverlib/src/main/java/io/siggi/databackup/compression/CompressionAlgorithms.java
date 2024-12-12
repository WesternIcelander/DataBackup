package io.siggi.databackup.compression;

import io.siggi.databackup.compression.algorithms.GzipCompressionAlgorithm;

import java.util.HashMap;
import java.util.Map;

public class CompressionAlgorithms {
    private CompressionAlgorithms() {
    }

    private static final Map<String, CompressionAlgorithm> algorithms = new HashMap<>();

    public static CompressionAlgorithm get(String type) {
        return algorithms.get(type);
    }
    public static void registerAlgorithm(String type, CompressionAlgorithm algorithm) {
        if (type == null || algorithm == null) throw new NullPointerException();
        if (algorithms.containsKey(type)) throw new IllegalArgumentException("Algorithm " + type + " already registered.");
        algorithms.put(type, algorithm);
    }

    static {
        registerAlgorithm("gz", new GzipCompressionAlgorithm());
    }
}
