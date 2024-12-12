package io.siggi.databackup.auth.simple;

import io.siggi.databackup.auth.Authorizer;
import io.siggi.databackup.util.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public class SimpleAuthorizer implements Authorizer {

    private final File tokenDirectory;

    public SimpleAuthorizer(File tokenDirectory) {
        this.tokenDirectory = tokenDirectory;
    }

    @Override
    public SimpleAuthorization getAuthorization(String authToken) {
        if (authToken == null) return null;
        MessageDigest sha256 = Util.sha256();
        sha256.update(authToken.getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha256.digest();
        String hashedToken = Util.bytesToHex(digest);
        File tokenFile = new File(tokenDirectory, hashedToken + ".txt");
        if (!tokenFile.exists()) return null;
        try {
            return readTokenFile(tokenFile);
        } catch (IOException e) {
            return null;
        }
    }

    private SimpleAuthorization readTokenFile(File tokenFile) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(tokenFile))) {
            String userId = null;
            String name = null;
            Set<String> permissions = new HashSet<>();

            String line;
            while ((line = reader.readLine()) != null) {
                int equalPos = line.indexOf("=");
                if (equalPos == -1) continue;
                String key = line.substring(0, equalPos);
                String value = line.substring(equalPos + 1);
                switch (key) {
                    case "id":
                        userId = value;
                    case "name":
                        name = value;
                    case "permission":
                        permissions.add(value);
                }
            }
            return new SimpleAuthorization(userId, name, permissions);
        }
    }
}
