package io.siggi.databackup.data.extra;

public abstract class ExtraData {

    public int getTypeId() {
        return ExtraDataTypes.dataTypesReverseLookup.get(getClass());
    }

    public abstract byte[] serialize();

    public static ExtraData deserialize(int typeId, byte[] data) {
        try {
            if (typeId < 1 || typeId > ExtraDataTypes.dataTypes.size()) {
                return new ExtraDataUnsupported(typeId, data);
            }
            ExtraDataTypes.TypeRegistration typeRegistration = ExtraDataTypes.dataTypes.get(typeId - 1);
            return typeRegistration.deserializer().apply(data);
        } catch (Exception e) {
        }
        return null;
    }
}
