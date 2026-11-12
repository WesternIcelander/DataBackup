package io.siggi.databackup.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientPathsTest {

    @Test
    void macOs() {
        ClientPaths p = ClientPaths.forOs("Mac OS X");
        assertEquals(Paths.get("/Library/Application Support/DataBackup-client/client.json"), p.configFile());
        assertEquals(Paths.get("/Library/Application Support/DataBackup-client/state"), p.stateDir());
    }

    @Test
    void linux() {
        ClientPaths p = ClientPaths.forOs("Linux");
        assertEquals(Paths.get("/etc/databackup/client.json"), p.configFile());
        assertEquals(Paths.get("/var/lib/databackup-client"), p.stateDir());
    }

    @Test
    void windows() {
        ClientPaths p = ClientPaths.forOs("Windows 10");
        assertEquals("C:\\ProgramData\\DataBackup-client\\client.json", p.configFile().toString());
        assertEquals("C:\\ProgramData\\DataBackup-client\\state", p.stateDir().toString());
    }
}
