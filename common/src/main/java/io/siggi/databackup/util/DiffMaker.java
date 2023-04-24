package io.siggi.databackup.util;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryNull;
import java.util.Map;

public class DiffMaker {
    private DiffMaker() {
    }
    public static DirectoryEntryDirectoryMemory diff(DirectoryEntryDirectory original, DirectoryEntryDirectory target) {
        DirectoryEntryDirectoryMemory diff = new DirectoryEntryDirectoryMemory(target.getName());
        diff.getExtra().addAll(target.getExtra());
        return diff(original, target, diff);
    }
    private static DirectoryEntryDirectoryMemory diff(DirectoryEntryDirectory original, DirectoryEntryDirectory target, DirectoryEntryDirectoryMemory diff) {
        Map<String, DirectoryEntry> originals = original.getEntries();
        Map<String, DirectoryEntry> targets = target.getEntries();
        Map<String, DirectoryEntry> diffs = diff.getEntries();
        for (Map.Entry<String,DirectoryEntry> entry : originals.entrySet()) {
            if (!targets.containsKey(entry.getKey())) {
                diffs.put(entry.getKey(), new DirectoryEntryNull(entry.getKey()));
            }
        }
        for (Map.Entry<String,DirectoryEntry> entry : targets.entrySet()) {
            DirectoryEntry originalEntry = originals.get(entry.getKey());
            DirectoryEntry newEntry = entry.getValue();
            if (originalEntry == null || originalEntry.getType() != newEntry.getType()) {
                diffs.put(entry.getKey(), entry.getValue());
                continue;
            }
            if (!newEntry.isDirectory()) {
                if (!newEntry.equals(originalEntry)) {
                    diffs.put(entry.getKey(), entry.getValue());
                }
                continue;
            }
            DirectoryEntryDirectory originalDirectory = originalEntry.asDirectory();
            DirectoryEntryDirectory newDirectory = newEntry.asDirectory();
            DirectoryEntryDirectoryMemory diffDirectory = new DirectoryEntryDirectoryMemory(newDirectory.getName());
            diffDirectory.getExtra().addAll(newDirectory.getExtra());
            diff(originalDirectory, newDirectory, diffDirectory);
            if (!diffDirectory.getEntries().isEmpty() || !newDirectory.equals(originalDirectory)) {
                diffs.put(entry.getKey(), diffDirectory);
            }
        }
        return diff;
    }
}
