package calebxzhou.rdi.earlydisplay.neoforge;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.slf4j.LoggerFactory;

/**
 * FML 4.x always reloads its bundled log4j2.xml in production, whose root level is {@code all}. Every per-mod
 * {@code LOGGER.trace(LOADING, ...)} then builds a log event that the appenders discard. The launcher passes
 * {@value #PROPERTY} so we can lower the root level after FML's reload and before mods are constructed.
 */
final class RootLogLevelFix {
    static final String PROPERTY = "rdi.logging.rootLevel";

    private RootLogLevelFix() {
    }

    static void applyFromSystemProperty() {
        String levelName = System.getProperty(PROPERTY);
        if (levelName == null || levelName.isBlank()) {
            return;
        }
        try {
            if (apply(levelName, (LoggerContext) LogManager.getContext(false))) {
                LoggerFactory.getLogger("EARLYDISPLAY").info("Lowered log4j root level from ALL to {}", levelName);
            }
        } catch (Throwable e) {
            LoggerFactory.getLogger("EARLYDISPLAY").warn("Failed to lower log4j root level to {}", levelName, e);
        }
    }

    /** Returns whether the root level was changed; only a root level of ALL is lowered. */
    static boolean apply(String levelName, LoggerContext context) {
        Level target = Level.toLevel(levelName, null);
        if (target == null) {
            throw new IllegalArgumentException("Unknown log level " + levelName);
        }
        LoggerConfig root = context.getConfiguration().getRootLogger();
        if (root.getLevel() != Level.ALL) {
            return false;
        }
        root.setLevel(target);
        context.updateLoggers();
        return true;
    }
}
