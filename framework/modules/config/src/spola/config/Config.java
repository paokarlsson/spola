package spola.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * A merged, resolved and immutable configuration: every key with its value and the source the
 * value came from. Built with {@link #builder()}; bound to records with {@link #bind}.
 *
 * <pre>{@code
 * Config config = Config.builder()
 *         .add(Source.map("defaults", Map.of("db.pool.size", "10")))
 *         .add(Source.file(Path.of("config/app.properties")))
 *         .activeProfilesFrom("app.profiles")
 *         .addForEachProfile(p -> Source.optionalFile(Path.of("config/app-" + p + ".properties")))
 *         .add(Source.environment("APP_"))
 *         .add(Source.arguments(args))
 *         .sensitive("db.password")
 *         .build();
 * DbConfig db = config.bind("db", c -> new DbConfig(
 *         c.string("url"), c.intValue("pool.size"), c.duration("timeout", Duration.ofSeconds(5))));
 * }</pre>
 *
 * <p><b>Placeholders.</b> After all sources are merged, {@code ${key}} in a value is replaced
 * by the value of {@code key} and {@code ${key:default}} by the value of {@code key} or, if no
 * source has the key, by {@code default} (which may itself contain placeholders). Write
 * <code>$${</code> for a literal <code>${</code>: {@code price=$${amount}} gives the value
 * {@code ${amount}}. A cycle ({@code a=${b}}, {@code b=${a}}) is an error that shows the whole
 * chain.
 *
 * <p><b>Sensitive keys</b> ({@link Builder#sensitive(String...)}) are shown as {@link #MASK}
 * in {@link #describe()}, {@link #toString()} and every error message. A value built from a
 * sensitive value through a placeholder is sensitive too.
 *
 * <p>A {@code Config} never changes after it is built and is safe to read from any thread.
 */
public final class Config {

    /** What is shown instead of the value of a sensitive key. */
    public static final String MASK = "******";

    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    /** A resolved value, the source it came from and whether it must be masked. */
    record Entry(String value, String source, boolean sensitive) {
    }

    private final Map<String, Entry> entries;
    private final List<String> profiles;

    private Config(Map<String, Entry> entries, List<String> profiles) {
        this.entries = Collections.unmodifiableMap(new TreeMap<>(entries));
        this.profiles = List.copyOf(profiles);
    }

    /**
     * Starts a new configuration.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The value of a key, after placeholders are resolved.
     *
     * @param key the full key
     * @return the value, or empty if no source has the key
     */
    public Optional<String> get(String key) {
        var entry = entries.get(Objects.requireNonNull(key, "key"));
        return entry == null ? Optional.empty() : Optional.of(entry.value());
    }

    /**
     * The description of the source whose value won for a key.
     *
     * @param key the full key
     * @return the source's description, or empty if no source has the key
     */
    public Optional<String> sourceOf(String key) {
        var entry = entries.get(Objects.requireNonNull(key, "key"));
        return entry == null ? Optional.empty() : Optional.of(entry.source());
    }

    /**
     * Whether a key's value is masked: the key was marked sensitive, or its value was built
     * from a sensitive value through a placeholder.
     *
     * @param key the full key
     * @return whether the value is masked
     */
    public boolean isSensitive(String key) {
        var entry = entries.get(Objects.requireNonNull(key, "key"));
        return entry != null && entry.sensitive();
    }

    /**
     * Every key, sorted.
     *
     * @return the keys
     */
    public Set<String> keys() {
        return entries.keySet();
    }

    /**
     * The active profiles, in the order they were applied (a later profile wins).
     *
     * @return the profiles, empty if none are active
     */
    public List<String> profiles() {
        return profiles;
    }

    Entry entry(String key) {
        return entries.get(key);
    }

    /**
     * A diagnostic dump of the merged configuration: the active profiles, then one line per
     * key, sorted, as {@code key = value  [source]}, where the source is the one whose value
     * won. Sensitive values are shown as {@link #MASK}. Line breaks in values are shown as
     * {@code \n} and {@code \r}.
     *
     * @return the dump
     */
    public String describe() {
        var text = new StringBuilder("active profiles: ")
                .append(profiles.isEmpty() ? "(none)" : String.join(", ", profiles));
        entries.forEach((key, entry) -> text.append('\n').append(key).append(" = ")
                .append(entry.sensitive() ? MASK : printable(entry.value()))
                .append("  [").append(entry.source()).append(']'));
        return text.toString();
    }

    /**
     * The same as {@link #describe()}.
     *
     * @return the dump, with sensitive values masked
     */
    @Override
    public String toString() {
        return describe();
    }

    /**
     * Binds part of the configuration to a value, typically a record, with a function the
     * application writes:
     * {@code config.bind("db", c -> new DbConfig(c.string("url"), c.intValue("pool.size", 10)))}.
     *
     * <p>Every value the function asks for is checked. A missing key or an invalid value does
     * not stop the function: the {@link Section} records the problem and returns a stand-in
     * ({@code null}, {@code 0}, {@code false}, an empty list or {@code Optional}, or the
     * default given), and when the function returns, all recorded problems are thrown together
     * in one {@link ConfigException}. If the function throws (for example because a record
     * constructor rejects a stand-in {@code null}) after problems were recorded, the
     * {@code ConfigException} is thrown and the function's exception is dropped: it is most
     * likely a consequence of a stand-in, and its message could repeat a configuration value
     * (even a sensitive one). If no problems were recorded, the function's exception
     * propagates unchanged.
     *
     * <p>To get every problem of an application in one report, bind one root record whose
     * parts are bound with {@link Section#section}.
     *
     * @param <T> the type of the bound value
     * @param prefix the prefix of every key the function asks for ({@code "db"} turns
     *     {@code "url"} into {@code "db.url"}); empty for the root
     * @param binder the function that builds the value from a {@link Section}
     * @return what the function returned
     * @throws ConfigException with every missing key and invalid value
     */
    public <T> T bind(String prefix, Function<? super Section, ? extends T> binder) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(binder, "binder");
        var problems = Collections.synchronizedList(new ArrayList<ConfigProblem>());
        var section = new Section(this, prefix, problems);
        T result;
        try {
            result = binder.apply(section);
        } catch (RuntimeException e) {
            var found = List.copyOf(problems);
            if (found.isEmpty()) {
                throw e;
            }
            throw new ConfigException(what(prefix), found);
        } finally {
            section.close();
        }
        var found = List.copyOf(problems);
        if (!found.isEmpty()) {
            throw new ConfigException(what(prefix), found);
        }
        return result;
    }

    private static String what(String prefix) {
        return prefix.isEmpty() ? "the configuration binding" : "the configuration binding of '" + prefix + "'";
    }

    static String printable(String value) {
        return value.replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * Collects sources in priority order (a later source wins) and builds a {@link Config}.
     * Not thread-safe; {@link #build()} can be called more than once and reads the sources
     * again each time.
     *
     * <p><b>Profiles.</b> Active profiles are given either in code ({@link #activeProfiles}) or
     * as a comma-separated value of a key ({@link #activeProfilesFrom}). Profile sources are
     * added with {@link #addForEachProfile}, which takes the application's function from a
     * profile name to a source; no file names are built in. They take their place in the
     * priority order exactly where {@code addForEachProfile} is called, one source per active
     * profile, in the order the profiles are listed, so a later profile wins over an earlier
     * one.
     */
    public static final class Builder {

        private sealed interface Step permits Plain, PerProfile {
        }

        private record Plain(Source source) implements Step {
        }

        private record PerProfile(Function<String, Source> sourceFor) implements Step {
        }

        private final List<Step> steps = new ArrayList<>();
        private final Set<String> sensitiveKeys = new LinkedHashSet<>();
        private final List<Predicate<String>> sensitivePredicates = new ArrayList<>();
        private List<String> fixedProfiles;
        private String profileKey;

        private Builder() {
        }

        /**
         * Adds a source. It wins over every source added before it.
         *
         * @param source the source
         * @return this builder
         */
        public Builder add(Source source) {
            steps.add(new Plain(Objects.requireNonNull(source, "source")));
            return this;
        }

        /**
         * Sets the active profiles in code. At most one of {@code activeProfiles} and
         * {@link #activeProfilesFrom} may be called. A profile name is letters, digits,
         * {@code _}, {@code .} and {@code -}, starting with a letter or digit, and is not
         * repeated.
         *
         * @param profiles the profiles, in the order they are applied
         * @return this builder
         * @throws IllegalArgumentException if a name is invalid or repeated
         * @throws IllegalStateException if the active profiles are already set
         */
        public Builder activeProfiles(String... profiles) {
            checkProfilesUnset();
            var names = List.of(profiles);
            for (var name : names) {
                if (!PROFILE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("invalid profile name '" + name + "'");
                }
            }
            if (new LinkedHashSet<>(names).size() != names.size()) {
                throw new IllegalArgumentException("a profile is listed twice: " + names);
            }
            fixedProfiles = names;
            return this;
        }

        /**
         * Reads the active profiles from a key, e.g. {@code app.profiles} given as
         * {@code --app.profiles=dev,local}. The value is looked up in the sources added with
         * {@link #add} (all of them, whatever their position, merged by the usual priority);
         * profile sources cannot activate profiles, and a profile source that sets this key
         * is an error. The value is split on commas, each name is trimmed, empty names are
         * dropped; a name that is invalid (see {@link #activeProfiles}) or repeated is a
         * {@link ConfigProblem.InvalidValue}. Placeholders in this value are not resolved. If
         * no source has the key, no profile is active.
         *
         * @param key the key holding the comma-separated profile names
         * @return this builder
         * @throws IllegalStateException if the active profiles are already set
         */
        public Builder activeProfilesFrom(String key) {
            checkProfilesUnset();
            profileKey = Source.checkKey(key);
            return this;
        }

        private void checkProfilesUnset() {
            if (fixedProfiles != null || profileKey != null) {
                throw new IllegalStateException("the active profiles are already set");
            }
        }

        /**
         * Adds one source per active profile, at this position in the priority order: they win
         * over every source added before this call and lose to every source added after it.
         * The source of a later profile wins over that of an earlier one. The function is
         * called once per active profile each time the configuration is built, and must not
         * return {@code null}. Typical use:
         * {@code addForEachProfile(p -> Source.optionalFile(Path.of("config/app-" + p + ".properties")))}.
         *
         * @param sourceForProfile the application's function from a profile name to its source
         * @return this builder
         */
        public Builder addForEachProfile(Function<String, Source> sourceForProfile) {
            steps.add(new PerProfile(Objects.requireNonNull(sourceForProfile, "sourceForProfile")));
            return this;
        }

        /**
         * Marks keys as sensitive: their values are masked in error messages and in
         * {@link Config#describe()}.
         *
         * @param keys full keys, e.g. {@code db.password}
         * @return this builder
         */
        public Builder sensitive(String... keys) {
            for (var key : keys) {
                sensitiveKeys.add(Source.checkKey(key));
            }
            return this;
        }

        /**
         * Marks every key the predicate accepts as sensitive, e.g.
         * {@code key -> key.endsWith(".password")}. Can be combined with
         * {@link #sensitive(String...)}; a key is sensitive if any rule says so.
         *
         * @param isSensitive the rule, given the full key
         * @return this builder
         */
        public Builder sensitive(Predicate<String> isSensitive) {
            sensitivePredicates.add(Objects.requireNonNull(isSensitive, "isSensitive"));
            return this;
        }

        /**
         * Reads every source, merges them in priority order, resolves the placeholders and
         * returns the configuration.
         *
         * @return the configuration
         * @throws ConfigException with every problem found in the sources, the profiles and the
         *     placeholders
         */
        public Config build() {
            var problems = new ArrayList<ConfigProblem>();
            var loaded = new LinkedHashMap<Plain, Map<String, String>>();
            for (var step : steps) {
                if (step instanceof Plain plain && !loaded.containsKey(plain)) {
                    loaded.put(plain, plain.source().load(problems));
                }
            }
            var profiles = profiles(loaded, problems);

            var merged = new TreeMap<String, Placeholders.Raw>();
            for (var step : steps) {
                switch (step) {
                    case Plain plain -> put(merged, loaded.get(plain), plain.source());
                    case PerProfile perProfile -> {
                        for (var profile : profiles) {
                            var source = Objects.requireNonNull(perProfile.sourceFor().apply(profile),
                                    () -> "the profile source function returned null for profile " + profile);
                            var values = source.load(problems);
                            if (profileKey != null && values.containsKey(profileKey)) {
                                problems.add(new ConfigProblem.SourceError(source.description(),
                                        "sets the profile key '" + profileKey
                                                + "', but profile sources cannot activate profiles"));
                            }
                            put(merged, values, source);
                        }
                    }
                }
            }

            Predicate<String> sensitive = this::isSensitive;
            var resolved = Placeholders.resolveAll(merged, sensitive, problems);
            if (!problems.isEmpty()) {
                throw new ConfigException("the configuration", problems);
            }
            var entries = new TreeMap<String, Entry>();
            resolved.forEach((key, value) ->
                    entries.put(key, new Entry(value.value(), merged.get(key).source(), value.tainted())));
            return new Config(entries, profiles);
        }

        private boolean isSensitive(String key) {
            if (sensitiveKeys.contains(key)) {
                return true;
            }
            for (var predicate : sensitivePredicates) {
                if (predicate.test(key)) {
                    return true;
                }
            }
            return false;
        }

        private List<String> profiles(Map<Plain, Map<String, String>> loaded, List<ConfigProblem> problems) {
            if (fixedProfiles != null) {
                return fixedProfiles;
            }
            if (profileKey == null) {
                return List.of();
            }
            String value = null;
            String source = null;
            for (var step : steps) {
                if (step instanceof Plain plain && loaded.get(plain).containsKey(profileKey)) {
                    value = loaded.get(plain).get(profileKey);
                    source = plain.source().description();
                }
            }
            if (value == null) {
                return List.of();
            }
            var names = new ArrayList<String>();
            for (var part : value.split(",", -1)) {
                var name = part.strip();
                if (name.isEmpty()) {
                    continue;
                }
                var shown = isSensitive(profileKey) ? MASK : name;
                if (!PROFILE_NAME.matcher(name).matches()) {
                    problems.add(new ConfigProblem.InvalidValue(profileKey, "profile list", source, shown,
                            "invalid profile name"));
                } else if (names.contains(name)) {
                    problems.add(new ConfigProblem.InvalidValue(profileKey, "profile list", source, shown,
                            "profile listed twice"));
                } else {
                    names.add(name);
                }
            }
            return names;
        }

        private static void put(Map<String, Placeholders.Raw> merged, Map<String, String> values, Source source) {
            values.forEach((key, value) -> merged.put(key, new Placeholders.Raw(value, source.description())));
        }
    }
}
