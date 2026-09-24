package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.syncchunk.SyncChunkService
import calebxzau.rdi.mc.client.mixin.AccessorSectionStorage
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Manual, local-only benchmark for the FirmSection backup payload. */
object DmSnapshotTestService {
    private const val MAX_COLUMNS = 256
    private const val MAX_STAGING_BYTES = 256L * 1024L * 1024L
    private const val MAX_RUNS = 64
    private val logger = LoggerFactory.getLogger(DmSnapshotTestService::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private val active = AtomicBoolean(false)

    @Volatile
    private var current: Job? = null

    private class Job(
        val minecraft: Minecraft,
        val owner: IntegratedServer,
        val worldRoot: Path,
        val runDirectory: Path,
        val stagingDirectory: Path,
        val archive: Path,
        val mode: DmSnapshotMode,
        val lease: DmSnapshotOperationGate.Lease,
    ) {
        val cancelled = AtomicBoolean(false)
        val tickMonitor = Object()
        @Volatile var captureStarted = false
        @Volatile var hasPreCaptureTick = false
        @Volatile var captureEndedNanos: Long? = null
        @Volatile var capturedAt: String? = null
        @Volatile var pauseMs: Long? = null
        @Volatile var queueMs: Long? = null
        @Volatile var saveFlushMs: Long? = null
        @Volatile var copyMs: Long? = null
        @Volatile var packMs: Long? = null
        @Volatile var zipBytes: Long? = null
        @Volatile var maxTickGapNanos: Long? = null
        @Volatile var firstPostCaptureTickGapNanos: Long? = null
        @Volatile var lastTickNanos: Long? = null
        @Volatile var stage: String = "queued"
        @Volatile var failure: Throwable? = null
        @Volatile var failureStage: String? = null
        @Volatile var columns: List<DmSnapshotArchive.Column> = emptyList()
        @Volatile var records: List<ColumnRecord> = emptyList()
        @Volatile var memoryFuture: CompletableFuture<DmSnapshotMemoryCapture.Batch>? = null
        @Volatile var memoryBatch: DmSnapshotMemoryCapture.Batch? = null
        @Volatile var memoryDeadlineNanos: Long? = null
        @Volatile var readinessStartedNanos: Long? = null
        @Volatile var captureEnqueuedNanos: Long? = null
        @Volatile var readinessWaitMs: Long? = null
        @Volatile var captureTerrainMs: Long? = null
        @Volatile var captureEntitiesMs: Long? = null
        @Volatile var capturePoiMs: Long? = null
        @Volatile var captureOtherMs: Long? = null
        @Volatile var storageWaitMs: Long? = null
        @Volatile var writeMs: Long? = null
        @Volatile var maxObservedPreflightMs: Long? = null
        val readiness = DmSnapshotReadiness()
        @Volatile var memoryColumns: Int? = null
        @Volatile var storedColumns: Int? = null
        @Volatile var mixedColumns: Int? = null
        @Volatile var memoryEntityColumns: Int? = null
        @Volatile var storedEntityColumns: Int? = null
        @Volatile var gameTimes: Map<String, Long> = emptyMap()
    }

    private data class ColumnRecord(
        val column: DmSnapshotArchive.Column,
        val terrainPath: String,
        val entitiesPath: String?,
        val poiPath: String?,
        val entitiesAbsent: Boolean,
        val poiAbsent: Boolean,
        val sourceMode: String,
    )

    fun start(mode: DmSnapshotMode = DmSnapshotMode.Disk): Result<Unit> {
        if (!active.compareAndSet(false, true)) return Result.failure(IllegalStateException("同步区块快照测试正在进行"))
        val lease = DmSnapshotOperationGate.tryAcquire("manual") ?: run {
            active.set(false)
            return Result.failure(IllegalStateException("同步区块同步任务正在进行"))
        }
        val minecraft = Minecraft.getInstance()
        val owner = minecraft.getSingleplayerServer()
        if (!minecraft.hasSingleplayerServer() || minecraft.player == null || owner == null) {
            active.set(false)
            lease.release()
            return Result.failure(IllegalStateException("请先进入单人世界"))
        }
        send(minecraft, "开始同步区块快照测试（${mode.displayName}）")
        executor.submit {
            try {
                val worldRoot = owner.getWorldPath(LevelResource.ROOT).toRealPath()
                val base = minecraft.gameDirectory.toPath().resolve("rdi-snapshot-tests").toAbsolutePath().normalize()
                Files.createDirectories(base)
                val canonicalBase = base.toRealPath()
                require(!canonicalBase.startsWith(worldRoot)) { "快照测试目录不能位于世界目录内" }
                val runCount = Files.list(canonicalBase).use { paths ->
                    paths.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().startsWith("run-") }.count()
                }
                require(runCount < MAX_RUNS.toLong()) { "快照测试目录已达到${MAX_RUNS}次上限，请先保留或移动旧结果" }
                val run = Files.createTempDirectory(canonicalBase, "run-")
                val staging = run.resolve("staging")
                Files.createDirectories(staging)
                val job = Job(minecraft, owner, worldRoot, run, staging, run.resolve("snapshot.zip"), mode, lease)
                current = job
                run(job)
            } catch (error: Throwable) {
                current = null
                active.set(false)
                lease.release()
                logger.error("Failed to prepare FirmChunk snapshot test", error)
                send(minecraft, "同步区块快照测试无法开始：${error.message ?: "未知错误"}")
            }
        }
        return Result.success(Unit)
    }

    fun cancel(): Boolean {
        val job = current ?: return false
        job.cancelled.set(true)
        synchronized(job.tickMonitor) { job.tickMonitor.notifyAll() }
        return true
    }

    fun onServerTick(event: ServerTickEvent.Pre) {
        val job = current ?: return
        if (event.server !== job.owner) return
        val now = System.nanoTime()
        synchronized(job.tickMonitor) {
            if (job.captureStarted) return
            val previous = job.lastTickNanos
            if (previous != null) {
                val gap = (now - previous).coerceAtLeast(0L)
                job.maxTickGapNanos = max(job.maxTickGapNanos ?: 0L, gap)
                if (job.hasPreCaptureTick && job.captureEndedNanos != null && job.firstPostCaptureTickGapNanos == null) {
                    job.firstPostCaptureTickGapNanos = gap
                }
            }
            job.lastTickNanos = now
            job.tickMonitor.notifyAll()
        }
    }

    fun onServerTickPost(event: ServerTickEvent.Post) {
        val job = current ?: return
        if (job.mode != DmSnapshotMode.Memory || event.server !== job.owner) return
        val future = job.memoryFuture ?: return
        if (future.isDone || job.cancelled.get() || job.owner.isStopped || job.minecraft.getSingleplayerServer() !== job.owner) return
        val started = System.nanoTime()
        try {
            if (job.readinessStartedNanos == null) job.readinessStartedNanos = started
            checkCurrentOwner(job)
            val selected = SyncChunkService.snapshotForBackup(job.owner).getOrThrow()
            val columns = DmSnapshotArchive.columns(selected)
            require(columns.isNotEmpty()) { "当前没有可保存的同步区块" }
            require(columns.size <= MAX_COLUMNS) { "同步区块数量${columns.size}超过${MAX_COLUMNS}列手动测试上限" }
            job.columns = columns
            if (job.queueMs == null) job.queueMs = ((started - (job.captureEnqueuedNanos ?: started)) / 1_000_000L).coerceAtLeast(0L)
            val deadline = job.memoryDeadlineNanos ?: (started + TimeUnit.SECONDS.toNanos(5)).also { job.memoryDeadlineNanos = it }
            try {
                DmSnapshotMemoryCapture.preflight(job.owner, columns)
                job.readiness.clear()
            } catch (deferred: DmSnapshotMemoryCapture.Deferred) {
                job.readiness.update(deferred.blockers.map(::formatReadinessBlocker))
                job.maxObservedPreflightMs = max(job.maxObservedPreflightMs ?: 0L, (System.nanoTime() - started) / 1_000_000L)
                if (started > deadline) {
                    job.readinessWaitMs = ((started - (job.readinessStartedNanos ?: started)) / 1_000_000L).coerceAtLeast(0L)
                    future.completeExceptionally(IllegalStateException("等待选中列就绪超时：${job.readiness.describe()}"))
                }
                return
            }
            job.maxObservedPreflightMs = max(job.maxObservedPreflightMs ?: 0L, (System.nanoTime() - started) / 1_000_000L)
            val appender = DmSnapshotSaveErrors().also { it.install() }
            job.captureStarted = true
            job.hasPreCaptureTick = job.lastTickNanos != null
            job.capturedAt = Instant.now().toString()
            val captureStart = System.nanoTime()
            job.readinessWaitMs = ((captureStart - (job.readinessStartedNanos ?: captureStart)) / 1_000_000L).coerceAtLeast(0L)
            var capturedBatch: DmSnapshotMemoryCapture.Batch? = null
            try {
                val batch = DmSnapshotMemoryCapture.capture(job.owner, columns, { job.cancelled.get() }, executor)
                val finalPreflightStart = System.nanoTime()
                try {
                    DmSnapshotMemoryCapture.preflight(job.owner, columns)
                    job.readiness.clear()
                } catch (deferred: DmSnapshotMemoryCapture.Deferred) {
                    job.readiness.update(deferred.blockers.map(::formatReadinessBlocker))
                    throw deferred
                }
                job.maxObservedPreflightMs = max(job.maxObservedPreflightMs ?: 0L, (System.nanoTime() - finalPreflightStart) / 1_000_000L)
                val afterCapture = DmSnapshotArchive.columns(SyncChunkService.snapshotForBackup(job.owner).getOrThrow())
                require(afterCapture == columns) { "同步区块选择在捕获期间发生变化" }
                require(batch.memoryColumns + batch.storedColumns + batch.mixedColumns == columns.size) { "FirmChunk来源分类计数不一致" }
                require(batch.memoryEntityColumns + batch.storedEntityColumns == columns.size) { "FirmChunk实体来源分类计数不一致" }
                checkSaveErrors(appender.summary())
                val captureEnd = System.nanoTime()
                job.pauseMs = (captureEnd - captureStart) / 1_000_000L
                job.captureTerrainMs = batch.captureTerrainMs
                job.captureEntitiesMs = batch.captureEntitiesMs
                job.capturePoiMs = batch.capturePoiMs
                job.captureOtherMs = (job.pauseMs!! - batch.captureTerrainMs - batch.captureEntitiesMs - batch.capturePoiMs).coerceAtLeast(0L)
                job.memoryColumns = batch.memoryColumns
                job.storedColumns = batch.storedColumns
                job.mixedColumns = batch.mixedColumns
                job.memoryEntityColumns = batch.memoryEntityColumns
                job.storedEntityColumns = batch.storedEntityColumns
                job.gameTimes = batch.gameTimes
                capturedBatch = batch
                job.captureEndedNanos = captureEnd
            } catch (error: Throwable) {
                job.failureStage = "capture"
                if (error is DmSnapshotMemoryCapture.Deferred) {
                    job.readiness.update(error.blockers.map(::formatReadinessBlocker))
                }
                future.completeExceptionally(error)
            } finally {
                val summary = appender.summary()
                appender.remove()
                if (summary.errors > 0 || summary.warnings > 0) {
                    logger.warn("FirmChunk memory snapshot observed save logs: errors={}, warnings={}, messages={}", summary.errors, summary.warnings, summary.messages)
                }
                if (job.captureEndedNanos == null) job.captureEndedNanos = System.nanoTime()
                capturedBatch?.let { future.complete(it) }
                job.captureStarted = false
                synchronized(job.tickMonitor) { job.tickMonitor.notifyAll() }
            }
        } catch (error: Throwable) {
            job.failureStage = "preflight"
            if (error is DmSnapshotMemoryCapture.Deferred) {
                job.readiness.update(error.blockers.map(::formatReadinessBlocker))
            }
            if (!future.isDone) future.completeExceptionally(error)
        }
    }

    private fun run(job: Job) {
        var result = "failed"
        try {
            awaitTick(job, afterCapture = false)
            checkCurrentOwner(job)
            if (job.mode == DmSnapshotMode.Memory) {
                try {
                    submitMemoryCapture(job)
                    writeMemoryBatch(job, job.memoryBatch ?: error("memory capture returned no batch"))
                } finally {
                    job.memoryBatch = null
                    job.memoryFuture = null
                }
            } else submitCapture(job, System.nanoTime())
            writeMetadata(job)
            if (job.cancelled.get()) throw IllegalStateException("世界已离开，快照保留为失败结果")
            checkCurrentOwner(job)
            job.stage = "pack"
            val packStart = System.nanoTime()
            try {
                DmSnapshotArchive.pack(job.stagingDirectory, job.archive).getOrThrow()
                job.zipBytes = Files.size(job.archive)
            } finally {
                job.packMs = (System.nanoTime() - packStart) / 1_000_000L
            }
            result = "ok"
        } catch (error: Throwable) {
            job.failure = error
            if (job.failureStage == null) job.failureStage = job.stage
            logger.error("FirmChunk snapshot test failed at {}", job.failureStage, error)
        } finally {
            if (job.captureEndedNanos != null) awaitTick(job, afterCapture = true)
            if (!writeMetrics(job, result)) {
                result = "failed"
                if (job.failure == null) {
                    job.failure = IllegalStateException("无法写入metrics.json")
                    job.failureStage = "metrics"
                }
            }
            val tickGap = job.firstPostCaptureTickGapNanos?.let { it / 1_000_000L }
            logger.info(
                "DM_SNAPSHOT_TEST mode={} chunks={} capture_ms={} terrain_ms={} entities_ms={} poi_ms={} storage_wait_ms={} write_ms={} pause_ms={} save_flush_ms={} copy_ms={} pack_ms={} zip_bytes={} result={} stage={} readiness_blockers={} tick_gap_ms={} path={}",
                job.mode.wireName,
                job.columns.size,
                job.pauseMs ?: "unavailable",
                job.captureTerrainMs ?: "unavailable",
                job.captureEntitiesMs ?: "unavailable",
                job.capturePoiMs ?: "unavailable",
                job.storageWaitMs ?: "unavailable",
                job.writeMs ?: "unavailable",
                job.pauseMs ?: "unavailable",
                job.saveFlushMs ?: "unavailable",
                job.copyMs ?: "unavailable",
                job.packMs ?: "unavailable",
                job.zipBytes ?: "unavailable",
                result,
                job.failureStage ?: "complete",
                job.readiness.describe(),
                tickGap ?: "unavailable",
                job.runDirectory,
            )
            if (current === job) {
                current = null
                active.set(false)
                job.lease.release()
            }
                val message = if (result == "ok") {
                "同步区块快照测试（${job.mode.displayName}）完成：${job.columns.size}列，暂停${job.pauseMs ?: "?"}ms，ZIP${job.zipBytes ?: "?"}字节，结果：${job.archive}"
            } else {
                "FirmChunk快照测试失败（${job.failureStage ?: "unknown"}）：${job.failure?.message ?: "未知错误"}；结果：${job.runDirectory}"
            }
            send(job, message)
        }
    }

    private fun submitMemoryCapture(job: Job) {
        val future = CompletableFuture<DmSnapshotMemoryCapture.Batch>()
        job.memoryFuture = future
        job.captureEnqueuedNanos = System.nanoTime()
        job.memoryDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        job.stage = "waitingForCapture"
        while (true) {
            try {
                val batch = future.get(100, TimeUnit.MILLISECONDS)
                job.memoryBatch = batch
                return
            } catch (_: java.util.concurrent.TimeoutException) {
                checkCurrentOwner(job)
            } catch (error: Throwable) {
                throw (error.cause ?: error)
            }
        }
    }

    private fun writeMemoryBatch(job: Job, batch: DmSnapshotMemoryCapture.Batch) {
        job.stage = "writingStaging"
        val output = DmSnapshotBatchWriter.write(batch, job.stagingDirectory, { checkCurrentOwner(job); true })
        job.storageWaitMs = output.storageWaitMs
        job.records = output.records.map { record ->
            ColumnRecord(
                record.column,
                record.terrainPath,
                record.entitiesPath,
                record.poiPath,
                record.entitiesPath == null,
                record.poiPath == null,
                record.sourceMode,
            )
        }
        job.writeMs = output.writeMs
    }

    private fun submitCapture(job: Job, enqueueNanos: Long) {
        val future = CompletableFuture<Unit>()
        try {
            job.owner.execute {
                val started = System.nanoTime()
                job.queueMs = (started - enqueueNanos) / 1_000_000L
                if (job.cancelled.get() || job.owner.isStopped || job.minecraft.getSingleplayerServer() !== job.owner) {
                    future.completeExceptionally(IllegalStateException("单人世界已停止"))
                    return@execute
                }
                try {
                    captureOnServerThread(job)
                    future.complete(Unit)
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
        } catch (error: Throwable) {
            future.completeExceptionally(error)
        }
        while (true) {
            try {
                future.get(100, TimeUnit.MILLISECONDS)
                return
            } catch (_: java.util.concurrent.TimeoutException) {
                if (job.owner.isStopped && !job.captureStarted) {
                    job.cancelled.set(true)
                    throw IllegalStateException("单人世界已停止")
                }
            } catch (error: Throwable) {
                throw (error.cause ?: error)
            }
        }
    }

    private fun awaitTick(job: Job, afterCapture: Boolean) {
        if (afterCapture && !job.hasPreCaptureTick) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        synchronized(job.tickMonitor) {
            while ((if (afterCapture) job.firstPostCaptureTickGapNanos == null else job.lastTickNanos == null) && !job.cancelled.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L || job.owner.isStopped) break
                TimeUnit.NANOSECONDS.timedWait(job.tickMonitor, remaining)
            }
        }
    }

    private fun captureOnServerThread(job: Job) {
        val pauseStart = System.nanoTime()
        job.captureStarted = true
        job.hasPreCaptureTick = job.lastTickNanos != null
        job.capturedAt = Instant.now().toString()
        var appender: DmSnapshotSaveErrors? = null
        var saveStartNanos: Long? = null
        var copyStartNanos: Long? = null
        try {
            job.stage = "selection"
            val selected = SyncChunkService.snapshotForBackup(job.owner).getOrThrow()
            val columns = DmSnapshotArchive.columns(selected)
            require(columns.isNotEmpty()) { "当前没有可保存的同步区块" }
            require(columns.size <= MAX_COLUMNS) { "同步区块数量${columns.size}超过${MAX_COLUMNS}列手动测试上限" }
            job.columns = columns
            val levels = columns.map { column ->
                val id = ResourceLocation.parse(column.dimensionId)
                val key = ResourceKey.create(Registries.DIMENSION, id)
                val level = job.owner.getLevel(key) ?: error("维度不存在：${column.dimensionId}")
                column to level
            }
            appender = DmSnapshotSaveErrors().also { it.install() }
            job.stage = "save"
            saveStartNanos = System.nanoTime()
            check(job.owner.saveEverything(false, true, true)) { "世界保存失败" }
            levels.map { it.second }.distinct().forEach { level ->
                levels.filter { it.second === level }.forEach { (column, _) -> level.poiManager.flush(ChunkPos(column.chunkX, column.chunkZ)) }
                (level.poiManager as AccessorSectionStorage).`rdi$getSimpleRegionStorage`().synchronize(true).join()
            }
            job.saveFlushMs = (System.nanoTime() - saveStartNanos) / 1_000_000L
            checkSaveErrors(appender.summary())

            job.stage = "copy"
            copyStartNanos = System.nanoTime()
            var stagingBytes = 0L
            val root = job.worldRoot
            val records = ArrayList<ColumnRecord>(columns.size)
            levels.forEach { (column, level) ->
                check(job.owner.isRunning && !job.cancelled.get()) { "单人世界已停止或测试已取消" }
                val chunkPos = ChunkPos(column.chunkX, column.chunkZ)
                if (level.chunkSource.getChunkNow(column.chunkX, column.chunkZ) != null && !level.areEntitiesLoaded(chunkPos.toLong())) {
                    error("已加载区块的实体资料尚未完成：${column.dimensionId},${column.chunkX},${column.chunkZ}")
                }
                val dimensionRoot = DimensionType.getStorageFolder(level.dimension(), root)
                val terrain = DmSnapshotArchive.readRecord(dimensionRoot.resolve("region"), column.chunkX, column.chunkZ).getOrThrow()
                    ?: error("缺少terrain记录：${column.dimensionId},${column.chunkX},${column.chunkZ}")
                require(terrain.contains("xPos", 3) && terrain.contains("zPos", 3) &&
                    terrain.getInt("xPos") == column.chunkX && terrain.getInt("zPos") == column.chunkZ) {
                    "terrain坐标不匹配：${column.dimensionId},${column.chunkX},${column.chunkZ}"
                }
                require(terrain.getString("Status") == "minecraft:full") {
                    "terrain不是FULL状态：${column.dimensionId},${column.chunkX},${column.chunkZ}"
                }
                stagingBytes += DmSnapshotArchive.writeRecord(job.stagingDirectory, column, "terrain", DmSnapshotArchive.prepareTerrain(terrain)).getOrThrow()
                require(stagingBytes <= MAX_STAGING_BYTES) { "快照暂存数据超过256MiB" }

                val entities = DmSnapshotArchive.readRecord(dimensionRoot.resolve("entities"), column.chunkX, column.chunkZ).getOrThrow()
                entities?.let {
                    val position = it.getIntArray("Position")
                    require(position.size == 2 && position[0] == column.chunkX && position[1] == column.chunkZ) {
                        "实体记录坐标不匹配：${column.dimensionId},${column.chunkX},${column.chunkZ}"
                    }
                    stagingBytes += DmSnapshotArchive.writeRecord(job.stagingDirectory, column, "entities", it).getOrThrow()
                    require(stagingBytes <= MAX_STAGING_BYTES) { "快照暂存数据超过256MiB" }
                }
                val poi = DmSnapshotArchive.readRecord(dimensionRoot.resolve("poi"), column.chunkX, column.chunkZ).getOrThrow()
                poi?.let {
                    stagingBytes += DmSnapshotArchive.writeRecord(job.stagingDirectory, column, "poi", it).getOrThrow()
                    require(stagingBytes <= MAX_STAGING_BYTES) { "快照暂存数据超过256MiB" }
                }
                val base = "dimensions/${DmSnapshotArchive.dimensionPath(column.dimensionId)}"
                records += ColumnRecord(
                    column,
                    "$base/terrain/${column.chunkX}.${column.chunkZ}.nbt",
                    entities?.let { "$base/entities/${column.chunkX}.${column.chunkZ}.nbt" },
                    poi?.let { "$base/poi/${column.chunkX}.${column.chunkZ}.nbt" },
                    entities == null,
                    poi == null,
                    "disk",
                )
            }
            job.records = records
            job.copyMs = (System.nanoTime() - copyStartNanos) / 1_000_000L
            checkSaveErrors(appender.summary())
        } catch (error: Throwable) {
            job.failureStage = job.stage
            throw error
        } finally {
            appender?.let {
                val summary = it.summary()
                it.remove()
                if (summary.errors > 0 || summary.warnings > 0) {
                    logger.warn("FirmChunk snapshot observed save logs: errors={}, warnings={}, messages={}", summary.errors, summary.warnings, summary.messages)
                }
            }
            job.captureEndedNanos = System.nanoTime()
            if (job.saveFlushMs == null) saveStartNanos?.let { job.saveFlushMs = ((job.captureEndedNanos ?: System.nanoTime()) - it) / 1_000_000L }
            if (job.copyMs == null) copyStartNanos?.let { job.copyMs = ((job.captureEndedNanos ?: System.nanoTime()) - it) / 1_000_000L }
            job.pauseMs = ((job.captureEndedNanos ?: System.nanoTime()) - pauseStart) / 1_000_000L
            job.captureStarted = false
            synchronized(job.tickMonitor) { job.tickMonitor.notifyAll() }
        }
    }

    private fun checkSaveErrors(summary: DmSnapshotSaveErrors.Summary) {
        if (summary.errors > 0 || summary.warnings > 0) {
            error("保存期间出现${summary.errors}条错误和${summary.warnings}条警告：${summary.messages.firstOrNull() ?: "未知保存日志"}")
        }
    }

    private fun writeMetadata(job: Job) {
        job.stage = "metadata"
        val metadata = JsonObject().apply {
            addProperty("formatVersion", 1)
            addProperty("kind", "sync-chunk-snapshot-test")
            addProperty("captureMode", job.mode.wireName)
            addProperty("capturedAt", job.capturedAt)
            addProperty("columns", job.columns.size)
            addProperty("absentRecordMeaning", "no persisted record in this snapshot; never retain data from an older snapshot")
            addProperty("terrainPreparation", "removed computed heightmaps and light arrays; preserved mod light-source data")
            addProperty("exclusions", "player and world-global data are excluded; this is not restore-safe")
            addProperty("restoreSafe", false)
            add("gameTime", JsonObject().apply { job.gameTimes.forEach { (dimension, time) -> addProperty(dimension, time) } })
            add("chunks", JsonArray().apply {
                job.records.forEach { record ->
                    add(JsonObject().apply {
                        addProperty("dimensionId", record.column.dimensionId)
                        addProperty("chunkX", record.column.chunkX)
                        addProperty("chunkZ", record.column.chunkZ)
                        addProperty("terrainPath", record.terrainPath)
                        add("entitiesPath", record.entitiesPath?.let { com.google.gson.JsonPrimitive(it) })
                        add("poiPath", record.poiPath?.let { com.google.gson.JsonPrimitive(it) })
                        addProperty("entitiesAbsent", record.entitiesAbsent)
                        addProperty("poiAbsent", record.poiAbsent)
                        addProperty("sourceMode", record.sourceMode)
                    })
                }
            })
        }
        Files.writeString(job.stagingDirectory.resolve("metadata.json"), gson.toJson(metadata), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    private fun writeMetrics(job: Job, result: String): Boolean {
        val metrics = JsonObject().apply {
            addProperty("formatVersion", 1)
            addProperty("kind", "sync-chunk-snapshot-test")
            addProperty("captureMode", job.mode.wireName)
            addProperty("capturedAt", job.capturedAt)
            addProperty("chunks", job.columns.size)
            addNullable("queueMs", job.queueMs)
            addNullable("readinessWaitMs", job.readinessWaitMs)
            addNullable("pauseMs", job.pauseMs)
            addNullable("captureMs", job.pauseMs)
            addNullable("captureTerrainMs", job.captureTerrainMs)
            addNullable("captureEntitiesMs", job.captureEntitiesMs)
            addNullable("capturePoiMs", job.capturePoiMs)
            addNullable("captureOtherMs", job.captureOtherMs)
            addNullable("storageWaitMs", job.storageWaitMs)
            addNullable("writeMs", job.writeMs)
            addNullable("saveFlushMs", job.saveFlushMs)
            addNullable("copyMs", job.copyMs)
            addNullable("packMs", job.packMs)
            addNullable("zipBytes", job.zipBytes)
            addNullable("maxAdjacentTickGapMs", job.maxTickGapNanos?.let { it / 1_000_000L })
            addNullable("firstPostCaptureTickGapMs", job.firstPostCaptureTickGapNanos?.let { it / 1_000_000L })
            addNullable("maxObservedPreflightMs", job.maxObservedPreflightMs)
            addNullable("memoryColumns", job.memoryColumns)
            addNullable("storedColumns", job.storedColumns)
            addNullable("mixedColumns", job.mixedColumns)
            addNullable("memoryEntityColumns", job.memoryEntityColumns)
            addNullable("storedEntityColumns", job.storedEntityColumns)
            add("readinessBlockers", JsonArray().apply { job.readiness.blockers.forEach { add(it) } })
            add("gameTime", JsonObject().apply { job.gameTimes.forEach { (dimension, time) -> addProperty(dimension, time) } })
            addProperty("result", result)
            addProperty("stage", job.failureStage ?: "complete")
            job.failure?.message?.let { addProperty("error", it) }
            if (job.zipBytes != null) addProperty("archivePath", job.archive.toString())
            else add("archivePath", com.google.gson.JsonNull.INSTANCE)
            addProperty("restoreSafe", false)
        }
        return runCatching {
            Files.writeString(job.runDirectory.resolve("metrics.json"), gson.toJson(metrics), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            true
        }.getOrElse {
            logger.error("Failed to write snapshot metrics", it)
            false
        }
    }

    private fun JsonObject.addNullable(name: String, value: Number?) {
        if (value == null) add(name, com.google.gson.JsonNull.INSTANCE) else addProperty(name, value)
    }

    private fun formatReadinessBlocker(blocker: DmSnapshotMemoryCapture.ReadinessBlocker): String =
        "${blocker.coordinate}: ${blocker.reason}"

    private fun checkCurrentOwner(job: Job) {
        check(!job.cancelled.get()) { "快照测试已取消" }
        check(job.minecraft.hasSingleplayerServer() && job.minecraft.getSingleplayerServer() === job.owner) { "单人世界已离开" }
        check(job.owner.isRunning && !job.owner.isStopped) { "单人世界已停止" }
    }

    private fun send(job: Job, message: String) {
        send(job.minecraft, message)
    }

    private fun send(minecraft: Minecraft, message: String) {
        minecraft.execute {
            if (minecraft.player != null) minecraft.player!!.displayClientMessage(Component.literal(message), false)
            else minecraft.gui.chat.addMessage(Component.literal(message))
        }
    }
}
