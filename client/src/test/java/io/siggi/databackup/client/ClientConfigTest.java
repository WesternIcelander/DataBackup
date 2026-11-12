package io.siggi.databackup.client;

import io.siggi.databackup.client.ClientConfig.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientConfigTest {

    @TempDir
    Path dir;

    private Path configFile() {
        return dir.resolve("client.json");
    }

    private Path defaultState() {
        return dir.resolve("default-state");
    }

    private ClientConfig parse(String json) throws ConfigException {
        return ClientConfig.parse(json.replace('\'', '"'), configFile(), defaultState());
    }

    private String sourcePath(String name) {
        return dir.resolve(name).toAbsolutePath().toString().replace("\\", "\\\\");
    }

    private String minimal(String extra) {
        return "{'serverUrl': 'http://localhost:8080/', 'token': 'abc123', 'machineName': 'laptop', "
                + "'sources': [{'name': 'Data', 'path': '" + sourcePath("src") + "'}]" + extra + "}";
    }

    private String errorOf(String json) {
        return assertThrows(ConfigException.class, () -> parse(json)).getMessage();
    }

    @Test
    void parsesFullConfig() throws Exception {
        ClientConfig config = parse("{'serverUrl': 'https://backup.example.com/base/', 'token': 'abc123', "
                + "'machineName': 'my-laptop.home', 'stateDir': 'state', "
                + "'sources': ["
                + "  {'name': 'Data', 'path': '" + sourcePath("data") + "', 'excludes': ['private/var/vm']},"
                + "  {'name': 'C', 'path': '" + sourcePath("c") + "'}],"
                + "'excludes': ['**/node_modules', '*.tmp']}");
        assertEquals("https://backup.example.com/base", config.getServerUrl());
        assertEquals("abc123", config.getToken());
        assertNull(config.getTokenFile());
        assertEquals("my-laptop.home", config.getMachineName());
        assertEquals(dir.toAbsolutePath().resolve("state"), config.getStateDir());
        assertEquals(List.of("**/node_modules", "*.tmp"), config.getExcludes());
        assertEquals(2, config.getSources().size());

        ClientConfig.Source data = config.getSources().get(0);
        assertEquals("Data", data.name());
        assertEquals(dir.resolve("data").toAbsolutePath(), data.path());
        assertEquals(List.of("private/var/vm"), data.excludes());
        assertTrue(data.matcher().matches("private/var/vm"));
        assertTrue(data.matcher().matches("a/node_modules"));
        assertTrue(data.matcher().matches("x.tmp"));
        assertFalse(data.matcher().matches("private/var"));

        ClientConfig.Source c = config.getSources().get(1);
        assertEquals(List.of(), c.excludes());
        assertFalse(c.matcher().matches("private/var/vm"));
        assertTrue(c.matcher().matches("node_modules"));
    }

    @Test
    void defaultsStateDir() throws Exception {
        ClientConfig config = parse(minimal(""));
        assertEquals(defaultState(), config.getStateDir());
        assertEquals("http://localhost:8080", config.getServerUrl());
        assertEquals(List.of(), config.getExcludes());
    }

    @Test
    void readsTokenFileRelativeToConfig() throws Exception {
        Files.writeString(dir.resolve("token.txt"), "  secret-token\n", StandardCharsets.UTF_8);
        String json = minimal("").replace("'token': 'abc123'", "'tokenFile': 'token.txt'");
        ClientConfig config = parse(json);
        assertEquals("secret-token", config.getToken());
        assertEquals(dir.toAbsolutePath().resolve("token.txt"), config.getTokenFile());
    }

    @Test
    void loadReadsFileAndNamesItInErrors() throws Exception {
        Files.writeString(configFile(), minimal("").replace('\'', '"'), StandardCharsets.UTF_8);
        ClientConfig config = ClientConfig.load(configFile(), defaultState());
        assertEquals(configFile(), config.getConfigFile());
        assertEquals("laptop", config.getMachineName());

        Files.writeString(configFile(), "{", StandardCharsets.UTF_8);
        String message = assertThrows(ConfigException.class, () -> ClientConfig.load(configFile(), defaultState()))
                .getMessage();
        assertTrue(message.contains(configFile().toString()), message);
        assertTrue(message.contains("not valid JSON"), message);

        message = assertThrows(ConfigException.class,
                () -> ClientConfig.load(dir.resolve("missing.json"), defaultState())).getMessage();
        assertTrue(message.startsWith("Config file not found: "), message);
        assertTrue(message.contains("--config"), message);
    }

    private void assertError(String json, String expectedPart) {
        String message = errorOf(json);
        assertTrue(message.contains(expectedPart), "expected \"" + expectedPart + "\" in: " + message);
    }

    @Test
    void rejectsInvalidJson() {
        assertError("[]", "must be a JSON object");
        assertError("{'serverUrl': ", "not valid JSON");
    }

    @Test
    void rejectsUnknownKeys() {
        assertError(minimal(", 'machinename': 'x'"), "unknown key \"machinename\"");
        assertError(minimal("").replace("'path':", "'exclude': [], 'path':"), "unknown key \"sources[0].exclude\"");
    }

    @Test
    void validatesServerUrl() {
        assertError(minimal("").replace("'serverUrl': 'http://localhost:8080/', ", ""), "\"serverUrl\" is required");
        assertError(minimal("").replace("http://localhost:8080/", "ftp://host/"), "http:// or https://");
        assertError(minimal("").replace("http://localhost:8080/", "localhost:8080"), "http:// or https://");
        assertError(minimal("").replace("http://localhost:8080/", "http://"), "serverUrl");
        assertError(minimal("").replace("http://localhost:8080/", "http://host/?a=b"), "query");
        assertError(minimal("").replace("http://localhost:8080/", "http://a b"), "not a valid URL");
        assertError(minimal("").replace("'http://localhost:8080/'", "8080"), "\"serverUrl\" must be a string");
    }

    @Test
    void validatesToken() throws Exception {
        String noToken = minimal("").replace("'token': 'abc123', ", "");
        assertError(noToken, "\"token\" or \"tokenFile\" is required");
        assertError(minimal(", 'tokenFile': 't.txt'"), "not both");
        assertError(minimal("").replace("abc123", "has space"), "printable ASCII");
        assertError(minimal("").replace("abc123", ""), "\"token\" is empty");
        assertError(minimal("").replace("'token': 'abc123'", "'tokenFile': 'missing.txt'"), "token file not found");
        Files.writeString(dir.resolve("empty.txt"), "\n", StandardCharsets.UTF_8);
        assertError(minimal("").replace("'token': 'abc123'", "'tokenFile': 'empty.txt'"), "is empty");
    }

    @Test
    void validatesMachineName() {
        assertError(minimal("").replace("'laptop'", "'.hidden'"), "\"machineName\" must be");
        assertError(minimal("").replace("'laptop'", "'a/b'"), "\"machineName\" must be");
        assertError(minimal("").replace("'laptop'", "'..'"), "\"machineName\" must be");
        assertError(minimal("").replace("'machineName': 'laptop', ", ""), "\"machineName\" is required");
    }

    @Test
    void validatesSources() {
        String base = "{'serverUrl': 'http://h', 'token': 't', 'machineName': 'm'";
        assertError(base + "}", "\"sources\" is required");
        assertError(base + ", 'sources': {}}", "\"sources\" must be a list");
        assertError(base + ", 'sources': []}", "at least one source");
        assertError(base + ", 'sources': ['x']}", "sources[0] must be an object");
        assertError(base + ", 'sources': [{'path': '" + sourcePath("a") + "'}]}", "\"sources[0].name\" is required");
        assertError(base + ", 'sources': [{'name': 'Data'}]}", "\"sources[0].path\" is required");
        assertError(base + ", 'sources': [{'name': 'a/b', 'path': '" + sourcePath("a") + "'}]}",
                "sources[0].name must be");
        assertError(base + ", 'sources': [{'name': 'D', 'path': 'relative/path'}]}", "must be an absolute path");
        assertError(base + ", 'sources': [{'name': 'Data', 'path': '" + sourcePath("a") + "'}, "
                + "{'name': 'data', 'path': '" + sourcePath("b") + "'}]}", "sources[1].name \"data\" is already used");
        assertError(base + ", 'sources': [{'name': 'A', 'path': '" + sourcePath("a") + "'}, "
                + "{'name': 'B', 'path': '" + sourcePath("a") + "'}]}", "is already the path of source \"A\"");
    }

    @Test
    void validatesExcludes() {
        assertError(minimal(", 'excludes': '*.tmp'"), "\"excludes\" must be a list of strings");
        assertError(minimal(", 'excludes': [1]"), "\"excludes[0]\" must be a string");
        assertError(minimal(", 'excludes': ['[abc']"), "unclosed [");
        assertError(minimal("").replace("'path':", "'excludes': ['{a'], 'path':"), "sources[0].excludes");
    }
}
