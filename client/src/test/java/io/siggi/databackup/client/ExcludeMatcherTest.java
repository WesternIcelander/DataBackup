package io.siggi.databackup.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcludeMatcherTest {

    private static ExcludeMatcher m(String... globs) {
        return ExcludeMatcher.of(List.of(globs));
    }

    @Test
    void literalMatchesWholePathOnly() {
        ExcludeMatcher m = m("Library/Caches");
        assertTrue(m.matches("Library/Caches"));
        assertTrue(m.matches("/Library/Caches/"));
        assertFalse(m.matches("Library/Caches/x"));
        assertFalse(m.matches("Users/a/Library/Caches"));
        assertFalse(m.matches("Library/CachesX"));
    }

    @Test
    void starStaysInOneComponent() {
        ExcludeMatcher m = m("*.tmp");
        assertTrue(m.matches("a.tmp"));
        assertFalse(m.matches("dir/a.tmp"));
        assertTrue(m("dir/*").matches("dir/x"));
        assertFalse(m("dir/*").matches("dir/x/y"));
    }

    @Test
    void doubleStarCrossesDirectories() {
        ExcludeMatcher m = m("**/node_modules");
        assertTrue(m.matches("node_modules"));
        assertTrue(m.matches("a/node_modules"));
        assertTrue(m.matches("a/b/node_modules"));
        assertFalse(m.matches("a/node_modules_x"));
        assertTrue(m("tmp/**").matches("tmp/a/b"));
        assertTrue(m("a/**/z").matches("a/z"));
        assertTrue(m("a/**/z").matches("a/b/c/z"));
        assertTrue(m("**").matches("anything/at/all"));
    }

    @Test
    void questionClassesAndBraces() {
        assertTrue(m("file?.txt").matches("file1.txt"));
        assertFalse(m("file?.txt").matches("file/.txt"));
        assertTrue(m("[abc].log").matches("b.log"));
        assertFalse(m("[abc].log").matches("d.log"));
        assertTrue(m("[!abc].log").matches("d.log"));
        assertFalse(m("[!abc].log").matches("a.log"));
        assertTrue(m("v[0-9]").matches("v7"));
        assertTrue(m("[]x]").matches("]"));
        assertTrue(m("*.{jpg,png}").matches("a.png"));
        assertFalse(m("*.{jpg,png}").matches("a.gif"));
        assertTrue(m("{a,b{c,d}}").matches("bd"));
        assertTrue(m("a,b").matches("a,b"));
    }

    @Test
    void escapesAndRegexCharactersAreLiteral() {
        assertTrue(m("a\\*b").matches("a*b"));
        assertFalse(m("a\\*b").matches("axb"));
        assertTrue(m("a.b+(c)$^|").matches("a.b+(c)$^|"));
        assertFalse(m("a.b").matches("axb"));
        assertTrue(m("Größe ü").matches("Größe ü"));
        assertTrue(m("[\\a]").matches("a"));
    }

    @Test
    void combinedMatchers() {
        ExcludeMatcher both = m("a").and(m("b"));
        assertTrue(both.matches("a"));
        assertTrue(both.matches("b"));
        assertFalse(both.matches("c"));
        assertFalse(ExcludeMatcher.none().matches("a"));
        assertFalse(m().matches(""));
    }

    @Test
    void invalidPatternsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> m(""));
        assertThrows(IllegalArgumentException.class, () -> m("/"));
        assertThrows(IllegalArgumentException.class, () -> m("[abc"));
        assertThrows(IllegalArgumentException.class, () -> m("{a,b"));
        assertThrows(IllegalArgumentException.class, () -> m("abc\\"));
        assertThrows(IllegalArgumentException.class, () -> m("[a/b]"));
    }
}
