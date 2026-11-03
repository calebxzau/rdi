package calebxzhou.rdi.mc.client.mcpimpl

import calebxzau.mc.common2021.mcp.McpScreenPlatform
import calebxzhou.rdi.mc.client.mixin.AAbstractSelectionList
import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen

object McpScreenPlatform20 : McpScreenPlatform {
    override fun rowTopBottom(list: GuiEventListener, index: Int): Pair<Int, Int> {
        val accessor = list as AAbstractSelectionList
        return accessor.`rdi$getRowTop`(index) to accessor.`rdi$getRowBottom`(index)
    }

    override fun listViewport(list: GuiEventListener): Pair<Int, Int> = (list as AbstractSelectionList<*>).let { it.top to it.bottom }

    override fun scroll(screen: Screen, x: Double, y: Double, amount: Double): Boolean =
        screen.mouseScrolled(x, y, amount)
}
