package io.siggi.databackup.data.extra;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

class ExtraDataTypes {
    static final List<TypeRegistration> dataTypes = new ArrayList<>();
    static final Map<Class<? extends ExtraData>, Integer> dataTypesReverseLookup = new HashMap<>();

    private static void registerType(Class<? extends ExtraData> extraDataType, Function<byte[], ExtraData> deserializer) {
        dataTypes.add(new TypeRegistration(extraDataType, deserializer));
        dataTypesReverseLookup.put(extraDataType, dataTypes.size());
    }

    static {
        registerType(ExtraDataFilePath.class, ExtraDataFilePath::deserialize);
        registerType(ExtraDataPosixPermissions.class, ExtraDataPosixPermissions::deserialize);
        registerType(ExtraDataNanosecondModifiedDate.class, ExtraDataNanosecondModifiedDate::deserialize);
        registerType(ExtraDataDiffMetadata.class, ExtraDataDiffMetadata::deserialize);
        registerType(ExtraDataMacOSFSEvents.class, ExtraDataMacOSFSEvents::deserialize);
        registerType(ExtraDataSnapshotInfoApfs.class, ExtraDataSnapshotInfoApfs::deserialize);
        registerType(ExtraDataSnapshotInfoBtrfs.class, ExtraDataSnapshotInfoBtrfs::deserialize);
    }

    record TypeRegistration(Class<? extends ExtraData> clazz, Function<byte[], ExtraData> deserializer) {
    }
}
