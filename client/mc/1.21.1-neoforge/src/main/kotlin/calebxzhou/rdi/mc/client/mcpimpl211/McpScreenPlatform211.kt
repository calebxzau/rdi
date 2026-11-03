package calebxzhou.rdi.mc.client.mcpimpl211

import calebxzau.mc.common2021.mcp.McpScreenPlatform
import calebxzau.rdi.mc.client.mixin.AccessorAbstractSelectionList
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen

object McpScreenPlatform211 : McpScreenPlatform {
    override fun rowTopBottom(list: GuiEventListener, index: Int): Pair<Int, Int> {
        val accessor = list as AccessorAbstractSelectionList
        return accessor.`rdi$getRowTop`(index) to accessor.`rdi$getRowBottom`(index)
    }

    // 1.21 selection lists are widgets.
    override fun listViewport(list: GuiEventListener): Pair<Int, Int> = (list as AbstractWidget).let { it.y to it.bottom }

    override fun scroll(screen: Screen, x: Double, y: Double, amount: Double): Boolean =
        screen.mouseScrolled(x, y, 0.0, amount)
}
