package calebxzhou.rdi.mc.server.rcmd

import calebxzhou.rdi.mc.rcmd.chat.ChatRange
import calebxzhou.rdi.mc.rcmd.chat.PlayerChatRangeState
import calebxzhou.rdi.mc.rcmd.home.HomeResult
import calebxzhou.rdi.mc.rcmd.home.HomeService
import calebxzhou.rdi.mc.rcmd.tpa.TpaResult
import calebxzhou.rdi.mc.rcmd.tpa.TpaService
import calebxzhou.rdi.mc.rcmd.*
import calebxzhou.rdi.mc.rcmd.RcmdResult.Companion.error
import calebxzhou.rdi.mc.rcmd.RcmdResult.Companion.ok
import calebxzhou.rdi.mc.server.home.HomePlayer1710
import calebxzhou.rdi.mc.server.tpa.TpaPlayer1710
import calebxzhou.rdi.mc.server.tpa.TpaPlayerLookup1710
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.server.dedicated.DedicatedServer
import java.util.Locale
import java.util.UUID

object RcmdServerCommands1710 : RcmdServerCommandHandler {
    private val DISPATCHER = RcmdDispatcher()
    private var server: DedicatedServer? = null

    init {
        RcmdCommonServerCommands.register(DISPATCHER, this, false)
    }

    @JvmStatic
    fun init(dedicatedServer: DedicatedServer) {
        server = dedicatedServer
    }

    @JvmStatic
    fun dispatcher(): RcmdDispatcher {
        return DISPATCHER
    }

    @JvmStatic
    fun reply(source: RcmdSource, result: RcmdResult?) {
        if (result == null || result.message().isEmpty()) {
            return
        }
        for (message in result.message().split("\\R".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            if (!message.isEmpty()) {
                if (result.success()) {
                    source.sendFeedback(message)
                } else {
                    source.sendError(message)
                }
            }
        }
    }

    @JvmStatic
    fun getChatRange(playerId: UUID): String {
        return PlayerChatRangeState.get(playerId)?.name?.lowercase(Locale.ROOT) ?: ChatRange.GLOBAL.displayName
    }

    override fun ping(context: RcmdContext): RcmdResult {
        return ok("pong，rcmd正常")
    }

    override fun setChatRange(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return error("此rcmd命令只能由玩家执行")
        val chatRange = ChatRange.fromRcmdValue(context.getString("range"))
        PlayerChatRangeState.set(player.uniqueID, chatRange, PlayerNbtChatRangeStore(player))
        return ok("聊天范围已切换为" + chatRange.displayName)
    }

    override fun requestTpa(context: RcmdContext): RcmdResult {
        val requester = playerOrNull(context.source)
        if (requester == null) {
            return error("此rcmd命令只能由玩家执行")
        }
        val dedicatedServer = server ?: return error("服务器尚未初始化")
        val result =
            TpaService.request(TpaPlayer1710(requester), context.getString("playerName"), TpaPlayerLookup1710(dedicatedServer))
        return toRcmdResult(result)
    }

    override fun acceptTpa(context: RcmdContext): RcmdResult {
        val target = playerOrNull(context.source)
        if (target == null) {
            return error("此rcmd命令只能由玩家执行")
        }
        val dedicatedServer = server ?: return error("服务器尚未初始化")
        val result = TpaService.accept(TpaPlayer1710(target), TpaPlayerLookup1710(dedicatedServer))
        return toRcmdResult(result)
    }

    override fun setHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source)
        if (player == null) {
            return error("此rcmd命令只能由玩家执行")
        }
        val dedicatedServer = server ?: return error("服务器尚未初始化")
        val result = HomeService.setHome(HomePlayer1710(dedicatedServer, player), context.getString("name"))
        return toRcmdResult(result)
    }

    override fun goHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source)
        if (player == null) {
            return error("此rcmd命令只能由玩家执行")
        }
        val dedicatedServer = server ?: return error("服务器尚未初始化")
        val result = HomeService.goHome(HomePlayer1710(dedicatedServer, player), context.getString("name"))
        return toRcmdResult(result)
    }

    override fun listHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source)
        if (player == null) {
            return error("此rcmd命令只能由玩家执行")
        }
        val dedicatedServer = server ?: return error("服务器尚未初始化")
        val result = HomeService.listHomes(HomePlayer1710(dedicatedServer, player))
        return toRcmdResult(result)
    }

    override fun deleteHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source)
        if (player == null) {
            return error("此rcmd命令只能由玩家执行")
        }
        val dedicatedServer = server ?: return error("服务器尚未初始化")
        val result = HomeService.deleteHome(HomePlayer1710(dedicatedServer, player), context.getString("name"))
        return toRcmdResult(result)
    }

    override fun togglePosLock(context: RcmdContext): RcmdResult {
        return error("1.7.10暂不支持位置锁定")
    }

    override fun testEntity(context: RcmdContext): RcmdResult {
        return error("1.7.10暂不支持testentity")
    }

    private fun toRcmdResult(result: TpaResult): RcmdResult {
        return if (result.success) ok(result.message) else error(result.message)
    }

    private fun toRcmdResult(result: HomeResult): RcmdResult {
        return if (result.success) ok(result.message) else error(result.message)
    }

    private fun playerOrNull(source: RcmdSource): EntityPlayerMP? {
        if (source is RcmdServerSource1710) {
            return source.player
        }
        return null
    }

}
