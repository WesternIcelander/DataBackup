package io.siggi.databackup.util;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

public class DirectoryPrinter {
    private DirectoryPrinter() {
    }

    public static void print(PrintStream stream, DirectoryEntryDirectory directory) {
        print(stream, directory, "");
    }

    private static void print(PrintStream stream, DirectoryEntryDirectory directory, String prefix) {
        String dirName = directory.getName();
        stream.println(prefix + dirName + "/" + getSuffix(directory));
        prefix += "  ";
        Map<String, DirectoryEntry> entries = directory.getEntries();
        List<String> names = Util.sortedKeys(entries);
        for (String name : names) {
            DirectoryEntry entry = entries.get(name);
            if (entry.isSymlink()) {
                stream.println(prefix + name + " -> " + entry.asSymlink().getTarget());
            } else if (entry.isFile()) {
                DirectoryEntryFile file = entry.asFile();
                byte[] sha256 = file.getSha256();
                String hash = Util.isZero(sha256) ? "unhashed" : Util.bytesToHex(sha256);
                stream.println(prefix + name + " (" + hash + ", " + file.getSize() + ")" + getSuffix(file));
            } else if (entry.isDirectory()) {
                DirectoryEntryDirectory dir = entry.asDirectory();
                print(stream, dir, prefix);
            } else if (entry.isNull()) {
                stream.println(prefix + name + " (deleted)");
            }
        }
    }

    private static String getSuffix(DirectoryEntry entry) {
        String permissionsSuffix;
        ExtraDataPosixPermissions posixPermissions = entry.getExtra(ExtraDataPosixPermissions.class);
        if (posixPermissions == null) {
            permissionsSuffix = "";
        } else {
            permissionsSuffix = " " + posixPermissions.getPermissionsAsString() + " " + posixPermissions.owner + " " + posixPermissions.group;
        }
        return permissionsSuffix;
    }
}
