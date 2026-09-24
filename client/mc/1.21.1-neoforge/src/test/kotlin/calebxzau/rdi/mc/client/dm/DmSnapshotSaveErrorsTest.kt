package calebxzau.rdi.mc.client.dm

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.impl.Log4jLogEvent
import org.apache.logging.log4j.message.SimpleMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmSnapshotSaveErrorsTest {
    @Test
    fun countsErrorsAndRelevantSaveWarningsOnly(): Unit {
        val appender = DmSnapshotSaveErrors()
        appender.append(event(Level.ERROR, "terrain save failed"))
        appender.append(event(Level.FATAL, "entity write failed"))
        appender.append(event(Level.WARN, "Chunk write was slow"))
        appender.append(event(Level.WARN, "render warning unrelated to saving"))
        appender.append(event(Level.INFO, "save completed"))

        val summary = appender.summary()
        assertEquals(2, summary.errors)
        assertEquals(1, summary.warnings)
        assertEquals(3, summary.messages.size)
        assertTrue(summary.messages.any { it.contains("terrain save failed") })
        assertTrue(summary.messages.any { it.contains("Chunk write was slow") })
        assertTrue(summary.messages.none { it.contains("render warning") })
    }

    @Test
    fun boundsMessagesToEightEntriesAnd2048Characters(): Unit {
        val appender = DmSnapshotSaveErrors()
        repeat(32) { index ->
            appender.append(event(Level.ERROR, "error-$index " + "x".repeat(3000)))
        }

        val summary = appender.summary()
        assertEquals(32, summary.errors)
        assertEquals(0, summary.warnings)
        assertEquals(8, summary.messages.size)
        assertTrue(summary.messages.all { it.length <= 2048 })
    }

    @Test
    fun concurrentAppendAndSummaryRemainConsistent(): Unit {
        val appender = DmSnapshotSaveErrors()
        val writers = (0 until 6).map { writerIndex ->
            Thread {
                repeat(100) { eventIndex ->
                    appender.append(event(Level.ERROR, "writer-$writerIndex save error $eventIndex"))
                }
            }
        }
        val summaries = Thread {
            repeat(1000) { appender.summary() }
        }

        writers.forEach { it.start() }
        summaries.start()
        writers.forEach { it.join() }
        summaries.join()

        val summary = appender.summary()
        assertEquals(600, summary.errors)
        assertEquals(0, summary.warnings)
        assertEquals(8, summary.messages.size)
        assertTrue(summary.messages.all { it.length <= 2048 })
    }

    private fun event(level: Level, message: String) = Log4jLogEvent.newBuilder()
        .setLoggerName("rdi.snapshot.test")
        .setLevel(level)
        .setMessage(SimpleMessage(message))
        .build()
}
