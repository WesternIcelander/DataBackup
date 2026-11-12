package io.siggi.databackup.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.siggi.databackup.datarepository.CompressionPolicy;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server's settings, read from {@code server.json} in the data folder. A missing file, or a missing key, means
 * the default. Example with every key:
 *
 * <pre>
 * {
 *   "bindAddress": "0.0.0.0",
 *   "port": 8080,
 *   "retention": {"keepLast": 10, "keepDaily": 14, "keepWeekly": 8, "keepMonthly": 24},
 *   "machineRetention": {"laptop": {"keepLast": 30}},
 *   "gcGracePeriodHours": 48,
 *   "minCompressionSavings": 0.05
 * }
 * </pre>
 */
public final class ServerConfig {

    public static final String FILE_NAME = "server.json";
    public static final int DEFAULT_PORT = 8080;
    public static final long DEFAULT_GC_GRACE_PERIOD_HOURS = 48;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /**
     * The address to listen on; {@code null} or empty means all interfaces.
     */
    private String bindAddress = null;
    private int port = DEFAULT_PORT;
    /**
     * The default retention policy (applied in the retention step).
     */
    private Retention retention = new Retention();
    /**
     * Per-machine retention overrides; a key left out of an override falls back to {@link #retention}.
     */
    private Map<String, Retention> machineRetention = new LinkedHashMap<>();
    private long gcGracePeriodHours = DEFAULT_GC_GRACE_PERIOD_HOURS;
    private double minCompressionSavings = CompressionPolicy.DEFAULT_MIN_SAVINGS;

    /**
     * Retention settings. {@code null} fields mean "not set" (use the default policy's value).
     */
    public static final class Retention {
        private Integer keepLast;
        private Integer keepDaily;
        private Integer keepWeekly;
        private Integer keepMonthly;

        public Retention() {
        }

        public Retention(Integer keepLast, Integer keepDaily, Integer keepWeekly, Integer keepMonthly) {
            this.keepLast = keepLast;
            this.keepDaily = keepDaily;
            this.keepWeekly = keepWeekly;
            this.keepMonthly = keepMonthly;
        }

        static Retention defaults() {
            return new Retention(10, 14, 8, 24);
        }

        public Integer getKeepLast() {
            return keepLast;
        }

        public Integer getKeepDaily() {
            return keepDaily;
        }

        public Integer getKeepWeekly() {
            return keepWeekly;
        }

        public Integer getKeepMonthly() {
            return keepMonthly;
        }

        /**
         * Returns these settings with every unset value taken from {@code fallback}.
         */
        public Retention orElse(Retention fallback) {
            return new Retention(
                    keepLast != null ? keepLast : fallback.keepLast,
                    keepDaily != null ? keepDaily : fallback.keepDaily,
                    keepWeekly != null ? keepWeekly : fallback.keepWeekly,
                    keepMonthly != null ? keepMonthly : fallback.keepMonthly);
        }

        private void validate(String where) {
            check(where + ".keepLast", keepLast);
            check(where + ".keepDaily", keepDaily);
            check(where + ".keepWeekly", keepWeekly);
            check(where + ".keepMonthly", keepMonthly);
        }

        private static void check(String key, Integer value) {
            if (value != null && value < 0) {
                throw new IllegalArgumentException(key + " must not be negative, was " + value);
            }
        }
    }

    /**
     * Returns a config with every setting at its default, as if there were no {@code server.json}.
     */
    public static ServerConfig defaults() {
        ServerConfig config = new ServerConfig();
        config.normalize();
        return config;
    }

    /**
     * Reads {@code server.json} from the data folder, or returns the defaults if it doesn't exist.
     *
     * @throws IOException if the file can't be read, isn't valid JSON, or has an invalid value
     */
    public static ServerConfig load(File dataRoot) throws IOException {
        File file = new File(dataRoot, FILE_NAME);
        if (!file.exists()) return defaults();
        ServerConfig config;
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            config = GSON.fromJson(reader, ServerConfig.class);
        } catch (JsonParseException e) {
            throw new IOException("Invalid " + file + ": " + e.getMessage(), e);
        }
        if (config == null) return defaults(); // empty file
        try {
            config.normalize();
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid " + file + ": " + e.getMessage(), e);
        }
        return config;
    }

    private void normalize() {
        if (bindAddress != null && bindAddress.isBlank()) bindAddress = null;
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535, was " + port);
        }
        retention = retention == null ? Retention.defaults() : retention.orElse(Retention.defaults());
        retention.validate("retention");
        if (machineRetention == null) machineRetention = new LinkedHashMap<>();
        for (Map.Entry<String, Retention> entry : machineRetention.entrySet()) {
            if (entry.getValue() == null) entry.setValue(new Retention());
            entry.getValue().validate("machineRetention." + entry.getKey());
        }
        if (gcGracePeriodHours < 0) {
            throw new IllegalArgumentException("gcGracePeriodHours must not be negative, was " + gcGracePeriodHours);
        }
        if (!(minCompressionSavings >= 0.0 && minCompressionSavings <= 1.0)) {
            throw new IllegalArgumentException("minCompressionSavings must be between 0 and 1, was "
                    + minCompressionSavings);
        }
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public int getPort() {
        return port;
    }

    /**
     * The address to listen on: {@link #getBindAddress()} (or all interfaces) and {@link #getPort()}.
     */
    public InetSocketAddress getListenerAddress() {
        return bindAddress == null ? new InetSocketAddress(port) : new InetSocketAddress(bindAddress, port);
    }

    /**
     * The default retention policy, with every value set.
     */
    public Retention getRetention() {
        return retention;
    }

    /**
     * The retention policy for a machine: its override, with unset values from the default policy.
     */
    public Retention getRetention(String machine) {
        Retention override = machineRetention.get(machine);
        return override == null ? retention : override.orElse(retention);
    }

    public long getGcGracePeriodHours() {
        return gcGracePeriodHours;
    }

    public long getGcGracePeriodMillis() {
        return gcGracePeriodHours * 3600000L;
    }

    public double getMinCompressionSavings() {
        return minCompressionSavings;
    }

    /**
     * The repository compression policy: the default policy with {@link #getMinCompressionSavings()}.
     */
    public CompressionPolicy getCompressionPolicy() {
        return CompressionPolicy.defaults().withMinSavings(minCompressionSavings);
    }

    /**
     * Returns this config as pretty-printed JSON, in the format {@link #load(File)} reads.
     */
    public String toJson() {
        return GSON.toJson(this);
    }
}
