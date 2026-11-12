package io.siggi.databackup.auth.simple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleAuthorizerTest {

    @TempDir
    Path tokenDir;

    private static String sha256Hex(String token) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private void writeToken(String token, String... lines) throws Exception {
        Files.write(tokenDir.resolve(sha256Hex(token) + ".txt"), List.of(lines), StandardCharsets.UTF_8);
    }

    private SimpleAuthorizer authorizer() {
        return new SimpleAuthorizer(tokenDir.toFile());
    }

    @Test
    void looksUpTokenFileBySha256OfToken() throws Exception {
        writeToken("secret-token", "id=user1", "name=User One", "permission=upload:laptop");
        SimpleAuthorization auth = authorizer().getAuthorization("secret-token");
        assertNotNull(auth);
        assertEquals("user1", auth.getUserId());
        assertEquals("User One", auth.getName());
    }

    @Test
    void tokenFileNamedAfterRawTokenIsNotUsed() throws Exception {
        Files.write(tokenDir.resolve("plain.txt"), List.of("id=user1", "permission=admin"), StandardCharsets.UTF_8);
        assertNull(authorizer().getAuthorization("plain"));
    }

    @Test
    void unknownAndNullTokensAreRejected() throws Exception {
        writeToken("known", "id=user1");
        assertNull(authorizer().getAuthorization("unknown"));
        assertNull(authorizer().getAuthorization(null));
        assertNull(authorizer().getAuthorization(""));
    }

    @Test
    void keysDoNotFallThrough() throws Exception {
        writeToken("t", "id=user1", "name=Some Name");
        SimpleAuthorization auth = authorizer().getAuthorization("t");
        assertNotNull(auth);
        assertEquals("user1", auth.getUserId());
        assertEquals("Some Name", auth.getName());
        assertFalse(auth.checkPermission("user1"));
        assertFalse(auth.checkPermission("Some Name"));
    }

    @Test
    void idLineDoesNotSetNameWhenNameMissing() throws Exception {
        writeToken("t", "id=user1");
        SimpleAuthorization auth = authorizer().getAuthorization("t");
        assertNotNull(auth);
        assertEquals("user1", auth.getUserId());
        assertNull(auth.getName());
        assertFalse(auth.checkPermission("user1"));
    }

    @Test
    void ignoresBlankLinesCommentsAndMalformedLines() throws Exception {
        writeToken("t",
                "# a comment with id=evil",
                "",
                "   ",
                "  # indented comment, permission=admin",
                "id=user1",
                "not a key value line",
                "unknown=whatever",
                "permission=read:laptop",
                "permission=globalread");
        SimpleAuthorization auth = authorizer().getAuthorization("t");
        assertNotNull(auth);
        assertEquals("user1", auth.getUserId());
        assertNull(auth.getName());
        assertTrue(auth.checkPermission("read:laptop"));
        assertTrue(auth.checkPermission("globalread"));
        assertFalse(auth.checkPermission("admin"));
        assertFalse(auth.checkPermission("whatever"));
    }

    @Test
    void valuesMayContainEquals() throws Exception {
        writeToken("t", "id=user1", "name=a=b");
        SimpleAuthorization auth = authorizer().getAuthorization("t");
        assertNotNull(auth);
        assertEquals("a=b", auth.getName());
    }

    @Test
    void exactPermissionChecks() throws Exception {
        writeToken("t", "id=user1", "permission=upload:laptop", "permission=read:laptop");
        SimpleAuthorization auth = authorizer().getAuthorization("t");
        assertNotNull(auth);
        assertTrue(auth.checkPermission("upload:laptop"));
        assertTrue(auth.checkPermission("read:laptop"));
        assertFalse(auth.checkPermission("upload:desktop"));
        assertFalse(auth.checkPermission("read:desktop"));
        assertFalse(auth.checkPermission("read:*"));
        assertFalse(auth.checkPermission("globalread"));
        assertFalse(auth.checkPermission("admin"));
        assertFalse(auth.checkPermission(null));
    }

    @Test
    void wildcardPermissionChecks() throws Exception {
        writeToken("t", "id=user1", "permission=read:*");
        SimpleAuthorization auth = authorizer().getAuthorization("t");
        assertNotNull(auth);
        assertTrue(auth.checkPermission("read:anything"));
        assertTrue(auth.checkPermission("read:laptop"));
        assertTrue(auth.checkPermission("read:*"));
        assertFalse(auth.checkPermission("upload:laptop"));
        assertFalse(auth.checkPermission("upload:*"));
        assertFalse(auth.checkPermission("readx:laptop"));
        assertFalse(auth.checkPermission("read"));
        assertFalse(auth.checkPermission("globalread"));
        assertFalse(auth.checkPermission("admin"));
    }

    @Test
    void uploadWildcardDoesNotGrantRead() throws Exception {
        writeToken("t", "id=user1", "permission=upload:*");
        SimpleAuthorization auth = authorizer().getAuthorization("t");
        assertNotNull(auth);
        assertTrue(auth.checkPermission("upload:laptop"));
        assertFalse(auth.checkPermission("read:laptop"));
    }

    @Test
    void unreadableTokenFileIsRejected() throws Exception {
        // A directory where the token file should be cannot be read as a file.
        Files.createDirectory(tokenDir.resolve(sha256Hex("t") + ".txt"));
        assertNull(authorizer().getAuthorization("t"));
    }
}
