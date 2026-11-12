package io.siggi.databackup.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataBackupServerTest {

    private ServerTestHarness harness;

    @BeforeEach
    void setUp() throws IOException {
        harness = ServerTestHarness.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
    }

    @Test
    void bindsToAnEphemeralPort() {
        assertNotEquals(0, harness.server().getPort());
    }

    @Test
    void requestWithoutTokenIsRejected() throws Exception {
        HttpResponse<String> response = harness.get("/backups/", null);
        assertEquals(401, response.statusCode());
    }

    @Test
    void requestWithUnknownTokenIsRejected() throws Exception {
        HttpResponse<String> response = harness.get("/backups/", "not-a-real-token");
        assertEquals(401, response.statusCode());
    }

    @Test
    void shutdownStopsListeningAndCanBeRepeated() throws Exception {
        int port = harness.server().getPort();
        harness.server().shutdown();
        assertThrows(IOException.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            }
        });
        assertDoesNotThrow(() -> harness.server().shutdown());
    }
}
