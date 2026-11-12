package io.siggi.databackup.auth.simple;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Creates, lists and revokes the token files that {@link SimpleAuthorizer} reads. Each token is stored only as the
 * file {@code <sha256(token)>.txt}, so a token can't be shown again after it is created.
 * <p>
 * A token file holds {@code id=}, {@code name=}, {@code created=} and one {@code permission=} line per permission.
 * The id is a short random hex string used to refer to the token (for example to revoke it) without knowing it.
 */
public final class SimpleTokenStore {

    private static final Pattern TOKEN_FILE_NAME = Pattern.compile("[0-9a-f]{64}\\.txt");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final int TOKEN_BYTES = 32;
    private static final int ID_BYTES = 6;

    private final File tokenDirectory;
    private final SecureRandom random = new SecureRandom();

    public SimpleTokenStore(File tokenDirectory) {
        this.tokenDirectory = Objects.requireNonNull(tokenDirectory);
    }

    public File getTokenDirectory() {
        return tokenDirectory;
    }

    /**
     * A token as stored in its file.
     *
     * @param id          the token's id, or {@code null} if the file has none (for example a hand-written file)
     * @param name        the token's name, or {@code null}
     * @param created     when the token was created, or {@code null} if unknown
     * @param permissions the granted permission strings, in file order
     * @param hash        the hex SHA-256 of the token, which names its file
     */
    public record TokenInfo(String id, String name, String created, List<String> permissions, String hash) {
    }

    /**
     * A newly created token. {@link #token()} is the secret; it isn't stored anywhere.
     */
    public record CreatedToken(String token, TokenInfo info) {
    }

    /**
     * Creates a new random token with the given name and permissions, and writes its token file.
     *
     * @throws IllegalArgumentException if the name or a permission is blank or contains a line break
     */
    public CreatedToken create(String name, List<String> permissions) throws IOException {
        checkValue("name", name);
        if (permissions == null || permissions.isEmpty()) {
            throw new IllegalArgumentException("At least one permission is required");
        }
        List<String> perms = new ArrayList<>();
        for (String permission : permissions) {
            checkValue("permission", permission);
            String stripped = permission.strip();
            if (!perms.contains(stripped)) perms.add(stripped);
        }
        Path dir = tokenDirectory.toPath();
        Files.createDirectories(dir);

        List<TokenInfo> existing = list();
        String id;
        do {
            id = randomHex(ID_BYTES);
        } while (findById(existing, id) != null);

        byte[] tokenBytes = new byte[TOKEN_BYTES];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String hash = SimpleAuthorizer.hashToken(token);
        String created = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

        StringBuilder content = new StringBuilder();
        content.append("id=").append(id).append('\n');
        content.append("name=").append(name.strip()).append('\n');
        content.append("created=").append(created).append('\n');
        for (String permission : perms) {
            content.append("permission=").append(permission).append('\n');
        }

        Path file = dir.resolve(hash + ".txt");
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException e) {
            // A SHA-256 collision between random tokens doesn't happen; refuse rather than overwrite.
            throw new IOException("Token file already exists: " + file, e);
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Not a POSIX filesystem (Windows); rely on the folder's ACLs.
        }
        return new CreatedToken(token, new TokenInfo(id, name.strip(), created, List.copyOf(perms), hash));
    }

    /**
     * Lists every token file in the folder, sorted by creation time (unknown first), then name and id. Files that
     * can't be read are skipped.
     */
    public List<TokenInfo> list() throws IOException {
        List<TokenInfo> result = new ArrayList<>();
        File[] files = tokenDirectory.listFiles();
        if (files == null) return result;
        for (File file : files) {
            if (!file.isFile() || !TOKEN_FILE_NAME.matcher(file.getName()).matches()) continue;
            TokenInfo info;
            try {
                info = read(file);
            } catch (IOException e) {
                continue;
            }
            result.add(info);
        }
        result.sort(Comparator.comparing(TokenInfo::created, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(TokenInfo::name, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(TokenInfo::id, Comparator.nullsFirst(Comparator.naturalOrder())));
        return result;
    }

    /**
     * Revokes a token by deleting its file.
     *
     * @param idOrHash the token's id, or the full hex SHA-256 that names its file (for files without an id)
     * @return the revoked token, or {@code null} if no token matches
     * @throws IllegalStateException if more than one token has that id; revoke those by hash instead
     */
    public TokenInfo revoke(String idOrHash) throws IOException {
        if (idOrHash == null || idOrHash.isBlank()) return null;
        String key = idOrHash.strip();
        List<TokenInfo> tokens = list();
        TokenInfo match = findById(tokens, key);
        if (match == null && HASH.matcher(key).matches()) {
            for (TokenInfo token : tokens) {
                if (token.hash().equals(key)) {
                    match = token;
                    break;
                }
            }
        }
        if (match == null) return null;
        Files.delete(tokenDirectory.toPath().resolve(match.hash() + ".txt"));
        return match;
    }

    private static TokenInfo findById(List<TokenInfo> tokens, String id) {
        TokenInfo found = null;
        for (TokenInfo token : tokens) {
            if (id.equals(token.id())) {
                if (found != null) {
                    throw new IllegalStateException("More than one token has id " + id
                            + "; revoke by the token file's hash instead");
                }
                found = token;
            }
        }
        return found;
    }

    private static TokenInfo read(File file) throws IOException {
        String hash = file.getName().substring(0, file.getName().length() - ".txt".length());
        String created = null;
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            line = line.strip();
            if (line.startsWith("created=")) {
                created = line.substring("created=".length()).strip();
                if (created.isEmpty()) created = null;
            }
        }
        SimpleAuthorization auth = SimpleAuthorizer.readTokenFile(file);
        return new TokenInfo(auth.getUserId(), auth.getName(), created, List.copyOf(auth.getPermissions()), hash);
    }

    private String randomHex(int bytes) {
        byte[] data = new byte[bytes];
        random.nextBytes(data);
        return HexFormat.of().formatHex(data);
    }

    private static void checkValue(String what, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("The " + what + " must not be blank");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("The " + what + " must not contain a line break");
        }
    }
}
