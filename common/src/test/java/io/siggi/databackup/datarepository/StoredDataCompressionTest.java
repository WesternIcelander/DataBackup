package io.siggi.databackup.datarepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoredDataCompressionTest {

    @TempDir
    Path tmp;

    private DataRepository repository() {
        return new DataRepository(tmp.resolve("repository").toFile());
    }

    private File tempFile(byte[] data) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("incoming"));
        Path file = Files.createTempFile(dir, "upload", ".tmp");
        Files.write(file, data);
        return file.toFile();
    }

    private static byte[] compressible() {
        return "the same line over and over again\n".repeat(500).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] random(int size) {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        return data;
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        }
    }

    private void assertNoLeftovers() throws Exception {
        try (var files = Files.list(tmp.resolve("incoming"))) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    void compressibleContentIsStoredGzipped() throws Exception {
        DataRepository repository = repository();
        byte[] data = compressible();
        StoredData storedData = repository.getStoredData("aaaa1111");
        assertTrue(storedData.storeCompressing(tempFile(data), "notes.txt"));

        assertTrue(storedData.dataExists());
        assertEquals("gzip", storedData.getCompression());
        assertFalse(storedData.isDoNotCompress());
        assertTrue(storedData.getFile().getName().endsWith(".gzip"));
        assertTrue(storedData.getFile().length() < data.length);
        assertArrayEquals(data, gunzip(Files.readAllBytes(storedData.getFile().toPath())));
        assertNoLeftovers();

        // A fresh StoredData reads the same state back from the metadata.
        StoredData reread = repository.getStoredData("aaaa1111");
        assertEquals("gzip", reread.getCompression());
        assertTrue(reread.dataExists());
    }

    @Test
    void incompressibleContentIsStoredPlainAndMarked() throws Exception {
        DataRepository repository = repository();
        byte[] data = random(100_000);
        StoredData storedData = repository.getStoredData("bbbb2222");
        assertTrue(storedData.storeCompressing(tempFile(data), "data.bin"));

        assertEquals("none", storedData.getCompression());
        assertTrue(storedData.isDoNotCompress());
        assertArrayEquals(data, Files.readAllBytes(storedData.getFile().toPath()));
        assertTrue(repository.getStoredData("bbbb2222").isDoNotCompress());
        assertNoLeftovers();
    }

    @Test
    void emptyContentIsStoredPlain() throws Exception {
        DataRepository repository = repository();
        StoredData storedData = repository.getStoredData("cccc3333");
        assertTrue(storedData.storeCompressing(tempFile(new byte[0]), null));
        assertEquals("none", storedData.getCompression());
        assertTrue(storedData.isDoNotCompress());
        assertEquals(0L, storedData.getFile().length());
        assertNoLeftovers();
    }

    @Test
    void alreadyCompressedTypesAreNotTried() throws Exception {
        DataRepository repository = repository();
        byte[] data = compressible();
        for (String name : new String[]{"photo.JPG", "movie.mp4", "archive.zip", "old.tar.gz", "pic.png"}) {
            StoredData storedData = repository.getStoredData("dd" + Integer.toHexString(name.hashCode()));
            assertTrue(storedData.storeCompressing(tempFile(data), name));
            assertEquals("none", storedData.getCompression(), name);
            assertTrue(storedData.isDoNotCompress(), name);
            assertArrayEquals(data, Files.readAllBytes(storedData.getFile().toPath()));
        }
        assertNoLeftovers();
    }

    @Test
    void thresholdIsConfigurable() throws Exception {
        // Random bytes followed by an equal run of zeros compress to a bit over half the size.
        byte[] data = new byte[20_000];
        System.arraycopy(random(10_000), 0, data, 0, 10_000);

        DataRepository strict = repository();
        strict.setCompressionPolicy(CompressionPolicy.defaults().withMinSavings(0.6));
        StoredData plain = strict.getStoredData("eeee5555");
        assertTrue(plain.storeCompressing(tempFile(data), null));
        assertEquals("none", plain.getCompression());

        DataRepository lenient = new DataRepository(tmp.resolve("repository2").toFile());
        lenient.setCompressionPolicy(CompressionPolicy.defaults().withMinSavings(0.4));
        StoredData gzipped = lenient.getStoredData("eeee5555");
        assertTrue(gzipped.storeCompressing(tempFile(data), null));
        assertEquals("gzip", gzipped.getCompression());

        DataRepository never = new DataRepository(tmp.resolve("repository3").toFile());
        never.setCompressionPolicy(CompressionPolicy.never());
        StoredData never1 = never.getStoredData("eeee5555");
        assertTrue(never1.storeCompressing(tempFile(compressible()), "a.txt"));
        assertEquals("none", never1.getCompression());
        assertNoLeftovers();
    }

    @Test
    void policyRules() {
        CompressionPolicy policy = new CompressionPolicy(0.05, Set.of("JPG"));
        assertTrue(policy.shouldTry(null));
        assertTrue(policy.shouldTry("README"));
        assertTrue(policy.shouldTry("trailing."));
        assertTrue(policy.shouldTry("a.txt"));
        assertFalse(policy.shouldTry("a.jpg"));
        assertFalse(policy.shouldTry("a.Jpg"));
        assertTrue(policy.isWorthKeeping(100, 95));
        assertFalse(policy.isWorthKeeping(100, 96));
        assertFalse(policy.isWorthKeeping(0, 0));
        assertFalse(policy.isWorthKeeping(100, 120));
        assertThrows(IllegalArgumentException.class, () -> new CompressionPolicy(-0.1, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CompressionPolicy(Double.NaN, Set.of()));
    }
}
