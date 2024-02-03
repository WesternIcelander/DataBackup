package io.siggi.databackup.datarepository;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class StoredData {
    private final DataRepository repository;
    private final String contentId;
    private File file;
    private File metadataFile;
    private File parentDirectory;
    private boolean read = false;
    private long lastTouch = 0L;

    private final List<String> knownFilenames = new ArrayList<>();
    private String compression = null;
    private boolean doNotCompress = false;

    StoredData(DataRepository repository, String contentId) {
        this.repository = repository;
        this.contentId = contentId;
    }

    private void read() {
        if (read) return;
        read = true;
        getParentDirectory().mkdirs();
        try (BufferedReader reader = new BufferedReader(new FileReader(getMetadataFile()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int equalPos = line.indexOf("=");
                if (equalPos == -1) continue;
                String key = line.substring(0, equalPos);
                String val = line.substring(equalPos + 1);
                switch (key) {
                    case "known-filename":
                        knownFilenames.add(val);
                        break;
                    case "compression":
                        compression = val;
                        break;
                    case "do-not-compress":
                        doNotCompress = val.equals("1");
                        break;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private boolean appendLine(String key, String val) {
        try (FileWriter writer = new FileWriter(getMetadataFile(), true)) {
            writer.write(key + "=" + val + "\n");
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    private boolean removeLine(String key) {
        String keyEq = key + "=";
        File metaFile = getMetadataFile();
        File tmpFile = new File(getMetadataFile().getParentFile(), "tmp-" + UUID.randomUUID());
        if (!metaFile.exists()) return true;
        try {
            try (FileWriter writer = new FileWriter(tmpFile)) {
                try (BufferedReader reader = new BufferedReader(new FileReader(metaFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.equals(key) || line.startsWith(keyEq)) continue;
                        writer.write(line + "\n");
                    }
                }
            }
            tmpFile.renameTo(metaFile);
        } catch (IOException e) {
            return false;
        } finally {
            if (tmpFile.exists())
                tmpFile.delete();
        }
        return true;
    }

    public String getContentId() {
        return contentId;
    }

    public File getFile() {
        if (file == null) {
            file = repository.getContentFile(contentId, getCompression());
        }
        return file;
    }

    public File getMetadataFile() {
        if (metadataFile == null) {
            metadataFile = repository.getMetadataFile(contentId);
        }
        return metadataFile;
    }

    public File getParentDirectory() {
        if (parentDirectory == null) {
            parentDirectory = getMetadataFile().getParentFile();
        }
        return parentDirectory;
    }

    public boolean store(File file, String compression) {
        File originalFile = getFile();
        File newFile = repository.getContentFile(contentId, compression);
        getParentDirectory().mkdirs();
        if (!file.renameTo(newFile)) return false;
        if (setCompression(compression)) {
            if (!originalFile.equals(newFile)) originalFile.delete();
            return true;
        }
        return false;
    }

    public boolean dataExists() {
        return getFile().exists();
    }

    public void deleteFile() {
        getFile().delete();
        getMetadataFile().delete();
    }

    public void touch() {
        File metaFile = getMetadataFile();
        if (!metaFile.exists()) {
            metaFile.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(metaFile, true)) {
                // do nothing, just close the writer
            } catch (IOException ignored) {
            }
        }
        metaFile.setLastModified(lastTouch = System.currentTimeMillis());
    }

    public long getLastTouch() {
        if (lastTouch == 0L) lastTouch = getMetadataFile().lastModified();
        return lastTouch;
    }

    public List<String> getKnownFilenames() {
        read();
        return Collections.unmodifiableList(knownFilenames);
    }

    public boolean addKnownFilename(String name) {
        read();
        if (name.contains("\n") || name.contains("\r")) return false;
        if (knownFilenames.contains(name)) return true;
        if (appendLine("known-filename", name)) {
            knownFilenames.add(name);
            return true;
        }
        return false;
    }

    public String getCompression() {
        read();
        return compression;
    }

    private boolean setCompression(String compression) {
        if (compression == null) {
            if (removeLine("compression")) {
                this.compression = null;
                return true;
            }
            return false;
        }
        if (compression.equals(getCompression())) return true;
        if (appendLine("compression", compression)) {
            this.compression = compression;
            return true;
        }
        return false;
    }

    public boolean isDoNotCompress() {
        read();
        return doNotCompress;
    }

    public void setDoNotCompress() {
        read();
        if (doNotCompress) return;
        appendLine("do-not-compress", "1");
        doNotCompress = true;
    }
}
