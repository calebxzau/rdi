package calebxzhou.rdi.mc.server.rcmd

import calebxzhou.rdi.mc.common.RDI
import calebxzau.mc.common2021.mcs
import calebxzhou.rdi.mc.firmsection.FirmSectionKey
import calebxzhou.rdi.mc.firmsection.FirmSectionLimits
import calebxzhou.rdi.mc.firmsection.FirmSectionSetStatus
import calebxzhou.rdi.mc.rcmd.*
import calebxzhou.rdi.mc.rcmd.chat.ChatRange
import calebxzhou.rdi.mc.rcmd.chat.PlayerChatRangeState
import calebxzhou.rdi.mc.rcmd.home.HomeResult
import calebxzhou.rdi.mc.rcmd.home.HomeService
import calebxzhou.rdi.mc.rcmd.tpa.TpaResult
import calebxzhou.rdi.mc.rcmd.tpa.TpaService
import calebxzhou.rdi.mc.server.firmsection.FirmSectionService
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent
import net.neoforged.neoforge.event.tick.PlayerTickEvent
import java.util.Locale
import java.util.UUID

@EventBusSubscriber(modid = "rdi")
object RcmdServerCommands : RcmdServerCommandHandler {
    private val DISPATCHER = RcmdDispatcher()
    private val POS_LOCKS = mutableMapOf<UUID, PosLockState>()
    private const val POS_LOCK_MAX_DISTANCE_SQR = 0.0001
    private const val TEST_ENTITY_COUNT = 65535
    private const val TEST_ENTITY_ITEM_COUNT = 32

    init {
        RcmdCommonServerCommands.register(DISPATCHER, this, RDI.DEBUG)
    }

    @JvmStatic
    fun dispatcher(): RcmdDispatcher = DISPATCHER

    @JvmStatic
    fun reply(source: RcmdSource, result: RcmdResult?) {
        if (result == null || result.message.isEmpty()) {
            return
        }
        result.message
            .lineSequence()
            .filter { it.isNotEmpty() }
            .forEach { replyLine(source, result.success, it) }
    }

    private fun replyLine(source: RcmdSource, success: Boolean, message: String) {
        if (success) {
            source.sendFeedback(message)
        } else {
            source.sendError(message)
        }
    }

    fun getChatRange(playerId: UUID): String {
        return PlayerChatRangeState.get(playerId).name.lowercase(Locale.ROOT)
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerTick(event: PlayerTickEvent.Post) {
        val player = event.entity as? ServerPlayer ?: return
        val state = POS_LOCKS[player.uuid] ?: return
        keepPosLocked(player, state)
    }

    @SubscribeEvent
    @JvmStatic
    fun onPlayerLogout(event: PlayerLoggedOutEvent) {
        val player = event.entity
        if (player is ServerPlayer) {
            val state = POS_LOCKS.remove(player.uuid)
            if (state != null) {
                restorePosLockState(player, state)
            }
        }
    }

    override fun ping(context: RcmdContext): RcmdResult = RcmdResult.ok("pong，rcmd正常")

    override fun setChatRange(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val chatRange = ChatRange.fromRcmdValue(context.getString("range"))
        PlayerChatRangeState.set(player.uuid, chatRange, PlayerNbtChatRangeStore(player))
        return RcmdResult.ok("聊天范围已切换为" + chatRange.displayName)
    }

    override fun togglePosLock(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val playerId = player.uuid
        val removed = POS_LOCKS.remove(playerId)
        if (removed != null) {
            restorePosLockState(player, removed)
            return RcmdResult.ok("位置锁定已关闭")
        }
        POS_LOCKS[playerId] = PosLockState.from(player)
        player.isInvulnerable = true
        player.isInvisible = true
        stopPlayerMovement(player)
        return RcmdResult.ok("位置锁定已开启")
    }

    override fun setFirmSection(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = FirmSectionService.set(player)
        val label = firmSectionLabel(result.key)
        return when (result.status) {
            FirmSectionSetStatus.ADDED ->
                RcmdResult.ok("已设为同步区域：$label ${firmSectionCountLabel(result.playerCount, result.total)}")

            FirmSectionSetStatus.ALREADY_PRESENT ->
                RcmdResult.ok("这已经是同步区域了")

            FirmSectionSetStatus.OCCUPIED_BY_OTHER ->
                RcmdResult.error("这早就是其他玩家的同步区域了")

            FirmSectionSetStatus.PLAYER_LIMIT_REACHED ->
                RcmdResult.error("你设的同步区域数量 已经达到了上限${FirmSectionLimits.maxPerson}个")

            FirmSectionSetStatus.TOTAL_LIMIT_REACHED ->
                RcmdResult.error("同步区域数量 已达到全房间上限${FirmSectionLimits.maxTotal}个")
        }
    }

    override fun unsetFirmSection(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = FirmSectionService.unset(player)
        val label = firmSectionLabel(result.key)
        return if (result.removed) {
            RcmdResult.ok("已取消同步区域：$label ${firmSectionCountLabel(result.playerCount, result.total)}")
        } else {
            RcmdResult.ok("这不是同步区域")
        }
    }

    override fun listFirmSections(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
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

    override fun setFirmSectionAutoSet(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val enabled = context.getBool("enabled")
        FirmSectionService.setAutoSetEnabled(player, enabled)
        return RcmdResult.ok("放置容器时 自动设置同步区域 已${if (enabled) "开启" else "关闭"}")
    }

    override fun requestTpa(context: RcmdContext): RcmdResult {
        val requester = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val targetName = context.getString("playerName")
        val result = TpaService.request(TpaPlayer211(requester), targetName, TpaPlayerLookup211(requester.server))
        return toRcmdResult(result)
    }

    override fun acceptTpa(context: RcmdContext): RcmdResult {
        val target = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = TpaService.accept(TpaPlayer211(target), TpaPlayerLookup211(target.server))
        return toRcmdResult(result)
    }

    override fun setHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = HomeService.setHome(HomePlayer211(player), context.getString("name"))
        return toRcmdResult(result)
    }

    override fun goHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = HomeService.goHome(HomePlayer211(player), context.getString("name"))
        return toRcmdResult(result)
    }

    override fun listHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = HomeService.listHomes(HomePlayer211(player))
        return toRcmdResult(result)
    }

    override fun deleteHome(context: RcmdContext): RcmdResult {
        val player = playerOrNull(context.source) ?: return RcmdResult.error("此rcmd命令只能由玩家执行")
        val result = HomeService.deleteHome(HomePlayer211(player), context.getString("name"))
        return toRcmdResult(result)
    }

    override fun testEntity(context: RcmdContext): RcmdResult {
        mcs.execute {


            val player = playerOrNull(context.source) ?: return@execute
            val level = player.serverLevel()
            val x = player.x
            val y = player.y - 3.0
            val z = player.z
            var added = 0
            repeat(TEST_ENTITY_COUNT) {
                val itemEntity = ItemEntity(level, x, y, z, ItemStack(Items.DIAMOND_AXE, TEST_ENTITY_ITEM_COUNT))
                if (level.addFreshEntity(itemEntity)) {
                    added++
                }
            }
        }
        return RcmdResult.ok()
    }

    private fun toRcmdResult(result: TpaResult): RcmdResult =
        if (result.success) RcmdResult.ok(result.message) else RcmdResult.error(result.message)

    private fun toRcmdResult(result: HomeResult): RcmdResult =
        if (result.success) RcmdResult.ok(result.message) else RcmdResult.error(result.message)

    private fun keepPosLocked(player: ServerPlayer, state: PosLockState) {
        player.setInvulnerable(true)
        player.setInvisible(true)
        stopPlayerMovement(player)
        val targetLevel = player.server.getLevel(state.dimension)
        if (targetLevel == null) {
            restorePosLockState(player, state)
            POS_LOCKS.remove(player.uuid)
            return
        }
        val currentPos = player.position()
        val moved = currentPos.distanceToSqr(state.x, state.y, state.z) > POS_LOCK_MAX_DISTANCE_SQR
        if (moved || player.level().dimension() != state.dimension) {
            player.teleportTo(targetLevel, state.x, state.y, state.z, state.yaw, state.pitch)
            stopPlayerMovement(player)
        }
    }

    private fun restorePosLockState(player: ServerPlayer, state: PosLockState) {
        player.setInvulnerable(state.wasInvulnerable)
        player.setInvisible(state.wasInvisible)
        stopPlayerMovement(player)
    }

    private fun stopPlayerMovement(player: ServerPlayer) {
        player.setDeltaMovement(Vec3.ZERO)
        player.resetFallDistance()
    }

    private fun playerOrNull(source: RcmdSource): ServerPlayer? =
        when (source) {
            is RcmdServerSource211 -> source.player
            is RcmdCommandSourceStackSource -> source.player
            else -> null
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

    @JvmRecord
    data class PosLockState(
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
                player.level().dimension(),
                player.x,
                player.y,
                player.z,
                player.yRot,
                player.xRot,
                player.isInvulnerable,
                player.isInvisible
            )
        }
    }
}
