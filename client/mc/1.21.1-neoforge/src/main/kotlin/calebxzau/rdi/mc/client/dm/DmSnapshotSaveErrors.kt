package calebxzau.rdi.mc.client.dm

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import java.util.concurrent.atomic.AtomicInteger

/** Captures save related errors emitted while a snapshot is being made. */
internal class DmSnapshotSaveErrors : AbstractAppender(
    "rdi-dm-snapshot-test",
    null,
    PatternLayout.createDefaultLayout(),
    false,
    Property.EMPTY_ARRAY,
) {
    private val rootLogger: Logger = (LogManager.getRootLogger() as? Logger)
        ?: throw IllegalStateException("Log4j Core root logger is unavailable; snapshot save error monitoring cannot start")
    private val errorCount = AtomicInteger()
    private val warningCount = AtomicInteger()
    private val messages = java.util.Collections.synchronizedList(ArrayList<String>())

    data class Summary(val errors: Int, val warnings: Int, val messages: List<String>)

    fun install() {
        start()
        rootLogger.addAppender(this)
    }

    fun remove() {
        rootLogger.removeAppender(this)
        stop()
    }

    fun summary(): Summary = synchronized(messages) {
        Summary(errorCount.get(), warningCount.get(), messages.toList())
    }

    override fun append(event: LogEvent) {
        val text = event.message?.formattedMessage ?: return
        val level = event.level
        val saveWarning = level == Level.WARN && SAVE_WARNING_WORDS.any { text.contains(it, ignoreCase = true) }
        if (level.isMoreSpecificThan(Level.ERROR)) errorCount.incrementAndGet()
        if (saveWarning) warningCount.incrementAndGet()
        if (level.isMoreSpecificThan(Level.ERROR) || saveWarning) {
            synchronized(messages) {
                if (messages.size < MAX_MESSAGES) messages += "$level: $text".take(MAX_MESSAGE_LENGTH)
            }
        }
    }

    companion object {
        private const val MAX_MESSAGES = 8
        private const val MAX_MESSAGE_LENGTH = 2048
        private val SAVE_WARNING_WORDS = listOf("save", "write", "store", "serialize", "chunk", "poi", "entity")
    }
}
