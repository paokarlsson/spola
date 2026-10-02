package spola.demo;

import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import spola.config.Config;
import spola.config.ConfigException;
import spola.di.CloseException;
import spola.di.Container;
import spola.di.ContainerModule;
import spola.di.ResolutionException;
import spola.di.WiringException;
import spola.util.Log;
import spola.util.Logging;
import spola.util.LoggingSettings;

/**
 * The stock service: reads an order file, takes each order from the stock and writes a
 * summary (processed orders, rejected orders with the reason, the stock per SKU afterwards).
 *
 * <pre>{@code
 * java -p out/classes -m spola.demo/spola.demo.InventoryApp --config.file=shop/inventory.properties
 * }</pre>
 *
 * <p>Configuration, from lowest to highest priority: defaults in code, the file
 * ({@code config.file}, default {@code inventory.properties}), one file per active profile
 * next to it ({@code inventory-<profile>.properties}, profiles from {@code profiles}),
 * environment variables with the prefix {@code INVENTORY_} and arguments
 * {@code --key=value}. Keys: {@code inventory.orders}, {@code inventory.stock.<sku>},
 * {@code rules.max.quantity}, {@code rules.blocked}, {@code audit.file},
 * {@code audit.secret} (sensitive, at least 16 characters) and {@code logging.level.<prefix>}.
 *
 * <p>Errors a user can meet are reported as one message on the log stream, without a stack
 * trace, and an exit code: {@link #CONFIG_ERROR}, {@link #DATA_ERROR}, {@link #IO_ERROR} or
 * {@link #SOFTWARE_ERROR}.
 */
public final class InventoryApp {

    /** The orders were processed (rejected orders included) and the summary written. */
    public static final int OK = 0;

    /** A broken line in the order file ({@code EX_DATAERR} in BSD's sysexits). */
    public static final int DATA_ERROR = 65;

    /** A wiring error, a component that could not be created or closed, or an unexpected failure ({@code EX_SOFTWARE}). */
    public static final int SOFTWARE_ERROR = 70;

    /** The order file could not be read or the audit trail could not be opened ({@code EX_IOERR}). */
    public static final int IO_ERROR = 74;

    /** Missing or invalid configuration ({@code EX_CONFIG}). */
    public static final int CONFIG_ERROR = 78;

    private static final Log LOG = Log.forClass(InventoryApp.class);

    private InventoryApp() {
    }

    /**
     * Runs the program with the process's arguments, environment, {@code System.out} (the
     * summary) and {@code System.err} (log and errors), and exits with the exit code if it is
     * not 0.
     *
     * @param args {@code --key=value} arguments
     */
    public static void main(String[] args) {
        var code = run(List.of(args), System.getenv(), System.out, System.err);
        if (code != OK) {
            System.exit(code);
        }
    }

    /**
     * Runs the whole program. Logging is configured to write to {@code log} for the duration
     * of the call and reset afterwards; the streams are flushed, not closed.
     *
     * @param args {@code --key=value} arguments
     * @param environment the environment variables, e.g. {@code System.getenv()}
     * @param out where the summary is written
     * @param log where log lines and error messages are written
     * @return the exit code: {@link #OK} or one of the error codes
     */
    public static int run(List<String> args, Map<String, String> environment, PrintStream out, PrintStream log) {
        return run(args, environment, out, log, InventoryWiring::modules);
    }

    /** Like {@link #run(List, Map, PrintStream, PrintStream)}, with the container's modules given by {@code wiring}. */
    static int run(List<String> args, Map<String, String> environment, PrintStream out, PrintStream log,
                   BiFunction<AppSettings, PrintStream, List<ContainerModule>> wiring) {
        Config config;
        AppSettings settings;
        try {
            config = InventoryWiring.config(args, environment);
            settings = InventoryWiring.settings(config);
        } catch (ConfigException e) {
            return fail(log, CONFIG_ERROR, "invalid configuration: " + e.getMessage());
        }
        if (AuditTrail.overwritesOrders(settings.inventory(), settings.audit())) {
            return fail(log, CONFIG_ERROR, "invalid configuration: audit.file is the order file (inventory.orders) "
                    + settings.inventory().orders() + "; the audit trail would overwrite it");
        }
        try {
            Logging.configure(LoggingSettings.defaults().withLevels(settings.logLevels()).withOutput(log));
        } catch (IllegalArgumentException e) { // a logger-name prefix like logging.level.a..b
            return fail(log, CONFIG_ERROR, "invalid configuration: logging.level: " + e.getMessage());
        }
        try {
            config.describe().lines().forEach(line -> LOG.debug("config: {}", line));
            LOG.debug("settings: {}", settings);
            try (var container = Container.create(wiring.apply(settings, out).toArray(ContainerModule[]::new))) {
                var summary = container.get(OrderProcessor.class).process();
                container.get(ReportWriter.class).write(summary);
            }
            return OK;
        } catch (WiringException e) {
            return fail(log, SOFTWARE_ERROR, "wiring error: " + e.getMessage());
        } catch (OrderFileException e) {
            return fail(log, DATA_ERROR, "broken order file: " + e.getMessage());
        } catch (UncheckedIOException e) {
            return ioFailure(log, e);
        } catch (ResolutionException e) {
            if (e.getCause() instanceof UncheckedIOException io) { // e.g. audit.file in a missing directory
                return ioFailure(log, io);
            }
            return fail(log, SOFTWARE_ERROR, e.getMessage());
        } catch (CloseException e) {
            return fail(log, SOFTWARE_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            LOG.debug("unexpected failure", e);
            return fail(log, SOFTWARE_ERROR, "unexpected failure: " + e);
        } finally {
            out.flush();
            Logging.reset();
        }
    }

    private static int ioFailure(PrintStream log, UncheckedIOException e) {
        return fail(log, IO_ERROR, e.getMessage() + ": " + e.getCause());
    }

    private static int fail(PrintStream log, int code, String message) {
        log.println("error: " + message);
        log.flush();
        return code;
    }
}
