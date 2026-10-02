package spola.demo;

import java.lang.System.Logger.Level;
import java.util.Map;

/**
 * Everything the program reads from its configuration, bound in one report.
 *
 * @param inventory the orders and the starting stock
 * @param rules the order rules
 * @param audit the audit trail
 * @param logLevels the level per logger-name prefix ({@code logging.level.*}; {@code root} is
 *     the empty prefix)
 */
record AppSettings(InventorySettings inventory, RuleSettings rules, AuditSettings audit, Map<String, Level> logLevels) {

    AppSettings {
        logLevels = Map.copyOf(logLevels);
    }
}
