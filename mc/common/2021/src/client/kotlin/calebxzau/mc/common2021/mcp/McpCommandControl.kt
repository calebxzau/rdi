package calebxzau.mc.common2021.mcp

import calebxzhou.rdi.mc.common2.mcp.McpBadArgsError
import calebxzhou.rdi.mc.common2.mcp.McpError
import calebxzhou.rdi.mc.common2.mcp.McpNoPlayerError
import calebxzhou.rdi.mc.common2.mcp.model.CommandQ
import calebxzhou.rdi.mc.common2.mcp.model.CommandResult
import net.minecraft.client.Minecraft
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Supplier

/**
 * Runs a command for the MCP command tool the same way the chat screen does: client commands run
 * locally, everything else goes to the server. Chat shown during the wait is returned as output.
 */
object McpCommandControl {
    private const val TIMEOUT_SECONDS = 10L

    /** Same limit the chat screen trims input to. */
    private const val MAX_INPUT_LENGTH = 256

    fun run(req: CommandQ): Result<CommandResult> = runCatching {
        val command = normalizeCommand(req.command)
        if (req.waitMs !in 0..CommandQ.MAX_WAIT_MS) {
            throw McpBadArgsError("waitMs must be between 0 and ${CommandQ.MAX_WAIT_MS}")
        }
        McpChatCapture.record().use { recording ->
            onClient {
                val mc = Minecraft.getInstance()
                val connection = mc.connection
                if (mc.player == null || connection == null) throw McpNoPlayerError()
                mc.gui.chat.addRecentChat("/$command")
                connection.sendCommand(command)
            }.getOrThrow()
            if (req.waitMs > 0) Thread.sleep(req.waitMs.toLong())
            CommandResult(command, recording.lines())
        }
    }

    /** Trims [raw] and drops one leading slash; fails when nothing is left or it is too long for chat. */
    fun normalizeCommand(raw: String): String {
        val command = raw.trim().removePrefix("/").trim()
        if (command.isEmpty()) throw McpBadArgsError("command is empty")
        if (command.length + 1 > MAX_INPUT_LENGTH) {
            throw McpBadArgsError("command is longer than ${MAX_INPUT_LENGTH - 1} characters")
        }
        return command
    }

    private fun <T> onClient(action: () -> T): Result<T> = runCatching {
        try {
            Minecraft.getInstance().submit(Supplier { action() }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw McpError("the game did not respond in time")
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}
