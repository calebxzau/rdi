package calebxzau.rdi.mc.client.dm

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sizes and durations for one world synchronization cycle.
 *
 * All durations come from [System.nanoTime] and are reported in milliseconds; UTC time is
 * only ever used for event timestamps. Stages can overlap, so `totalMs` is measured on its
 * own clock and is not the sum of the other fields. A stage that never ran is reported as
 * `null`, never as `0`, so a failed cycle cannot look like a completed one.
 *
 * Exactly one terminal line is emitted per cycle, from a single guarded path, whatever the
 * outcome.
 */
class DmSyncMetrics(
    private val cycleId: UUID,
    private val hostId: UUID,
    private val sink: (String) -> Unit = { logger.info(it) },
) {
    private val startedNanos = System.nanoTime()
    private val started = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)
    private val stages = LinkedHashMap<String, Long>()

    @Volatile var sequence: Long? = null
    @Volatile var snapshotId: UUID? = null
    @Volatile var stage: String = "scheduled"
    @Volatile var uploadAttempts: Int = 0

    /** Set when the client cannot tell whether Master committed the candidate. */
    @Volatile var commitState: String? = null

    @Volatile var chunkCapturedBytes: Long? = null
    @Volatile var worldCapturedBytes: Long? = null
    @Volatile var chunkPayloadBytes: Long? = null
    @Volatile var worldPayloadBytes: Long? = null
    @Volatile var uploadZipBytes: Long? = null
    @Volatile var snapshotExpandedBytes: Long? = null

    @Volatile var filesTotal: Int? = null
    @Volatile var filesChanged: Int? = null
    @Volatile var filesRemoved: Int? = null

    @Volatile var chunksObserved: Int? = null
    @Volatile var chunksChanged: Int? = null
    @Volatile var chunksDeferred: Int? = null
    @Volatile var chunksStored: Int? = null
    @Volatile var chunksTotal: Int? = null

    fun begin() {
        if (started.compareAndSet(false, true)) {
            sink("DM_WORLD_SYNC_START cycle=$cycleId host=$hostId")
        }
    }

    /** Runs [block] and accumulates its wall time under [name]. */
    fun <T> measure(name: String, block: () -> T): T {
        stage = name
        val begun = System.nanoTime()
        try {
            return block()
        } finally {
            accumulate(name, System.nanoTime() - begun)
        }
    }

    /** Accumulates an already measured duration under [name]. */
    fun add(name: String, nanos: Long) = accumulate(name, nanos)

    /**
     * Accumulates server-thread callback time. Only the callback body counts; background
     * waiting is never added here.
     */
    fun addMainThread(nanos: Long) = accumulate(MAIN_THREAD, nanos)

    @Synchronized
    private fun accumulate(name: String, nanos: Long) {
        stages[name] = (stages[name] ?: 0L) + nanos.coerceAtLeast(0L)
    }

    @Synchronized
    private fun millis(name: String): Long? = stages[name]?.let { it / 1_000_000L }

    /**
     * Emits the terminal summary. The first call wins; later calls are ignored so a cycle
     * cannot report twice.
     */
    fun report(
        result: DmWorldSyncResult,
        committed: Boolean,
        reason: String? = null,
        error: Throwable? = null,
    ) {
        if (!reported.compareAndSet(false, true)) return
        begin()
        if (chunkCapturedBytes != null || chunksObserved != null) {
            sink(
                "DM_WORLD_SYNC_DATA cycle=$cycleId kind=chunks capturedBytes=${text(chunkCapturedBytes)} " +
                    "payloadBytes=${text(chunkPayloadBytes)} observed=${text(chunksObserved)} " +
                    "changed=${text(chunksChanged)} deferred=${text(chunksDeferred)} " +
                    "stored=${text(chunksStored)} total=${text(chunksTotal)}",
            )
        }
        if (worldCapturedBytes != null || filesTotal != null) {
            sink(
                "DM_WORLD_SYNC_DATA cycle=$cycleId kind=worlddata capturedBytes=${text(worldCapturedBytes)} " +
                    "payloadBytes=${text(worldPayloadBytes)} files=${text(filesTotal)} " +
                    "changed=${text(filesChanged)} removed=${text(filesRemoved)}",
            )
        }
        val summary = buildString {
            append("DM_WORLD_SYNC_END cycle=$cycleId host=$hostId result=$result committed=$committed")
            append(" sequence=${text(sequence)} snapshotId=${text(snapshotId)}")
            commitState?.let { append(" commitState=$it") }
            append(" uploadZipBytes=${text(uploadZipBytes)} snapshotExpandedBytes=${text(snapshotExpandedBytes)}")
            append(" uploadAttempts=$uploadAttempts")
            STAGE_FIELDS.forEach { (field, name) -> append(" $field=${text(millis(name))}") }
            append(" totalMs=${(System.nanoTime() - startedNanos) / 1_000_000L}")
            append(" stage=$stage")
            reason?.let { append(" reason=$it") }
        }
        sink(summary)
        if (error != null) logger.warn("世界同步cycle={} 以{}结束", cycleId, result, error)
    }

    fun hasReported(): Boolean = reported.get()

    private fun text(value: Any?): String = value?.toString() ?: "null"

    companion object {
        private val logger = LoggerFactory.getLogger(DmSyncMetrics::class.java)

        const val STATUS = "status"
        const val READINESS_WAIT = "readinessWait"
        const val SAVE = "save"
        const val CHUNK_CAPTURE = "chunkCapture"
        const val MAIN_THREAD = "mainThread"
        const val SAVE_WAIT = "saveWait"
        const val CHUNK_STAGE = "chunkStage"
        const val WORLD_COPY_HASH = "worldCopyHash"
        const val PACK = "pack"
        const val ZIP_HASH = "zipHash"
        const val UPLOAD = "upload"
        const val CLEANUP = "cleanup"

        private val STAGE_FIELDS = listOf(
            "statusMs" to STATUS,
            "readinessWaitMs" to READINESS_WAIT,
            "saveMs" to SAVE,
            "chunkCaptureMs" to CHUNK_CAPTURE,
            "mainThreadMs" to MAIN_THREAD,
            "saveWaitMs" to SAVE_WAIT,
            "chunkStageMs" to CHUNK_STAGE,
            "worldCopyHashMs" to WORLD_COPY_HASH,
            "packMs" to PACK,
            "zipHashMs" to ZIP_HASH,
            "uploadMs" to UPLOAD,
            "cleanupMs" to CLEANUP,
        )
    }
}
