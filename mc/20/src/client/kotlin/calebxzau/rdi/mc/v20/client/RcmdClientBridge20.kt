package calebxzau.rdi.mc.v20.client

import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.rcmd.RcmdClientBridge
import calebxzhou.rdi.mc.rcmd.RcmdSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.UUID

/**
 * Loader-independent client bridge used by the shared 1.20.1 Rcmd chat input mixin.
 */
class RcmdClientBridge20(private val minecraft: Minecraft) : RcmdClientBridge {
    override fun name(): String = minecraft.user.name

    override fun playerId(): UUID = minecraft.player?.uuid ?: RcmdSource.NO_PLAYER_ID

    override fun hasPermission(permission: String): Boolean = true

    override fun sendFeedback(message: String) {
        sendMessage(message)
    }

    override fun sendError(message: String) {
        sendMessage("[rcmd] $message")
    }

    override fun gameDirectory(): Path = minecraft.gameDirectory.toPath()

    override fun executeOnMainThread(task: Runnable) {
        minecraft.execute(task)
    }

    override fun toggleSetFirmSectionsVisible(): Boolean {
        RDI.SHOW_SET_FIRM_SECTIONS = !RDI.SHOW_SET_FIRM_SECTIONS
        return RDI.SHOW_SET_FIRM_SECTIONS
    }

    override fun toggleNowFirmSectionVisible(): Boolean {
        RDI.SHOW_NOW_FIRM_SECTION = !RDI.SHOW_NOW_FIRM_SECTION
        return RDI.SHOW_NOW_FIRM_SECTION
    }

    private fun sendMessage(message: String) {
        val component = Component.literal(message)
        val player = minecraft.player
        if (player != null) {
            player.displayClientMessage(component, false)
        } else {
            minecraft.gui.chat.addMessage(component)
        }
    }
}
