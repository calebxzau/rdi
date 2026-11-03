@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.mixin.AccessorIOUtilities
import calebxzau.rdi.mc.client.syncchunk.SyncChunkService
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import net.minecraft.client.server.IntegratedServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Vanilla incremental-save based DM world checkpoint lifecycle. */
object DmWorldCheckpointService {
    private val logger = LoggerFactory.getLogger(DmWorldCheckpointService::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val monitor = Any()
    /** How long saving and quitting may wait for the final checkpoint before going offline without it. */
    private const val FINAL_CHECKPOINT_MILLIS = 20_000L
    @Volatile private var binding: Binding? = null
    private var generation = 0L

    private class Binding(
        val generation: Long,
        val owner: IntegratedServer,
        val hostId: UUID,
        val sessionId: UUID,
        val config: DmConfig,
        val mods: DmModFingerprint?,
        val playerId: UUID?,
    ) {
        val root: Path = owner.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        var intervalNanos = config.worldSyncIntervalNanos
        var nextDue = System.nanoTime()
        val uploads = DmCheckpointUploadState(sessionId)
        @Volatile var running: Job? = null
        var status = "等待世界checkpoint"
        var lastSuccess: String? = null
        var lastError: String? = null
        var lastPauseMs: Long? = null
        var lowPauseCount = 0
        val players = DmPlayerPositions()
        /** Master region tables by world-relative path; only the running job touches it. */
        val remoteTables = HashMap<String, DmRegionTable>()
        /** Taken while players are still online, for the checkpoint after the world's last save. */
        @Volatile var finalKeep: Set<SyncChunkKey>? = null
    }

    private class Job(val binding: Binding, val saveTasks: CompletableFuture<Void>?, val keep: Set<SyncChunkKey>) { val cancelled = AtomicBoolean(false) }

    /** [mods] go into every checkpoint so the room keeps the fingerprint of whoever hosts it. */
    fun begin(hostId: UUID, sessionId: UUID, owner: IntegratedServer, config: DmConfig? = null, mods: DmModFingerprint? = null, playerId: UUID? = null) {
        synchronized(monitor) {
            binding?.let { retire(it); DmRegionChangeLog.discard() }
            val resolved = config ?: DmConfig.fromSystemProperty().getOrElse { error -> logger.error("世界checkpoint配置无效", error); return } ?: run { logger.warn("世界checkpoint未启动：DM服务器未配置"); return }
            val created = Binding(++generation, owner, hostId, sessionId, resolved, mods, playerId)
            created.players.load(created.root)
                .onSuccess { count -> logger.info("Loaded {} saved DM player positions", count) }
                .onFailure { error -> logger.error("Failed to load saved DM player positions", error) }
            DmRegionChangeLog.activate(created.root)
            binding = created
        }
    }

    fun pause(owner: IntegratedServer, reason: String = "DM连接中断") {
        synchronized(monitor) { val current = binding?.takeIf { it.owner === owner } ?: return; binding = null; retire(current); DmRegionChangeLog.discard(); current.status = "已暂停：$reason" }
    }

    fun stop(owner: IntegratedServer? = null) {
        synchronized(monitor) { val current = binding ?: return; if (owner != null && current.owner !== owner) return; binding = null; retire(current); DmRegionChangeLog.discard(); current.status = "已停止" }
    }

    /** Under monitor: an active worker owns its ZIP until it returns from HTTP/I/O. */
    private fun retire(current: Binding) {
        current.running?.cancelled?.set(true)
        if (current.running == null) {
            current.uploads.discard()?.let { pending -> executor.execute { cleanupStaging(pending.staging) } }
        }
    }

    fun status(): String {
        val current = binding ?: return "世界同步：未运行"
        val interval = current.intervalNanos / DmWorldSyncTiming.NANOS_PER_SECOND
        val pause = current.lastPauseMs?.toString() ?: "未测量"
        val success = current.lastSuccess?.let { "，最近成功：$it" } ?: ""
        val error = current.lastError?.let { "，错误：$it" } ?: ""
        return "世界同步：${current.status}，间隔${interval}秒，暂停${pause}毫秒，待处理区块${DmRegionChangeLog.pendingCount()}${success}${error}"
    }

    fun onPlayerLoggedOut(player: ServerPlayer) {
        binding?.takeIf { it.owner === player.server }?.players?.update(player)
    }

    /** The chunks this checkpoint keeps; fails while the sync chunk list is unavailable, so nothing is cleared by mistake. */
    private fun keepSet(current: Binding): Result<Set<SyncChunkKey>> =
        SyncChunkService.backupSnapshot(current.owner, current.hostId, current.sessionId).map { snapshot ->
            current.owner.playerList.players.forEach(current.players::update)
            DmCheckpointKeepSet.build(snapshot.chunks, current.players.all())
        }

    /** Server thread, before players are saved and removed: the chunks the final checkpoint keeps. */
    fun prepareFinal(owner: IntegratedServer) {
        val current = binding?.takeIf { it.owner === owner } ?: return
        keepSet(current)
            .onSuccess { keep ->
                DmRegionChangeLog.setKeep(keep)
                current.finalKeep = keep
            }
            .onFailure { error -> logger.warn("No final DM checkpoint for {}: the sync chunk list is unavailable", current.hostId, error) }
    }

    /**
     * Server thread, after the world's last save and before the host goes offline: uploads what changed
     * since the last checkpoint, so whoever takes over does not lose it. Saving and quitting waits for
     * this at most [FINAL_CHECKPOINT_MILLIS]; on failure the room keeps the previous checkpoint.
     */
    fun runFinal(owner: IntegratedServer) {
        val current = binding?.takeIf { it.owner === owner } ?: return
        try {
            val keep = current.finalKeep ?: return
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FINAL_CHECKPOINT_MILLIS)
            // A periodic checkpoint still uploading owns the region change log until it finishes.
            while (current.running != null && System.nanoTime() < deadline) Thread.sleep(50)
            if (current.running != null) {
                logger.warn("No final DM checkpoint for {}: the previous checkpoint is still uploading", current.hostId)
                return
            }
            val saveTasks = runCatching { AccessorIOUtilities.`rdi$getSaveDataTasks`() }.getOrElse { error ->
                logger.warn("No final DM checkpoint for {}: cannot read the save tasks", current.hostId, error)
                return
            }
            val job = Job(current, saveTasks, keep)
            synchronized(monitor) { current.running = job }
            current.status = "正在上传最终checkpoint"
            logger.info("Uploading the final DM checkpoint for {} before going offline", current.hostId)
            val upload = executor.submit { runJob(job) }
            try {
                upload.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                job.cancelled.set(true)
                upload.cancel(true)
                logger.warn("The final DM checkpoint for {} did not finish in {} ms; going offline without it", current.hostId, FINAL_CHECKPOINT_MILLIS)
            }
        } catch (error: Throwable) {
            logger.error("The final DM checkpoint for {} failed", current.hostId, error)
        } finally {
            stop(owner)
        }
    }

    fun onServerTickPost(event: ServerTickEvent.Post) {
        val current = binding ?: return
        if (event.server !== current.owner || current.owner.isStopped || current.running != null || System.nanoTime() < current.nextDue) return
        current.nextDue = DmWorldSyncTiming.nextDue(current.nextDue, System.nanoTime(), current.intervalNanos)
        if (current.owner.allLevels.any { it.noSave }) { current.status = "世界同步暂停：自动保存已关闭"; return }
        val keep = keepSet(current).getOrElse { error -> current.status = "世界同步等待同步区块列表"; current.lastError = error.message ?: "同步区块列表不可用"; return }
        DmRegionChangeLog.setKeep(keep)
        val saveStarted = System.nanoTime()
        val saved = runCatching { current.owner.saveEverything(true, false, false) }.getOrElse { error -> current.status = "世界checkpoint失败"; current.lastError = error.message ?: "世界保存失败"; return }
        if (!saved) { current.status = "世界checkpoint失败"; current.lastError = "世界保存失败"; return }
        val pauseMs = (System.nanoTime() - saveStarted) / 1_000_000L
        current.lastPauseMs = pauseMs
        val previousLow = current.lowPauseCount
        current.lowPauseCount = if (pauseMs < DmWorldSyncTiming.PAUSE_BUDGET_MILLIS / 2) previousLow + 1 else 0
        current.intervalNanos = DmWorldSyncTiming.adaptInterval(current.intervalNanos, pauseMs, previousLow)
        val saveTasks = runCatching { AccessorIOUtilities.`rdi$getSaveDataTasks`() }.getOrElse { error -> current.status = "世界checkpoint失败"; current.lastError = error.message ?: "无法读取保存任务"; return }
        val job = Job(current, saveTasks, keep); current.running = job; current.status = "正在保存世界"
        executor.execute { runJob(job) }
    }

    private fun runJob(job: Job) {
        val current = job.binding
        val started = System.nanoTime()
        var staging: Path? = null
        try {
            val client = DmHttpClient(current.config)
            // Includes the final checkpoint: settle any earlier upload, then capture the latest save.
            resolveUpload(job, client)
            job.saveTasks?.let { tasks -> runCatching { tasks.get(DmWorldSyncTiming.SAVE_WAIT_NANOS, TimeUnit.NANOSECONDS) }
                .getOrElse { error -> throw IllegalStateException("等待世界保存任务失败", error) } }
            checkActive(job)
            val remoteStatus = client.getWorldCheckpointStatus(current.hostId, current.sessionId).getOrThrow()
            val workspace = Files.createTempDirectory("rdi-dm-checkpoint-")
            staging = workspace
            val remoteFiles = client.getWorldCheckpointFiles(current.hostId, current.sessionId).getOrThrow()
            val remoteDigests = client.getWorldCheckpointRegions(current.hostId, current.sessionId).getOrThrow().associate { it.path to it.digest }
            current.remoteTables.keys.retainAll(remoteDigests.keys)
            remoteDigests.forEach { (region, digest) ->
                if (current.remoteTables[region]?.digest() != digest) {
                    current.remoteTables[region] = DmRegionTable(client.getWorldCheckpointRegion(current.hostId, current.sessionId, region).getOrThrow())
                }
            }
            val world = DmWorldDataCapture.capture(current.root, workspace.resolve("world"), DmWorldSyncPolicy.EMPTY, null, System.nanoTime() + DmWorldSyncTiming.WORLD_CAPTURE_NANOS) { job.cancelled.get() || binding !== current }.getOrThrow()
            val localTables = HashMap<String, DmRegionTable?>()
            fun localTable(region: String): DmRegionTable? = localTables.getOrPut(region) {
                current.root.resolve(region).takeIf { Files.isRegularFile(it) }?.let { DmRegionScanner.table(it).getOrThrow() }
            }
            val previous = remoteFiles.files.associateBy { it.path }
            val changedFiles = world.files.filter { file -> previous[file.path]?.let { it.bytes == file.bytes && it.sha1 == file.sha1 } != true }.map { it.path }
            val removedFiles = previous.keys - world.files.map { it.path }.toSet()
            var drained = emptyList<DmRegionChange>()
            current.uploads.prepare(remoteStatus, workspace, restoreChanges = {
                synchronized(monitor) {
                    // Never merge an old session's changes into the next world's log.
                    if (binding === current) DmRegionChangeLog.mergeBack(drained)
                }
            }) { sequence ->
                synchronized(monitor) {
                    checkActive(job)
                    DmRegionChangeLog.releaseReconcile()
                    drained = DmRegionChangeLog.drain()
                }
                val changes = DmCheckpointReconcile.plan(
                    keep = job.keep,
                    logged = drained,
                    remote = current.remoteTables,
                    localRegions = localRegionPaths(current.root),
                    localTable = ::localTable,
                    readLocal = { region, index ->
                        DmRegionScanner.readEntry(current.root.resolve(region), index).getOrElse { error ->
                            logger.warn("Skipping unreadable DM chunk {} slot {} in this checkpoint", region, index, error)
                            null
                        }
                    },
                )
                checkActive(job)
                val cycleId = UUID.fromString(kotlin.uuid.Uuid.generateV7().toString())
                DmWorldCheckpointArchive.write(
                    DmWorldCheckpointArchive.Request(
                        current.hostId, current.sessionId, cycleId, sequence, !remoteStatus.baselineComplete,
                        workspace.resolve("world"), changedFiles, removedFiles.toList(), world.directories, changes,
                        modFingerprint = current.mods, hostPlayerId = current.playerId,
                    ),
                    workspace.resolve("checkpoint.zip"),
                ).getOrThrow()
            }.getOrThrow()
            resolveUpload(job, client)
            current.lastError = null
            current.status = "世界checkpoint已完成"
            logger.debug("DM checkpoint job finished in {} ms", (System.nanoTime() - started) / 1_000_000L)
        } catch (error: Throwable) {
            finishFailure(current, error)
        } finally {
            val cleanup = synchronized(monitor) {
                if (binding === current) DmRegionChangeLog.releaseReconcile()
                val abandoned = if (binding !== current || job.cancelled.get()) current.uploads.discard() else null
                val retained = current.uploads.pending?.staging
                if (current.running === job) current.running = null
                listOfNotNull(staging?.takeIf { it != retained }, abandoned?.staging).distinct()
            }
            cleanup.forEach(::cleanupStaging)
        }
    }

    private fun resolveUpload(job: Job, client: DmHttpClient) {
        val current = job.binding
        val confirmed = current.uploads.resolve(
            readStatus = { client.getWorldCheckpointStatus(current.hostId, current.sessionId) },
            upload = { archive ->
                checkActive(job)
                val metadata = archive.metadata
                client.uploadWorldCheckpoint(current.hostId, archive.path, current.sessionId, metadata.sequence, metadata.cycleId, archive.sha1)
            },
            markPending = { cycle -> synchronized(monitor) {
                checkActive(job)
                DmWorldAssociationStore.markPending(current.root, current.hostId, cycle)
            } },
            markConfirmed = { cycle -> synchronized(monitor) {
                checkActive(job)
                DmWorldAssociationStore.markConfirmed(current.root, current.hostId, cycle)
            } },
            cancelled = { job.cancelled.get() || binding !== current },
        ).getOrThrow() ?: return
        val archive = confirmed.archive
        val metadata = archive.metadata
        current.lastSuccess = "序列${metadata.sequence}，${archive.bytes}字节"
        logger.info("DM世界checkpoint已确认：序列{}，区块{}，清除{}，文件{}，删除文件{}，{}字节",
            metadata.sequence, metadata.chunks.size, metadata.clears.size,
            metadata.files.size, metadata.removedFiles.size, archive.bytes)
        cleanupStaging(confirmed.staging)
    }

    private fun checkActive(job: Job) {
        check(!job.cancelled.get() && binding === job.binding && !Thread.currentThread().isInterrupted) { "世界checkpoint已取消" }
    }

    private fun cleanupStaging(path: Path) {
        DmWorldInitArchive.deleteRecursively(path)
            .onFailure { error -> logger.warn("Failed to clean DM checkpoint staging {}", path, error) }
    }

    private fun localRegionPaths(root: Path): List<String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".mca") }
            .map { root.relativize(it).toString().replace('\\', '/') }
            .toList()
    }

    private fun finishFailure(current: Binding, error: Throwable) { current.lastError = error.message ?: error::class.simpleName ?: "未知错误"; current.status = "世界checkpoint失败"; logger.error("DM世界checkpoint失败", error) }
}
