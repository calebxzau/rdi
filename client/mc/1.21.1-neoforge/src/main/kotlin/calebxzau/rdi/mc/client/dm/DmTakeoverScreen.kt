package calebxzau.rdi.mc.client.dm

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Shown while this player waits for, or performs, a takeover of a room whose host left. */
class DmTakeoverScreen(private val parent: Screen) : Screen(Component.literal("房主已离线")) {
    private var button: Button? = null

    override fun init() {
        button = addRenderableWidget(Button.builder(label()) {
            DmTakeoverService.cancel()
            minecraft?.setScreen(parent)
        }.bounds(width / 2 - 100, height / 2 + 30, 200, 20).build())
    }

    override fun tick() {
        button?.message = label()
    }

    private fun label(): Component = Component.literal(if (DmTakeoverService.isFinished) "返回" else "取消")

    override fun shouldCloseOnEsc(): Boolean = false

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Screen.render draws the blurred background first; text drawn before it would be blurred too.
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        guiGraphics.drawCenteredString(font, title, width / 2, height / 2 - 40, 0xFFFFFF)
        guiGraphics.drawCenteredString(font, Component.literal(DmTakeoverService.status), width / 2, height / 2 - 10, 0xAAAAAA)
    }
}
