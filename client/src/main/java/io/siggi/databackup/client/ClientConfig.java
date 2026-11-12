package io.siggi.databackup.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The client's settings, read from {@code client.json}. Example with every key:
 *
 * <pre>
 * {
 *   "serverUrl": "https://backup.example.com",
 *   "token": "...",                      // or "tokenFile": "token.txt" (relative to this file's folder)
 *   "machineName": "laptop",
 *   "stateDir": "/var/lib/databackup-client",   // optional; defaults by OS
 *   "sources": [
 *     {"name": "Data", "path": "/System/Volumes/Data", "excludes": ["private/var/vm", "**&#47;.Trash"]}
 *   ],
 *   "excludes": ["**&#47;node_modules"]
 * }
 * </pre>
 * <p>
 * Exclude patterns are globs matched against paths relative to the source, with {@code /} as the separator (see
 * {@link ExcludeMatcher}). A source's matcher combines the global and its own patterns. Unknown keys are rejected, so
 * a misspelled key isn't silently ignored.
 */
public final class ClientConfig {

    /**
     * Machine and source names: the same rule the server applies to machine and path names it accepts.
     */
    static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}");

    private static final Set<String> TOP_KEYS = Set.of("serverUrl", "token", "tokenFile", "machineName", "stateDir",
            "sources", "excludes");
    private static final Set<String> SOURCE_KEYS = Set.of("name", "path", "excludes");

    private final Path configFile;
    private final String serverUrl;
    private final String token;
    private final Path tokenFile;
    private final String machineName;
    private final Path stateDir;
    private final List<Source> sources;
    private final List<String> excludes;

    /**
     * One configured backup source: a top-level directory named {@code name} in each backup, holding the contents of
     * {@code path}.
     *
     * @param name     the directory name in the backup
     * @param path     the absolute live path
     * @param excludes this source's own patterns
     * @param matcher  the global patterns and this source's patterns together
     */
    public record Source(String name, Path path, List<String> excludes, ExcludeMatcher matcher) {
    }

    private ClientConfig(Path configFile, String serverUrl, String token, Path tokenFile, String machineName,
                         Path stateDir, List<Source> sources, List<String> excludes) {
        this.configFile = configFile;
        this.serverUrl = serverUrl;
        this.token = token;
        this.tokenFile = tokenFile;
        this.machineName = machineName;
        this.stateDir = stateDir;
        this.sources = sources;
        this.excludes = excludes;
    }

    /**
     * Reads and validates a config file.
     *
     * @param configFile      the file
     * @param defaultStateDir the state folder to use when the file doesn't set {@code stateDir}
     * @throws ConfigException with a message naming the file and the problem
     */
    public static ClientConfig load(Path configFile, Path defaultStateDir) throws ConfigException {
        String json;
        try {
            json = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            throw new ConfigException("Config file not found: " + configFile + " (use --config <file> to choose one)");
        } catch (IOException e) {
            throw new ConfigException("Can't read config file " + configFile + ": " + e);
        }
        try {
            return parse(json, configFile, defaultStateDir);
        } catch (ConfigException e) {
            throw new ConfigException("Invalid config file " + configFile + ": " + e.getMessage());
        }
    }

    /**
     * Parses and validates config JSON. Relative {@code tokenFile} and {@code stateDir} paths are resolved against
     * the folder holding {@code configFile}.
     *
     * @throws ConfigException with a message naming the problem (but not the file)
     */
    public static ClientConfig parse(String json, Path configFile, Path defaultStateDir) throws ConfigException {
        Path baseDir = configFile.toAbsolutePath().getParent();
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) throw new ConfigException("the top level must be a JSON object");
            root = element.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ConfigException("not valid JSON: " + rootCause(e).getMessage());
        }
        checkKeys(root, TOP_KEYS, "");

        String serverUrl = parseServerUrl(requireString(root, "serverUrl", ""));

        String token = optionalString(root, "token", "");
        String tokenFileValue = optionalString(root, "tokenFile", "");
        Path tokenFile = null;
        if (token != null && tokenFileValue != null) {
            throw new ConfigException("set either \"token\" or \"tokenFile\", not both");
        } else if (token == null && tokenFileValue == null) {
            throw new ConfigException("\"token\" or \"tokenFile\" is required");
        } else if (token != null) {
            checkToken(token, "\"token\"");
        } else {
            tokenFile = resolvePath(baseDir, tokenFileValue, "tokenFile");
            try {
                token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
            } catch (NoSuchFileException e) {
                throw new ConfigException("token file not found: " + tokenFile);
            } catch (IOException e) {
                throw new ConfigException("can't read token file " + tokenFile + ": " + e);
            }
            checkToken(token, "the token in " + tokenFile);
        }

        String machineName = requireString(root, "machineName", "");
        if (!NAME_PATTERN.matcher(machineName).matches()) {
            throw new ConfigException("\"machineName\" must be 1 to 128 letters, digits, '.', '_' or '-', not "
                    + "starting with '.', but was \"" + machineName + "\"");
        }

        String stateDirValue = optionalString(root, "stateDir", "");
        Path stateDir = stateDirValue == null ? defaultStateDir : resolvePath(baseDir, stateDirValue, "stateDir");

        List<String> excludes = optionalStringList(root, "excludes", "");
        ExcludeMatcher globalMatcher = compileExcludes(excludes, "excludes");

        JsonElement sourcesElement = root.get("sources");
        if (sourcesElement == null || sourcesElement.isJsonNull()) {
            throw new ConfigException("\"sources\" is required: a list of {\"name\", \"path\", \"excludes\"}");
        }
        if (!sourcesElement.isJsonArray()) throw new ConfigException("\"sources\" must be a list");
        JsonArray sourcesArray = sourcesElement.getAsJsonArray();
        if (sourcesArray.isEmpty()) throw new ConfigException("\"sources\" must list at least one source");
        List<Source> sources = new ArrayList<>();
        Map<String, String> namesSeen = new HashMap<>();
        Map<Path, String> pathsSeen = new HashMap<>();
        for (int i = 0; i < sourcesArray.size(); i++) {
            String where = "sources[" + i + "]";
            JsonElement sourceElement = sourcesArray.get(i);
            if (!sourceElement.isJsonObject()) throw new ConfigException(where + " must be an object");
            JsonObject source = sourceElement.getAsJsonObject();
            checkKeys(source, SOURCE_KEYS, where + ".");

            String name = requireString(source, "name", where + ".");
            if (!NAME_PATTERN.matcher(name).matches()) {
                throw new ConfigException(where + ".name must be 1 to 128 letters, digits, '.', '_' or '-', not "
                        + "starting with '.', but was \"" + name + "\"");
            }
            // Case-insensitive, so a restore onto a case-insensitive filesystem can't merge two sources.
            String previous = namesSeen.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            if (previous != null) {
                throw new ConfigException(where + ".name \"" + name + "\" is already used by another source"
                        + (previous.equals(name) ? "" : " (\"" + previous + "\"; names are compared ignoring case)"));
            }

            String pathValue = requireString(source, "path", where + ".");
            Path path;
            try {
                path = Paths.get(pathValue);
            } catch (InvalidPathException e) {
                throw new ConfigException(where + ".path is not a valid path: \"" + pathValue + "\"");
            }
            if (!path.isAbsolute()) {
                throw new ConfigException(where + ".path must be an absolute path, but was \"" + pathValue + "\"");
            }
            path = path.normalize();
            String previousPath = pathsSeen.putIfAbsent(path, name);
            if (previousPath != null) {
                throw new ConfigException(where + ".path " + path + " is already the path of source \""
                        + previousPath + "\"");
            }

            List<String> sourceExcludes = optionalStringList(source, "excludes", where + ".");
            ExcludeMatcher matcher = globalMatcher.and(compileExcludes(sourceExcludes, where + ".excludes"));
            sources.add(new Source(name, path, sourceExcludes, matcher));
        }

        return new ClientConfig(configFile, serverUrl, token, tokenFile, machineName, stateDir,
                Collections.unmodifiableList(sources), excludes);
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    private static void checkKeys(JsonObject object, Set<String> allowed, String prefix) throws ConfigException {
        for (String key : object.keySet()) {
            if (!allowed.contains(key)) {
                List<String> sorted = new ArrayList<>(allowed);
                Collections.sort(sorted);
                throw new ConfigException("unknown key \"" + prefix + key + "\" (allowed: " + String.join(", ", sorted)
                        + ")");
            }
        }
    }

    private static String optionalString(JsonObject object, String key, String prefix) throws ConfigException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isString()) {
            throw new ConfigException("\"" + prefix + key + "\" must be a string");
        }
        return primitive.getAsString();
    }

    private static String requireString(JsonObject object, String key, String prefix) throws ConfigException {
        String value = optionalString(object, key, prefix);
        if (value == null) throw new ConfigException("\"" + prefix + key + "\" is required");
        if (value.isBlank()) throw new ConfigException("\"" + prefix + key + "\" must not be empty");
        return value;
    }

    private static List<String> optionalStringList(JsonObject object, String key, String prefix)
            throws ConfigException {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return List.of();
        if (!element.isJsonArray()) throw new ConfigException("\"" + prefix + key + "\" must be a list of strings");
        List<String> result = new ArrayList<>();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (!(item instanceof JsonPrimitive primitive) || !primitive.isString()) {
                throw new ConfigException("\"" + prefix + key + "[" + i + "]\" must be a string");
            }
            result.add(primitive.getAsString());
        }
        return Collections.unmodifiableList(result);
    }

    private static ExcludeMatcher compileExcludes(List<String> globs, String where) throws ConfigException {
        try {
            return ExcludeMatcher.of(globs);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("\"" + where + "\": " + e.getMessage());
        }
    }

    private static String parseServerUrl(String value) throws ConfigException {
        URI uri;
        try {
            uri = new URI(value.strip());
        } catch (URISyntaxException e) {
            throw new ConfigException("\"serverUrl\" is not a valid URL: \"" + value + "\"");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ConfigException("\"serverUrl\" must start with http:// or https://, but was \"" + value + "\"");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new ConfigException("\"serverUrl\" has no host name: \"" + value + "\"");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new ConfigException("\"serverUrl\" must not have a user name, query or fragment: \"" + value
                    + "\"");
        }
        String url = uri.toString();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static void checkToken(String token, String what) throws ConfigException {
        if (token.isEmpty()) throw new ConfigException(what + " is empty");
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= 0x20 || c >= 0x7f) {
                throw new ConfigException(what + " must be printable ASCII without spaces");
            }
        }
    }

    private static Path resolvePath(Path baseDir, String value, String key) throws ConfigException {
        if (value.isBlank()) throw new ConfigException("\"" + key + "\" must not be empty");
        try {
            Path path = Paths.get(value);
            return (baseDir == null ? path : baseDir.resolve(path)).normalize();
        } catch (InvalidPathException e) {
            throw new ConfigException("\"" + key + "\" is not a valid path: \"" + value + "\"");
        }
    }

    /**
     * The file this config was read from.
     */
    public Path getConfigFile() {
        return configFile;
    }

    /**
     * The server's base URL, without a trailing {@code /}.
     */
    public String getServerUrl() {
        return serverUrl;
    }

    /**
     * The token, read from {@link #getTokenFile()} if the config named one. Never print it.
     */
    public String getToken() {
        return token;
    }

    /**
     * The file the token was read from, or {@code null} if it was given inline.
     */
    public Path getTokenFile() {
        return tokenFile;
    }

    public String getMachineName() {
        return machineName;
    }

    public Path getStateDir() {
        return stateDir;
    }

    public List<Source> getSources() {
        return sources;
    }

    /**
     * The global exclude patterns.
     */
    public List<String> getExcludes() {
        return excludes;
    }

    /**
     * A config file that can't be read or isn't valid.
     */
    public static final class ConfigException extends Exception {
        public ConfigException(String message) {
            super(message);
        }
    }
}
