package io.siggi.databackup.data.extra;

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
}
