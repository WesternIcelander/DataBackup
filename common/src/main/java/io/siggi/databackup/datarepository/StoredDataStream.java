package io.siggi.databackup.datarepository;

import io.siggi.databackup.util.ReadingIterator;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class StoredDataStream implements Iterable<StoredData>, AutoCloseable {
    private final DataRepository repository;
    private final File root;
    private final List<DirectoryStream<Path>> directoryStreams = new ArrayList<>();
    private final List<Iterator<Path>> directoryStreamIterators = new ArrayList<>();
    private final Iterator<StoredData> iterator;

    StoredDataStream(DataRepository repository, File root) throws IOException {
        this.repository = repository;
        this.root = root;
        DirectoryStream<Path> stream = Files.newDirectoryStream(root.toPath());
        Iterator<Path> streamIterator = stream.iterator();
        directoryStreams.add(stream);
        directoryStreamIterators.add(streamIterator);
        iterator = new ReadingIterator<>() {
            @Override
            protected StoredData read() {
                topLoop:
                while (true) {
                    int depth = directoryStreams.size() - 1;
                    if (depth == -1) return null;
                    DirectoryStream<Path> stream = directoryStreams.get(depth);
                    Iterator<Path> iterator = directoryStreamIterators.get(depth);
                    while (true) {
                        if (!iterator.hasNext()) {
                            try {
                                stream.close();
                            } catch (IOException ignored) {
                            }
                            directoryStreams.remove(depth);
                            directoryStreamIterators.remove(depth);
                            continue topLoop;
                        }
                        Path next = iterator.next();
                        if (Files.isDirectory(next)) {
                            try {
                                DirectoryStream<Path> newStream = Files.newDirectoryStream(next);
                                Iterator<Path> newIterator = newStream.iterator();
                                directoryStreams.add(newStream);
                                directoryStreamIterators.add(newIterator);
                                continue topLoop;
                            } catch (Exception e) {
                                continue;
                            }
                        }
                        String name = next.getFileName().toString();
                        if (name.startsWith(".") || !name.endsWith(".metadata")) {
                            continue;
                        }
                        name = name.substring(0, name.length() - 9);
                        return repository.getStoredData(name);
                    }
                }
            }
        };
    }

    @Override
    public Iterator<StoredData> iterator() {
        return iterator;
    }

    @Override
    public void close() {
        for (DirectoryStream<Path> stream : directoryStreams) {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
        }
        directoryStreams.clear();
    }
}
