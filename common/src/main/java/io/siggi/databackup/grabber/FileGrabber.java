package io.siggi.databackup.grabber;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntryNull;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.util.IO;
import io.siggi.databackup.util.MessageDigestOutputStream;
import io.siggi.databackup.util.Util;
import io.siggi.stat.Stat;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class FileGrabber {

    public final File rootFile;
    public final DirectoryEntryDirectoryMemory rootDirectory;
    public final List<String> ignoredItems = new LinkedList<>();
    public final List<String> failedItems = new LinkedList<>();
    private Predicate<String> pathChecker;
    private boolean hasGrabbed = false;
    private DirectoryEntryDirectory baseForDiff;
    private Function<String, DiffAction> diffFunction;
    public long fileCount = 0L;
    public long unhashedFiles = 0L;
    public long totalFileSize = 0L;
    public long unhashedSize = 0L;
    public long directoryCount = 0L;
    public long symlinkCount = 0L;

    public FileGrabber(File rootFile) {
        this.rootFile = rootFile;
        this.rootDirectory = new DirectoryEntryDirectoryMemory("ROOT");
        String absolutePath = rootFile.getAbsolutePath().replace(File.separatorChar, '/');
        while (absolutePath.endsWith("/")) {
            absolutePath = absolutePath.substring(0, absolutePath.length() - 1);
        }
        this.rootDirectory.getExtra().add(new ExtraDataFilePath(absolutePath));
    }

    public FileGrabber setPathPredicate(Predicate<String> pathPredicate) {
        this.pathChecker = pathPredicate;
        return this;
    }

    public FileGrabber createDiff(DirectoryEntryDirectory baseForDiff, Function<String, DiffAction> diffFunction) {
        this.baseForDiff = baseForDiff;
        this.diffFunction = diffFunction;
        return this;
    }

    public FileGrabber grabFiles() throws InterruptedException {
        if (hasGrabbed) {
            throw new IllegalStateException("Already grabbed files!");
        }
        hasGrabbed = true;
        DiffAction rootDiffAction = diffFunction == null ? DiffAction.FULL_SCAN : diffFunction.apply("");
        if (rootDiffAction == null) rootDiffAction = DiffAction.FULL_SCAN;
        if (rootDiffAction != DiffAction.DO_NOT_SCAN)
            grabFiles(rootFile, "", rootDirectory, baseForDiff, rootDiffAction, ignoredItems, failedItems);
        return this;
    }

    private void grabFiles(File directory, String path, DirectoryEntryDirectoryMemory directoryEntry,
                           DirectoryEntryDirectory baseForDiff, DiffAction diffAction,
                           List<String> ignoredItems, List<String> failedItems) throws InterruptedException {
        File[] files = directory.listFiles();
        if (files == null) return;
        Map<String, DirectoryEntry> entries = directoryEntry.getEntries();
        Map<String, DirectoryEntry> baseEntries = baseForDiff == null ? null : baseForDiff.getEntries();
        Set<String> skippedEntries = new HashSet<>();
        for (File file : files) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            String fileName = file.getName();
            String filePath = path + fileName;
            DirectoryEntry baseEntry = baseEntries == null ? null : baseEntries.get(fileName);
            File noBackup = new File(file, ".nobackup");
            if (pathChecker != null && !pathChecker.test(filePath) || noBackup.exists()) {
                ignoredItems.add(filePath);
                continue;
            }
            Path filePathO = file.toPath();
            if (Files.isSymbolicLink(filePathO)) {
                if (diffAction == DiffAction.SUBDIRS_ONLY) {
                    continue;
                }
                try {
                    String target = Files.readSymbolicLink(filePathO).toString();
                    DirectoryEntrySymlink symlinkEntry = new DirectoryEntrySymlink(fileName, target);
                    symlinkEntry.setParent(directoryEntry);
                    addExtra(file, symlinkEntry);
                    if (symlinkEntry.equals(baseEntry)) {
                        skippedEntries.add(fileName);
                        continue;
                    }
                    entries.put(fileName, symlinkEntry);
                    symlinkCount += 1L;
                } catch (Exception e) {
                    failedItems.add(filePath);
                }
            } else if (Files.isRegularFile(filePathO, LinkOption.NOFOLLOW_LINKS)) {
                if (diffAction == DiffAction.SUBDIRS_ONLY) {
                    continue;
                }
                DirectoryEntryFile fileEntry = new DirectoryEntryFile(fileName, new byte[32], file.lastModified(), file.length());
                fileEntry.setParent(directoryEntry);
                addExtra(file, fileEntry);
                if (baseEntry instanceof DirectoryEntryFile baseFile && fileEntry.equalsIgnoreHash(baseFile)) {
                    skippedEntries.add(fileName);
                    continue;
                }
                entries.put(fileName, fileEntry);
                fileCount += 1L;
                unhashedFiles += 1L;
                totalFileSize += file.length();
                unhashedSize += file.length();
            } else if (Files.isDirectory(filePathO, LinkOption.NOFOLLOW_LINKS)) {
                DiffAction subDirDiffAction = diffAction == DiffAction.FULL_SCAN ? DiffAction.FULL_SCAN : diffFunction.apply(filePath);
                if (subDirDiffAction == null) subDirDiffAction = DiffAction.FULL_SCAN;
                DirectoryEntryDirectoryMemory subDirectory = new DirectoryEntryDirectoryMemory(fileName);
                subDirectory.setParent(directoryEntry);
                addExtra(file, subDirectory);
                entries.put(fileName, subDirectory);
                directoryCount += 1L;
                if (subDirDiffAction == DiffAction.DO_NOT_SCAN) {
                    continue;
                }
                DirectoryEntryDirectory subDirectoryBase = (baseEntry instanceof DirectoryEntryDirectory) ? baseEntry.asDirectory() : null;
                grabFiles(file, filePath + "/", subDirectory, subDirectoryBase, subDirDiffAction, ignoredItems, failedItems);
            }
        }
        if (baseEntries != null && diffAction.level >= DiffAction.PARTIAL_SCAN.level) {
            for (String name : baseEntries.keySet()) {
                if (!entries.containsKey(name) && !skippedEntries.contains(name)) {
                    entries.put(name, new DirectoryEntryNull(name));
                }
            }
        }
    }

    private static void addExtra(File file, DirectoryEntry entry) {
        Stat stat = Stat.lstat(file);
        if (stat == null) return;
        entry.getExtra().add(new ExtraDataPosixPermissions(stat.mode & 07777, stat.uid, stat.gid));
        if (stat.mtimensec % 1000000L != 0L) {
            // only add nanoseconds if the file timestamp has an apparent level of precision higher than milliseconds
            // 1 million nanoseconds in 1 millisecond
            entry.getExtra().add(new ExtraDataNanosecondModifiedDate(stat.mtime, (int) stat.mtimensec));
        }
    }

    public void copyHashes(DirectoryEntryDirectory root) {
        copyHashes(root, rootDirectory);
    }

    private void copyHashes(DirectoryEntryDirectory from, DirectoryEntryDirectoryMemory to) {
        Map<String, DirectoryEntry> fromEntries = from.getEntries();
        for (Map.Entry<String, DirectoryEntry> entry : to.getEntries().entrySet()) {
            String name = entry.getKey();
            DirectoryEntry toValue = entry.getValue();
            DirectoryEntry fromValue = fromEntries.get(name);
            if (fromValue == null) continue;
            if (fromValue.isDirectory() && toValue.isDirectory()) {
                if (toValue instanceof DirectoryEntryDirectoryMemory toDirectory) {
                    copyHashes(fromValue.asDirectory(), toDirectory);
                }
            } else if (fromValue.isFile() && toValue.isFile()) {
                DirectoryEntryFile fromFile = fromValue.asFile();
                DirectoryEntryFile toFile = toValue.asFile();
                if (fromFile.getSize() != toFile.getSize() || !fromFile.hasSameLastModified(toFile)) continue;
                if (!Util.isZero(fromFile.getSha256())) {
                    if (Util.isZero(toFile.getSha256())) {
                        unhashedSize -= toFile.getSize();
                        unhashedFiles -= 1;
                    }
                    System.arraycopy(fromFile.getSha256(), 0, toFile.getSha256(), 0, 32);
                }
            }
        }
    }

    public void fillInHashes(Consumer<String> status) throws InterruptedException {
        fillInHashes(status, rootDirectory, "");
    }

    private void fillInHashes(Consumer<String> status, DirectoryEntryDirectoryMemory directory, String path) throws InterruptedException {
        Map<String, DirectoryEntry> entries = directory.getEntries();
        List<String> names = Util.sortedKeys(entries);
        for (String name : names) {
            DirectoryEntry entry = entries.get(name);
            if (entry.isFile()) {
                DirectoryEntryFile file = entry.asFile();
                if (!Util.isZero(file.getSha256())) {
                    continue;
                }
                String filePath = path + name;
                if (status != null) status.accept(filePath);
                long originalUnhashedSize = unhashedSize;
                boolean failed = true;
                try (FileInputStream in = new FileInputStream(new File(rootFile, filePath))) {
                    MessageDigest sha256 = Util.sha256();
                    MessageDigestOutputStream out = new MessageDigestOutputStream(sha256);
                    IO.copyInterruptible(in, out, (progress) -> unhashedSize -= progress);
                    byte[] digest = sha256.digest();
                    System.arraycopy(digest, 0, file.getSha256(), 0, 32);
                    failed = false;
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    failedItems.add(filePath);
                } finally {
                    if (failed) {
                        unhashedSize = originalUnhashedSize;
                    } else {
                        unhashedSize = originalUnhashedSize - file.getSize();
                        unhashedFiles -= 1L;
                    }
                }
            } else if (entry.isDirectory()) {
                DirectoryEntryDirectoryMemory subDirectory = (DirectoryEntryDirectoryMemory) entry;
                String directoryPath = path + name + "/";
                fillInHashes(status, subDirectory, directoryPath);
            }
        }
    }
}
