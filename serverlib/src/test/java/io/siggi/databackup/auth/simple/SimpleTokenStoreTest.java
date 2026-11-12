package io.siggi.databackup.auth.simple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleTokenStoreTest {

    @TempDir
    Path dataDir;

    private Path tokenDir() {
        return dataDir.resolve("tokens");
    }

    private SimpleTokenStore store() {
        return new SimpleTokenStore(tokenDir().toFile());
    }

    @Test
    void createWritesFileNamedAfterHashAndAuthorizerAcceptsToken() throws Exception {
        SimpleTokenStore.CreatedToken created = store().create("laptop", List.of("upload:laptop", "read:laptop"));
        String token = created.token();
        assertTrue(token.length() >= 40, "token should carry at least 256 bits: " + token);
        Path file = tokenDir().resolve(SimpleAuthorizer.hashToken(token) + ".txt");
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains(token), "the token itself isn't stored");

        SimpleAuthorization auth = new SimpleAuthorizer(tokenDir().toFile()).getAuthorization(token);
        assertNotNull(auth);
        assertEquals(created.info().id(), auth.getUserId());
        assertEquals("laptop", auth.getName());
        assertTrue(auth.checkPermission("upload:laptop"));
        assertTrue(auth.checkPermission("read:laptop"));
        assertFalse(auth.checkPermission("read:desktop"));
        assertNotNull(created.info().created());
    }

    @Test
    void tokensAndIdsAreUnique() throws Exception {
        SimpleTokenStore store = store();
        SimpleTokenStore.CreatedToken a = store.create("a", List.of("admin"));
        SimpleTokenStore.CreatedToken b = store.create("b", List.of("admin"));
        assertNotEquals(a.token(), b.token());
        assertNotEquals(a.info().id(), b.info().id());
    }

    @Test
    void listShowsCreatedTokensAndHandWrittenFiles() throws Exception {
        SimpleTokenStore store = store();
        assertTrue(store.list().isEmpty(), "missing folder lists as empty");
        SimpleTokenStore.CreatedToken created = store.create("server", List.of("read:*", "globalread"));
        String handHash = SimpleAuthorizer.hashToken("hand-written");
        Files.write(tokenDir().resolve(handHash + ".txt"), List.of("name=old", "permission=read:x"));
        Files.writeString(tokenDir().resolve("notes.txt"), "not a token file");

        List<SimpleTokenStore.TokenInfo> tokens = store.list();
        assertEquals(2, tokens.size());
        SimpleTokenStore.TokenInfo hand = tokens.get(0); // no creation time sorts first
        assertNull(hand.id());
        assertEquals("old", hand.name());
        assertEquals(handHash, hand.hash());
        SimpleTokenStore.TokenInfo info = tokens.get(1);
        assertEquals(created.info(), info);
        assertEquals(List.of("read:*", "globalread"), info.permissions());
    }

    @Test
    void revokeByIdDeletesTokenFile() throws Exception {
        SimpleTokenStore store = store();
        SimpleTokenStore.CreatedToken keep = store.create("keep", List.of("read:a"));
        SimpleTokenStore.CreatedToken drop = store.create("drop", List.of("read:b"));
        SimpleAuthorizer authorizer = new SimpleAuthorizer(tokenDir().toFile());
        assertNotNull(authorizer.getAuthorization(drop.token()));

        SimpleTokenStore.TokenInfo revoked = store.revoke(drop.info().id());
        assertNotNull(revoked);
        assertEquals("drop", revoked.name());
        assertNull(authorizer.getAuthorization(drop.token()));
        assertNotNull(authorizer.getAuthorization(keep.token()));
        assertEquals(List.of(keep.info()), store.list());

        assertNull(store.revoke(drop.info().id()), "revoking again finds nothing");
        assertNull(store.revoke("unknown"));
    }

    @Test
    void revokeByHashWorksForFilesWithoutId() throws Exception {
        SimpleTokenStore store = store();
        Files.createDirectories(tokenDir());
        String hash = SimpleAuthorizer.hashToken("legacy");
        Files.write(tokenDir().resolve(hash + ".txt"), List.of("permission=admin"));
        assertNotNull(store.revoke(hash));
        assertTrue(store.list().isEmpty());
    }

    @Test
    void revokeRefusesAmbiguousId() throws Exception {
        Files.createDirectories(tokenDir());
        Files.write(tokenDir().resolve(SimpleAuthorizer.hashToken("one") + ".txt"), List.of("id=same"));
        Files.write(tokenDir().resolve(SimpleAuthorizer.hashToken("two") + ".txt"), List.of("id=same"));
        assertThrows(IllegalStateException.class, () -> store().revoke("same"));
        assertEquals(2, store().list().size());
    }

    @Test
    void createRejectsBadInput() {
        SimpleTokenStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.create(" ", List.of("admin")));
        assertThrows(IllegalArgumentException.class, () -> store.create("x", List.of()));
        assertThrows(IllegalArgumentException.class, () -> store.create("x\nid=evil", List.of("admin")));
        assertThrows(IllegalArgumentException.class, () -> store.create("x", List.of("admin\npermission=read:*")));
    }
}
