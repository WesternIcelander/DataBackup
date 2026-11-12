package io.siggi.databackup.client;

import io.siggi.databackup.client.Arguments.UsageException;
import io.siggi.databackup.client.ClientConfig.ConfigException;
import io.siggi.databackup.client.api.ServerApi;
import io.siggi.databackup.client.snapshot.SnapshotPlatform;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * The client's command line: {@code backup}, {@code list}, {@code restore}, {@code snapshots cleanup} and
 * {@code help}. {@code --config <file>} (before or after the command) chooses the config file; the default depends
 * on the OS (see {@link ClientPaths}).
 */
final class ClientCli {

    static final int OK = 0;
    static final int FAILED = 1;
    /**
     * A usage error or an invalid config.
     */
    static final int CONFIG_ERROR = 2;

    static final String USAGE_TEXT = """
            Usage: databackup [--config <file>] <command> [arguments]

            Commands:
              backup                                  Snapshot the configured sources and back them up.
              list                                    List the machines this token can read.
              list <machine>                          List a machine's backups.
              list <machine> <backup> [path]          List a directory inside a backup ("latest" works as a
                                                      backup name).
              restore <machine> <backup> <path-in-backup> <destination> [--overwrite]
                                                      Restore a file or directory from a backup.
              snapshots cleanup                       Delete snapshots left behind by an interrupted backup.
              help                                    Show this help.

            Options:
              --config <file>                         The config file (default: %s).

            Exit codes: 0 success, 1 failure, 2 usage or config error.
            """;

    private ClientCli() {
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, ClientPaths.defaults(), out, err);
    }

    static int run(String[] args, ClientPaths defaults, PrintStream out, PrintStream err) {
        return run(args, defaults, SnapshotPlatform.system(), out, err);
    }

    static int run(String[] args, ClientPaths defaults, SnapshotPlatform platform, PrintStream out,
                   PrintStream err) {
        return run(args, defaults, platform, ServerApi::forConfig, out, err);
    }

    /**
     * @param apiFactory makes the server client from the loaded config (tests pass one with short retries)
     */
    static int run(String[] args, ClientPaths defaults, SnapshotPlatform platform,
                   Function<ClientConfig, ServerApi> apiFactory, PrintStream out, PrintStream err) {
        String config = null;
        int i = 0;
        // Options before the command.
        while (i < args.length && args[i].startsWith("-")) {
            String arg = args[i];
            if (arg.equals("--help") || arg.equals("-h")) {
                out.print(usageText(defaults));
                return OK;
            } else if (arg.equals("--config")) {
                if (i + 1 >= args.length) return usage(err, defaults, "--config needs a value");
                if (config != null) return usage(err, defaults, "--config given more than once");
                config = args[i + 1];
                i += 2;
            } else if (arg.startsWith("--config=")) {
                if (config != null) return usage(err, defaults, "--config given more than once");
                config = arg.substring("--config=".length());
                i++;
            } else {
                return usage(err, defaults, "Unknown option: " + arg);
            }
        }
        if (i >= args.length) return usage(err, defaults, "No command given.");
        String command = args[i];
        List<String> rest = List.of(args).subList(i + 1, args.length);

        Set<String> flagOptions = command.equals("restore") ? Set.of("overwrite") : Set.of();
        Arguments parsed;
        try {
            parsed = Arguments.parse(rest, Set.of("config"), flagOptions);
        } catch (UsageException e) {
            return usage(err, defaults, e.getMessage());
        }
        if (parsed.option("config") != null) {
            if (config != null) return usage(err, defaults, "--config given more than once");
            config = parsed.option("config");
        }
        List<String> positionals = parsed.positionals();

        switch (command) {
            case "help":
                if (positionals.size() > 1) return usage(err, defaults, "help takes at most one argument");
                out.print(usageText(defaults));
                return OK;
            case "backup":
                if (!positionals.isEmpty()) return usage(err, defaults, "backup takes no arguments");
                break;
            case "list":
                if (positionals.size() > 3) return usage(err, defaults, "list takes at most 3 arguments");
                break;
            case "restore":
                if (positionals.size() != 4) {
                    return usage(err, defaults,
                            "restore needs <machine> <backup> <path-in-backup> <destination>");
                }
                break;
            case "snapshots":
                if (positionals.size() != 1 || !positionals.get(0).equals("cleanup")) {
                    return usage(err, defaults, "snapshots needs a subcommand: cleanup");
                }
                break;
            default:
                return usage(err, defaults, "Unknown command: " + command);
        }

        ClientConfig clientConfig;
        try {
            clientConfig = loadConfig(config, defaults);
        } catch (ConfigException e) {
            err.println("Error: " + e.getMessage());
            return CONFIG_ERROR;
        }

        switch (command) {
            case "backup":
                return BackupCommand.run(clientConfig, platform, apiFactory, out, err);
            case "list":
                return ListCommand.run(clientConfig, positionals, apiFactory, out, err);
            case "snapshots":
                return SnapshotCommands.cleanup(clientConfig, platform, out, err);
            default:
                return notImplemented(command, clientConfig, err);
        }
    }

    static ClientConfig loadConfig(String configArg, ClientPaths defaults) throws ConfigException {
        Path configFile;
        if (configArg == null) {
            configFile = defaults.configFile();
        } else {
            if (configArg.isBlank()) throw new ConfigException("--config must not be empty");
            try {
                configFile = Paths.get(configArg);
            } catch (InvalidPathException e) {
                throw new ConfigException("--config is not a valid path: " + configArg);
            }
        }
        return ClientConfig.load(configFile, defaults.stateDir());
    }

    private static int notImplemented(String command, ClientConfig config, PrintStream err) {
        err.println("The " + command + " command is not implemented yet (config " + config.getConfigFile()
                + " is valid).");
        return FAILED;
    }

    private static String usageText(ClientPaths defaults) {
        return String.format(USAGE_TEXT, defaults.configFile());
    }

    private static int usage(PrintStream err, ClientPaths defaults, String message) {
        err.println(message);
        err.println();
        err.print(usageText(defaults));
        return CONFIG_ERROR;
    }
}
