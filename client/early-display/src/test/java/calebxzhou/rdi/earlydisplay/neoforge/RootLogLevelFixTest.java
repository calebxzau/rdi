package calebxzhou.rdi.earlydisplay.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.MarkerManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RootLogLevelFixTest {
    private LoggerContext context;

    @BeforeEach
    void setUp() {
        context = new LoggerContext("rdi-root-log-level-test");
        context.start();
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    @Test
    void lowersRootLevelAllSoTraceIsDisabled() {
        context.getConfiguration().getRootLogger().setLevel(Level.ALL);
        context.updateLoggers();
        var logger = context.getLogger("net.neoforged.fml.ModContainer");
        var loading = MarkerManager.getMarker("LOADING");
        assertTrue(logger.isTraceEnabled(loading));

        assertTrue(RootLogLevelFix.apply("DEBUG", context));

        assertEquals(Level.DEBUG, context.getConfiguration().getRootLogger().getLevel());
        assertFalse(logger.isTraceEnabled(loading));
        assertTrue(logger.isDebugEnabled(loading));
    }

    @Test
    void keepsRootLevelThatIsNotAll() {
        context.getConfiguration().getRootLogger().setLevel(Level.INFO);

        assertFalse(RootLogLevelFix.apply("DEBUG", context));

        assertEquals(Level.INFO, context.getConfiguration().getRootLogger().getLevel());
    }

    @Test
    void rejectsUnknownLevel() {
        context.getConfiguration().getRootLogger().setLevel(Level.ALL);

        assertThrows(IllegalArgumentException.class, () -> RootLogLevelFix.apply("LOUD", context));

        assertEquals(Level.ALL, context.getConfiguration().getRootLogger().getLevel());
    }
}
