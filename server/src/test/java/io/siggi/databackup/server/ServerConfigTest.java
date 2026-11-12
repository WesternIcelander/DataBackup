package io.siggi.databackup.server;

import io.siggi.databackup.datarepository.CompressionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {

    @TempDir
    Path dataDir;

    private void writeConfig(String json) throws IOException {
        Files.writeString(dataDir.resolve(ServerConfig.FILE_NAME), json, StandardCharsets.UTF_8);
    }

    @Test
    void missingFileGivesDefaults() throws Exception {
        ServerConfig config = ServerConfig.load(dataDir.toFile());
        assertNull(config.getBindAddress());
        assertEquals(8080, config.getPort());
        assertEquals(48, config.getGcGracePeriodHours());
        assertEquals(48L * 3600 * 1000, config.getGcGracePeriodMillis());
        assertEquals(CompressionPolicy.DEFAULT_MIN_SAVINGS, config.getMinCompressionSavings());
        assertTrue(config.getListenerAddress().getAddress().isAnyLocalAddress());
        ServerConfig.Retention retention = config.getRetention();
        assertEquals(10, retention.getKeepLast());
        assertEquals(14, retention.getKeepDaily());
        assertEquals(8, retention.getKeepWeekly());
        assertEquals(24, retention.getKeepMonthly());
    }

    @Test
    void readsEverySetting() throws Exception {
        writeConfig("""
                {
                  "bindAddress": "127.0.0.1",
                  "port": 9123,
                  "retention": {"keepLast": 3, "keepMonthly": 6},
                  "machineRetention": {"laptop": {"keepDaily": 30}},
                  "gcGracePeriodHours": 12,
                  "minCompressionSavings": 0.2
                }
                """);
        ServerConfig config = ServerConfig.load(dataDir.toFile());
        assertEquals("127.0.0.1", config.getBindAddress());
        assertEquals(9123, config.getPort());
        assertEquals(new InetSocketAddress("127.0.0.1", 9123), config.getListenerAddress());
        assertEquals(12, config.getGcGracePeriodHours());
        assertEquals(0.2, config.getMinCompressionSavings());
        assertEquals(0.2, config.getCompressionPolicy().getMinSavings());

        ServerConfig.Retention retention = config.getRetention();
        assertEquals(3, retention.getKeepLast());
        assertEquals(14, retention.getKeepDaily(), "unset keys keep their default");
        assertEquals(6, retention.getKeepMonthly());
        ServerConfig.Retention laptop = config.getRetention("laptop");
        assertEquals(3, laptop.getKeepLast(), "overrides fall back to the configured default");
        assertEquals(30, laptop.getKeepDaily());
        assertEquals(retention.getKeepDaily(), config.getRetention("desktop").getKeepDaily());
    }

    @Test
    void partialAndEmptyFilesKeepDefaults() throws Exception {
        writeConfig("{\"port\": 1234}");
        ServerConfig config = ServerConfig.load(dataDir.toFile());
        assertEquals(1234, config.getPort());
        assertEquals(48, config.getGcGracePeriodHours());
        assertEquals(10, config.getRetention().getKeepLast());

        writeConfig("");
        assertEquals(8080, ServerConfig.load(dataDir.toFile()).getPort());
    }

    @Test
    void invalidFilesAreRejected() throws Exception {
        writeConfig("{not json");
        assertThrows(IOException.class, () -> ServerConfig.load(dataDir.toFile()));
        writeConfig("{\"port\": 70000}");
        assertThrows(IOException.class, () -> ServerConfig.load(dataDir.toFile()));
        writeConfig("{\"minCompressionSavings\": 1.5}");
        assertThrows(IOException.class, () -> ServerConfig.load(dataDir.toFile()));
        writeConfig("{\"gcGracePeriodHours\": -1}");
        assertThrows(IOException.class, () -> ServerConfig.load(dataDir.toFile()));
        writeConfig("{\"machineRetention\": {\"x\": {\"keepLast\": -2}}}");
        assertThrows(IOException.class, () -> ServerConfig.load(dataDir.toFile()));
    }

    @Test
    void toJsonRoundTrips() throws Exception {
        writeConfig("{\"port\": 4321, \"minCompressionSavings\": 0.1, \"machineRetention\": {\"m\": {\"keepLast\": 2}}}");
        ServerConfig config = ServerConfig.load(dataDir.toFile());
        writeConfig(config.toJson());
        ServerConfig again = ServerConfig.load(dataDir.toFile());
        assertEquals(4321, again.getPort());
        assertEquals(0.1, again.getMinCompressionSavings());
        assertEquals(2, again.getRetention("m").getKeepLast());
    }

    @Test
    void serverAppliesConfigFromDataFolder() throws Exception {
        writeConfig("{\"minCompressionSavings\": 0.3, \"bindAddress\": \"127.0.0.1\", \"port\": 0}");
        try (DataBackupServer server = new DataBackupServer(dataDir.toFile(), null)) {
            assertEquals(0.3, server.getConfig().getMinCompressionSavings());
            assertEquals(0.3, server.getDataRepository().getCompressionPolicy().getMinSavings());
            assertTrue(server.getPort() > 0, "port 0 from server.json picks a free port");
        }
        try (DataBackupServer server = new DataBackupServer(dataDir.toFile(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            assertEquals(0.3, server.getDataRepository().getCompressionPolicy().getMinSavings(),
                    "an explicit address still applies the rest of the config");
        }
    }
}
