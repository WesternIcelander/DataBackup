package io.siggi.databackup.osutils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class LinuxUtility {
    public static boolean snapshotSubvolume(File source, File target, boolean readOnly) {
        try {
            List<String> args = new ArrayList<>();
            args.add("/usr/bin/btrfs");
            args.add("subvolume");
            args.add("snapshot");
            if (readOnly) args.add("-r");
            args.add(source.getAbsolutePath());
            args.add(target.getAbsolutePath());
            String[] programArgs = args.toArray(new String[args.size()]);
            Process exec = Runtime.getRuntime().exec(programArgs);
            return exec.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deleteSubvolume(File path) {
        try {
            List<String> args = new ArrayList<>();
            args.add("/usr/bin/btrfs");
            args.add("subvolume");
            args.add("delete");
            args.add(path.getAbsolutePath());
            String[] programArgs = args.toArray(new String[args.size()]);
            Process exec = Runtime.getRuntime().exec(programArgs);
            return exec.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<MountedDisk> getMountedDisks() {
        List<MountedDisk> mountedDisks = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/usr/bin/mount"});
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int endOfDisk = line.indexOf(" on ");
                    int endOfType = line.indexOf(" (");
                    if (endOfDisk == -1 || endOfType == -1) continue;
                    int endOfPath = line.lastIndexOf(" type ", endOfType);
                    if (endOfPath == -1) continue;
                    int startOfPath = endOfDisk + 4;
                    int startOfType = endOfPath + 6;
                    int startOfOptions = endOfType + 2;
                    int endOfOptions = line.indexOf(")", startOfOptions);
                    String disk = line.substring(0, endOfDisk);
                    String path = line.substring(startOfPath, endOfPath);
                    String type = line.substring(startOfType, endOfType);
                    String options = line.substring(startOfOptions, endOfOptions);
                    List<String> optionsArray = List.of(options.split(","));
                    mountedDisks.add(new MountedDisk(
                        disk,
                        path,
                        type,
                        optionsArray
                    ));
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        }
        return mountedDisks;
    }
}
