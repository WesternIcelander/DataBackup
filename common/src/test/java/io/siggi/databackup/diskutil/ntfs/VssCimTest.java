package io.siggi.databackup.diskutil.ntfs;

import io.siggi.databackup.diskutil.SnapshotException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VssCimTest {

    private static final UUID SHADOW = UUID.fromString("0a1b2c3d-4e5f-6071-8293-a4b5c6d7e8f9");
    private static final UUID VOLUME = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void parseCreateSuccess() throws SnapshotException {
        String output = "CREATED\t0\t{0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9}\r\n";
        assertEquals(SHADOW, VssCim.parseCreate(output));
    }

    @Test
    void parseCreateReturnValueError() {
        SnapshotException e = assertThrows(SnapshotException.class,
                () -> VssCim.parseCreate("CREATED\t1\t\r\n"));
        assertTrue(e.getMessage().contains("access denied"), e.getMessage());
        assertTrue(e.getMessage().contains("returned 1"), e.getMessage());
    }

    @Test
    void parseCreateScriptError() {
        SnapshotException e = assertThrows(SnapshotException.class,
                () -> VssCim.parseCreate("ERROR\tInvalid class \"Win32_ShadowCopy\"\r\n"));
        assertTrue(e.getMessage().endsWith("Invalid class \"Win32_ShadowCopy\""), e.getMessage());
    }

    @Test
    void parseCreateNoOutput() {
        assertThrows(SnapshotException.class, () -> VssCim.parseCreate(""));
    }

    @Test
    void parseList() throws SnapshotException {
        String output = "SHADOW\t{0A1B2C3D-4E5F-6071-8293-A4B5C6D7E8F9}\t\\\\?\\Volume{11111111-2222-3333-4444-555555555555}\\"
                + "\t\\\\?\\GLOBALROOT\\Device\\HarddiskVolumeShadowCopy3\r\n"
                + "SHADOW\t{22222222-2222-3333-4444-555555555555}\t\\\\?\\Volume{11111111-2222-3333-4444-555555555555}\\\t\r\n"
                + "END\r\n";
        List<VssCim.ShadowCopy> list = VssCim.parseList(output);
        assertEquals(2, list.size());
        assertEquals(SHADOW, list.get(0).id());
        assertEquals(VOLUME, list.get(0).volumeId());
        assertEquals("\\\\?\\GLOBALROOT\\Device\\HarddiskVolumeShadowCopy3", list.get(0).deviceObject());
        assertNull(list.get(1).deviceObject());
    }

    @Test
    void parseEmptyList() throws SnapshotException {
        assertEquals(List.of(), VssCim.parseList("END\n"));
    }

    @Test
    void parseListFailureIsAnError() {
        // A listing that didn't finish must not look like "no shadow copies".
        assertThrows(SnapshotException.class, () -> VssCim.parseList(""));
        SnapshotException e = assertThrows(SnapshotException.class, () -> VssCim.parseList("ERROR\tAccess denied\n"));
        assertTrue(e.getMessage().endsWith("Access denied"), e.getMessage());
    }

    @Test
    void scriptsUseCimAndValidatedValues() {
        assertTrue(VssCim.createScript("C:\\").contains("Volume = 'C:\\'"));
        assertTrue(VssCim.createScript("C:\\").contains("Invoke-CimMethod -ClassName Win32_ShadowCopy -MethodName Create"));
        assertTrue(VssCim.deleteScript(SHADOW).contains("ID='{" + SHADOW + "}'"));
        assertTrue(VssCim.listScript().contains("-replace '\\s+', ' '"));
    }

    @Test
    void encodeIsUtf16LeBase64() {
        String script = "Write-Output 'é'";
        String decoded = new String(Base64.getDecoder().decode(VssCim.encode(script)), StandardCharsets.UTF_16LE);
        assertEquals(script, decoded);
    }
}
