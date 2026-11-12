package io.siggi.databackup.client;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.security.Security;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsSecureRandomTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Test
    void doesNothingOutsideWindows() {
        Assumptions.assumeFalse(isWindows());
        assertFalse(WindowsSecureRandom.install());
        assertNull(Security.getProvider(WindowsSecureRandom.PROVIDER_NAME));
    }

    @Test
    void makesWindowsPrngTheDefaultOnWindows() {
        Assumptions.assumeTrue(isWindows());
        try {
            assertTrue(WindowsSecureRandom.install());
            assertTrue(WindowsSecureRandom.install());
            SecureRandom random = new SecureRandom();
            assertEquals(WindowsSecureRandom.PROVIDER_NAME, random.getProvider().getName());
            assertEquals(WindowsSecureRandom.ALGORITHM, random.getAlgorithm());
            random.nextLong();
        } finally {
            Security.removeProvider(WindowsSecureRandom.PROVIDER_NAME);
        }
    }

    @Test
    void spiDelegates() throws Exception {
        SecureRandom a = SecureRandom.getInstance("SHA1PRNG");
        a.setSeed(42L);
        SecureRandom b = SecureRandom.getInstance("SHA1PRNG");
        b.setSeed(42L);
        SecureRandom wrapped = new SecureRandom(new WindowsSecureRandom.Spi(b), b.getProvider()) {
        };
        byte[] expected = new byte[32];
        byte[] actual = new byte[32];
        a.nextBytes(expected);
        wrapped.nextBytes(actual);
        assertArrayEquals(expected, actual);
        assertEquals(16, wrapped.generateSeed(16).length);
    }
}
