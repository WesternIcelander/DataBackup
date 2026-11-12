package io.siggi.databackup.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A small command-line parser: options ({@code --name value}, {@code --name=value}), flags ({@code --name}) and
 * positional arguments. {@code --} ends option parsing; everything after it is positional. A lone {@code -} is
 * positional.
 */
final class Arguments {

    private final Map<String, String> options;
    private final Set<String> flags;
    private final List<String> positionals;

    private Arguments(Map<String, String> options, Set<String> flags, List<String> positionals) {
        this.options = options;
        this.flags = flags;
        this.positionals = positionals;
    }

    /**
     * Thrown for an unknown option, a missing value, or an option given twice.
     */
    static final class UsageException extends Exception {
        UsageException(String message) {
            super(message);
        }
    }

    /**
     * Parses arguments.
     *
     * @param args          the arguments
     * @param valueOptions  options that take a value, without the leading {@code --}
     * @param flagOptions   options that take no value, without the leading {@code --}
     */
    static Arguments parse(List<String> args, Set<String> valueOptions, Set<String> flagOptions)
            throws UsageException {
        Map<String, String> options = new HashMap<>();
        Set<String> flags = new java.util.HashSet<>();
        List<String> positionals = new ArrayList<>();
        boolean onlyPositionals = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (onlyPositionals || !arg.startsWith("--")) {
                positionals.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                onlyPositionals = true;
                continue;
            }
            String name = arg.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            if (valueOptions.contains(name)) {
                if (value == null) {
                    if (i + 1 >= args.size()) throw new UsageException("--" + name + " needs a value");
                    value = args.get(++i);
                }
                if (options.putIfAbsent(name, value) != null) {
                    throw new UsageException("--" + name + " given more than once");
                }
            } else if (flagOptions.contains(name)) {
                if (value != null) throw new UsageException("--" + name + " takes no value");
                if (!flags.add(name)) throw new UsageException("--" + name + " given more than once");
            } else {
                throw new UsageException("Unknown option: --" + name);
            }
        }
        return new Arguments(Collections.unmodifiableMap(options), Collections.unmodifiableSet(flags),
                Collections.unmodifiableList(positionals));
    }

    String option(String name) {
        return options.get(name);
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    List<String> positionals() {
        return positionals;
    }
}
