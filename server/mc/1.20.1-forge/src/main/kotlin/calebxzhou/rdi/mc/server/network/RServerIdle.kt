package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.v20.forge.idle.IdleChannel
import calebxzhou.rdi.mc.server.mixin.AClientboundMoveEntityPacket
import calebxzhou.rdi.mc.server.mixin.AClientboundRotateHeadPacket
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAnimatePacket
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.FishingHook
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import org.apache.logging.log4j.LogManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds back what a player who stepped away cannot see or hear, and restores the current state when
 * they come back; the client reports both through [IdleChannel].
 *
 * While away, far sounds, particles and swing animations are dropped. Movement of other entities and
 * block and block entity updates are only recorded; on return the server sends their current state.
 * Everything else, including chunks, spawns, removals, the player's own state and mod payloads, keeps
 * flowing. Only sends on the server thread without a completion listener are held back.
 *
 * On by default; `-Drdi.idle.enabled=false` ignores the reports.
 */
object RServerIdle {
    private val logger = LogManager.getLogger("rdi")

    /** Presentation within this distance still reaches the player, for example a fishing splash. */
    private const val NEAR_DISTANCE = 16.0

    /** Past this many recorded entities and positions, updates pass again; the restore stays bounded. */
    private const val MAXIMUM_RECORDED = 32_768

    private val awayPlayers = ConcurrentHashMap<UUID, AwayState>()

    private class AwayState(var level: ResourceKey<Level>) {
        val entities = IntOpenHashSet()
        val blocks = LongOpenHashSet()
        val blockEntities = LongOpenHashSet()
        var dropped = 0L
        var held = 0L

        val recorded: Int get() = entities.size + blocks.size + blockEntities.size

        fun clear() {
            entities.clear()
            blocks.clear()
            blockEntities.clear()
        }
    }

    internal fun isEnabled(property: (String) -> String?): Boolean =
        property("rdi.idle.enabled")?.trim()?.toBoolean() ?: true

    fun register() {
        IdleChannel.serverHandler = ::onReport
    }

    /** Runs on the server thread. */
    private fun onReport(player: ServerPlayer, away: Boolean) {
        if (!isEnabled(System::getProperty)) return
        if (away) {
            if (awayPlayers.putIfAbsent(player.uuid, AwayState(player.level().dimension())) == null) {
                logger.info("Player {} stepped away; holding back updates they cannot see", player.gameProfile.name)
            }
            return
        }
        val state = awayPlayers.remove(player.uuid) ?: return
        runCatching { restore(player, state) }.onFailure { error ->
            logger.error("Failed to restore the world state of {} after being away", player.gameProfile.name, error)
        }
    }

    fun onLogout(player: ServerPlayer) {
        awayPlayers.remove(player.uuid)
    }

    /** True when [packet] must not be sent now; it was dropped or recorded for the return. */
    @JvmStatic
    fun intercept(player: ServerPlayer, packet: Packet<*>): Boolean {
        val state = awayPlayers[player.uuid] ?: return false
        if (!player.server.isSameThread) return false
        val level = player.serverLevel()
        if (level.dimension() != state.level) {
            // A new dimension arrives as fresh chunks and spawns; nothing recorded in the old one matters.
            state.clear()
            state.level = level.dimension()
        }
        val held = when (packet) {
            is ClientboundSoundPacket -> isFar(player, packet.x, packet.y, packet.z)
            is ClientboundLevelParticlesPacket -> isFar(player, packet.x, packet.y, packet.z)
            is ClientboundLevelEventPacket -> isFar(player, packet.pos)
            is ClientboundBlockDestructionPacket -> isFar(player, packet.pos)
            is ClientboundSoundEntityPacket -> isFarEntity(player, level, packet.id)
            is ClientboundAnimatePacket -> isFarEntity(player, level, packet.id)
            else -> null
        }
        if (held != null) {
            if (held) state.dropped++
            return held
        }
        if (state.recorded >= MAXIMUM_RECORDED) return false
        val recorded = when (packet) {
            is ClientboundMoveEntityPacket -> recordEntity(player, level, state, (packet as AClientboundMoveEntityPacket).entityId)
            is ClientboundRotateHeadPacket -> recordEntity(player, level, state, (packet as AClientboundRotateHeadPacket).entityId)
            is ClientboundSetEntityMotionPacket -> recordEntity(player, level, state, packet.id)
            is ClientboundTeleportEntityPacket -> recordEntity(player, level, state, packet.id)
            is ClientboundBlockUpdatePacket -> state.blocks.add(packet.pos.asLong()).let { true }
            is ClientboundSectionBlocksUpdatePacket -> {
                packet.runUpdates { pos, _ -> state.blocks.add(pos.asLong()) }
                true
            }
            is ClientboundBlockEntityDataPacket -> state.blockEntities.add(packet.pos.asLong()).let { true }
            else -> false
        }
        if (recorded) state.held++
        return recorded
    }

    private fun isFar(player: ServerPlayer, x: Double, y: Double, z: Double): Boolean =
        player.distanceToSqr(x, y, z) > NEAR_DISTANCE * NEAR_DISTANCE

    private fun isFar(player: ServerPlayer, pos: BlockPos): Boolean =
        isFar(player, pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    private fun isFarEntity(player: ServerPlayer, level: ServerLevel, id: Int): Boolean {
        val entity = level.getEntity(id) ?: return false
        return isFar(player, entity.x, entity.y, entity.z)
    }

    /** The player's own vehicle, passengers and fishing hook keep moving on screen. */
    private fun recordEntity(player: ServerPlayer, level: ServerLevel, state: AwayState, id: Int): Boolean {
        val entity = level.getEntity(id) ?: return false
        if (entity.rootVehicle === player.rootVehicle || entity is FishingHook) return false
        state.entities.add(id)
        return true
    }

    /** Sends the current state of everything recorded that the player still tracks. */
    private fun restore(player: ServerPlayer, state: AwayState) {
        val level = player.serverLevel()
        if (level.dimension() != state.level) return
        val chunkMap = level.chunkSource.chunkMap
        val tracked = HashMap<Long, Boolean>()
        fun isTracked(chunk: ChunkPos): Boolean = tracked.getOrPut(chunk.toLong()) {
            chunkMap.getPlayers(chunk, false).contains(player)
        }

        var blockPackets = 0
        for ((section, positions) in state.blocks.groupBy { SectionPos.of(BlockPos.of(it)) }) {
            val chunk = level.chunkSource.getChunkNow(section.x(), section.z()) ?: continue
            if (!isTracked(section.chunk())) continue
            val packet = if (positions.size == 1) {
                ClientboundBlockUpdatePacket(level, BlockPos.of(positions.single()))
            } else {
                val relative = ShortOpenHashSet(positions.size)
                positions.forEach { relative.add(SectionPos.sectionRelativePos(BlockPos.of(it))) }
                ClientboundSectionBlocksUpdatePacket(section, relative, chunk.getSection(chunk.getSectionIndexFromSectionY(section.y())))
            }
            player.connection.send(packet)
            blockPackets++
        }

        var blockEntityPackets = 0
        for (packed in state.blockEntities.toLongArray()) {
            val pos = BlockPos.of(packed)
            if (level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4) == null || !isTracked(ChunkPos(pos))) continue
            val packet = level.getBlockEntity(pos)?.updatePacket ?: continue
            player.connection.send(packet)
            blockEntityPackets++
        }

        // An entity the client no longer knows ignores these; its removal was never held back.
        var entities = 0
        for (id in state.entities.toIntArray()) {
            val entity = level.getEntity(id) ?: continue
            sendEntityState(player, entity)
            entities++
        }
        logger.info(
            "Player {} is back; dropped {} and held {} updates, restored {} block, {} block entity and {} entity states",
            player.gameProfile.name,
            state.dropped,
            state.held,
            blockPackets,
            blockEntityPackets,
            entities,
        )
    }

    private fun sendEntityState(player: ServerPlayer, entity: Entity) {
        player.connection.send(ClientboundTeleportEntityPacket(entity))
        player.connection.send(ClientboundRotateHeadPacket(entity, Mth.floor(entity.yHeadRot * 256.0f / 360.0f).toByte()))
        player.connection.send(ClientboundSetEntityMotionPacket(entity))
    }
}
