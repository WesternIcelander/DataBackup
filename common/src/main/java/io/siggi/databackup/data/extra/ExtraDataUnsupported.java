package io.siggi.databackup.data.extra;

import java.util.Arrays;

public class ExtraDataUnsupported extends ExtraData {

    private final int id;
    private final byte[] data;

    public ExtraDataUnsupported(int id, byte[] data) {
        this.id = id;
        this.data = data;
    }

    public int getTypeId() {
        return id;
    }

    @Override
    public byte[] serialize() {
        return data;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) return true;
        if (!(other instanceof ExtraDataUnsupported o)) return false;
        return id == o.id && Arrays.equals(data, o.data);
    }

    @Override
    public int hashCode() {
        return 31 * id + Arrays.hashCode(data);
    }
}
