package calebxzau.rdi.mc.v20.server.rcmd

import calebxzhou.rdi.mc.rcmd.chat.RChatMessage

/** Loader bridge used by the shared 1.20 server command and chat mixins. */
interface RcmdServerAdapter20 {
    fun dispatchCommand(source: Any, command: String): Int?

    fun handlePlayerChat(player: Any, message: String): Boolean

    fun receiveRoomChat(message: RChatMessage)
}

object RcmdServerRuntime20 {
    @Volatile
    private var adapter: RcmdServerAdapter20? = null

    @JvmStatic
    fun install(adapter: RcmdServerAdapter20) {
        this.adapter = adapter
    }

    @JvmStatic
    fun clear(adapter: RcmdServerAdapter20) {
        if (this.adapter === adapter) this.adapter = null
    }

    @JvmStatic
    fun dispatchCommand(source: Any, command: String): Int? =
        adapter?.dispatchCommand(source, command)

    @JvmStatic
    fun handlePlayerChat(player: Any, message: String): Boolean =
        adapter?.handlePlayerChat(player, message) ?: false

    @JvmStatic
    fun receiveRoomChat(message: RChatMessage) {
        adapter?.receiveRoomChat(message)
    }

    @JvmStatic
    fun isActive(adapter: RcmdServerAdapter20): Boolean = this.adapter === adapter
}
