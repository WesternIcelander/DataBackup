package io.siggi.databackup.client.snapshot;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/**
 * Readable messages for I/O errors, whose own messages are often just a path.
 */
public final class IoMessages {

    private IoMessages() {
    }

    public static String describe(IOException e) {
        if (e instanceof AccessDeniedException a) {
            return "permission denied: " + a.getFile() + " (run as root, or as Administrator on Windows)";
        }
        if (e instanceof NoSuchFileException n) {
            return "no such file or folder: " + n.getFile();
        }
        if (e instanceof FileSystemException f && f.getReason() == null) {
            return e.getClass().getSimpleName() + ": " + f.getFile();
        }
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }
}
