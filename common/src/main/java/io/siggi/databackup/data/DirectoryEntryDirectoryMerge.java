package io.siggi.databackup.data;

import io.siggi.databackup.data.extra.ExtraDataDiffMetadata;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.Map;

public class DirectoryEntryDirectoryMerge extends DirectoryEntryDirectory {
    private final DirectoryEntryDirectory base;
    private final DirectoryEntryDirectory mergeIn;
    private SoftReference<Map<String,DirectoryEntry>> entries = null;
    public DirectoryEntryDirectoryMerge(DirectoryEntryDirectory base, DirectoryEntryDirectory mergeIn) {
        super(mergeIn.getName());
        this.base = base;
        this.mergeIn = mergeIn;
        getExtra().addAll(mergeIn.getExtra());
        getExtra().removeIf(data -> data instanceof ExtraDataDiffMetadata);
    }

    @Override
    public DirectoryEntry getEntry(String name) {
        return getEntries().get(name);
    }

    @Override
    public Map<String, DirectoryEntry> getEntries() {
        Map<String,DirectoryEntry> entryMap = entries == null ? null : entries.get();
        if (entryMap == null) {
            entries = new SoftReference<>(entryMap = new HashMap<>());
            entryMap.putAll(base.getEntries());
            for (Map.Entry<String,DirectoryEntry> mapEntry : mergeIn.getEntries().entrySet()) {
                String key = mapEntry.getKey();
                DirectoryEntry entry = mapEntry.getValue();
                if (entry.isNull()) {
                    entryMap.remove(key);
                } else if (entry.isDirectory()) {
                    DirectoryEntry baseEntry = entryMap.get(key);
                    if (baseEntry == null || !baseEntry.isDirectory()) {
                        entryMap.put(key, entry);
                        continue;
                    }
                    DirectoryEntryDirectoryMerge subDirectory = new DirectoryEntryDirectoryMerge(baseEntry.asDirectory(), entry.asDirectory());
                    subDirectory.setParent(this);
                    entryMap.put(key, subDirectory);
                } else {
                    entry.setParent(this);
                    entryMap.put(key, entry);
                }
            }
        }
        return entryMap;
    }
}
