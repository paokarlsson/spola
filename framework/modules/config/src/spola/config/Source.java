package spola.config;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Where configuration values come from. Each source is a value created explicitly in code and
 * added to a {@link Config.Builder} in the order of priority: a later source wins over an
 * earlier one for the same key. A source is read when {@link Config.Builder#build()} runs, not
 * when it is created.
 *
 * <p>The kinds of sources:
 * <ul>
 *   <li>{@link #map} – a fixed {@code Map<String,String>}, for defaults and tests;</li>
 *   <li>{@link #file} and {@link #optionalFile} – a {@link java.util.Properties} file read as
 *       UTF-8;</li>
 *   <li>{@link #environment(String)} and {@link #environmentTable(Map)} – environment
 *       variables, by prefix or by an explicit table (there is no other "relaxed binding");</li>
 *   <li>{@link #systemProperties()};</li>
 *   <li>{@link #arguments} – command-line arguments of the form {@code --key=value}.</li>
 * </ul>
 * The environment variants that take an extra {@code Map} read that map instead of the
 * process environment, so tests do not depend on the real environment.
 */
public final class Source {

    /** Reads a source; adds what went wrong to {@code problems} and returns what it could read. */
    @FunctionalInterface
    interface Loader {
        Map<String, String> load(List<ConfigProblem> problems);
    }

    private final String description;
    private final Loader loader;

    private Source(String description, Loader loader) {
        this.description = description;
        this.loader = loader;
    }

    /**
     * A human-readable description, used in error messages and in {@link Config#describe()} to
     * say where a value came from, e.g. {@code file /etc/app.properties} or
     * {@code environment (prefix APP_)}.
     *
     * @return the description
     */
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return description;
    }

    Map<String, String> load(List<ConfigProblem> problems) {
        return loader.load(problems);
    }

    /**
     * A fixed set of values, for defaults and tests. The map is copied when the source is
     * created.
     *
     * @param name a name for the source, shown as {@code map <name>}
     * @param values the values; keys must not be blank
     * @return the source
     * @throws NullPointerException if the name, the map or any key or value is null
     * @throws IllegalArgumentException if a key is blank
     */
    public static Source map(String name, Map<String, String> values) {
        Objects.requireNonNull(name, "name");
        var copy = new TreeMap<>(Map.copyOf(values));
        copy.keySet().forEach(Source::checkKey);
        var frozen = java.util.Collections.unmodifiableMap(copy);
        return new Source("map " + name, _ -> frozen);
    }

    /**
     * A required properties file. It is read as UTF-8 with {@link Properties#load(java.io.Reader)}
     * when the configuration is built. A UTF-8 byte order mark at the start of the file is
     * skipped. A missing or unreadable file is a
     * {@link ConfigProblem.SourceError} whose message contains the path.
     *
     * @param path the file
     * @return the source
     */
    public static Source file(Path path) {
        var absolute = path.toAbsolutePath().normalize();
        return new Source("file " + absolute, problems -> readProperties(absolute, false, problems));
    }

    /**
     * An optional properties file: like {@link #file}, but a file that does not exist is not an
     * error and gives no values. A file that exists but cannot be read or parsed is still an
     * error.
     *
     * @param path the file
     * @return the source
     */
    public static Source optionalFile(Path path) {
        var absolute = path.toAbsolutePath().normalize();
        return new Source("optional file " + absolute, problems -> readProperties(absolute, true, problems));
    }

    private static Map<String, String> readProperties(Path path, boolean optional, List<ConfigProblem> problems) {
        var description = (optional ? "optional file " : "file ") + path;
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            skipByteOrderMark(reader);
            properties.load(reader);
        } catch (NoSuchFileException e) {
            if (!optional) {
                problems.add(new ConfigProblem.SourceError(description, "file not found: " + path));
            }
            return Map.of();
        } catch (CharacterCodingException e) {
            problems.add(new ConfigProblem.SourceError(description, "not valid UTF-8: " + path));
            return Map.of();
        } catch (IOException | IllegalArgumentException e) {
            problems.add(new ConfigProblem.SourceError(description, "cannot read " + path + ": " + e));
            return Map.of();
        }
        var values = new TreeMap<String, String>();
        for (var key : properties.stringPropertyNames()) {
            if (key.isBlank()) {
                problems.add(new ConfigProblem.SourceError(description, "blank key"));
            } else {
                values.put(key, properties.getProperty(key));
            }
        }
        return values;
    }

    /**
     * Skips a UTF-8 byte order mark (U+FEFF) at the very start of the file. Some Windows editors
     * write one; without this it would become part of the first key, and that setting would be
     * silently ignored.
     */
    private static void skipByteOrderMark(java.io.BufferedReader reader) throws IOException {
        reader.mark(1);
        if (reader.read() != '\uFEFF') {
            reader.reset();
        }
    }

    /**
     * The process environment, by prefix. The rule is exact: a variable is read only if its
     * name starts with {@code prefix} (case-sensitive); the prefix is removed, the rest is
     * lower-cased ({@link Locale#ROOT}) and every {@code _} becomes {@code .}. So with prefix
     * {@code APP_}, {@code APP_DB_POOL_SIZE} becomes {@code db.pool.size}. Variables without
     * the prefix are not read, and a variable that is exactly the prefix is ignored. Two
     * variables that give the same key (e.g. {@code APP_DB_URL} and {@code APP_db_url}) are a
     * {@link ConfigProblem.SourceError}.
     *
     * @param prefix the prefix, not blank
     * @return the source
     * @throws IllegalArgumentException if the prefix is blank (that would read every variable)
     */
    public static Source environment(String prefix) {
        return environment(prefix, System::getenv);
    }

    /**
     * Like {@link #environment(String)}, but reads the given map instead of the process
     * environment. The map is read when the configuration is built.
     *
     * @param prefix the prefix, not blank
     * @param environment the variables, name to value
     * @return the source
     * @throws IllegalArgumentException if the prefix is blank
     */
    public static Source environment(String prefix, Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return environment(prefix, () -> environment);
    }

    private static Source environment(String prefix, Supplier<Map<String, String>> environment) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("the environment prefix must not be blank");
        }
        var description = "environment (prefix " + prefix + ")";
        return new Source(description, problems -> {
            var values = new TreeMap<String, String>();
            var origin = new TreeMap<String, String>();
            for (var variable : new TreeMap<>(environment.get()).entrySet()) {
                var name = variable.getKey();
                if (!name.startsWith(prefix) || name.length() == prefix.length()) {
                    continue;
                }
                var key = name.substring(prefix.length()).toLowerCase(Locale.ROOT).replace('_', '.');
                var earlier = origin.putIfAbsent(key, name);
                if (earlier != null) {
                    problems.add(new ConfigProblem.SourceError(description, "variables " + earlier + " and "
                            + name + " both give key '" + key + "'"));
                    continue;
                }
                values.put(key, variable.getValue());
            }
            return values;
        });
    }

    /**
     * The process environment, by an explicit table from variable name to key, e.g.
     * {@code Map.of("DATABASE_URL", "db.url")}. Only the variables in the table are read;
     * a variable in the table that is not set gives no value.
     *
     * @param variableToKey variable name to configuration key; two variables must not map to
     *     the same key
     * @return the source
     * @throws IllegalArgumentException if a key is blank or two variables map to the same key
     */
    public static Source environmentTable(Map<String, String> variableToKey) {
        return environmentTable(variableToKey, System::getenv);
    }

    /**
     * Like {@link #environmentTable(Map)}, but reads the given map instead of the process
     * environment. The map is read when the configuration is built.
     *
     * @param variableToKey variable name to configuration key
     * @param environment the variables, name to value
     * @return the source
     * @throws IllegalArgumentException if a key is blank or two variables map to the same key
     */
    public static Source environmentTable(Map<String, String> variableToKey, Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return environmentTable(variableToKey, () -> environment);
    }

    private static Source environmentTable(Map<String, String> variableToKey,
            Supplier<Map<String, String>> environment) {
        var table = new TreeMap<>(Map.copyOf(variableToKey));
        var keys = new TreeMap<String, String>();
        table.forEach((variable, key) -> {
            checkKey(key);
            var earlier = keys.putIfAbsent(key, variable);
            if (earlier != null) {
                throw new IllegalArgumentException("variables " + earlier + " and " + variable
                        + " both map to key '" + key + "'");
            }
        });
        return new Source("environment (table)", _ -> {
            var env = environment.get();
            var values = new TreeMap<String, String>();
            table.forEach((variable, key) -> {
                var value = env.get(variable);
                if (value != null) {
                    values.put(key, value);
                }
            });
            return values;
        });
    }

    /**
     * The JVM's system properties ({@link System#getProperties()}), every property under its
     * own name, read when the configuration is built.
     *
     * @return the source
     */
    public static Source systemProperties() {
        return new Source("system properties", _ -> {
            var properties = System.getProperties();
            var values = new TreeMap<String, String>();
            for (var name : properties.stringPropertyNames()) {
                var value = properties.getProperty(name);
                if (value != null && !name.isBlank()) {
                    values.put(name, value);
                }
            }
            return values;
        });
    }

    /**
     * Command-line arguments. Each argument of the form {@code --key=value} gives a value
     * (the value may be empty and may contain {@code =}). Arguments that do not start with
     * {@code --} are left to the application and ignored, and so is everything after a lone
     * {@code --}. An argument {@code --key} without {@code =}, or with an empty key, is a
     * {@link ConfigProblem.SourceError}. If a key is given twice, the later argument wins.
     *
     * @param args the arguments, typically those of {@code main}; copied when the source is
     *     created
     * @return the source
     */
    public static Source arguments(String... args) {
        return arguments(List.of(args));
    }

    /**
     * Like {@link #arguments(String...)}, for a list.
     *
     * @param args the arguments; copied when the source is created
     * @return the source
     */
    public static Source arguments(List<String> args) {
        var copy = List.copyOf(args);
        var description = "arguments";
        return new Source(description, problems -> {
            var values = new TreeMap<String, String>();
            for (var position = 0; position < copy.size(); position++) {
                var arg = copy.get(position);
                if (arg.equals("--")) {
                    break;
                }
                if (!arg.startsWith("--")) {
                    continue;
                }
                var equals = arg.indexOf('=');
                if (equals < 0) {
                    problems.add(new ConfigProblem.SourceError(description,
                            "argument '" + arg + "' has no '=value'"));
                    continue;
                }
                var key = arg.substring(2, equals);
                if (key.isBlank()) {
                    problems.add(new ConfigProblem.SourceError(description,
                            "argument at position " + position + " has an empty key"));
                    continue;
                }
                values.put(key, arg.substring(equals + 1));
            }
            return values;
        });
    }

    static String checkKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("a configuration key must not be blank");
        }
        return key;
    }
}
