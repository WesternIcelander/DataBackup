package io.siggi.databackup.auth.simple;

import io.siggi.databackup.auth.Authorizer;
import io.siggi.databackup.util.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;

public class SimpleAuthorizer implements Authorizer {

    private final File tokenDirectory;

    public SimpleAuthorizer(File tokenDirectory) {
        this.tokenDirectory = tokenDirectory;
    }

    /**
     * Returns the hex SHA-256 of a token, which names its token file ({@code <hash>.txt}).
     */
    public static String hashToken(String authToken) {
        MessageDigest sha256 = Util.sha256();
        sha256.update(authToken.getBytes(StandardCharsets.UTF_8));
        return Util.bytesToHex(sha256.digest());
    }

    @Override
    public SimpleAuthorization getAuthorization(String authToken) {
        if (authToken == null) return null;
        String hashedToken = hashToken(authToken);
        File tokenFile = new File(tokenDirectory, hashedToken + ".txt");
        if (!tokenFile.exists()) return null;
        try {
            return readTokenFile(tokenFile);
        } catch (IOException e) {
            return null;
        }
    }

    static SimpleAuthorization readTokenFile(File tokenFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(tokenFile), StandardCharsets.UTF_8))) {
            String userId = null;
            String name = null;
            Set<String> permissions = new LinkedHashSet<>();

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int equalPos = line.indexOf("=");
                if (equalPos == -1) continue;
                String key = line.substring(0, equalPos).strip();
                String value = line.substring(equalPos + 1).strip();
                switch (key) {
                    case "id" -> userId = value;
                    case "name" -> name = value;
                    case "permission" -> {
                        if (!value.isEmpty()) permissions.add(value);
                    }
                    default -> {
                        // Unknown keys are ignored.
                    }
                }
            }
            return new SimpleAuthorization(userId, name, permissions);
        }
    }
}
