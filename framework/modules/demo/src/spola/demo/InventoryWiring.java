package spola.demo;

import java.io.PrintStream;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import spola.config.Config;
import spola.config.Converter;
import spola.config.Section;
import spola.config.Source;
import spola.di.ContainerModule;
import spola.di.Key;
import spola.util.LoggingSettings;

/**
 * The program's wiring: where the configuration comes from, how it is bound to the settings
 * records and how the components are bound in the container. Together with
 * {@link InventoryApp} the only code that knows {@code Config} and the container; the
 * components get plain records and each other through their constructors.
 */
final class InventoryWiring {

    /** Environment variables starting with this are configuration: {@code INVENTORY_RULES_MAX_QUANTITY} is {@code rules.max.quantity}. */
    static final String ENVIRONMENT_PREFIX = "INVENTORY_";

    /** The key naming the configuration file. */
    static final String CONFIG_FILE_KEY = "config.file";

    /** The configuration file used when {@value #CONFIG_FILE_KEY} is not given. */
    static final String DEFAULT_CONFIG_FILE = "inventory.properties";

    /** The key holding the active profiles, comma-separated. */
    static final String PROFILES_KEY = "profiles";

    /** The defaults in code: the lowest priority. */
    static final Map<String, String> DEFAULTS = Map.of(
            CONFIG_FILE_KEY, DEFAULT_CONFIG_FILE,
            "rules.max.quantity", "100",
            "logging.level.root", "INFO");

    private static final Converter<Integer> POSITIVE_INT = intAtLeast(1);

    private static final Converter<Integer> STOCK_LEVEL = intAtLeast(0);

    private static final Converter<String> SECRET = new Converter<>("secret of at least 16 characters", text -> {
        if (text.length() < 16) {
            throw new IllegalArgumentException("too short");
        }
        return text;
    });

    private InventoryWiring() {
    }

    /** A whole number of at least {@code min}, whitespace around it allowed. */
    private static Converter<Integer> intAtLeast(int min) {
        return new Converter<>("int of at least " + min, text -> {
            var value = Integer.parseInt(text.strip());
            if (value < min) {
                throw new IllegalArgumentException("must be at least " + min);
            }
            return value;
        });
    }

    /**
     * Reads the configuration in the order the README of {@code spola.config} recommends:
     * defaults in code, the file, one file per active profile next to it
     * ({@code inventory-<profile>.properties}), environment variables with the prefix
     * {@value #ENVIRONMENT_PREFIX}, then arguments ({@code --key=value}). A later source wins.
     *
     * <p>The file's location is itself configuration ({@value #CONFIG_FILE_KEY}, from the
     * defaults, the environment or an argument), so it is read in a first, smaller round.
     */
    static Config config(List<String> args, Map<String, String> environment) {
        var bootstrap = Config.builder()
                .add(Source.map("defaults", DEFAULTS))
                .add(Source.environment(ENVIRONMENT_PREFIX, environment))
                .add(Source.arguments(args))
                .sensitive(InventoryWiring::isSensitive)
                .build();
        Path file = bootstrap.bind("", c -> c.path(CONFIG_FILE_KEY));
        return Config.builder()
                .add(Source.map("defaults", DEFAULTS))
                .add(Source.file(file))
                .activeProfilesFrom(PROFILES_KEY)
                .addForEachProfile(profile -> Source.optionalFile(
                        file.resolveSibling("inventory-" + profile + ".properties")))
                .add(Source.environment(ENVIRONMENT_PREFIX, environment))
                .add(Source.arguments(args))
                .sensitive(InventoryWiring::isSensitive)
                .build();
    }

    /** The keys whose values are never shown: the audit secret, and anything named like a secret. */
    static boolean isSensitive(String key) {
        return key.equals("audit.secret") || key.endsWith(".secret") || key.endsWith(".password");
    }

    /**
     * Binds the whole configuration to {@link AppSettings}; every missing key and invalid value
     * is in one {@code ConfigException}.
     */
    static AppSettings settings(Config config) {
        return config.bind("", c -> new AppSettings(
                c.section("inventory", i -> new InventorySettings(i.path("orders"), i.section("stock", InventoryWiring::stock))),
                c.section("rules", r -> new RuleSettings(
                        r.value("max.quantity", POSITIVE_INT),
                        new HashSet<>(r.list("blocked", Converter.STRING, List.of())))),
                c.section("audit", a -> new AuditSettings(
                        a.optional("file", Converter.PATH),
                        a.value("secret", SECRET))),
                c.section("logging.level", InventoryWiring::logLevels)));
    }

    /** {@code inventory.stock.<sku>=<level>}, one key per SKU. */
    private static Map<String, Integer> stock(Section stock) {
        var levels = new TreeMap<String, Integer>();
        for (var sku : stock.keys()) {
            levels.put(sku, stock.value(sku, STOCK_LEVEL));
        }
        return levels;
    }

    /** {@code logging.level.<prefix>=<level>} as in Spring Boot; {@code root} is every logger. */
    private static Map<String, Level> logLevels(Section logging) {
        var levels = new TreeMap<String, Level>();
        for (var name : logging.keys()) {
            var level = logging.value(name, "log level", LoggingSettings::parseLevel);
            if (level != null) {
                levels.put(name.equals("root") ? "" : name, level);
            }
        }
        return levels;
    }

    /** The settings records, and the stream the summary is written to. */
    static ContainerModule settings(AppSettings app, PrintStream summary) {
        return binder -> {
            binder.bind(InventorySettings.class).toInstance(app.inventory());
            binder.bind(RuleSettings.class).toInstance(app.rules());
            binder.bind(AuditSettings.class).toInstance(app.audit());
            binder.bind(Key.of(PrintStream.class, Channel.SUMMARY)).toInstance(summary);
        };
    }

    /** The stock and the audit trail. */
    static final ContainerModule STOCK = binder -> {
        binder.bind(Stock.class).to(InventorySettings.class, Stock::new);
        binder.bind(AuditTrail.class).to(InventorySettings.class, AuditSettings.class, AuditTrail::new);
    };

    /** The order checks, told apart by {@link CheckKind}. */
    static final ContainerModule CHECKS = binder -> {
        binder.bind(Key.of(OrderCheck.class, CheckKind.QUANTITY)).to(RuleSettings.class, QuantityLimit::new);
        binder.bind(Key.of(OrderCheck.class, CheckKind.BLOCKED)).to(RuleSettings.class, BlockedSkus::new);
    };

    /** The processor and the report. */
    static final ContainerModule PROCESSING = binder -> {
        binder.bind(OrderProcessor.class).to(
                Key.of(InventorySettings.class),
                Key.of(Stock.class),
                Key.of(OrderCheck.class, CheckKind.QUANTITY),
                Key.of(OrderCheck.class, CheckKind.BLOCKED),
                Key.of(AuditTrail.class),
                OrderProcessor::new);
        binder.bind(ReportWriter.class).to(Key.of(PrintStream.class, Channel.SUMMARY), ReportWriter::new);
    };

    /** Every module of the program. */
    static List<ContainerModule> modules(AppSettings app, PrintStream summary) {
        return List.of(settings(app, summary), STOCK, CHECKS, PROCESSING);
    }
}
