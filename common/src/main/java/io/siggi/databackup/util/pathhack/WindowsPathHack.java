package io.siggi.databackup.util.pathhack;

import io.siggi.reflectionfreedom.ReflectionFreedom;

import java.lang.ref.WeakReference;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public class WindowsPathHack extends PathHack {

    private static final boolean applicableToCurrentOS;

    private static final Field rootField;
    private static final Field pathField;
    private static final Field pathForCallsField;

    private static final Class<?> directoryStreamClass;
    private static final Field prefixField;

    static {
        Class<?> directoryStreamC = null;
        Field rootF = null;
        Field pathF = null;
        Field pathForCallsF = null;
        Field prefixF = null;
        applicableToCurrentOS = System.getProperty("os.name").toLowerCase().contains("windows");
        if (applicableToCurrentOS) {
            try {
                String currentWorkingDir = System.getProperty("user.dir");
                Path path = FileSystems.getDefault().getPath(currentWorkingDir);
                Class<?> pathC = path.getClass();
                rootF = pathC.getDeclaredField("root");
                pathF = pathC.getDeclaredField("path");
                pathForCallsF = pathC.getDeclaredField("pathForWin32Calls");
                unlock(rootF);
                unlock(pathF);
                unlock(pathForCallsF);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                    directoryStreamC = stream.getClass();
                    Iterator<Path> iterator = stream.iterator();
                    Class<?> directoryStreamIteratorC = iterator.getClass();
                    prefixF = directoryStreamIteratorC.getDeclaredField("prefix");
                    unlock(prefixF);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        directoryStreamClass = directoryStreamC;
        rootField = rootF;
        pathField = pathF;
        pathForCallsField = pathForCallsF;
        prefixField = prefixF;
    }

    WindowsPathHack() {
    }

    @Override
    public Path createPath(String pathString) {
        // This is needed because the java nio Path API does not allow question marks (?) inside a path at all
        // even though \\?\ is a valid path in the Windows filesystem API.
        if (!pathString.startsWith("\\\\?\\")) return super.createPath(pathString);
        Path path = FileSystems.getDefault().getPath("\\\\a\\b\\c");
        int rootEnd = pathString.indexOf("\\", (pathString.indexOf("\\", 2) + 1) + 1);
        String root = rootEnd == -1 ? pathString : pathString.substring(0, rootEnd + 1);
        if (!root.equals(pathString)) {
            while (pathString.endsWith("\\")) pathString = pathString.substring(0, pathString.length() - 1);
        }
        try {
            rootField.set(path, root);
            pathField.set(path, pathString);
            // the WeakReference will never clear because the exact same
            // String object is also strongly stored in the path field.
            pathForCallsField.set(path, new WeakReference<>(pathString));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return path;
    }

    @Override
    public Iterable<Path> fix(DirectoryStream<Path> pathStream) {
        if (pathStream.getClass() != directoryStreamClass) {
            return pathStream;
        }
        Iterator<Path> originalIterator = pathStream.iterator();
        String originalPrefix;
        try {
            originalPrefix = (String) prefixField.get(originalIterator);
            prefixField.set(originalIterator, "\\\\fake\\path\\");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Iterator<Path> iterator = new Iterator<>() {

            @Override
            public boolean hasNext() {
                return originalIterator.hasNext();
            }

            @Override
            public Path next() {
                String name = originalIterator.next().getFileName().toString();
                return createPath(originalPrefix + name);
            }
        };
        return () -> iterator;
    }

    private static void unlock(AccessibleObject accessible) {
        if (accessible instanceof Field) {
            ReflectionFreedom.setModifiers(accessible, ((Field) accessible).getModifiers() & ~Modifier.FINAL);
        }
        ReflectionFreedom.setAccessible(accessible, true);
    }
}
