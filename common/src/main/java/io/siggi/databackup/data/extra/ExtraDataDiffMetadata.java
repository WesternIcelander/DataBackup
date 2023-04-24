package io.siggi.databackup.data.extra;

import io.siggi.databackup.util.IO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public class ExtraDataDiffMetadata extends ExtraData {

    public ExtraDataDiffMetadata(String baseName, String baseHash) {
        this.baseName = baseName;
        this.baseHash = baseHash;
    }

    public final String baseName;
    public final String baseHash;

    public static ExtraDataDiffMetadata deserialize(byte[] data) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(data);
            String baseName = IO.readString(in);
            String baseHash = IO.readString(in);
            return new ExtraDataDiffMetadata(baseName.isEmpty() ? null : baseName, baseHash.isEmpty() ? null : baseHash);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        try {
            IO.writeString(out, baseName == null ? "" : baseName);
            IO.writeString(out, baseHash == null ? "" : baseHash);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataDiffMetadata o)) return false;
        return Objects.equals(baseName, o.baseName) && Objects.equals(baseHash, o.baseHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseName, baseHash);
    }
}
