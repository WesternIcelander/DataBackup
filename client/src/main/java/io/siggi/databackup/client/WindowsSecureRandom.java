package io.siggi.databackup.client;

import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.SecureRandomSpi;
import java.security.Security;
import java.util.Locale;
import java.util.Map;

/**
 * Makes {@code new SecureRandom()} use Windows' own random number generator ({@code Windows-PRNG} from the
 * {@code SunMSCAPI} provider) instead of the JDK's {@code DRBG}.
 * <p>
 * On Windows the JDK seeds {@code DRBG} through the CryptoAPI and, where that fails (as it does for an SSH session on
 * Windows 10), falls back to {@code ThreadedSeedGenerator}, which takes about 8 seconds. Every client run pays that
 * cost: {@code HttpClient} needs the default {@code SSLContext} even for {@code http://} URLs, its selector's wakeup
 * pipe needs a random secret, and {@code UUID.randomUUID()} needs one too. {@code Windows-PRNG} is backed by
 * {@code CryptGenRandom} and needs no seeding.
 * <p>
 * {@link #install()} adds a provider at the top of the list that offers only this one {@code SecureRandom}, so
 * every other algorithm still comes from the same provider as before. It must run before anything uses
 * {@code SecureRandom}.
 */
public final class WindowsSecureRandom {

    static final String PROVIDER_NAME = "DataBackupWindowsRandom";
    static final String ALGORITHM = "Windows-PRNG";
    static final String SOURCE_PROVIDER = "SunMSCAPI";

    private WindowsSecureRandom() {
    }

    /**
     * Installs the provider if this is Windows and {@code Windows-PRNG} is available. Returns whether the provider is
     * installed. Safe to call more than once.
     */
    public static synchronized boolean install() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return false;
        if (Security.getProvider(PROVIDER_NAME) != null) return true;
        try {
            SecureRandom.getInstance(ALGORITHM, SOURCE_PROVIDER).nextInt();
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
        try {
            return Security.insertProviderAt(new RandomProvider(), 1) != -1;
        } catch (SecurityException e) {
            return false;
        }
    }

    static final class RandomProvider extends Provider {
        RandomProvider() {
            super(PROVIDER_NAME, "1.0", "Windows-PRNG as the default SecureRandom");
            putService(new Service(this, "SecureRandom", ALGORITHM, Spi.class.getName(), null,
                    Map.of("ThreadSafe", "true")) {
                @Override
                public Object newInstance(Object constructorParameter) throws NoSuchAlgorithmException {
                    if (constructorParameter != null) {
                        throw new NoSuchAlgorithmException(ALGORITHM + " takes no SecureRandomParameters");
                    }
                    try {
                        // Named explicitly: this provider offers the same algorithm name and comes first.
                        return new Spi(SecureRandom.getInstance(ALGORITHM, SOURCE_PROVIDER));
                    } catch (NoSuchProviderException e) {
                        throw new NoSuchAlgorithmException(e);
                    }
                }
            });
        }
    }

    static final class Spi extends SecureRandomSpi {
        private final SecureRandom delegate;

        Spi(SecureRandom delegate) {
            this.delegate = delegate;
        }

        @Override
        protected void engineSetSeed(byte[] seed) {
            delegate.setSeed(seed);
        }

        @Override
        protected void engineNextBytes(byte[] bytes) {
            delegate.nextBytes(bytes);
        }

        @Override
        protected byte[] engineGenerateSeed(int numBytes) {
            return delegate.generateSeed(numBytes);
        }
    }
}
