package io.siggi.databackup.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Matches paths relative to a backup source against glob patterns. Paths and patterns always use {@code /} as the
 * separator, on every OS, and a pattern must match the whole relative path (for example {@code Library/Caches} or
 * {@code **}{@code /node_modules}).
 *
 * <p>Glob syntax:
 * <ul>
 *     <li>{@code *} matches any run of characters except {@code /}</li>
 *     <li>{@code **} matches any run of characters including {@code /}; {@code **}{@code /} matches zero or more
 *     whole directories</li>
 *     <li>{@code ?} matches one character except {@code /}</li>
 *     <li>{@code [abc]}, {@code [a-z]}, {@code [!abc]} match one character from (or not from) a set</li>
 *     <li>{@code {a,b}} matches either alternative; alternatives may nest</li>
 *     <li>{@code \} makes the next character literal</li>
 * </ul>
 * A leading {@code /} and a trailing {@code /} are ignored.
 */
public final class ExcludeMatcher {

    private static final ExcludeMatcher NONE = new ExcludeMatcher(List.of(), List.of());

    private final List<String> globs;
    private final List<Pattern> patterns;

    private ExcludeMatcher(List<String> globs, List<Pattern> patterns) {
        this.globs = globs;
        this.patterns = patterns;
    }

    public static ExcludeMatcher none() {
        return NONE;
    }

    /**
     * Compiles the patterns.
     *
     * @throws IllegalArgumentException naming the first invalid pattern
     */
    public static ExcludeMatcher of(List<String> globs) {
        if (globs.isEmpty()) return NONE;
        List<Pattern> patterns = new ArrayList<>(globs.size());
        for (String glob : globs) {
            patterns.add(compile(glob));
        }
        return new ExcludeMatcher(Collections.unmodifiableList(new ArrayList<>(globs)), List.copyOf(patterns));
    }

    /**
     * Combines two matchers: the result matches what either one matches.
     */
    public ExcludeMatcher and(ExcludeMatcher other) {
        if (other.patterns.isEmpty()) return this;
        if (patterns.isEmpty()) return other;
        List<String> g = new ArrayList<>(globs);
        g.addAll(other.globs);
        List<Pattern> p = new ArrayList<>(patterns);
        p.addAll(other.patterns);
        return new ExcludeMatcher(Collections.unmodifiableList(g), List.copyOf(p));
    }

    public List<String> getGlobs() {
        return globs;
    }

    /**
     * Whether a path relative to the source, with {@code /} separators, matches any pattern.
     */
    public boolean matches(String relativePath) {
        String path = trimSlashes(relativePath);
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) return true;
        }
        return false;
    }

    private static String trimSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }

    /**
     * Converts one glob to a regular expression.
     *
     * @throws IllegalArgumentException if the glob is empty or malformed
     */
    static Pattern compile(String glob) {
        if (glob == null) throw new IllegalArgumentException("exclude pattern must not be null");
        String g = trimSlashes(glob);
        if (g.isEmpty()) throw new IllegalArgumentException("exclude pattern must not be empty: \"" + glob + "\"");
        StringBuilder regex = new StringBuilder();
        int braceDepth = 0;
        int i = 0;
        while (i < g.length()) {
            char c = g.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                        i += 2;
                        if (i < g.length() && g.charAt(i) == '/') {
                            i++;
                            regex.append("(?:.*/)?");
                        } else {
                            regex.append(".*");
                        }
                        continue;
                    }
                    regex.append("[^/]*");
                }
                case '?' -> regex.append("[^/]");
                case '[' -> {
                    i = appendClass(glob, g, i, regex);
                    continue;
                }
                case '{' -> {
                    braceDepth++;
                    regex.append("(?:");
                }
                case '}' -> {
                    if (braceDepth == 0) {
                        regex.append("\\}");
                    } else {
                        braceDepth--;
                        regex.append(')');
                    }
                }
                case ',' -> regex.append(braceDepth > 0 ? "|" : ",");
                case '\\' -> {
                    if (i + 1 >= g.length()) {
                        throw new IllegalArgumentException("exclude pattern ends with a lone \\: \"" + glob + "\"");
                    }
                    appendLiteral(regex, g.charAt(++i));
                }
                default -> appendLiteral(regex, c);
            }
            i++;
        }
        if (braceDepth > 0) throw new IllegalArgumentException("exclude pattern has an unclosed {: \"" + glob + "\"");
        return Pattern.compile(regex.toString());
    }

    private static int appendClass(String original, String g, int start, StringBuilder regex) {
        int i = start + 1;
        StringBuilder cls = new StringBuilder("[");
        if (i < g.length() && (g.charAt(i) == '!' || g.charAt(i) == '^')) {
            cls.append('^');
            i++;
        }
        boolean first = true;
        while (true) {
            if (i >= g.length()) {
                throw new IllegalArgumentException("exclude pattern has an unclosed [: \"" + original + "\"");
            }
            char c = g.charAt(i);
            if (c == ']' && !first) break;
            if (c == '/') {
                throw new IllegalArgumentException("exclude pattern has / inside [...]: \"" + original + "\"");
            }
            if (c == '\\') {
                if (i + 1 >= g.length()) {
                    throw new IllegalArgumentException("exclude pattern ends with a lone \\: \"" + original + "\"");
                }
                c = g.charAt(++i);
                if (Character.isLetterOrDigit(c)) {
                    cls.append(c);
                } else {
                    cls.append('\\').append(c);
                }
            } else if (c == '-' || Character.isLetterOrDigit(c)) {
                cls.append(c);
            } else {
                cls.append('\\').append(c);
            }
            first = false;
            i++;
        }
        cls.append("&&[^/]]");
        regex.append(cls);
        return i + 1;
    }

    private static void appendLiteral(StringBuilder regex, char c) {
        if (Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '-' || c == ' ') {
            regex.append(c);
        } else if (c < 0x80) {
            regex.append('\\').append(c);
        } else {
            regex.append(Pattern.quote(String.valueOf(c)));
        }
    }
}
