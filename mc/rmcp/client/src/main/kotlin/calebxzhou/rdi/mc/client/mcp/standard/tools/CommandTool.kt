package calebxzhou.rdi.mc.client.mcp.standard.tools

import calebxzhou.rdi.mc.client.mcp.McpGameInterface
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpTool
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpToolResult
import calebxzhou.rdi.mc.client.mcp.standard.TypedMcpTool
import calebxzhou.rdi.mc.common2.mcp.model.CommandQ

object CommandTool {
    val all: List<StandardMcpTool> = listOf(RunCommandTool)
}

private object RunCommandTool : TypedMcpTool<CommandQ>(CommandQ.serializer(), CommandQ::class) {
    override val name = "command"
    override val readOnly = false
    override val destructive = true
    override val description = """
        Run a command as the local player, exactly like typing it in chat: client-side mod commands run locally,
        all others are sent to the server with the player's permissions.
        Returns the chat messages shown during waitMs, which hold the command's output but may also include unrelated chat.
        Output shown outside chat (action bar, screens) is not returned; use screenshot or screen_read for that.
    """.trimIndent()

    override fun callTyped(req: CommandQ, game: McpGameInterface) =
        game.runCommand(req).map { result -> StandardMcpToolResult.text(result.toString()) }
}
