package io.siggi.databackup.util;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Util {
    private Util() {
    }

    public static final Pattern uuidPattern = Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");

    public static List<String> sortedKeys(Map<String, ?> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        keys.sort(String::compareTo);
        return keys;
    }

    public static DirectoryEntryDirectoryMemory recursivelyMakeMemory(DirectoryEntryDirectory directory) {
        Map<String, DirectoryEntry> entries = directory.getEntries();
        for (Map.Entry<String,DirectoryEntry> entry : entries.entrySet()) {
            DirectoryEntry value = entry.getValue();
            if (value instanceof DirectoryEntryDirectory dir) {
                entry.setValue(recursivelyMakeMemory(dir));
            } else if (value instanceof DirectoryEntryFile file) {
                file.getFileContents(true);
            }
        }
        if (directory instanceof DirectoryEntryDirectoryMemory result) {
            return result;
        } else {
            DirectoryEntryDirectoryMemory result = new DirectoryEntryDirectoryMemory(directory.getName());
            result.getExtra().addAll(directory.getExtra());
            for (DirectoryEntry entry : entries.values()) {
                entry.setParent(result);
            }
            result.getEntries().putAll(entries);
            return result;
        }
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isZero(byte[] data) {
        for (byte b : data) {
            if (b != (byte) 0) return false;
        }
        return true;
    }

    private static final char[] hexCharset = "0123456789abcdef".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            hexChars[i * 2] = hexCharset[(bytes[i] >> 4) & 0xf];
            hexChars[(i * 2) + 1] = hexCharset[bytes[i] & 0xf];
        }
        return new String(hexChars);
    }

    public static String getErrorString(Process process) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
