@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.mixin.AccessorIOUtilities
import calebxzau.rdi.mc.client.syncchunk.SyncChunkRequestError
import calebxzau.rdi.mc.client.syncchunk.SyncChunkService
import net.minecraft.client.Minecraft
import net.minecraft.client.server.IntegratedServer
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Periodic production world synchronization.
 *
 * One cycle is a single joint pass: on the server thread it saves without flushing,
 * captures whatever columns are ready, and reads the save tasks that were already queued;
 * in the background it waits for those tasks, stages the columns and the WorldData, and
 * uploads one incremental `world-snapshot` archive.
 *
 * What this is not: it is not a strict same-tick snapshot of every mod's state, and it is
 * not a restore-safe backup. The server thread does not wait for the save queue, does not
 * walk or copy the world directory, does not compress, and does not perform HTTP.
 */
object DmWorldSyncService {
    private val logger = LoggerFactory.getLogger(DmWorldSyncService::class.java)
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val lifecycleMonitor = Any()

    @Volatile private var binding: Binding? = null
    private var nextGeneration = 0L

    private class Binding(
        val generation: Long,
        val owner: IntegratedServer,
        val hostId: UUID,
        val sessionId: UUID,
        val config: DmConfig,
    ) {
        val intervalNanos: Long = config.worldSyncIntervalNanos
        @Volatile var nextSequence: Long? = null
        @Volatile var sequenceReady = false
        @Volatile var nextAttemptNanos: Long = DmWorldSyncTiming.firstDue(System.nanoTime(), intervalNanos)
        @Volatile var status = "等待下一次世界同步"
        @Volatile var lastSuccess: String? = null
        @Volatile var lastError: String? = null
        @Volatile var job: Job? = null

        /** Cached base manifest, always keyed by the snapshot id it was read from. */
        @Volatile var baseline: DmWorldSyncBaseline? = null
        @Volatile var policy: DmWorldSyncPolicy = DmWorldSyncPolicy.EMPTY
    }

    private data class CaptureRequest(
        val revision: Long,
        val columns: List<DmSnapshotArchive.Column>,
        val capturedAt: String,
    )

    /** Everything the server thread hands to the background worker. */
    private class Capture(
        val request: CaptureRequest,
        val batch: DmSnapshotMemoryCapture.Batch,
        val saveTasks: CompletableFuture<Void>?,
        val selectedColumns: List<DmSnapshotArchive.Column>,
        val deferredColumns: Int,
    )

    private class Job(
        val binding: Binding,
        val lease: DmSnapshotOperationGate.Lease,
        val cycleId: UUID,
    ) {
        val cancelled = AtomicBoolean(false)
        val captureFuture = CompletableFuture<Capture>()
        val monitor = Object()
        val metrics = DmSyncMetrics(cycleId, binding.hostId)

        /** Limits are immutable for this cycle and come from its status response. */
        @Volatile var limits: DmWorldSnapshotLimits? = null

        @Volatile var request: CaptureRequest? = null
        @Volatile var readyDeadlineNanos: Long? = null
        @Volatile var readinessStartedNanos: Long? = null
        @Volatile var captureActive = false
        @Volatile var closing = false
        @Volatile var runningThread: Thread? = null
        @Volatile var jobRoot: Path? = null
        @Volatile var capture: Capture? = null
        @Volatile var leaseReleased = false
        @Volatile var deferredBlockers: List<DmSnapshotMemoryCapture.ReadinessBlocker> = emptyList()

        fun releaseLease() {
            if (!leaseReleased) {
                leaseReleased = true
                lease.release()
            }
        }
    }

    // --- lifecycle ------------------------------------------------------

    fun begin(hostId: UUID, sessionId: UUID, owner: IntegratedServer, config: DmConfig? = null) {
        synchronized(lifecycleMonitor) {
            binding?.let { previous ->
                binding = null
                previous.status = "已停止"
                cancelJob(previous.job)
            }
            val resolved = config ?: DmConfig.fromSystemProperty().getOrElse { error ->
                logger.error("世界同步配置无效", error)
                return
            } ?: run {
                logger.warn("世界同步未启动：DM服务器未配置")
                return
            }
            val created = Binding(++nextGeneration, owner, hostId, sessionId, resolved)
            created.policy = DmWorldSyncPolicyStore
                .read(Minecraft.getInstance().gameDirectory.toPath(), hostId)
                .getOrElse { error ->
                    logger.error("世界同步排除配置无效，本次使用默认策略", error)
                    DmWorldSyncPolicy.EMPTY
                }
            binding = created
        }
    }

    fun pause(owner: IntegratedServer, reason: String = "DM连接中断") {
        synchronized(lifecycleMonitor) {
            val current = binding?.takeIf { it.owner === owner } ?: return
            binding = null
            current.status = "已暂停：$reason"
            cancelJob(current.job)
        }
    }

    fun stop(owner: IntegratedServer? = null) {
        synchronized(lifecycleMonitor) {
            val current = binding ?: return
            if (owner != null && current.owner !== owner) return
            binding = null
            current.status = "已停止"
            cancelJob(current.job)
        }
    }

    fun status(): String {
        val current = binding ?: return "世界同步：未运行"
        val success = current.lastSuccess?.let { "，$it" } ?: ""
        val error = current.lastError?.let { "，错误：$it" } ?: ""
        return "世界同步：${current.status}$success$error"
    }

    // --- scheduling -----------------------------------------------------

    fun onServerTickPost(event: ServerTickEvent.Post) {
        val current = binding ?: return
        if (event.server !== current.owner || current.owner.isStopped) return
        val job = current.job
        if (job != null) {
            processCapture(current, job)
            return
        }
        val now = System.nanoTime()
        if (now < current.nextAttemptNanos) return
        current.nextAttemptNanos = DmWorldSyncTiming.nextDue(current.nextAttemptNanos, now, current.intervalNanos)
        val lease = DmSnapshotOperationGate.tryAcquire("production") ?: run {
            // A manual snapshot owns the gate; this is not a synchronization cycle at all.
            current.status = "等待手动快照完成"
            logger.debug("世界同步跳过本轮：手动快照进行中 host={}", current.hostId)
            return
        }
        val created = Job(current, lease, UUID.fromString(kotlin.uuid.Uuid.generateV7().toString()))
        current.job = created
        current.status = "正在准备世界同步"
        try {
            executor.execute { runWorker(created) }
        } catch (error: Throwable) {
            current.job = null
            created.releaseLease()
            created.metrics.report(DmWorldSyncResult.Failed, committed = false, reason = "SchedulerRejected", error = error)
            recordFailure(current, error)
        }
    }

    // --- server thread half ---------------------------------------------

    /**
     * Readiness, one non-flushing save, column capture, and the save-task read.
     *
     * Readiness may span several ticks up to [DmWorldSyncTiming.READINESS_NANOS]. Once the
     * cycle commits to capturing, everything below happens inside this one callback and
     * `saveEverything` is called exactly once.
     */
    private fun processCapture(current: Binding, job: Job) {
        if (job.captureFuture.isDone) return
        synchronized(job.monitor) {
            if (job.captureFuture.isDone || job.closing || job.cancelled.get() ||
                binding !== current || job.captureActive
            ) {
                return
            }
            if (job.readyDeadlineNanos == null) return
            job.captureActive = true
        }
        val callbackStarted = System.nanoTime()
        if (job.readinessStartedNanos == null) job.readinessStartedNanos = callbackStarted
        var saveErrors: DmSnapshotSaveErrors? = null
        var capturedBatch: DmSnapshotMemoryCapture.Batch? = null
        try {
            val request = job.request ?: run {
                val snapshot = SyncChunkService
                    .backupSnapshot(current.owner, current.hostId, current.sessionId).getOrThrow()
                CaptureRequest(
                    snapshot.revision,
                    DmSnapshotArchive.columns(snapshot.chunks),
                    Instant.now().toString(),
                ).also {
                    job.request = it
                    current.status = "正在检查同步区块"
                }
            }
            val deadline = job.readyDeadlineNanos ?: return
            val readiness = DmWorldSyncState.selectReady(request.columns) { column ->
                DmSnapshotMemoryCapture.preflight(current.owner, listOf(column))
            }
            job.deferredBlockers = readiness.blockers
            if (readiness.ready.size < request.columns.size && System.nanoTime() <= deadline) {
                // Retry on a later tick. Waiting is not the same as capturing later: the
                // cycle has not saved or serialized anything yet.
                return
            }

            job.readinessStartedNanos?.let { begun ->
                job.metrics.add(DmSyncMetrics.READINESS_WAIT, System.nanoTime() - begun)
                job.readinessStartedNanos = null
            }
            current.status = "正在保存并采集世界"
            saveErrors = DmSnapshotSaveErrors().also { it.install() }
            val saveStarted = System.nanoTime()
            // Non-flushing: this queues the save work and returns without draining the
            // IO queues, so the server thread is not blocked on disk.
            val saved = current.owner.saveEverything(true, false, true)
            job.metrics.add(DmSyncMetrics.SAVE, System.nanoTime() - saveStarted)
            check(saved) { "世界保存未完成" }

            // Readiness is rechecked after the save because saving can move columns.
            val selection = DmWorldSyncState.selectReady(request.columns) { column ->
                DmSnapshotMemoryCapture.preflight(current.owner, listOf(column))
            }
            val selected = selection.ready
            job.deferredBlockers = selection.blockers
            val deferred = request.columns.size - selected.size

            val captureStarted = System.nanoTime()
            capturedBatch = if (selected.isEmpty()) {
                emptyBatch()
            } else {
                DmSnapshotMemoryCapture.preflight(current.owner, selected)
                val batch = DmSnapshotMemoryCapture.capture(
                    current.owner,
                    selected,
                    { job.cancelled.get() },
                    executor,
                )
                DmSnapshotMemoryCapture.preflight(current.owner, selected)
                batch
            }
            job.metrics.add(DmSyncMetrics.CHUNK_CAPTURE, System.nanoTime() - captureStarted)

            val after = SyncChunkService
                .backupSnapshot(current.owner, current.hostId, current.sessionId).getOrThrow()
            check(
                DmWorldSyncState.sameRoster(
                    request.revision,
                    request.columns,
                    after.revision,
                    DmSnapshotArchive.columns(after.chunks),
                ),
            ) {
                "同步区块名册在采集期间发生变化"
            }
            checkSaveErrors(saveErrors.summary())
            check(!job.cancelled.get() && binding === current) { "世界同步已取消" }

            // Read after the save so the future covers work this cycle queued. Reading a
            // non-volatile static is safe here because only the server thread does it, and
            // the value is published to the worker through the capture future.
            val saveTasks = runCatching { AccessorIOUtilities.`rdi$getSaveDataTasks`() }
                .getOrElse { error ->
                    throw IllegalStateException("无法读取NeoForge保存任务队列", error)
                }
            val capture = Capture(request, capturedBatch, saveTasks, selected, deferred)
            job.capture = capture
            if (job.captureFuture.complete(capture)) capturedBatch = null
        } catch (deferred: DmSnapshotMemoryCapture.Deferred) {
            job.deferredBlockers = deferred.blockers
            if (System.nanoTime() > (job.readyDeadlineNanos ?: 0L)) {
                job.captureFuture.completeExceptionally(deferred)
            }
        } catch (error: Throwable) {
            job.captureFuture.completeExceptionally(error)
        } finally {
            saveErrors?.let {
                val summary = it.summary()
                it.remove()
                if (summary.errors > 0 || summary.warnings > 0) {
                    logger.warn(
                        "世界同步观察到保存日志：errors={}, warnings={}, messages={}",
                        summary.errors, summary.warnings, summary.messages,
                    )
                }
            }
            capturedBatch?.let(::discardBatch)
            job.metrics.addMainThread(System.nanoTime() - callbackStarted)
            synchronized(job.monitor) {
                job.captureActive = false
                job.monitor.notifyAll()
            }
        }
    }

    // --- background worker ----------------------------------------------

    private fun runWorker(job: Job) {
        job.runningThread = Thread.currentThread()
        val current = job.binding
        val metrics = job.metrics
        metrics.begin()
        var interrupted = false
        var result = DmWorldSyncResult.Failed
        var committed = false
        var reason: String? = null
        var failure: Throwable? = null
        try {
            checkAlive(job)
            val base = metrics.measure(DmSyncMetrics.STATUS) { resolveBase(job) }
            checkAlive(job)
            synchronized(job.monitor) {
                if (job.cancelled.get() || job.closing || binding !== current) error("世界同步会话已变化")
                job.readyDeadlineNanos = System.nanoTime() + DmWorldSyncTiming.READINESS_NANOS
            }
            // Waiting for the handoff is not itself a measured stage: the server thread
            // reports its own readiness window, save, and capture times.
            val capture = try {
                job.captureFuture.get(
                    DmWorldSyncTiming.READINESS_NANOS + DmWorldSyncTiming.SAVE_WAIT_NANOS,
                    TimeUnit.NANOSECONDS,
                )
            } catch (error: InterruptedException) {
                interrupted = true
                throw error
            }
            job.capture = capture
            checkAlive(job)

            // Wait for the queued save work with a bound. The shared future is only read:
            // it is never cancelled and the global queue is never reset.
            metrics.measure(DmSyncMetrics.SAVE_WAIT) {
                capture.saveTasks?.let { tasks ->
                    try {
                        tasks.get(DmWorldSyncTiming.SAVE_WAIT_NANOS, TimeUnit.NANOSECONDS)
                    } catch (error: InterruptedException) {
                        interrupted = true
                        throw error
                    } catch (error: TimeoutException) {
                        throw IllegalStateException("等待NeoForge保存任务超时", error)
                    }
                }
            }
            checkAlive(job)

            val root = createJobRoot(job)
            val staging = root.resolve("staging")
            val columns = metrics.measure(DmSyncMetrics.CHUNK_STAGE) {
                stageColumns(job, staging, capture)
            }
            checkAlive(job)
            val world = metrics.measure(DmSyncMetrics.WORLD_COPY_HASH) {
                DmWorldDataCapture.capture(
                    current.owner.getWorldPath(LevelResource.ROOT),
                    staging.resolve(DmWorldSnapshotArchive.WORLD_PREFIX.trimEnd('/')),
                    current.policy,
                    job.limits,
                    System.nanoTime() + DmWorldSyncTiming.WORLD_CAPTURE_NANOS,
                    { job.cancelled.get() || binding !== current },
                ).getOrThrow()
            }
            // Independent copies exist now, so manual snapshots may run again. The job
            // itself stays in flight so the next period cannot overlap this upload.
            job.releaseLease()
            checkAlive(job)

            metrics.worldCapturedBytes = world.capturedBytes
            metrics.chunkCapturedBytes = columns.stagedBytes
            require(
                Math.addExact(world.capturedBytes, columns.stagedBytes) <=
                    (job.limits?.maxExpandedBytes ?: DmWorldDataCapture.MAX_TOTAL_BYTES),
            ) { "世界快照展开数据超过Master限制" }
            metrics.filesTotal = world.files.size
            metrics.chunksObserved = columns.entries.size
            metrics.chunksDeferred = capture.deferredColumns
            metrics.chunksTotal = capture.request.columns.size

            val plan = DmWorldSyncState.plan(
                base,
                capture.request.revision,
                world.files,
                world.directories,
                current.policy,
                columns.entries,
            )
            metrics.filesChanged = plan.changedFiles.size
            metrics.filesRemoved = plan.removedFiles.size
            metrics.chunksChanged = plan.updatedColumns.size

            if (!plan.hasChanges) {
                metrics.uploadZipBytes = 0L
                result = DmWorldSyncResult.NoChanges
                current.status = "最近世界同步无变化"
                current.lastSuccess = "无变化，${world.files.size}个文件，${columns.entries.size}列已确认"
                current.lastError = null
                return
            }

            val sequence = peekSequence(current)
            metrics.sequence = sequence
            val output = metrics.measure(DmSyncMetrics.PACK) {
                DmWorldSnapshotArchive.write(
                    staging,
                    root.resolve("world-snapshot.zip"),
                    DmWorldSnapshotArchive.Request(
                        current.hostId,
                        current.sessionId,
                        job.cycleId,
                        sequence,
                        base?.snapshotId,
                        capture.request.revision,
                        capture.request.capturedAt,
                        current.policy,
                        world.files,
                        world.directories,
                        columns.entries,
                        plan,
                        job.limits,
                    ),
                    columns.records,
                ) { job.cancelled.get() || binding !== current }
            }
            val sha1 = metrics.measure(DmSyncMetrics.ZIP_HASH) {
                DmWorldSnapshotArchive.sha1(output.archive) { job.cancelled.get() || binding !== current }
            }
            metrics.uploadZipBytes = output.zipBytes
            metrics.worldPayloadBytes = output.worldPayloadBytes
            metrics.chunkPayloadBytes = output.chunkPayloadBytes

            allocateSequence(current, sequence)
            current.status = "正在上传世界快照"
            val expectation = DmWorldSnapshotExpectation(
                current.sessionId,
                sequence,
                capture.request.revision,
                job.cycleId,
                sha1,
                output.zipBytes,
                world.files.size,
                world.files.sumOf { it.bytes },
                columns.entries.size,
                plan.updatedColumns.size,
            )
            val receipt = metrics.measure(DmSyncMetrics.UPLOAD) { upload(job, output.archive, expectation) }
            checkAlive(job)
            committed = true
            metrics.snapshotId = receipt.snapshotId
            metrics.snapshotExpandedBytes = receipt.expandedBytes
            metrics.chunksStored = receipt.storedColumns
            metrics.chunksTotal = receipt.totalColumns

            synchronized(lifecycleMonitor) {
                check(binding === current && current.sessionId == receipt.sessionId) {
                    "世界同步确认时会话已变化"
                }
                // The next cycle reads this snapshot's manifest as its base, so the local
                // baseline is only ever adopted from what Master actually published.
                current.baseline = null
            }
            result = DmWorldSyncState.classify(
                capture.deferredColumns,
                receipt.storedColumns,
                receipt.totalColumns,
            )
            current.lastError = null
            current.lastSuccess = describeSuccess(receipt, capture.deferredColumns, output.zipBytes)
            current.status = "最近世界同步成功"
        } catch (error: Throwable) {
            interrupted = interrupted || error is InterruptedException
            failure = error
            if (job.cancelled.get() || binding !== current) {
                result = DmWorldSyncResult.Cancelled
                reason = "Cancelled"
            } else {
                result = DmWorldSyncResult.Failed
                reason = error.javaClass.simpleName
                recordFailure(current, error)
            }
        } finally {
            metrics.measure(DmSyncMetrics.CLEANUP) { finalizeJob(job, interrupted) }
            metrics.report(result, committed, reason, failure?.takeIf { result == DmWorldSyncResult.Failed })
        }
    }

    /**
     * Reads Master status and pins the base manifest for this cycle.
     *
     * The base is always a concrete snapshot id. Reconciliation after an unknown commit
     * result is inherent: whatever Master reports as latest is what the next cycle diffs
     * against, so a lost response cannot advance the local baseline on its own.
     */
    private fun resolveBase(job: Job): DmWorldSyncBaseline? {
        val current = job.binding
        val client = DmHttpClient(current.config)
        val status = client.getWorldSnapshotStatus(current.hostId, current.sessionId).getOrThrow()
        job.limits = status.limits
        val next = DmWorldSyncTiming.nextSequence(
            status.lastUpload?.takeIf { it.sessionId == current.sessionId }?.sequence,
        )
        synchronized(lifecycleMonitor) {
            if (binding !== current || job.cancelled.get()) error("世界同步会话已变化")
            if (!current.sequenceReady) {
                current.nextSequence = next
                current.sequenceReady = true
            } else {
                val local = current.nextSequence ?: error("世界同步序号尚未初始化")
                // Master may know about an upload this client never saw confirmed.
                if (next > local) current.nextSequence = next
            }
        }
        val latest = status.latest ?: run {
            synchronized(lifecycleMonitor) { if (binding === current) current.baseline = null }
            return null
        }
        current.baseline?.takeIf { it.snapshotId == latest.snapshotId }?.let { return it }
        val manifest = client
            .getWorldSnapshotManifest(
                current.hostId,
                current.sessionId,
                latest.snapshotId,
                latest.sessionId,
            )
            .getOrThrow()
        synchronized(lifecycleMonitor) { if (binding === current) current.baseline = manifest }
        return manifest
    }

    private class StagedColumns(
        val records: List<DmSnapshotBatchWriter.Record>,
        val entries: List<DmWorldColumnEntry>,
        val stagedBytes: Long,
    )

    private fun stageColumns(job: Job, staging: Path, capture: Capture): StagedColumns {
        val cancelled = { job.cancelled.get() || binding !== job.binding }
        if (capture.batch.plans.isEmpty()) return StagedColumns(emptyList(), emptyList(), 0L)
        val chunkStaging = staging.resolve(DmWorldSnapshotArchive.CHUNK_PREFIX.trimEnd('/'))
        val output = DmSnapshotBatchWriter.write(
            capture.batch,
            chunkStaging,
            cancelled,
            System.nanoTime() + DmWorldSyncTiming.CHUNK_STAGE_NANOS,
        )
        val entries = DmWorldSnapshotArchive.columnEntries(staging, output.records, cancelled)
        val bytes = output.records.sumOf { record ->
            DmWorldSnapshotArchive.columnMembers(record).sumOf { Files.size(staging.resolve(it)) }
        }
        return StagedColumns(output.records, entries, bytes)
    }

    /**
     * Uploads one already built candidate.
     *
     * A retry always sends the identical bytes under the identical sequence and cycle, so
     * Master can recognize it as the same request. A refusal Master actually answered is
     * never retried; only a transport failure is.
     */
    private fun upload(
        job: Job,
        archive: Path,
        expectation: DmWorldSnapshotExpectation,
    ): DmWorldSnapshotReceipt {
        val client = DmHttpClient(job.binding.config)
        var last: Throwable? = null
        for (attempt in 1..DmWorldSyncTiming.MAX_UPLOAD_ATTEMPTS) {
            checkAlive(job)
            job.metrics.uploadAttempts = attempt
            val outcome = client.uploadWorldSnapshot(job.binding.hostId, archive, expectation)
            outcome.onSuccess { return it }
            val error = outcome.exceptionOrNull() ?: IllegalStateException("世界快照上传失败")
            if (error is SyncChunkRequestError || error is IllegalArgumentException) throw error
            last = error
            logger.warn("世界快照上传第${attempt}次失败，将重试同一候选", error)
        }
        // Master may or may not have committed the candidate; the next cycle reconciles
        // against its published state before any local baseline moves.
        job.metrics.commitState = "Unknown"
        throw IllegalStateException("世界快照上传失败，提交结果未知", last)
    }

    // --- helpers --------------------------------------------------------

    private fun describeSuccess(
        receipt: DmWorldSnapshotReceipt,
        deferred: Int,
        uploadZipBytes: Long,
    ): String {
        val coverage = "覆盖${receipt.storedColumns}/${receipt.totalColumns}列"
        val waiting = if (deferred > 0) "，${deferred}列等待下轮采集" else ""
        return "序号${receipt.sequence}，上传${uploadZipBytes}字节，快照${receipt.expandedBytes}字节，$coverage$waiting"
    }

    private fun peekSequence(binding: Binding): Long = synchronized(lifecycleMonitor) {
        binding.nextSequence ?: error("世界同步序号尚未初始化")
    }

    private fun allocateSequence(binding: Binding, expected: Long): Long = synchronized(lifecycleMonitor) {
        val value = binding.nextSequence ?: error("世界同步序号尚未初始化")
        require(value == expected && value > 0L) { "世界同步序号状态已变化" }
        binding.nextSequence = Math.addExact(value, 1L)
        value
    }

    private fun createJobRoot(job: Job): Path {
        val gameDirectory = Minecraft.getInstance().gameDirectory.toPath().toRealPath()
        val worldRoot = job.binding.owner.getWorldPath(LevelResource.ROOT).toRealPath()
        val syncRoot = gameDirectory.resolve("rdi-world-sync").toAbsolutePath().normalize()
        Files.createDirectories(syncRoot)
        val canonical = syncRoot.toRealPath()
        require(!canonical.startsWith(worldRoot)) { "世界同步暂存目录不能位于世界目录内" }
        val root = Files.createTempDirectory(canonical, "cycle-${job.cycleId}-")
        job.jobRoot = root
        return root
    }

    private fun emptyBatch() = DmSnapshotMemoryCapture.Batch(
        emptyList(), DmSnapshotMemoryBudget(), emptyMap(), 0, 0, 0, 0, 0, 0L, 0L, 0L, 0L,
    )

    private fun checkAlive(job: Job) {
        check(!job.cancelled.get() && binding === job.binding) { "世界同步会话已变化" }
    }

    private fun cancelJob(job: Job?) {
        if (job == null) return
        job.cancelled.set(true)
        // Only this job's own future is completed; NeoForge's save future is untouched.
        job.captureFuture.completeExceptionally(IllegalStateException("世界同步已取消"))
        job.runningThread?.interrupt()
    }

    private fun recordFailure(current: Binding, error: Throwable) {
        if (binding !== current) return
        current.lastError = error.message ?: error.javaClass.simpleName
        current.status = "本轮世界同步失败"
        logger.warn("世界同步失败 host={} nextSequence={}", current.hostId, current.nextSequence, error)
    }

    private fun finalizeJob(job: Job, interrupted: Boolean) {
        var restoreInterrupt = interrupted
        try {
            synchronized(job.monitor) {
                job.closing = true
                // The capture callback may still own live batch data on the server thread.
                while (job.captureActive) {
                    try {
                        job.monitor.wait(100L)
                    } catch (_: InterruptedException) {
                        restoreInterrupt = true
                    }
                }
                job.request = null
                job.readyDeadlineNanos = null
            }
            job.capture?.batch?.let(::discardBatch)
            job.capture = null
            job.jobRoot?.let { root ->
                runCatching { deleteTree(root) }
                    .onFailure { logger.warn("世界同步临时文件清理失败：$root", it) }
            }
            job.jobRoot = null
        } finally {
            if (job.binding.job === job) {
                job.binding.job = null
                job.binding.nextAttemptNanos =
                    DmWorldSyncTiming.nextDue(
                        job.binding.nextAttemptNanos,
                        System.nanoTime(),
                        job.binding.intervalNanos,
                    )
            }
            job.releaseLease()
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    private fun discardBatch(batch: DmSnapshotMemoryCapture.Batch) {
        batch.plans.forEach { plan ->
            plan.terrain = null
            plan.entities = null
            plan.terrainRead = null
            plan.entitiesRead = null
            plan.poiRead = null
            plan.poiOverlays = emptyList()
        }
    }

    private fun checkSaveErrors(summary: DmSnapshotSaveErrors.Summary) {
        // Bounded observation only: Minecraft logs some save failures without throwing, so
        // a clean summary is not proof that every mod saved successfully.
        if (summary.errors > 0 || summary.warnings > 0) {
            error("保存期间出现${summary.errors}条错误和${summary.warnings}条警告：${summary.messages.firstOrNull() ?: "未知保存日志"}")
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder<Path>()).forEach { Files.deleteIfExists(it) }
        }
    }
}
