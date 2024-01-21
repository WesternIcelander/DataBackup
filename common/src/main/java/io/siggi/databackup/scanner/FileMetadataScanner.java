package io.siggi.databackup.scanner;

import io.siggi.databackup.data.DirectoryEntry;
import io.siggi.databackup.data.DirectoryEntryDirectory;
import io.siggi.databackup.data.DirectoryEntryDirectoryMemory;
import io.siggi.databackup.data.DirectoryEntryFile;
import io.siggi.databackup.data.DirectoryEntryNull;
import io.siggi.databackup.data.DirectoryEntrySymlink;
import io.siggi.databackup.data.content.Sha256FileContent;
import io.siggi.databackup.data.extra.ExtraDataFilePath;
import io.siggi.databackup.data.extra.ExtraDataNanosecondModifiedDate;
import io.siggi.databackup.data.extra.ExtraDataPosixPermissions;
import io.siggi.databackup.util.IO;
import io.siggi.databackup.util.MessageDigestOutputStream;
import io.siggi.databackup.util.Util;
import io.siggi.databackup.util.pathhack.PathHack;
import io.siggi.stat.Stat;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
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

public class FileMetadataScanner {

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

    public FileMetadataScanner(File rootFile) {
        this.rootFile = rootFile;
        this.rootDirectory = new DirectoryEntryDirectoryMemory("ROOT");
        String absolutePath = rootFile.getAbsolutePath().replace(File.separatorChar, '/');
        while (absolutePath.endsWith("/")) {
            absolutePath = absolutePath.substring(0, absolutePath.length() - 1);
        }
        this.rootDirectory.getExtra().add(new ExtraDataFilePath(absolutePath));
    }

    public FileMetadataScanner setPathPredicate(Predicate<String> pathPredicate) {
        this.pathChecker = pathPredicate;
        return this;
    }

    public FileMetadataScanner createDiff(DirectoryEntryDirectory baseForDiff, Function<String, DiffAction> diffFunction) {
        this.baseForDiff = baseForDiff;
        this.diffFunction = diffFunction;
        return this;
    }

    public FileMetadataScanner grabFiles() throws InterruptedException {
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
        Map<String, DirectoryEntry> entries = directoryEntry.getEntries();
        Map<String, DirectoryEntry> baseEntries = baseForDiff == null ? null : baseForDiff.getEntries();
        Set<String> skippedEntries = new HashSet<>();
        try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(PathHack.get().createPath(directory.getAbsolutePath()))) {
            for (Path filePathO : PathHack.get().fix(directoryStream)) {
                if (Thread.interrupted()) {
                    throw new InterruptedException();
                }
                String fileName = filePathO.toString();
                fileName = fileName.substring(fileName.lastIndexOf(File.separator) + 1);
                File file = new File(directory, fileName);
                String filePath = path + fileName;
                DirectoryEntry baseEntry = baseEntries == null ? null : baseEntries.get(fileName);
                File noBackup = new File(file, ".nobackup");
                if (pathChecker != null && !pathChecker.test(filePath) || noBackup.exists()) {
                    ignoredItems.add(filePath);
                    continue;
                }
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
                    DirectoryEntryFile fileEntry = new DirectoryEntryFile(fileName, file.lastModified(), file.length());
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
        } catch (IOException | DirectoryIteratorException e) {
            return;
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

    public void copyHashes(DirectoryEntryDirectory root) throws InterruptedException {
        copyHashes(root, rootDirectory);
    }

    private void copyHashes(DirectoryEntryDirectory from, DirectoryEntryDirectoryMemory to) throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
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
                if (!fromFile.getFileContents().isEmpty()) {
                    if (toFile.getFileContents().isEmpty()) {
                        unhashedSize -= toFile.getSize();
                        unhashedFiles -= 1;
                    }
                    toFile.getFileContents().addAll(fromFile.getFileContents());
                }
            }
        }
    }

    public void fillInHashes(Consumer<String> status) throws InterruptedException {
        fillInHashes(status, rootDirectory, "");
    }

    private void fillInHashes(Consumer<String> status, DirectoryEntryDirectoryMemory directory, String path) throws InterruptedException {
        for (DirectoryEntry entry : directory) {
            String name = entry.getName();
            if (entry.isFile()) {
                DirectoryEntryFile file = entry.asFile();
                if (file.hasFileContents()) {
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
                    Sha256FileContent fileContent = new Sha256FileContent();
                    fileContent.setOffset(0L);
                    fileContent.setLength(file.getSize());
                    System.arraycopy(digest, 0, fileContent.getHash(), 0, 32);
                    file.getFileContents().add(fileContent);
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
