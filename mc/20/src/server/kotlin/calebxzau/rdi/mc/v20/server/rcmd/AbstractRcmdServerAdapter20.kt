package calebxzau.rdi.mc.v20.server.rcmd

import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.common.WebSocketClient
import calebxzhou.rdi.mc.common.WsMessage
import calebxzhou.rdi.mc.rcmd.Rcmd
import calebxzhou.rdi.mc.rcmd.chat.PlayerChatRangeState
import calebxzhou.rdi.mc.rcmd.chat.RChatMessage
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Loader-independent player chat routing for the shared 1.20.1 server chat mixin.
 *
 * The loaders only provide the loader-specific rcmd dispatch; host/public chat routing,
 * inbound room chat broadcast and the master-connection fallback stay identical everywhere.
 */
abstract class AbstractRcmdServerAdapter20(protected val server: MinecraftServer) : RcmdServerAdapter20 {

    /** Runs an rcmd line that a player typed into chat. Returns whether the message was consumed. */
    protected abstract fun dispatchPlayerRcmd(player: ServerPlayer, message: String): Boolean

    override fun handlePlayerChat(player: Any, message: String): Boolean {
        val serverPlayer = player as? ServerPlayer ?: return false
        if (Rcmd.isRcmd(message)) {
            return dispatchPlayerRcmd(serverPlayer, message)
        }
        val global = PlayerChatRangeState.isGlobal(serverPlayer.uuid)
        val chatMessage = RChatMessage(
            UUID.randomUUID().toString(),
            RDI.HOST_ID,
            serverPlayer.uuid.toString(),
            serverPlayer.gameProfile.name,
            message,
            System.currentTimeMillis(),
            global
        )
        if (!WebSocketClient.sendMessage(WsMessage.Channel.Chat, chatMessage)) {
            serverPlayer.sendSystemMessage(Component.literal("聊天服务未连接"))
            return true
        }
        if (global) {
            val component = Component.literal("[公共] " + serverPlayer.gameProfile.name + ": " + message)
            server.playerList.players
                .filter { PlayerChatRangeState.isGlobal(it.uuid) }
                .forEach { it.sendSystemMessage(component) }
        } else {
            val component = Component.literal(serverPlayer.displayName.string + ": " + message)
            server.playerList.players.forEach { it.sendSystemMessage(component) }
        }
        return true
    }

    override fun receiveRoomChat(message: RChatMessage) {
        try {
            server.execute {
                if (!RcmdServerRuntime20.isActive(this)) return@execute
                val component = Component.literal("[公共] " + message.playerName + ": " + message.content)
                server.playerList.players
                    .filter { PlayerChatRangeState.isGlobal(it.uuid) }
                    .forEach { it.sendSystemMessage(component) }
            }
        } catch (error: RuntimeException) {
            if (RcmdServerRuntime20.isActive(this)) {
                throw error
            }
        }
    }
}
