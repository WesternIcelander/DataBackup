package io.siggi.databackup.osutils;

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
}
