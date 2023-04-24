package io.siggi.databackup.osutils;

public enum OS {
    MACOS, LINUX, WINDOWS, OTHER;
    private static final OS os;

    static {
        String osName = System.getProperty("os.name").toLowerCase();
        OS o;
        if (osName.contains("mac")) {
            o = MACOS;
        } else if (osName.contains("linux")) {
            o = LINUX;
        } else if (osName.contains("windows")) {
            o = WINDOWS;
        } else {
            o = OTHER;
        }
        os = o;
    }

    public static OS get() {
        return os;
    }
}
