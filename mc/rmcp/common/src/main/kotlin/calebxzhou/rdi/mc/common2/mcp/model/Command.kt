package calebxzhou.rdi.mc.common2.mcp.model

import kotlinx.serialization.Serializable

@Serializable
data class CommandQ(
    @McpParam("Command to run, with or without the leading slash, e.g. \"/time query day\".")
    val command: String,
    @McpParam(
        "Milliseconds to keep collecting chat messages after running the command.",
        minimum = 0,
        maximum = CommandQ.MAX_WAIT_MS,
    )
    val waitMs: Int = 1000,
) {
    companion object {
        const val MAX_WAIT_MS = 5000
    }
}

@Serializable
data class CommandResult(
    /** The command as run, without the leading slash. */
    val command: String,
    /** Chat messages shown while the command ran, oldest first; may include unrelated chat. */
    val messages: List<String>,
) {
    override fun toString() = buildString {
        appendLine("ran /$command")
        if (messages.isEmpty()) {
            append("no chat messages received; the command may have no output, or try a longer waitMs")
        } else {
            appendLine("chat messages received (may include unrelated chat):")
            messages.forEach { appendLine(it) }
        }
    }.trimEnd()
}
