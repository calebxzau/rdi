package calebxzau.rdi.mc.client.loading

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.MarkerManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.DefaultConfiguration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootLogLevelFixTest {
    private lateinit var context: LoggerContext

    @BeforeTest
    fun setUp() {
        context = LoggerContext("rdi-root-log-level-test")
        // FML's log4j2.xml on the test classpath ACCEPTs the LOADING marker at any level.
        context.start(DefaultConfiguration())
    }

    @AfterTest
    fun tearDown() {
        context.stop()
    }

    @Test
    fun lowersRootLevelAllSoTraceIsDisabled() {
        context.configuration.rootLogger.level = Level.ALL
        context.updateLoggers()
        val logger = context.getLogger("net.neoforged.fml.ModContainer")
        val loading = MarkerManager.getMarker("LOADING")
        assertTrue(logger.isTraceEnabled(loading))

        assertTrue(RootLogLevelFix.apply("DEBUG", context))

        assertEquals(Level.DEBUG, context.configuration.rootLogger.level)
        assertFalse(logger.isTraceEnabled(loading))
        assertTrue(logger.isDebugEnabled(loading))
    }

    @Test
    fun keepsRootLevelThatIsNotAll() {
        context.configuration.rootLogger.level = Level.INFO

        assertFalse(RootLogLevelFix.apply("DEBUG", context))

        assertEquals(Level.INFO, context.configuration.rootLogger.level)
    }

    @Test
    fun rejectsUnknownLevel() {
        context.configuration.rootLogger.level = Level.ALL

        assertFailsWith<IllegalArgumentException> { RootLogLevelFix.apply("LOUD", context) }

        assertEquals(Level.ALL, context.configuration.rootLogger.level)
    }
}
