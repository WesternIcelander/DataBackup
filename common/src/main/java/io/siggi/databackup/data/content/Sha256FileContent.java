package io.siggi.databackup.data.content;

import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.util.compression.CompressionAlgorithm;
import io.siggi.databackup.util.compression.DataCompression;
import io.siggi.databackup.util.stream.IO;
import io.siggi.databackup.util.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public final class Sha256FileContent extends FileContent {
    private final byte[] hash = new byte[32];

    @Override
    public int getTypeId() {
        return FileContent.TYPE_SHA256;
    }

    public byte[] getHash() {
        return hash;
    }

    @Override
    public void read(InputStream in) throws IOException {
        super.read(in);
        IO.readFully(in, hash);
    }

    @Override
    public void write(OutputStream out) throws IOException {
        super.write(out);
        out.write(hash);
    }

    @Override
    public boolean isDataAvailable(DataRepository repository) {
        return repository.getStoredData(Util.bytesToHex(getHash())).dataExists();
    }

    public InputStream getInputStream(DataRepository repository) throws IOException {
        FileInputStream in = null;
        boolean success = false;
        try {
            StoredData storedData = repository.getStoredData(Util.bytesToHex(getHash()));
            File contentFile = storedData.getFile();
            in = new FileInputStream(contentFile);
            CompressionAlgorithm compressionAlgorithm = DataCompression.getCompressionAlgorithm(storedData.getCompression());
            InputStream decompressedStream = compressionAlgorithm.decompress(in);
            success = true;
            return decompressedStream;
        } finally {
            if (!success && in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (!super.equals(other)) return false;
        if (other == this) return true;
        if (!(other instanceof Sha256FileContent o)) return false;
        return Arrays.equals(hash, o.hash);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + Arrays.hashCode(hash);
        return result;
    }

    @Override
    public String toString() {
        return "sha256:" + Util.bytesToHex(hash);
    }
}
