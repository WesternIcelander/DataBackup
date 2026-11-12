package io.siggi.databackup.client;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * The client's default config file and state folder for an operating system. {@code --config} overrides the config
 * file, and {@code stateDir} in the config overrides the state folder.
 *
 * <ul>
 *     <li>macOS: {@code /Library/Application Support/DataBackup-client/client.json}, state in
 *     {@code /Library/Application Support/DataBackup-client/state}</li>
 *     <li>Linux and other Unix: {@code /etc/databackup/client.json}, state in {@code /var/lib/databackup-client}</li>
 *     <li>Windows: {@code C:\ProgramData\DataBackup-client\client.json}, state in
 *     {@code C:\ProgramData\DataBackup-client\state}</li>
 * </ul>
 */
public record ClientPaths(Path configFile, Path stateDir) {

    public static final String CONFIG_FILE_NAME = "client.json";

    /**
     * The defaults for the operating system this JVM runs on.
     */
    public static ClientPaths defaults() {
        return forOs(System.getProperty("os.name", ""));
    }

    /**
     * The defaults for an operating system, by its {@code os.name}.
     */
    public static ClientPaths forOs(String osName) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("mac") || os.startsWith("darwin")) {
            String dir = "/Library/Application Support/DataBackup-client";
            return new ClientPaths(Paths.get(dir, CONFIG_FILE_NAME), Paths.get(dir, "state"));
        } else if (os.startsWith("windows")) {
            // Built as strings so the result is the same whichever OS runs the tests.
            String dir = "C:\\ProgramData\\DataBackup-client";
            return new ClientPaths(Paths.get(dir + "\\" + CONFIG_FILE_NAME), Paths.get(dir + "\\state"));
        } else {
            return new ClientPaths(Paths.get("/etc/databackup", CONFIG_FILE_NAME),
                    Paths.get("/var/lib/databackup-client"));
        }
    }
}
