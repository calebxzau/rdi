package calebxzau.rdi.mc.client.syncchunk
import calebxzau.rdi.mc.syncchunk.SyncChunkKey

import calebxzau.rdi.mc.client.dm.DmConfig
import calebxzau.rdi.mc.client.dm.DmHttpClient
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.syncchunk.network.RSyncChunksPayload
import net.minecraft.client.server.IntegratedServer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.network.PacketDistributor
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object SyncChunkService {
    private val logger = LoggerFactory.getLogger(SyncChunkService::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "rdi-dm-sync-chunk-retry").apply { isDaemon = true }
    }
    private val lifecycle = AtomicLong()
    private var binding: Binding? = null

    private data class Binding(
        val epoch: Long,
        val scopeId: Long,
        val hostId: UUID,
        val sessionId: UUID,
        val owner: IntegratedServer,
        val controller: DmSyncChunkSessionController,
    )

    fun begin(hostId: UUID, sessionId: UUID, integratedServer: IntegratedServer) {
        val epoch = lifecycle.incrementAndGet()
        integratedServer.execute {
            if (lifecycle.get() != epoch) return@execute
            val previous = binding
            val retained = previous?.takeIf { it.owner === integratedServer && it.hostId == hostId }?.controller?.currentSnapshot()
            previous?.controller?.stop()
            binding = null
            val config = DmConfig.fromSystemProperty().getOrElse { error ->
                notifyAll(integratedServer, "同步区块无法连接DM服务器：${error.message ?: "配置无效"}")
                sendPayload(integratedServer, emptyList())
                return@execute
            } ?: run {
                notifyAll(integratedServer, "同步区块无法连接DM服务器：DM服务器未配置")
                sendPayload(integratedServer, emptyList())
                return@execute
            }
            val transport = object : DmSyncChunkSessionController.Transport {
                private val client = DmHttpClient(config)
                override fun get(hostId: UUID, sessionId: UUID) = client.getSyncChunks(hostId, sessionId)
                override fun add(hostId: UUID, sessionId: UUID, revision: Long, playerId: UUID, chunk: SyncChunkKey) =
                    client.addSyncChunk(hostId, sessionId, revision, playerId, chunk)
                override fun remove(hostId: UUID, sessionId: UUID, revision: Long, playerId: UUID, chunk: SyncChunkKey) =
                    client.removeSyncChunk(hostId, sessionId, revision, playerId, chunk)
            }
            val controller = DmSyncChunkSessionController(
                transport = transport,
                background = executor,
                retryLater = { task -> scheduler.schedule(task, 2, TimeUnit.SECONDS) },
                dispatch = { task -> integratedServer.execute(task) },
                listener = listenerFor(integratedServer, epoch),
                initialSnapshot = retained,
            )
            binding = Binding(epoch, epoch, hostId, sessionId, integratedServer, controller)
            controller.begin(hostId, sessionId)
        }
    }

    fun pause(owner: IntegratedServer, message: String = "同步区块连接暂时中断") {
        val epoch = lifecycle.incrementAndGet()
        owner.execute {
            if (lifecycle.get() != epoch) return@execute
            val current = binding ?: return@execute
            if (current.owner !== owner) return@execute
            binding = current.copy(epoch = epoch)
            current.controller.pause(message)
        }
    }

    fun stop(owner: IntegratedServer) {
        val epoch = lifecycle.incrementAndGet()
        owner.execute {
            if (lifecycle.get() != epoch) return@execute
            val current = binding ?: return@execute
            if (current.owner !== owner) return@execute
            current.controller.stop()
            binding = null
            sendPayload(owner, emptyList())
        }
    }

    fun isDmHost(player: ServerPlayer): Boolean = binding?.owner === player.server
    fun isReady(player: ServerPlayer): Boolean = binding?.let {
        it.owner === player.server && it.epoch == lifecycle.get() && it.controller.canEdit()
    } == true

    fun hasSnapshot(owner: IntegratedServer): Boolean = binding?.takeIf { it.owner === owner }?.controller?.currentSnapshot() != null

    fun add(player: ServerPlayer): Boolean = request(player, DmSyncChunkOperationKind.Add, target(player.serverLevel(), player.blockPosition()))
    fun remove(player: ServerPlayer): Boolean = request(player, DmSyncChunkOperationKind.Remove, target(player.serverLevel(), player.blockPosition()))

    fun hasSyncChunk(level: ServerLevel, chunkPos: ChunkPos): Boolean = binding?.takeIf { it.owner === level.server }?.controller?.currentSnapshot()?.chunks?.any {
        it.key.dimensionId == level.dimension().location().toString() && it.key.chunkX == chunkPos.x && it.key.chunkZ == chunkPos.z
    } == true

    fun all(server: MinecraftServer): List<SyncChunkKey> = binding?.takeIf { it.owner === server }?.controller?.currentSnapshot()?.chunks?.map { it.key } ?: emptyList()

    fun entries(server: MinecraftServer): List<DmSyncChunkEntry> = binding?.takeIf { it.owner === server }?.controller?.currentSnapshot()?.chunks ?: emptyList()
    fun limits(server: MinecraftServer): DmSyncChunkLimits? = binding?.takeIf { it.owner === server }?.controller?.currentSnapshot()?.limits

    fun snapshotForBackup(owner: IntegratedServer): Result<List<SyncChunkKey>> = backupSnapshot(owner).map { it.chunks }

    data class BackupSnapshot(val revision: Long, val chunks: List<SyncChunkKey>)

    fun backupSnapshot(owner: IntegratedServer, expectedHostId: UUID? = null, expectedSessionId: UUID? = null): Result<BackupSnapshot> = runCatching {
        val current = binding ?: error("同步区块资料尚未加载")
        check(current.owner === owner) { "当前世界不是DM房主世界" }
        expectedHostId?.let { check(current.hostId == it) { "DM同步区块房间已变化" } }
        expectedSessionId?.let { check(current.sessionId == it) { "DM同步区块会话已变化" } }
        check(current.epoch == lifecycle.get()) { "DM同步区块会话已变化" }
        check(current.controller.canEdit()) { "DM同步区块资料暂时不可用" }
        val snapshot = current.controller.currentSnapshot() ?: error("同步区块资料尚未加载")
        BackupSnapshot(snapshot.revision, snapshot.chunks.map { it.key })
    }

    fun sendSyncChunksTo(player: ServerPlayer) {
        if (player.server !is IntegratedServer) return
        payload(entries(player.server))?.let { PacketDistributor.sendToPlayer(player, it) }
    }

    private fun request(player: ServerPlayer, kind: DmSyncChunkOperationKind, key: SyncChunkKey): Boolean {
        val server = player.server as? IntegratedServer ?: return false.also { player.sendSystemMessage(Component.literal("同步区块仅能在DM房间中使用")) }
        val current = binding
        if (current?.owner !== server || current.epoch != lifecycle.get() || !current.controller.canEdit()) return false
        return current.controller.request(DmSyncChunkOperation(kind, player.uuid, key))
    }

    private fun listenerFor(server: IntegratedServer, scopeId: Long) = object : DmSyncChunkSessionController.Listener {
        private fun active() = lifecycle.get() == binding?.epoch && binding?.owner === server && scopeId == binding?.scopeId

        override fun onSnapshot(snapshot: DmSyncChunkSnapshot, loading: Boolean) {
            if (active()) sendPayload(server, snapshot.chunks)
        }

        override fun onOperationAccepted(operation: DmSyncChunkOperation, mutation: DmSyncChunkMutation) {
            if (!active()) return
            val player = server.playerList.getPlayer(operation.playerId) ?: return
            val text = when (mutation.outcome) {
                DmSyncChunkMutation.Outcome.Added -> "已加入同步区块：${label(operation.chunk)}"
                DmSyncChunkMutation.Outcome.AlreadyPresent -> "这个区块已经在同步列表中"
                DmSyncChunkMutation.Outcome.Removed -> "已取消同步区块：${label(operation.chunk)}"
                DmSyncChunkMutation.Outcome.AlreadyAbsent -> "这个区块不在同步列表中"
            }
            player.sendSystemMessage(Component.literal("$text（总计：${mutation.snapshot.chunks.size}/${mutation.snapshot.limits.maxTotal}）"))
        }

        override fun onOperationFailed(operation: DmSyncChunkOperation, message: String) {
            if (active()) server.playerList.getPlayer(operation.playerId)?.sendSystemMessage(Component.literal(message))
        }

        override fun onOperationReconciled(operation: DmSyncChunkOperation, achieved: Boolean) {
            if (active()) server.playerList.getPlayer(operation.playerId)?.sendSystemMessage(Component.literal(if (achieved) "同步区块操作已在DM服务器生效" else "同步区块操作未生效"))
        }

        override fun onUnavailable(message: String) { if (active()) notifyAll(server, message) }
    }

    private fun target(level: ServerLevel, pos: BlockPos): SyncChunkKey {
        val chunk = ChunkPos(pos)
        return SyncChunkKey(level.dimension().location().toString(), chunk.x, chunk.z)
    }

    /** Null when the DM list breaks the shared list rules; the failure is logged and nothing is sent. */
    private fun payload(entries: List<DmSyncChunkEntry>): RSyncChunksPayload? =
        runCatching { RSyncChunksPayload.of(SyncChunkList(entries.map { it.key })) }.getOrElse { exception ->
            logger.error("Failed to prepare the DM sync chunk list", exception)
            null
        }

    private fun sendPayload(server: MinecraftServer, entries: List<DmSyncChunkEntry>) {
        if (server !is IntegratedServer) return
        val payload = payload(entries) ?: return
        server.playerList.players.forEach { PacketDistributor.sendToPlayer(it, payload) }
    }

    private fun notifyAll(server: MinecraftServer, message: String) = server.playerList.players.forEach { it.sendSystemMessage(Component.literal(message)) }
    private fun label(key: SyncChunkKey) = "${key.dimensionId},${key.chunkX},${key.chunkZ}"
}
