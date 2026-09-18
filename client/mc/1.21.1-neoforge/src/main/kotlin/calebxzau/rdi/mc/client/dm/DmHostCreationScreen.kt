package calebxzau.rdi.mc.client.dm

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.UUID

class DmHostCreationScreen : Screen(Component.literal("创建DM房间")) {
    private var message = stateMessage
    private var progress = stateProgress
    private var canCancel = stateCanCancel
    private var hostId = stateHostId

    override fun isPauseScreen(): Boolean = statePauseWorld

    override fun shouldCloseOnEsc(): Boolean = false

    override fun onClose() {
        DmHostCreationService.cancel()
    }

    override fun init() {
        addRenderableWidget(Button.builder(Component.literal("取消")) { DmHostCreationService.cancel() }
            .bounds(width / 2 - 105, height / 2 + 30, 100, 20).build().also { it.active = canCancel })
        addRenderableWidget(Button.builder(Component.literal("返回原世界")) { DmHostCreationService.returnToWorld() }
            .bounds(width / 2 + 5, height / 2 + 30, 100, 20).build().also { it.active = !canCancel })
        if (hostId != null) {
            addRenderableWidget(Button.builder(Component.literal("复制房间ID")) {
                stateHostId?.let { id -> Minecraft.getInstance().keyboardHandler.clipboard = id.toString() }
            }.bounds(width / 2 - 105, height / 2 + 55, 210, 20).build())
            addRenderableWidget(Button.builder(Component.literal("重试保存关联")) { DmHostCreationService.retryAssociationSave() }
                .bounds(width / 2 - 105, height / 2 + 80, 210, 20).build())
        }
    }

    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        message = stateMessage
        progress = stateProgress
        canCancel = stateCanCancel
        hostId = stateHostId
        renderBackground(guiGraphics, mouseX, mouseY, partialTick)
        guiGraphics.drawCenteredString(font, title, width / 2, height / 2 - 55, 0xFFFFFF)
        guiGraphics.drawCenteredString(font, message, width / 2, height / 2 - 30, 0xFFFFFF)
        val barWidth = 220
        guiGraphics.fill(width / 2 - barWidth / 2, height / 2 - 5, width / 2 + barWidth / 2, height / 2 + 3, 0xFF444444.toInt())
        guiGraphics.fill(width / 2 - barWidth / 2, height / 2 - 5, width / 2 - barWidth / 2 + (barWidth * progress.coerceIn(0f, 1f)).toInt(), height / 2 + 3, 0xFF55AA55.toInt())
        hostId?.let { guiGraphics.drawCenteredString(font, it.toString(), width / 2, height / 2 + 10, 0xFFFFFF) }
        super.render(guiGraphics, mouseX, mouseY, partialTick)
    }

    companion object {
        private var stateMessage = Component.literal("正在准备")
        private var stateProgress = 0f
        private var stateCanCancel = true
        private var stateHostId: UUID? = null
        private var statePauseWorld = true

        fun update(message: String, progress: Float, canCancel: Boolean, hostId: UUID?, pauseWorld: Boolean = true) {
            stateMessage = Component.literal(message)
            stateProgress = progress
            stateCanCancel = canCancel
            stateHostId = hostId
            statePauseWorld = pauseWorld
        }
    }
}
