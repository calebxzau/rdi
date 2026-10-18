@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.mixin.AccessorIOUtilities
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Vanilla incremental-save based DM world checkpoint lifecycle. */
object DmWorldCheckpointService {
    private val logger = LoggerFactory.getLogger(DmWorldCheckpointService::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val monitor = Any()
    @Volatile private var binding: Binding? = null
    private var generation = 0L

    private class Binding(val generation: Long, val owner: IntegratedServer, val hostId: UUID, val sessionId: UUID, val config: DmConfig) {
        val root: Path = owner.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        var intervalNanos = config.worldSyncIntervalNanos
        var nextDue = System.nanoTime()
        var sequence: Long? = null
        var running: Job? = null
        var status = "等待世界checkpoint"
        var lastSuccess: String? = null
        var lastError: String? = null
        var lastPauseMs: Long? = null
        var lowPauseCount = 0
    }

    private class Job(val binding: Binding, val saveTasks: CompletableFuture<Void>?) { val cancelled = AtomicBoolean(false) }

    fun begin(hostId: UUID, sessionId: UUID, owner: IntegratedServer, config: DmConfig? = null) {
        synchronized(monitor) {
            binding?.let { DmRegionChangeLog.discard() }
            val resolved = config ?: DmConfig.fromSystemProperty().getOrElse { error -> logger.error("世界checkpoint配置无效", error); return } ?: run { logger.warn("世界checkpoint未启动：DM服务器未配置"); return }
            val created = Binding(++generation, owner, hostId, sessionId, resolved)
            DmRegionChangeLog.activate(created.root)
            binding = created
        }
    }

    fun pause(owner: IntegratedServer, reason: String = "DM连接中断") {
        synchronized(monitor) { val current = binding?.takeIf { it.owner === owner } ?: return; binding = null; current.running?.cancelled?.set(true); DmRegionChangeLog.discard(); current.status = "已暂停：$reason" }
    }

    fun stop(owner: IntegratedServer? = null) {
        synchronized(monitor) { val current = binding ?: return; if (owner != null && current.owner !== owner) return; binding = null; current.running?.cancelled?.set(true); DmRegionChangeLog.discard(); current.status = "已停止" }
    }

    fun status(): String {
        val current = binding ?: return "世界同步：未运行"
        val interval = current.intervalNanos / DmWorldSyncTiming.NANOS_PER_SECOND
        val pause = current.lastPauseMs?.toString() ?: "未测量"
        val success = current.lastSuccess?.let { "，最近成功：$it" } ?: ""
        val error = current.lastError?.let { "，错误：$it" } ?: ""
        return "世界同步：${current.status}，间隔${interval}秒，暂停${pause}毫秒，待处理区块${DmRegionChangeLog.pendingCount()}${success}${error}"
    }

    fun onServerTickPost(event: ServerTickEvent.Post) {
        val current = binding ?: return
        if (event.server !== current.owner || current.owner.isStopped || current.running != null || System.nanoTime() < current.nextDue) return
        current.nextDue = DmWorldSyncTiming.nextDue(current.nextDue, System.nanoTime(), current.intervalNanos)
        if (current.owner.allLevels.any { it.noSave }) { current.status = "世界同步暂停：自动保存已关闭"; return }
        val saveStarted = System.nanoTime()
        val saved = runCatching { current.owner.saveEverything(true, false, false) }.getOrElse { error -> current.status = "世界checkpoint失败"; current.lastError = error.message ?: "世界保存失败"; return }
        if (!saved) { current.status = "世界checkpoint失败"; current.lastError = "世界保存失败"; return }
        val pauseMs = (System.nanoTime() - saveStarted) / 1_000_000L
        current.lastPauseMs = pauseMs
        val previousLow = current.lowPauseCount
        current.lowPauseCount = if (pauseMs < DmWorldSyncTiming.PAUSE_BUDGET_MILLIS / 2) previousLow + 1 else 0
        current.intervalNanos = DmWorldSyncTiming.adaptInterval(current.intervalNanos, pauseMs, previousLow)
        val saveTasks = runCatching { AccessorIOUtilities.`rdi$getSaveDataTasks`() }.getOrElse { error -> current.status = "世界checkpoint失败"; current.lastError = error.message ?: "无法读取保存任务"; return }
        val job = Job(current, saveTasks); current.running = job; current.status = "正在保存世界"
        executor.execute { runJob(job) }
    }

    private fun runJob(job: Job) {
        val current = job.binding
        val staging = runCatching { Files.createTempDirectory("rdi-dm-checkpoint-") }.getOrElse { error -> finishFailure(current, job, error); return }
        try {
            job.saveTasks?.let { tasks -> runCatching { tasks.get(DmWorldSyncTiming.SAVE_WAIT_NANOS, TimeUnit.NANOSECONDS) }
                .getOrElse { error -> throw IllegalStateException("等待世界保存任务失败", error) } }
            check(!job.cancelled.get() && binding === current) { "世界checkpoint已取消" }
            val client = DmHttpClient(current.config)
            val remoteFiles = client.getWorldCheckpointFiles(current.hostId, current.sessionId).getOrThrow()
            val remoteRegions = client.getWorldCheckpointRegions(current.hostId, current.sessionId).getOrThrow().associate { it.path to it.digest }
            val world = DmWorldDataCapture.capture(current.root, staging.resolve("world"), DmWorldSyncPolicy.EMPTY, null, System.nanoTime() + DmWorldSyncTiming.WORLD_CAPTURE_NANOS) { job.cancelled.get() || binding !== current }.getOrThrow()
            val scanned = DmRegionScanner.scan(current.root).getOrThrow()
            val reconcile = DmRegionChangeLog.needsReconcile()
            scanned.filter { reconcile || remoteRegions[it.path] != it.table.digest() }.flatMap { it.changes }.forEach { DmRegionChangeLog.recordScan(it) }
            (remoteRegions.keys - scanned.map { it.path }.toSet()).forEach { region ->
                client.getWorldCheckpointRegion(current.hostId, current.sessionId, region).getOrThrow().forEachIndexed { index, slot ->
                    if (slot.sectors > 0) DmRegionChangeLog.recordClear(region, index, slot.timestamp)
                }
            }
            DmRegionChangeLog.releaseReconcile()
            val changes = DmRegionChangeLog.drain()
            val remoteStatus = if (current.sequence == null) client.getWorldCheckpointStatus(current.hostId, current.sessionId).getOrThrow() else null
            val baselineComplete = remoteStatus?.baselineComplete != true
            val sequence = current.sequence?.plus(1) ?: remoteStatus?.lastUploadSequence?.plus(1) ?: 1L
            current.sequence = sequence
            val cycleId = UUID.fromString(kotlin.uuid.Uuid.generateV7().toString())
            val output = staging.resolve("checkpoint.zip")
            val previous = remoteFiles.files.associateBy { it.path }
            val changedFiles = world.files.filter { file -> previous[file.path]?.let { it.bytes == file.bytes && it.sha1 == file.sha1 } != true }.map { it.path }
            val removedFiles = previous.keys - world.files.map { it.path }.toSet()
            val archive = DmWorldCheckpointArchive.write(DmWorldCheckpointArchive.Request(current.hostId, current.sessionId, cycleId, sequence, baselineComplete, staging.resolve("world"), changedFiles, removedFiles.toList(), world.directories, changes), output).getOrThrow()
            val receipt = try {
                client.uploadWorldCheckpoint(current.hostId, archive.path, current.sessionId, sequence, cycleId, archive.sha1).getOrThrow()
            } catch (error: Throwable) {
                DmRegionChangeLog.mergeBack(changes)
                throw error
            }
            current.lastSuccess = "序列${receipt.sequence}，${archive.bytes}字节"
            DmRegionChangeLog.clearNeedsReconcile()
            current.lastError = null
            current.status = "世界checkpoint已完成"
        } catch (error: Throwable) { DmRegionChangeLog.releaseReconcile(); finishFailure(current, job, error) }
        finally { DmWorldInitArchive.deleteRecursively(staging); synchronized(monitor) { if (current.running === job) current.running = null } }
    }

    private fun finishFailure(current: Binding, job: Job, error: Throwable) { current.lastError = error.message ?: error::class.simpleName ?: "未知错误"; current.status = "世界checkpoint失败"; logger.error("DM世界checkpoint失败", error) }
}
