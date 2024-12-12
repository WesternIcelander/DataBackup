package io.siggi.databackup.data.reader;

import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.content.FileContent;
import io.siggi.databackup.datarepository.DataRepository;
import io.siggi.databackup.datarepository.StoredData;
import io.siggi.databackup.util.stream.ChainedInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

public class DataReader {
    private final DataRepository repository;
    private final DirectoryEntryFile file;

    public DataReader(DataRepository repository, DirectoryEntryFile file) {
        this.repository = repository;
        this.file = file;
    }

    private boolean scanned = false;
    private boolean entireFileAvailable = false;
    private boolean seekSupported = false;
    private long fileLength = 0L;

    private void scan() {
        if (scanned) return;
        entireFileAvailable = true;
        seekSupported = true;
        for (FileContent content : file) {
            if (!content.isDataAvailable(repository)) {
                entireFileAvailable = false;
                seekSupported = false;
            }
            StoredData storedData = content.getStoredData(repository);
            if (storedData != null && !storedData.getCompression().equalsIgnoreCase("none")) {
                seekSupported = false;
            }
            fileLength += content.getLength();
        }
        scanned = true;
    }

    public boolean isEntireFileAvailable() {
        scan();
        return entireFileAvailable;
    }

    public boolean isSeekSupported() {
        scan();
        return seekSupported;
    }

    public long getFileLength() {
        scan();
        return fileLength;
    }

    public InputStream getInputStream() throws IOException {
        return getInputStream(0L);
    }

    public InputStream getInputStream(long seek) throws IOException {
        if (seek < 0L) throw new IllegalArgumentException("negative seek");
        if (seek > 0L) {
            scan();
            if (!seekSupported) throw new IOException("seek not supported on this file");
        }
        long skipBytes;
        Iterator<FileContent> iterator = file.iterator();
        FileContent firstItem = null;
        if (seek > 0L) {
            firstItem = iterator.next();
            while (firstItem.getOffset() + firstItem.getLength() <= seek) {
                firstItem = iterator.next();
                if (firstItem == null) {
                    throw new IOException();
                }
            }
            skipBytes = seek - firstItem.getOffset();
        } else {
            skipBytes = 0L;
        }
        FileContent fNext = firstItem;
        return new ChainedInputStream(new Iterator<>() {
            FileContent forceNext = fNext;

            @Override
            public boolean hasNext() {
                return forceNext != null || iterator.hasNext();
            }

            @Override
            public InputStream next() {
                long initialSkippedBytes = 0L;
                FileContent content;
                if (forceNext != null) {
                    content = forceNext;
                    initialSkippedBytes = skipBytes;
                    forceNext = null;
                } else {
                    content = iterator.next();
                }
                InputStream in;
                try {
                    in = content.getInputStream(repository);
                    if (initialSkippedBytes > 0L) {
                        in.skipNBytes(initialSkippedBytes);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return in;
            }
        });
    }
}
