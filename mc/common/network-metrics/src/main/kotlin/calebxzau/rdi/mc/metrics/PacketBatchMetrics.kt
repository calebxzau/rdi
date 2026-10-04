package calebxzau.rdi.mc.metrics

import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Measurements for an emitted inner frame. Frame bytes exclude the outer length prefix. */
data class PacketBatchFrameSample(
    val frameKind: String,
    val recordCount: Int,
    val payloadBytes: Int,
    val frameBytes: Int,
    val outerPrefixBytes: Int,
    val flushReason: String,
    val waitNanos: Long,
    val compressionNanos: Long,
    val singleRecordFallback: Boolean,
    val bufferedFlush: Boolean = false,
    /** True only on the first emitted frame for this buffered flush. */
    val firstFrameOfFlush: Boolean = false,
)

/** Independent v4 measurements. The legacy [PacketMetrics] recorder and its callers are unchanged. */
object PacketBatchMetrics {
    private val logger = LogManager.getLogger("rdi.packet-batch-metrics")
    private val active = AtomicReference<PacketBatchMetricsRecorder?>(null)

    val isActive: Boolean
        get() = active.get() != null

    @JvmStatic
    fun start(databasePath: Path) {
        active.getAndSet(null)?.close()
        runCatching {
            Class.forName("org.sqlite.JDBC")
            active.set(PacketBatchMetricsRecorder(databasePath))
        }.onFailure { error ->
            logger.error("Failed to initialize packet batch metrics; collection is disabled", error)
            active.set(null)
        }
    }

    @JvmStatic
    fun recordLogical(key: PacketMetricKey, encodedBytes: Int) {
        active.get()?.offerLogical(key, encodedBytes)
    }

    @JvmStatic
    fun recordFrame(sample: PacketBatchFrameSample) {
        active.get()?.offerFrame(sample)
    }

    @JvmStatic
    fun recordWriteOutcome(success: Boolean, recordCount: Int) {
        active.get()?.offerOutcome(success, recordCount)
    }

    @JvmStatic
    fun recordEncodingFailure(recordCount: Int) {
        active.get()?.offerEncodingFailure(recordCount)
    }

    /** Records the compressed inner-frame content observed for an inbound packet, without outer framing. */
    @JvmStatic
    fun recordInbound(key: PacketMetricKey, compressedFrameBytes: Int) {
        active.get()?.offerInbound(key, compressedFrameBytes)
    }

    @JvmStatic
    fun stop() {
        active.getAndSet(null)?.close()
    }
}

private sealed interface PacketBatchEvent {
    data class Logical(val key: PacketMetricKey, val bytes: Int) : PacketBatchEvent
    data class Frame(val sample: PacketBatchFrameSample) : PacketBatchEvent
    data class Outcome(val success: Boolean, val records: Int) : PacketBatchEvent
    data class EncodingFailure(val records: Int) : PacketBatchEvent
    data class Inbound(val key: PacketMetricKey, val bytes: Int) : PacketBatchEvent
}

private data class BatchLogicalTotal(var count: Long = 0, var bytes: Long = 0)
private data class BatchFrameKey(val kind: String, val reason: String)
private data class BatchFrameTotal(
    var frames: Long = 0,
    var records: Long = 0,
    var payloadBytes: Long = 0,
    var frameBytes: Long = 0,
    var outerPrefixBytes: Long = 0,
    var waitNanos: Long = 0,
    var compressionNanos: Long = 0,
    var fallbackCount: Long = 0,
    var bufferedFlushes: Long = 0,
    var barrierInterruptions: Long = 0,
)

/** Bounded asynchronous ingress. Only its writer thread owns SQLite and aggregation state. */
internal class PacketBatchMetricsRecorder(
    databasePath: Path,
    private val flushIntervalMs: Long = 1_000,
    private val queueCapacity: Int = QUEUE_CAPACITY,
    private val keyCapacity: Int = KEY_CAPACITY,
    private val beforeTransaction: (() -> Unit)? = null,
    private val beforeQueueOffer: (() -> Unit)? = null,
) {
    private val logger = LogManager.getLogger("rdi.packet-batch-metrics")
    private val queue = ArrayBlockingQueue<PacketBatchEvent>(queueCapacity)
    private val dropped = AtomicLong()
    private val stopping = AtomicBoolean()
    private val inFlightOffers = AtomicInteger()
    private val shutdownDeadlineNanos = AtomicLong(Long.MAX_VALUE)
    private val connection: Connection
    private val windowId: Long
    private val writer: Thread

    internal val isStopping: Boolean
        get() = stopping.get()

    init {
        require(queueCapacity > 0)
        require(keyCapacity > 0)
        Files.createDirectories(databasePath.toAbsolutePath().parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS packet_batch_windows (
                        window_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        start_utc TEXT NOT NULL,
                        end_utc TEXT,
                        dropped_samples INTEGER NOT NULL DEFAULT 0,
                        write_successes INTEGER NOT NULL DEFAULT 0,
                        write_failures INTEGER NOT NULL DEFAULT 0,
                        successful_records INTEGER NOT NULL DEFAULT 0,
                        failed_records INTEGER NOT NULL DEFAULT 0,
                        encoding_failures INTEGER NOT NULL DEFAULT 0,
                        encoding_failure_records INTEGER NOT NULL DEFAULT 0
                    )""".trimIndent(),
                )
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS packet_batch_logical_totals (
                        window_id INTEGER NOT NULL REFERENCES packet_batch_windows(window_id),
                        packet_type TEXT NOT NULL,
                        direction TEXT NOT NULL CHECK (direction IN ('s2c', 'c2s')),
                        namespace TEXT,
                        path TEXT,
                        packet_count INTEGER NOT NULL,
                        sum_encoded_bytes INTEGER NOT NULL,
                        CHECK ((namespace IS NULL AND path IS NULL) OR (namespace IS NOT NULL AND path IS NOT NULL))
                    )""".trimIndent(),
                )
                statement.execute(
                    """CREATE UNIQUE INDEX IF NOT EXISTS packet_batch_logical_key
                       ON packet_batch_logical_totals(window_id, packet_type, direction, COALESCE(namespace, ':'), COALESCE(path, ':'))""".trimIndent(),
                )
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS packet_batch_frame_totals (
                        window_id INTEGER NOT NULL REFERENCES packet_batch_windows(window_id),
                        frame_kind TEXT NOT NULL,
                        flush_reason TEXT NOT NULL,
                        frame_count INTEGER NOT NULL,
                        record_count INTEGER NOT NULL,
                        sum_payload_bytes INTEGER NOT NULL,
                        sum_frame_bytes INTEGER NOT NULL,
                        sum_outer_prefix_bytes INTEGER NOT NULL,
                        sum_wait_nanos INTEGER NOT NULL,
                        sum_compression_nanos INTEGER NOT NULL,
                        single_record_fallbacks INTEGER NOT NULL,
                        buffered_flushes INTEGER NOT NULL DEFAULT 0,
                        barrier_interruptions INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (window_id, frame_kind, flush_reason)
                    )""".trimIndent(),
                )
                ensureFrameMetricColumns(statement)
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS packet_batch_inbound_totals (
                        window_id INTEGER NOT NULL REFERENCES packet_batch_windows(window_id),
                        packet_type TEXT NOT NULL,
                        direction TEXT NOT NULL CHECK (direction = 'c2s'),
                        namespace TEXT,
                        path TEXT,
                        packet_count INTEGER NOT NULL,
                        sum_compressed_frame_bytes INTEGER NOT NULL,
                        CHECK ((namespace IS NULL AND path IS NULL) OR (namespace IS NOT NULL AND path IS NOT NULL))
                    )""".trimIndent(),
                )
                statement.execute(
                    """CREATE UNIQUE INDEX IF NOT EXISTS packet_batch_inbound_key
                       ON packet_batch_inbound_totals(window_id, packet_type, direction, COALESCE(namespace, ':'), COALESCE(path, ':'))""".trimIndent(),
                )
            }
            windowId = connection.prepareStatement(
                "INSERT INTO packet_batch_windows(start_utc) VALUES (?)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setString(1, Instant.now().toString())
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    check(keys.next()) { "SQLite did not return packet batch window id" }
                    keys.getLong(1)
                }
            }
            writer = Thread(::runWriter, "rdi-packet-batch-metrics-writer").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            runCatching { connection.close() }.onFailure(error::addSuppressed)
            throw error
        }
        logger.info("Packet batch metrics window {} started at {}", windowId, databasePath)
    }

    fun offerLogical(key: PacketMetricKey, encodedBytes: Int) {
        submit {
            val bounded = validatedKey(key)
            if (encodedBytes < 0) {
                dropped.incrementAndGet()
                null
            } else {
                PacketBatchEvent.Logical(bounded, encodedBytes)
            }
        }
    }

    fun offerInbound(key: PacketMetricKey, compressedFrameBytes: Int) {
        submit {
            val bounded = validatedKey(key)
            if (bounded.direction != PacketDirection.C2S || compressedFrameBytes < 0) {
                dropped.incrementAndGet()
                null
            } else {
                PacketBatchEvent.Inbound(bounded, compressedFrameBytes)
            }
        }
    }

    fun offerFrame(sample: PacketBatchFrameSample) {
        submit {
            if (sample.recordCount < 0 || sample.payloadBytes < 0 || sample.frameBytes < 0 ||
                sample.outerPrefixBytes < 0 || sample.waitNanos < 0 || sample.compressionNanos < 0
            ) {
                dropped.incrementAndGet()
                null
            } else {
                PacketBatchEvent.Frame(
                    sample.copy(
                        frameKind = boundedLabel(sample.frameKind, OVERFLOW_LABEL),
                        flushReason = boundedLabel(sample.flushReason, OVERFLOW_LABEL),
                    ),
                )
            }
        }
    }

    fun offerOutcome(success: Boolean, recordCount: Int) {
        submit {
            if (recordCount < 0) {
                dropped.incrementAndGet()
                null
            } else {
                PacketBatchEvent.Outcome(success, recordCount)
            }
        }
    }

    fun offerEncodingFailure(recordCount: Int) {
        submit {
            if (recordCount < 0) {
                dropped.incrementAndGet()
                null
            } else {
                PacketBatchEvent.EncodingFailure(recordCount)
            }
        }
    }

    private inline fun submit(eventFactory: () -> PacketBatchEvent?) {
        if (stopping.get()) return
        inFlightOffers.incrementAndGet()
        try {
            if (stopping.get()) return
            val event = eventFactory() ?: return
            beforeQueueOffer?.invoke()
            if (!queue.offer(event)) dropped.incrementAndGet()
        } finally {
            inFlightOffers.decrementAndGet()
        }
    }

    private fun validatedKey(key: PacketMetricKey): PacketMetricKey {
        if ((key.namespace == null) != (key.path == null) || key.direction !in PacketDirection.entries) {
            dropped.incrementAndGet()
            return overflowKey(key.direction)
        }
        val type = boundedLabel(key.packetType, OVERFLOW_PACKET_TYPE)
        val namespace = key.namespace?.let { boundedLabel(it, OVERFLOW_LABEL) }
        val path = key.path?.let { boundedLabel(it, OVERFLOW_LABEL) }
        return key.copy(packetType = type, namespace = namespace, path = path)
    }

    private fun ensureFrameMetricColumns(statement: java.sql.Statement) {
        val columns = mutableSetOf<String>()
        statement.executeQuery("PRAGMA table_info(packet_batch_frame_totals)").use { result ->
            while (result.next()) columns += result.getString("name")
        }
        if ("buffered_flushes" !in columns) {
            statement.execute("ALTER TABLE packet_batch_frame_totals ADD COLUMN buffered_flushes INTEGER NOT NULL DEFAULT 0")
        }
        if ("barrier_interruptions" !in columns) {
            statement.execute("ALTER TABLE packet_batch_frame_totals ADD COLUMN barrier_interruptions INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun boundedLabel(value: String, overflow: String): String =
        if (value.length <= MAX_LABEL_LENGTH) value else overflow

    private fun overflowKey(direction: PacketDirection) = PacketMetricKey(
        packetType = OVERFLOW_PACKET_TYPE,
        direction = direction,
        namespace = OVERFLOW_LABEL,
        path = OVERFLOW_LABEL,
    )

    fun close() {
        if (!stopping.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT_MS)
        shutdownDeadlineNanos.set(deadline)
        try {
            writer.join(SHUTDOWN_WAIT_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.error("Interrupted while draining packet batch metrics window {}", windowId, interrupted)
            return
        }
        if (writer.isAlive) {
            logger.error("Packet batch metrics window {} did not stop by {}; queued events={}, dropped={}", windowId, deadline, queue.size, dropped.get())
        }
    }

    private fun runWriter() {
        val logical = LinkedHashMap<PacketMetricKey, BatchLogicalTotal>()
        val inbound = LinkedHashMap<PacketMetricKey, BatchLogicalTotal>()
        val frames = LinkedHashMap<BatchFrameKey, BatchFrameTotal>()
        var writeSuccesses = 0L
        var writeFailures = 0L
        var successfulRecords = 0L
        var failedRecords = 0L
        var encodingFailures = 0L
        var encodingFailureRecords = 0L
        var nextFlush = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushIntervalMs)
        try {
            var dirty = false
            var lastPersistedDropped = -1L
            var finished = false
            while (!finished) {
                val now = System.nanoTime()
                if (stopping.get() && now >= shutdownDeadlineNanos.get()) {
                    logger.error(
                        "Packet batch metrics window {} drain deadline expired; queued={}, offers in flight={}, logicalKeys={}, inboundKeys={}, frameKeys={}, dropped={}, in-memory logical packets={}, in-memory inbound packets={}, in-memory frames={}, writes success/failure={}/{}, encoding failures/records={}/{}",
                        windowId,
                        queue.size,
                        inFlightOffers.get(),
                        logical.size,
                        inbound.size,
                        frames.size,
                        dropped.get(),
                        logical.values.sumOf { it.count },
                        inbound.values.sumOf { it.count },
                        frames.values.sumOf { it.frames },
                        writeSuccesses,
                        writeFailures,
                        encodingFailures,
                        encodingFailureRecords,
                    )
                    break
                }
                val first = try {
                    queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (interrupted: InterruptedException) {
                    if (!stopping.get()) logger.warn("Packet batch metrics writer interrupted", interrupted)
                    null
                }
                if (first != null) {
                    apply(first, logical, inbound, frames, { writeSuccesses++ }, { writeFailures++ }, { successfulRecords += it }, { failedRecords += it }, { encodingFailures++; encodingFailureRecords += it })
                    dirty = true
                }
                // Stop at the first empty poll; each poll takes the queue lock shared with producers.
                var remaining = MAX_DRAIN_BATCH - if (first == null) 0 else 1
                while (remaining-- > 0) {
                    val event = queue.poll() ?: break
                    apply(event, logical, inbound, frames, { writeSuccesses++ }, { writeFailures++ }, { successfulRecords += it }, { failedRecords += it }, { encodingFailures++; encodingFailureRecords += it })
                    dirty = true
                }
                // Check admission first. Once it reaches zero after stopping, no producer can add a new event.
                val drainComplete = stopping.get() && inFlightOffers.get() == 0 && queue.isEmpty()
                val flushDue = System.nanoTime() >= nextFlush
                if ((flushDue && (dirty || dropped.get() != lastPersistedDropped)) || drainComplete) {
                    try {
                        val droppedSnapshot = dropped.get()
                        val endUtc = if (drainComplete) Instant.now().toString() else null
                        persist(logical, inbound, frames, writeSuccesses, writeFailures, successfulRecords, failedRecords, encodingFailures, encodingFailureRecords, droppedSnapshot, endUtc)
                        lastPersistedDropped = droppedSnapshot
                        dirty = false
                        nextFlush = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(flushIntervalMs)
                        if (drainComplete) finished = true
                    } catch (error: Throwable) {
                        logger.error("Packet batch metrics window {} persistence failed; absolute totals and window state will be retried", windowId, error)
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (interrupted: InterruptedException) {
                            if (!stopping.get()) Thread.currentThread().interrupt()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            logger.error("Packet batch metrics writer terminated; window {} remains incomplete", windowId, error)
        } finally {
            runCatching { connection.close() }.onFailure { logger.error("Failed to close packet batch metrics database", it) }
        }
    }

    private inline fun apply(
        event: PacketBatchEvent,
        logical: LinkedHashMap<PacketMetricKey, BatchLogicalTotal>,
        inbound: LinkedHashMap<PacketMetricKey, BatchLogicalTotal>,
        frames: LinkedHashMap<BatchFrameKey, BatchFrameTotal>,
        success: () -> Unit,
        failure: () -> Unit,
        successRecords: (Long) -> Unit,
        failureRecords: (Long) -> Unit,
        encodingFailure: (Long) -> Unit,
    ) {
        when (event) {
            is PacketBatchEvent.Logical -> addBounded(logical, event.key, event.bytes.toLong())
            is PacketBatchEvent.Inbound -> addBounded(inbound, event.key, event.bytes.toLong())
            is PacketBatchEvent.Frame -> {
                val sample = event.sample
                val key = BatchFrameKey(sample.frameKind, sample.flushReason)
                val total = frames[key] ?: if (frames.size < MAX_FRAME_KEYS) BatchFrameTotal().also { frames[key] = it } else {
                    dropped.incrementAndGet()
                    return
                }
                total.frames++
                total.records += sample.recordCount
                total.payloadBytes += sample.payloadBytes
                total.frameBytes += sample.frameBytes
                total.outerPrefixBytes += sample.outerPrefixBytes
                total.compressionNanos += sample.compressionNanos
                if (sample.singleRecordFallback) total.fallbackCount++
                if (sample.bufferedFlush && sample.firstFrameOfFlush) {
                    total.bufferedFlushes++
                    total.waitNanos += sample.waitNanos
                    if (sample.flushReason.equals("BARRIER", ignoreCase = true)) total.barrierInterruptions++
                }
            }
            is PacketBatchEvent.Outcome -> if (event.success) { success(); successRecords(event.records.toLong()) } else { failure(); failureRecords(event.records.toLong()) }
            is PacketBatchEvent.EncodingFailure -> encodingFailure(event.records.toLong())
        }
    }

    private fun addBounded(map: LinkedHashMap<PacketMetricKey, BatchLogicalTotal>, key: PacketMetricKey, bytes: Long) {
        val existing = map[key]
        if (existing != null) {
            existing.count++
            existing.bytes += bytes
        } else if (map.size < keyCapacity) {
            map[key] = BatchLogicalTotal(1, bytes)
        } else {
            dropped.incrementAndGet()
        }
    }

    private fun persist(
        logical: Map<PacketMetricKey, BatchLogicalTotal>,
        inbound: Map<PacketMetricKey, BatchLogicalTotal>,
        frames: Map<BatchFrameKey, BatchFrameTotal>,
        writeSuccesses: Long,
        writeFailures: Long,
        successfulRecords: Long,
        failedRecords: Long,
        encodingFailures: Long,
        encodingFailureRecords: Long,
        dropped: Long,
        endUtc: String?,
    ) {
        beforeTransaction?.invoke()
        connection.autoCommit = false
        try {
            upsertLogical(logical)
            upsertInbound(inbound)
            upsertFrames(frames)
            connection.prepareStatement(
                """UPDATE packet_batch_windows SET dropped_samples=?, write_successes=?, write_failures=?,
                   successful_records=?, failed_records=?, encoding_failures=?, encoding_failure_records=?, end_utc=? WHERE window_id=?""".trimIndent(),
            ).use { statement ->
                statement.setLong(1, dropped)
                statement.setLong(2, writeSuccesses)
                statement.setLong(3, writeFailures)
                statement.setLong(4, successfulRecords)
                statement.setLong(5, failedRecords)
                statement.setLong(6, encodingFailures)
                statement.setLong(7, encodingFailureRecords)
                statement.setString(8, endUtc)
                statement.setLong(9, windowId)
                statement.executeUpdate()
            }
            connection.commit()
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun upsertLogical(totals: Map<PacketMetricKey, BatchLogicalTotal>) {
        connection.prepareStatement(
            """INSERT INTO packet_batch_logical_totals VALUES (?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(window_id, packet_type, direction, COALESCE(namespace, ':'), COALESCE(path, ':')) DO UPDATE SET
               packet_count=excluded.packet_count, sum_encoded_bytes=excluded.sum_encoded_bytes""".trimIndent(),
        ).use { statement ->
            totals.forEach { (key, value) ->
                statement.setLong(1, windowId)
                statement.setString(2, key.packetType)
                statement.setString(3, key.direction.databaseValue)
                statement.setString(4, key.namespace)
                statement.setString(5, key.path)
                statement.setLong(6, value.count)
                statement.setLong(7, value.bytes)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertInbound(totals: Map<PacketMetricKey, BatchLogicalTotal>) {
        connection.prepareStatement(
            """INSERT INTO packet_batch_inbound_totals VALUES (?, ?, 'c2s', ?, ?, ?, ?)
               ON CONFLICT(window_id, packet_type, direction, COALESCE(namespace, ':'), COALESCE(path, ':')) DO UPDATE SET
               packet_count=excluded.packet_count, sum_compressed_frame_bytes=excluded.sum_compressed_frame_bytes""".trimIndent(),
        ).use { statement ->
            totals.forEach { (key, value) ->
                statement.setLong(1, windowId)
                statement.setString(2, key.packetType)
                statement.setString(3, key.namespace)
                statement.setString(4, key.path)
                statement.setLong(5, value.count)
                statement.setLong(6, value.bytes)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun upsertFrames(totals: Map<BatchFrameKey, BatchFrameTotal>) {
        connection.prepareStatement(
            """INSERT INTO packet_batch_frame_totals(
                   window_id, frame_kind, flush_reason, frame_count, record_count, sum_payload_bytes,
                   sum_frame_bytes, sum_outer_prefix_bytes, sum_wait_nanos, sum_compression_nanos,
                   single_record_fallbacks, buffered_flushes, barrier_interruptions
               ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(window_id, frame_kind, flush_reason) DO UPDATE SET
               frame_count=excluded.frame_count, record_count=excluded.record_count,
               sum_payload_bytes=excluded.sum_payload_bytes, sum_frame_bytes=excluded.sum_frame_bytes,
               sum_outer_prefix_bytes=excluded.sum_outer_prefix_bytes, sum_wait_nanos=excluded.sum_wait_nanos,
               sum_compression_nanos=excluded.sum_compression_nanos, single_record_fallbacks=excluded.single_record_fallbacks,
               buffered_flushes=excluded.buffered_flushes, barrier_interruptions=excluded.barrier_interruptions""".trimIndent(),
        ).use { statement ->
            totals.forEach { (key, value) ->
                statement.setLong(1, windowId)
                statement.setString(2, key.kind)
                statement.setString(3, key.reason)
                statement.setLong(4, value.frames)
                statement.setLong(5, value.records)
                statement.setLong(6, value.payloadBytes)
                statement.setLong(7, value.frameBytes)
                statement.setLong(8, value.outerPrefixBytes)
                statement.setLong(9, value.waitNanos)
                statement.setLong(10, value.compressionNanos)
                statement.setLong(11, value.fallbackCount)
                statement.setLong(12, value.bufferedFlushes)
                statement.setLong(13, value.barrierInterruptions)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private companion object {
        const val QUEUE_CAPACITY = 32_768
        const val KEY_CAPACITY = 4_096
        const val MAX_FRAME_KEYS = 128
        const val MAX_LABEL_LENGTH = 256
        const val OVERFLOW_LABEL = ":overflow"
        const val OVERFLOW_PACKET_TYPE = "rdi:overflow"
        const val MAX_DRAIN_BATCH = 4_096
        const val POLL_MS = 100L
        const val RETRY_DELAY_MS = 250L
        const val SHUTDOWN_WAIT_MS = 10_000L
    }
}
