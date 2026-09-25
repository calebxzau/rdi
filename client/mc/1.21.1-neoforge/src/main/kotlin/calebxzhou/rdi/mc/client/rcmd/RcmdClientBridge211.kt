package calebxzhou.rdi.mc.client.rcmd

import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.rcmd.RcmdClientBridge
import calebxzhou.rdi.mc.rcmd.RcmdSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Path
import java.util.*

class RcmdClientBridge211(private val minecraft: Minecraft) : RcmdClientBridge {
    override fun name(): String {
        return minecraft.getUser().getName()
    }

    override fun playerId(): UUID {
        return if (minecraft.player == null) RcmdSource.NO_PLAYER_ID else minecraft.player!!.getUUID()
    }

    override fun hasPermission(permission: String): Boolean {
        return true
    }

    override fun sendFeedback(message: String) {
        sendMessage(message)
    }

    override fun sendError(message: String) {
        sendMessage("[rcmd] $message")
    }

    override fun gameDirectory(): Path {
        return minecraft.gameDirectory.toPath()
    }

    override fun executeOnMainThread(task: Runnable) {
        minecraft.execute(task)
    }

    override fun toggleSetFirmSectionsVisible(): Boolean {
        RDI.SHOW_SET_SYNC_CHUNKS = !RDI.SHOW_SET_SYNC_CHUNKS
        return RDI.SHOW_SET_SYNC_CHUNKS
    }

    override fun toggleNowFirmSectionVisible(): Boolean {
        RDI.SHOW_NOW_SYNC_CHUNK = !RDI.SHOW_NOW_SYNC_CHUNK
        return RDI.SHOW_NOW_SYNC_CHUNK
    }


    private fun sendMessage(message: String) {
        val component = Component.literal(message)
        if (minecraft.player != null) {
            minecraft.player!!.displayClientMessage(component, false)
            return
        }
        minecraft.gui.getChat().addMessage(component)
    }
}
