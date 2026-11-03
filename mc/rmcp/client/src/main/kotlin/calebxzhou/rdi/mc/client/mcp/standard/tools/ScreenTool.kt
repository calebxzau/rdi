package calebxzhou.rdi.mc.client.mcp.standard.tools

import calebxzhou.rdi.mc.client.mcp.McpGameInterface
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpTool
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpToolResult
import calebxzhou.rdi.mc.client.mcp.standard.TypedMcpTool
import calebxzhou.rdi.mc.common2.mcp.model.ScreenClickAtQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenClickQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenKeyQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenScrollQ
import calebxzhou.rdi.mc.common2.mcp.model.ScreenSnapshot
import calebxzhou.rdi.mc.common2.mcp.model.ScreenTypeQ
import kotlinx.serialization.json.JsonObject

object ScreenTool {
    val all: List<StandardMcpTool> = listOf(
        ScreenReadTool,
        ScreenClickTool,
        ScreenClickAtTool,
        ScreenTypeTool,
        ScreenKeyTool,
        ScreenScrollTool,
    )
}

private fun Result<ScreenSnapshot>.toToolResult(): Result<StandardMcpToolResult> =
    map { snapshot -> StandardMcpToolResult.text(snapshot.toString()) }

private object ScreenReadTool : StandardMcpTool {
    override val name = "screen_read"
    override val description = """
        Read the open game screen (menus, settings, container and mod GUIs): screen class, title, and every clickable widget
        with its id, type, text, GUI-scaled bounds, and state. List rows are included as ListEntry widgets.
        Widget ids are only valid for the returned version; read again after the screen changes.
        Text drawn directly on the screen is not listed; use screenshot to see it.
    """.trimIndent()

    override fun call(params: JsonObject, game: McpGameInterface): Result<StandardMcpToolResult> =
        game.screenRead().toToolResult()
}

private object ScreenClickTool : TypedMcpTool<ScreenClickQ>(ScreenClickQ.serializer(), ScreenClickQ::class) {
    override val name = "screen_click"
    override val readOnly = false
    override val destructive = true
    override val description = """
        Click a widget from screen_read at its center, exactly like the player's mouse would.
        Fails if the screen changed since that read. Returns the screen after the click.
    """.trimIndent()

    override fun callTyped(req: ScreenClickQ, game: McpGameInterface) = game.screenClick(req).toToolResult()
}

private object ScreenClickAtTool : TypedMcpTool<ScreenClickAtQ>(ScreenClickAtQ.serializer(), ScreenClickAtQ::class) {
    override val name = "screen_click_at"
    override val readOnly = false
    override val destructive = true
    override val description = """
        Click the open screen at GUI-scaled coordinates, for custom mod GUIs whose parts are not listed by screen_read.
        Convert screenshot pixels by dividing by guiScale from screen_read. Returns the screen after the click.
    """.trimIndent()

    override fun callTyped(req: ScreenClickAtQ, game: McpGameInterface) = game.screenClickAt(req).toToolResult()
}

private object ScreenTypeTool : TypedMcpTool<ScreenTypeQ>(ScreenTypeQ.serializer(), ScreenTypeQ::class) {
    override val name = "screen_type"
    override val readOnly = false
    override val destructive = true
    override val description = """
        Type text into an EditBox widget from screen_read, optionally clearing it first.
        Fails if the screen changed since that read. Returns the screen after typing.
    """.trimIndent()

    override fun callTyped(req: ScreenTypeQ, game: McpGameInterface) = game.screenType(req).toToolResult()
}

private object ScreenKeyTool : TypedMcpTool<ScreenKeyQ>(ScreenKeyQ.serializer(), ScreenKeyQ::class) {
    override val name = "screen_key"
    override val readOnly = false
    override val destructive = true
    override val description = """
        Press a key on the open screen, e.g. Escape to close it or Enter to confirm.
        With no screen open, only Escape is accepted and opens the pause menu. Returns the screen afterwards.
    """.trimIndent()

    override fun callTyped(req: ScreenKeyQ, game: McpGameInterface) = game.screenKey(req).toToolResult()
}

private object ScreenScrollTool : TypedMcpTool<ScreenScrollQ>(ScreenScrollQ.serializer(), ScreenScrollQ::class) {
    override val name = "screen_scroll"
    override val readOnly = false
    override val description = """
        Scroll the mouse wheel at GUI-scaled coordinates, e.g. over a list to reveal rows marked hidden.
        Returns the screen after scrolling.
    """.trimIndent()

    override fun callTyped(req: ScreenScrollQ, game: McpGameInterface) = game.screenScroll(req).toToolResult()
}
