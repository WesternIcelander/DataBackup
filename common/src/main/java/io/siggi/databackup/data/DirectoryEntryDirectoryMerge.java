package io.siggi.databackup.data;

import io.siggi.databackup.data.extra.ExtraDataDiffMetadata;
import java.util.HashMap;
import java.util.Map;

public class DirectoryEntryDirectoryMerge extends DirectoryEntryDirectory {
    private final DirectoryEntryDirectory base;
    private final DirectoryEntryDirectory mergeIn;
    private boolean didCopyEntries = false;
    private final Map<String,DirectoryEntry> entries = new HashMap<>();
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
        if (!didCopyEntries) {
            didCopyEntries = true;
            entries.putAll(base.getEntries());
            for (Map.Entry<String,DirectoryEntry> mapEntry : mergeIn.getEntries().entrySet()) {
                String key = mapEntry.getKey();
                DirectoryEntry entry = mapEntry.getValue();
                if (entry.isNull()) {
                    entries.remove(key);
                } else if (entry.isDirectory()) {
                    DirectoryEntry baseEntry = entries.get(key);
                    if (baseEntry == null || !baseEntry.isDirectory()) {
                        entries.put(key, entry);
                        continue;
                    }
                    entries.put(key, new DirectoryEntryDirectoryMerge(baseEntry.asDirectory(), entry.asDirectory()));
                } else {
                    entries.put(key, entry);
                }
            }
        }
        return entries;
    }
}
