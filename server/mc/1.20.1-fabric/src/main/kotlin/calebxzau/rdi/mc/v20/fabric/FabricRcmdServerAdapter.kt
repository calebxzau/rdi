package calebxzau.rdi.mc.v20.fabric

import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.rcmd.Rcmd
import calebxzhou.rdi.mc.rcmd.RcmdCommonServerCommands
import calebxzhou.rdi.mc.rcmd.RcmdContext
import calebxzhou.rdi.mc.rcmd.RcmdDispatcher
import calebxzhou.rdi.mc.rcmd.RcmdResult
import calebxzhou.rdi.mc.rcmd.RcmdServerCommandHandler
import calebxzhou.rdi.mc.rcmd.RcmdSource
import calebxzhou.rdi.mc.rcmd.chat.ChatRange
import calebxzhou.rdi.mc.rcmd.chat.PlayerChatRangeState
import calebxzhou.rdi.mc.rcmd.home.HomeResult
import calebxzhou.rdi.mc.rcmd.home.HomeService
import calebxzhou.rdi.mc.rcmd.tpa.TpaPlayer
import calebxzhou.rdi.mc.rcmd.tpa.TpaPlayerLookup
import calebxzhou.rdi.mc.rcmd.tpa.TpaResult
import calebxzhou.rdi.mc.rcmd.tpa.TpaService
import calebxzau.rdi.mc.v20.server.rcmd.AbstractRcmdServerAdapter20
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.util.UUID

class FabricRcmdServerAdapter(server: MinecraftServer) : AbstractRcmdServerAdapter20(server), RcmdServerCommandHandler {
    private val dispatcher = RcmdDispatcher().also {
        RcmdCommonServerCommands.register(it, this, RDI.DEBUG, includeFirmSection = false)
    }
    private val posLocks = mutableMapOf<UUID, PosLockState>()

    override fun dispatchCommand(source: Any, command: String): Int? {
        val normalized = command.removePrefix("/")
        if (!Rcmd.isRcmd(normalized)) return null
        val commandSource = source as? CommandSourceStack ?: return null
        val rcmdSource = FabricRcmdSource(commandSource)
        val result = dispatcher.execute(rcmdSource, normalized)
        reply(rcmdSource, result)
        return if (result.success) 1 else 0
    }

    override fun dispatchPlayerRcmd(player: ServerPlayer, message: String): Boolean {
        val source = FabricRcmdSource(player.createCommandSourceStack())
        reply(source, dispatcher.execute(source, message))
        return true
    }

    fun onPlayerJoin(player: ServerPlayer) {
        PlayerChatRangeState.restore(player.uuid, FabricPlayerRdiStore(player))
    }

    fun onPlayerDisconnect(player: ServerPlayer) {
        PlayerChatRangeState.remove(player.uuid)
        TpaService.removeRelated(player.uuid)
        posLocks.remove(player.uuid)?.let { restorePosLockState(player, it) }
    }

    fun tick() {
        server.playerList.players.forEach { player ->
            posLocks[player.uuid]?.let { keepPosLocked(player, it) }
        }
    }

    fun clear() {
        posLocks.clear()
        PlayerChatRangeState.clear()
        TpaService.clear()
    }

    override fun ping(context: RcmdContext): RcmdResult = RcmdResult.ok("pong，rcmd正常")

    override fun setChatRange(context: RcmdContext): RcmdResult {
        val player = player(context.source) ?: return playerOnly()
        val range = ChatRange.fromRcmdValue(context.getString("range"))
        PlayerChatRangeState.set(player.uuid, range, FabricPlayerRdiStore(player))
        return RcmdResult.ok("聊天范围已切换为${range.displayName}")
    }

    override fun requestTpa(context: RcmdContext): RcmdResult {
        val requester = player(context.source) ?: return playerOnly()
        return TpaService.request(FabricTpaPlayer(requester), context.getString("playerName"), FabricTpaLookup(server)).toRcmdResult()
    }

    override fun acceptTpa(context: RcmdContext): RcmdResult {
        val target = player(context.source) ?: return playerOnly()
        return TpaService.accept(FabricTpaPlayer(target), FabricTpaLookup(server)).toRcmdResult()
    }

    override fun setHome(context: RcmdContext): RcmdResult {
        val player = player(context.source) ?: return playerOnly()
        return HomeService.setHome(FabricPlayerRdiStore(player), context.getString("name")).toRcmdResult()
    }

    override fun goHome(context: RcmdContext): RcmdResult {
        val player = player(context.source) ?: return playerOnly()
        return HomeService.goHome(FabricPlayerRdiStore(player), context.getString("name")).toRcmdResult()
    }

    override fun listHome(context: RcmdContext): RcmdResult {
        val player = player(context.source) ?: return playerOnly()
        return HomeService.listHomes(FabricPlayerRdiStore(player)).toRcmdResult()
    }

    override fun deleteHome(context: RcmdContext): RcmdResult {
        val player = player(context.source) ?: return playerOnly()
        return HomeService.deleteHome(FabricPlayerRdiStore(player), context.getString("name")).toRcmdResult()
    }

    override fun togglePosLock(context: RcmdContext): RcmdResult {
        val player = player(context.source) ?: return playerOnly()
        posLocks.remove(player.uuid)?.let {
            restorePosLockState(player, it)
            return RcmdResult.ok("位置锁定已关闭")
        }
        posLocks[player.uuid] = PosLockState.from(player)
        player.isInvulnerable = true
        player.isInvisible = true
        stopPlayerMovement(player)
        return RcmdResult.ok("位置锁定已开启")
    }

    override fun testEntity(context: RcmdContext): RcmdResult {
        val player = player(context.source) ?: return playerOnly()
        server.execute {
            val level = player.serverLevel()
            repeat(TEST_ENTITY_COUNT) {
                level.addFreshEntity(
                    ItemEntity(level, player.x, player.y - 3.0, player.z, ItemStack(Items.DIAMOND_AXE, TEST_ENTITY_ITEM_COUNT))
                )
            }
        }
        return RcmdResult.ok()
    }

    private fun keepPosLocked(player: ServerPlayer, state: PosLockState) {
        player.isInvulnerable = true
        player.isInvisible = true
        stopPlayerMovement(player)
        val targetLevel = server.getLevel(state.dimension)
        if (targetLevel == null) {
            restorePosLockState(player, state)
            posLocks.remove(player.uuid)
            return
        }
        if (player.position().distanceToSqr(state.x, state.y, state.z) > POS_LOCK_MAX_DISTANCE_SQR || player.level().dimension() != state.dimension) {
            player.teleportTo(targetLevel, state.x, state.y, state.z, state.yaw, state.pitch)
            stopPlayerMovement(player)
        }
    }

    private fun restorePosLockState(player: ServerPlayer, state: PosLockState) {
        player.isInvulnerable = state.wasInvulnerable
        player.isInvisible = state.wasInvisible
        stopPlayerMovement(player)
    }

    private fun stopPlayerMovement(player: ServerPlayer) {
        player.deltaMovement = Vec3.ZERO
        player.resetFallDistance()
    }

    private fun player(source: RcmdSource): ServerPlayer? =
        (source as? FabricRcmdSource)?.player

    private fun playerOnly() = RcmdResult.error("此rcmd命令只能由玩家执行")

    private fun reply(source: RcmdSource, result: RcmdResult) {
        result.message.lineSequence().filter(String::isNotEmpty).forEach { message ->
            if (result.success) source.sendFeedback(message) else source.sendError(message)
        }
    }

    private fun TpaResult.toRcmdResult() = if (success) RcmdResult.ok(message) else RcmdResult.error(message)

    private fun HomeResult.toRcmdResult() = if (success) RcmdResult.ok(message) else RcmdResult.error(message)

    private data class PosLockState(
        val dimension: ResourceKey<Level>,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val wasInvulnerable: Boolean,
        val wasInvisible: Boolean
    ) {
        companion object {
            fun from(player: ServerPlayer) = PosLockState(
                player.serverLevel().dimension(), player.x, player.y, player.z, player.yRot, player.xRot,
                player.isInvulnerable, player.isInvisible
            )
        }
    }

    private class FabricRcmdSource(private val source: CommandSourceStack) : RcmdSource {
        val player: ServerPlayer?
            get() = source.entity as? ServerPlayer

        override fun name(): String = source.textName
        override fun playerId(): UUID = player?.uuid ?: RcmdSource.NO_PLAYER_ID
        override fun hasPermission(permission: String): Boolean = source.hasPermission(4)
        override fun sendFeedback(message: String) = source.sendSuccess({ Component.literal(message) }, false)
        override fun sendError(message: String) = source.sendFailure(Component.literal("[rcmd] $message"))
    }

    private class FabricTpaPlayer(val player: ServerPlayer) : TpaPlayer {
        override fun id(): UUID = player.uuid
        override fun name(): String = player.gameProfile.name
        override fun sendMessage(message: String) = player.sendSystemMessage(Component.literal(message))
    }

    private class FabricTpaLookup(private val server: MinecraftServer) : TpaPlayerLookup {
        override fun findByName(name: String): TpaPlayer? = server.playerList.getPlayerByName(name)?.let(::FabricTpaPlayer)
        override fun findById(id: UUID): TpaPlayer? = server.playerList.getPlayer(id)?.let(::FabricTpaPlayer)

        override fun teleportTo(requester: TpaPlayer, target: TpaPlayer) {
            val requesterPlayer = (requester as FabricTpaPlayer).player
            val targetPlayer = (target as FabricTpaPlayer).player
            requesterPlayer.teleportTo(
                targetPlayer.serverLevel(), targetPlayer.x, targetPlayer.y, targetPlayer.z,
                targetPlayer.yRot, targetPlayer.xRot
            )
        }
    }

    private companion object {
        const val POS_LOCK_MAX_DISTANCE_SQR = 0.0001
        const val TEST_ENTITY_COUNT = 65535
        const val TEST_ENTITY_ITEM_COUNT = 32
    }
}
