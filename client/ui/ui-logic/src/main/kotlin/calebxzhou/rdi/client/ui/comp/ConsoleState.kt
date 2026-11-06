package calebxzhou.rdi.client.ui.comp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ConsoleState(
    private val maxLogLines: Int = DEFAULT_MAX_LOG_LINES
) {
    private val lock = Any()
    private val lines = ArrayDeque<String>(maxLogLines)
    private var removedLineCount = 0L
    private val mutableRevision = MutableStateFlow(0L)

    val revision = mutableRevision.asStateFlow()

    init {
        require(maxLogLines > 0) { "maxLogLines必须大于0" }
    }

    fun append(text: String) {
        addLines(splitLines(text))
    }

    fun appendAll(texts: Iterable<String>) {
        addLines(texts.flatMap(::splitLines))
    }

    fun clear() {
        val changed = synchronized(lock) {
            if (lines.isEmpty()) {
                false
            } else {
                lines.clear()
                removedLineCount = 0
                true
            }
        }
        if (changed) notifyChanged()
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun uiSnapshot(): ConsoleUiSnapshot = synchronized(lock) {
        ConsoleUiSnapshot(lines.toList(), removedLineCount)
    }

    private fun addLines(newLines: List<String>) {
        if (newLines.isEmpty()) return
        synchronized(lock) {
            lines.addAll(newLines)
            while (lines.size > maxLogLines) {
                lines.removeFirst()
                removedLineCount++
            }
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        mutableRevision.update { it + 1 }
    }

    private fun splitLines(text: String): List<String> = buildList {
        var lineStart = 0
        text.forEachIndexed { index, char ->
            if (char == '\n') {
                add(text.substring(lineStart, index).removeSuffix("\r"))
                lineStart = index + 1
            }
        }
        if (lineStart < text.length || text.isEmpty()) {
            add(text.substring(lineStart).removeSuffix("\r"))
        }
    }

    private companion object {
        const val DEFAULT_MAX_LOG_LINES = 5000
    }
}

data class ConsoleUiSnapshot(
    val lines: List<String>,
    val removedLineCount: Long
)


