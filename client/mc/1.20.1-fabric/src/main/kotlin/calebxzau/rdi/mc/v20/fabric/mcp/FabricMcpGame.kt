package calebxzau.rdi.mc.v20.fabric.mcp

import calebxzau.rdi.mc.v20.client.mcp.McpGame20
import calebxzhou.rdi.mc.client.mcp.standard.tools.ScreenTool
import calebxzhou.rdi.mc.common2.mcp.McpError
import calebxzhou.rdi.mc.common2.mcp.model.BlockFetchBoxP
import calebxzhou.rdi.mc.common2.mcp.model.BlockFetchBoxQ
import calebxzhou.rdi.mc.common2.mcp.model.BlockFindP
import calebxzhou.rdi.mc.common2.mcp.model.BlockFindQ
import calebxzhou.rdi.mc.common2.mcp.model.McpC2SNetPacket
import calebxzhou.rdi.mc.common2.mcp.model.ModInfo
import calebxzhou.rdi.mc.common2.mcp.model.RecipeQ
import calebxzhou.rdi.mc.common2.mcp.model.RecipeTreeQ

internal object FabricMcpGame : McpGame20() {
    private val supportedTools = ScreenTool.all.mapTo(
        mutableSetOf("screenshot", "command", "player", "inventory", "inventory_slot"),
    ) { it.name }

    override fun supportsMcpTool(name: String): Boolean = name in supportedTools

    override fun send(packet: McpC2SNetPacket): Result<String> = unsupported()
    override fun blockFind(req: BlockFindQ): Result<BlockFindP> = unsupported()
    override fun blockFetchBox(req: BlockFetchBoxQ): Result<BlockFetchBoxP> = unsupported()
    override fun recipes(req: RecipeQ): Result<String> = unsupported()
    override fun recipeTree(req: RecipeTreeQ): Result<String> = unsupported()
    override fun modIds(): Result<String> = unsupported()
    override fun modInfo(id: String): Result<ModInfo> = unsupported()
    override fun questChapterList(): Result<String> = unsupported()
    override fun questsOfChapter(chapterId: String): Result<String> = unsupported()

    private fun <T> unsupported(): Result<T> =
        Result.failure(McpError("This tool is unavailable on Fabric 1.20.1"))
}
