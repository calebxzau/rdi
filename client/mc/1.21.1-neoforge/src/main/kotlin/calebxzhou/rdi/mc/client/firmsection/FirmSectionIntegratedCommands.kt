package calebxzhou.rdi.mc.client.firmsection

import calebxzhou.rdi.mc.firmsection.FirmSectionKey
import calebxzhou.rdi.mc.firmsection.FirmSectionLimits
import calebxzhou.rdi.mc.firmsection.FirmSectionSetStatus
import calebxzhou.rdi.mc.rcmd.RcmdArgumentTypes
import calebxzhou.rdi.mc.rcmd.RcmdContext
import calebxzhou.rdi.mc.rcmd.RcmdDispatcher
import calebxzhou.rdi.mc.rcmd.RcmdResult
import calebxzhou.rdi.mc.rcmd.RcmdSource
import calebxzhou.rdi.mc.rcmd.RcmdParser
import calebxzhou.rdi.mc.rcmd.Rcmd
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.client.server.IntegratedServer
import java.util.UUID

object FirmSectionIntegratedCommands {
    private val dispatcher = RcmdDispatcher()

    init {
        dispatcher.register(
            calebxzhou.rdi.mc.rcmd.RcmdCommandSpec.builder("firmsection", "set")
                .description("Save current player section")
                .command { context -> set(context) }
                .build()
        )
        dispatcher.register(
            calebxzhou.rdi.mc.rcmd.RcmdCommandSpec.builder("firmsection", "unset")
                .description("Forget current player section")
                .command { context -> unset(context) }
                .build()
        )
        dispatcher.register(
            calebxzhou.rdi.mc.rcmd.RcmdCommandSpec.builder("firmsection", "list")
                .description("List saved firm sections")
                .command { context -> list(context) }
                .build()
        )
        dispatcher.register(
            calebxzhou.rdi.mc.rcmd.RcmdCommandSpec.builder("firmsection", "autoset")
                .description("Toggle automatic firm section creation when placing block entities")
                .argument("enabled", RcmdArgumentTypes.BOOL)
                .command { context -> autoSet(context) }
                .build()
        )
    }

    fun dispatch(minecraft: Minecraft, rawInput: String): Boolean {
        val server = minecraft.singleplayerServer ?: return false
        if (server !is IntegratedServer) {
            return false
        }
        if (!isFirmSectionCommand(rawInput)) {
            return false
        }
        val playerId = minecraft.player?.uuid ?: return false
        server.execute {
            val player = server.playerList.getPlayer(playerId) ?: return@execute
            reply(player, dispatcher.execute(IntegratedSource(player), rawInput))
        }
        return true
    }

    private fun isFirmSectionCommand(rawInput: String): Boolean {
        if (!Rcmd.isRcmd(rawInput)) {
            return false
        }
        val tokens = try {
            RcmdParser.tokenize(Rcmd.stripPrefix(rawInput))
        } catch (_: Exception) {
            return Rcmd.stripPrefix(rawInput).trimStart().startsWith("firmsection", ignoreCase = true)
        }
        if (tokens.isEmpty() || !tokens.first().equals("firmsection", ignoreCase = true)) {
            return false
        }
        if (tokens.size == 1) {
            return true
        }
        val command = tokens[1].lowercase()
        return listOf("set", "unset", "list", "autoset").any { it.startsWith(command) }
    }

    private fun set(context: RcmdContext): RcmdResult {
        val player = player(context) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = FirmSectionService.set(player)
        val label = firmSectionLabel(result.key)
        return when (result.status) {
            FirmSectionSetStatus.ADDED ->
                RcmdResult.ok("已设为同步区域：$label ${firmSectionCountLabel(result.playerCount, result.total)}")
            FirmSectionSetStatus.ALREADY_PRESENT -> RcmdResult.ok("这已经是同步区域了")
            FirmSectionSetStatus.OCCUPIED_BY_OTHER -> RcmdResult.error("这早就是其他玩家的同步区域了")
            FirmSectionSetStatus.PLAYER_LIMIT_REACHED ->
                RcmdResult.error("你设的同步区域数量 已经达到了上限${FirmSectionLimits.maxPerson}个")
            FirmSectionSetStatus.TOTAL_LIMIT_REACHED ->
                RcmdResult.error("同步区域数量 已达到全房间上限${FirmSectionLimits.maxTotal}个")
        }
    }

    private fun unset(context: RcmdContext): RcmdResult {
        val player = player(context) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = FirmSectionService.unset(player)
        val label = firmSectionLabel(result.key)
        return if (result.removed) {
            RcmdResult.ok("已取消同步区域：$label ${firmSectionCountLabel(result.playerCount, result.total)}")
        } else {
            RcmdResult.ok("这不是同步区域")
        }
    }

    private fun list(context: RcmdContext): RcmdResult {
        val player = player(context) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = FirmSectionService.list(player)
        if (result.sections.isEmpty()) {
            return RcmdResult.ok("你还没有同步区域。${firmSectionCountLabel(result.playerCount, result.total)}")
        }
        val lines = buildList {
            add("同步区域数量：${firmSectionCountLabel(result.playerCount, result.total)}")
            result.sections.groupBy { it.dimensionId }.forEach { (dimensionId, sections) ->
                add("$dimensionId : ${sections.map { firmSectionPositionLabel(it) }}")
            }
        }
        return RcmdResult.ok(lines.joinToString("\n"))
    }

    private fun autoSet(context: RcmdContext): RcmdResult {
        val player = player(context) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val enabled = context.getBool("enabled")
        FirmSectionService.setAutoSetEnabled(player, enabled)
        return RcmdResult.ok("放置容器时 自动设置同步区域 已${if (enabled) "开启" else "关闭"}")
    }

    private fun player(context: RcmdContext): ServerPlayer? =
        (context.source as? IntegratedSource)?.player

    private fun reply(player: ServerPlayer, result: RcmdResult) {
        if (result.message.isEmpty()) {
            return
        }
        result.message.lineSequence()
            .filter { it.isNotEmpty() }
            .forEach { line ->
                if (result.success) {
                    player.sendSystemMessage(Component.literal(line))
                } else {
                    player.sendSystemMessage(Component.literal("[rcmd] $line"))
                }
            }
    }

    private fun firmSectionLabel(key: FirmSectionKey): String =
        "${key.dimensionId},${key.chunkX},${key.sectionY},${key.chunkZ}"

    private fun firmSectionPositionLabel(key: FirmSectionKey): String =
        "${key.chunkX},${key.sectionY},${key.chunkZ}"

    private fun firmSectionCountLabel(playerCount: Int, total: Int): String =
        if (FirmSectionLimits.maxPerson > 0) {
            "你：${playerCount}/${FirmSectionLimits.maxPerson}，全世界：${total}/${FirmSectionLimits.maxTotal}"
        } else {
            "你：${playerCount}个，全世界：${total}/${FirmSectionLimits.maxTotal}"
        }

    private class IntegratedSource(val player: ServerPlayer) : RcmdSource {
        override fun name(): String = player.gameProfile.name
        override fun playerId(): UUID = player.uuid
        override fun hasPermission(permission: String): Boolean = player.hasPermissions(2)
        override fun sendFeedback(message: String) = player.sendSystemMessage(Component.literal(message))
        override fun sendError(message: String) = player.sendSystemMessage(Component.literal("[rcmd] $message"))
    }
}
