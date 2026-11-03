package calebxzau.mc.common2021.mcp

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Collects chat lines while MCP commands run. Chat arrives on the client thread through the
 * ChatComponent mixin; recordings are read on the MCP thread.
 */
object McpChatCapture {
    class Recording internal constructor() : AutoCloseable {
        internal val lines = ConcurrentLinkedQueue<String>()

        /** Lines received so far, oldest first. */
        fun lines(): List<String> = lines.toList()

        override fun close() {
            recordings.remove(this)
        }
    }

    private val recordings = CopyOnWriteArraySet<Recording>()

    fun record(): Recording = Recording().also { recordings += it }

    @JvmStatic
    fun isRecording(): Boolean = recordings.isNotEmpty()

    @JvmStatic
    fun onChat(text: String) {
        recordings.forEach { it.lines += text }
    }
}
