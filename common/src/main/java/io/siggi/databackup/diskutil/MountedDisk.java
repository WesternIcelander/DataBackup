package io.siggi.databackup.diskutil;

import io.siggi.databackup.osutils.OS;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public record MountedDisk(String dev, String path, String type, List<String> options) {
    public boolean isReadOnly() {
        for (String option : options) {
            // ro = Linux
            // read-only = macOS
            if (option.equals("ro") || option.equals("read-only")) {
                return true;
            }
        }
        return false;
    }

    public String getOption(String option) {
        for (String optionValue : options) {
            if (optionValue.equals(option)) {
                return optionValue;
            } else if (optionValue.startsWith(option + "=")) {
                return optionValue.substring(option.length() + 1);
            }
        }
        return null;
    }

    public static List<MountedDisk> getAll() {
        switch (OS.get()) {
            case MACOS: {
                List<MountedDisk> mountedDisks = new ArrayList<>();
                try {
                    Process process = Runtime.getRuntime().exec(new String[]{"/sbin/mount"});
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            int endOfDisk = line.indexOf(" on ");
                            int endOfPath = line.lastIndexOf(" (");
                            if (endOfDisk == -1 || endOfPath == -1) continue;
                            int startOfPath = endOfDisk + 4;
                            int startOfOptions = endOfPath + 2;
                            int endOfOptions = line.indexOf(")", startOfOptions);
                            String disk = line.substring(0, endOfDisk);
                            String path = line.substring(startOfPath, endOfPath);
                            String options = line.substring(startOfOptions, endOfOptions).replace(", ", ",");
                            int firstComma = options.indexOf(",");
                            if (firstComma == -1) continue;
                            String type = options.substring(0, firstComma);
                            List<String> optionsArray = List.of(options.substring(firstComma + 1).split(","));
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
            case LINUX: {
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
            default:
                throw new UnsupportedOperationException("Not supported on the current OS.");
        }
    }
}
