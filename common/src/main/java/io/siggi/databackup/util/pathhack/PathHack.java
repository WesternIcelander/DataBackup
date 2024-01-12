package io.siggi.databackup.util.pathhack;

import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public class PathHack {
    private static PathHack instance;

    public static PathHack get() {
        if (instance == null) {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                instance = new WindowsPathHack();
            } else {
                instance = new PathHack();
            }
        }
        return instance;
    }

    PathHack() {
    }

    public Path createPath(String pathString) {
        return FileSystems.getDefault().getPath(pathString);
    }

    public Iterable<Path> fix(DirectoryStream<Path> pathStream) {
        if (pathStream == null) throw new NullPointerException();
        return pathStream;
    }
}
