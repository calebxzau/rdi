package calebxzau.rdi.mc.client.dm

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineLabel
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Shown when the player hosts a room whose recorded mods differ from theirs, e.g. after a modpack
 * update. Nothing is uploaded until they confirm, so cancelling leaves the room's world untouched.
 */
class DmModUpdateScreen(
    private val detail: String,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit,
) : Screen(Component.literal("你的Mod和房间不一致")) {
    private var label = MultiLineLabel.EMPTY
    private var decided = false

    override fun init() {
        label = MultiLineLabel.create(font, Component.literal("$detail\n\n$WARNING"), width - 60)
        addRenderableWidget(Button.builder(Component.literal("按我现在的Mod更新房间")) { decide(onConfirm) }
            .bounds(width / 2 - 155, height - 40, 150, 20).build())
        addRenderableWidget(Button.builder(Component.literal("取消托管")) { decide(onCancel) }
            .bounds(width / 2 + 5, height - 40, 150, 20).build())
    }

    private fun decide(action: () -> Unit) {
        if (decided) return
        decided = true
        minecraft?.setScreen(null)
        action()
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Screen.render draws the blurred background first; text drawn before it would be blurred too.
        super.render(guiGraphics, mouseX, mouseY, partialTick)
        guiGraphics.drawCenteredString(font, title, width / 2, 30, 0xFFFFFF)
        label.renderCentered(guiGraphics, width / 2, 55)
    }

    private companion object {
        const val WARNING = "确认后，房间会改用你现在的Mod：你缺少的Mod的方块、物品等内容会从房间世界里删掉，" +
            "其他玩家也要装和你一样的Mod才能进入。取消则不会改动房间里的世界。"
    }
}
