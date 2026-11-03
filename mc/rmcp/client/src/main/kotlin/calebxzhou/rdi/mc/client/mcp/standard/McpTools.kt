package calebxzhou.rdi.mc.client.mcp.standard

import calebxzhou.rdi.mc.client.mcp.standard.tools.BlockTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.CommandTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.ContainerTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.CraftTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.ModTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.PlayerTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.QuestTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.RecipeTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.ResourceTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.ScreenTool
import calebxzhou.rdi.mc.client.mcp.standard.tools.ScreenshotTool

object StandardMcpTools {
    val all: List<StandardMcpTool> =
        PlayerTool.all +
            ScreenshotTool.all +
            ScreenTool.all +
            CommandTool.all +
            BlockTool.all +
            ContainerTool.all +
            CraftTool.all +
            ModTool.all +
            QuestTool.all +
            RecipeTool.all +
            ResourceTool.all

    private val byName = all.associateBy { it.name }

    fun get(name: String): StandardMcpTool? = byName[name]
}
