package calebxzau.rdi.mc.metrics

import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class PacketMetricSample(
    val key: PacketMetricKey,
    /** Compressed frame-content bytes, including the inner compression envelope and excluding the outer frame length. */
    val compressedFrameBytes: Long,
)

data class PacketMetricKey(
    val packetType: String,
    val direction: PacketDirection,
    val namespace: String?,
    val path: String?,
)

internal data class PacketMetricTotal(
    var packetCount: Long,
    var compressedFrameBytes: Long,
    var baselineLoaded: Boolean = false,
)

/** Thread-safe ingress; the writer thread exclusively owns the SQLite connection and aggregates. */
object PacketMetrics {
    private val logger = LogManager.getLogger("rdi.packet-metrics")
    private val active = AtomicReference<PacketMetricsRecorder?>(null)

    val isActive: Boolean
        get() = active.get() != null

    @JvmStatic
    fun start(databasePath: Path) {
        active.getAndSet(null)?.close()
        runCatching {
            Class.forName("org.sqlite.JDBC")
            val recorder = PacketMetricsRecorder(databasePath)
            active.set(recorder)
        }.onFailure { error ->
            logger.error("Failed to initialize packet metrics; collection is disabled", error)
            active.set(null)
        }
    }

    @JvmStatic
    fun record(key: PacketMetricKey, compressedFrameBytes: Int) {
        val recorder = active.get() ?: return
        runCatching {
            recorder.offer(
                PacketMetricSample(
                    key = key,
                    compressedFrameBytes = compressedFrameBytes.toLong(),
                ),
            )
        }.onFailure { error -> logger.error("Packet metrics ingress failed", error) }
    }

    @JvmStatic
    fun stop() {
        active.getAndSet(null)?.close()
    }
}

internal class PacketMetricsRecorder(
    databasePath: Path,
    private val flushIntervalMs: Long = 5_000L,
    private val beforeBaselineRead: (() -> Unit)? = null,
    private val channelCapacity: Int = MAX_CHANNEL_KEYS,
) {
    private val logger = LogManager.getLogger("rdi.packet-metrics")
    private val queue = ArrayBlockingQueue<PacketMetricSample>(QUEUE_CAPACITY)
    private val droppedSamples = AtomicLong()
    private val stopping = AtomicBoolean(false)
    private val shutdownDeadlineNanos = AtomicLong(Long.MAX_VALUE)
    private val persistedDroppedSamples = AtomicLong()
    // Initialized from SQLite before the writer starts, then owned only by the writer.
    private val admittedChannelKeys = HashSet<PacketMetricKey>()
    private var channelSlotsUsed = 0
    private val connection: Connection
    private val initialDroppedSamples: Long
    private val writer: Thread

    init {
        require(channelCapacity >= 0) { "Packet metric channel capacity must be nonnegative" }
        Files.createDirectories(databasePath.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS packet_compressed_totals (
                        packet_type TEXT NOT NULL,
                        direction TEXT NOT NULL CHECK (direction IN ('s2c', 'c2s')),
                        namespace TEXT,
                        path TEXT,
                        packet_count INTEGER NOT NULL,
                        sum_compressed_frame_bytes INTEGER NOT NULL,
                        CHECK ((namespace IS NULL AND path IS NULL) OR (namespace IS NOT NULL AND path IS NOT NULL))
                    )""".trimIndent(),
                )
                // ':' is invalid in ResourceLocation components, so NULL stays distinct from an empty component.
                statement.execute(
                    """CREATE UNIQUE INDEX IF NOT EXISTS packet_compressed_totals_key
                        ON packet_compressed_totals(packet_type, direction, COALESCE(namespace, ':'), COALESCE(path, ':'))""".trimIndent(),
                )
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS packet_metrics_state (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        dropped_samples INTEGER NOT NULL
                    )""".trimIndent(),
                )
                statement.execute("INSERT OR IGNORE INTO packet_metrics_state(id, dropped_samples) VALUES (1, 0)")
            }
            initialDroppedSamples = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT dropped_samples FROM packet_metrics_state WHERE id=1").use { result ->
                    check(result.next()) { "Packet metrics dropped-sample state row is missing" }
                    result.getLong(1)
                }
            }
            loadChannelKeys()
            writer = Thread(::runWriter, "rdi-packet-metrics-writer").apply {
                isDaemon = true
                start()
            }
        } catch (error: Throwable) {
            runCatching { connection.close() }
                .onFailure { error.addSuppressed(it) }
            throw error
        }
        logger.info("Packet metrics started at {}", databasePath)
    }

    fun offer(sample: PacketMetricSample) {
        // Do not retain arbitrarily long client-controlled names in the bounded queue.
        val boundedSample = if (hasOversizedChannel(sample.key)) {
            sample.copy(key = overflowKey(sample.key))
        } else {
            sample
        }
        if (stopping.get() || !queue.offer(boundedSample)) {
            droppedSamples.incrementAndGet()
        }
    }

    private fun loadChannelKeys() {
        connection.prepareStatement(
            """SELECT packet_type, direction, namespace, path FROM packet_compressed_totals
               WHERE namespace IS NOT NULL AND NOT (namespace=? AND path=?)
               ORDER BY rowid LIMIT ?""".trimIndent(),
        ).use { statement ->
            statement.setString(1, OVERFLOW_CHANNEL)
            statement.setString(2, OVERFLOW_CHANNEL)
            statement.setInt(3, channelCapacity)
            statement.executeQuery().use { result ->
                while (result.next()) {
                    // Legacy oversized rows still occupy a slot, without retaining their names.
                    channelSlotsUsed++
                    val key = PacketMetricKey(
                        packetType = result.getString(1),
                        direction = PacketDirection.entries.first { it.databaseValue == result.getString(2) },
                        namespace = result.getString(3),
                        path = result.getString(4),
                    )
                    if (!hasOversizedChannel(key)) admittedChannelKeys.add(key)
                }
            }
        }
    }

    private fun boundedChannelKey(key: PacketMetricKey): PacketMetricKey {
        if (key.namespace == null || isOverflowKey(key) || key in admittedChannelKeys) return key
        if (channelSlotsUsed >= channelCapacity) return overflowKey(key)
        admittedChannelKeys.add(key)
        channelSlotsUsed++
        return key
    }

    private fun hasOversizedChannel(key: PacketMetricKey): Boolean =
        key.namespace != null && key.namespace.length.toLong() + 1 + (key.path?.length ?: 0) > MAX_CHANNEL_NAME_LENGTH

    private fun isOverflowKey(key: PacketMetricKey): Boolean =
        key.namespace == OVERFLOW_CHANNEL && key.path == OVERFLOW_CHANNEL

    private fun overflowKey(key: PacketMetricKey): PacketMetricKey =
        key.copy(namespace = OVERFLOW_CHANNEL, path = OVERFLOW_CHANNEL)

    fun close() {
        if (!stopping.compareAndSet(false, true)) return
        shutdownDeadlineNanos.set(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT_MS))
        try {
            writer.join(SHUTDOWN_WAIT_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("Interrupted while waiting for packet metrics to shut down", interrupted)
            return
        }
        if (writer.isAlive) {
            logger.warn("Packet metrics writer did not drain within {} ms; it will close its database connection after retrying", SHUTDOWN_WAIT_MS)
        }
    }

    private fun runWriter() {
        var lastFlush = System.nanoTime()
        val aggregates = LinkedHashMap<PacketMetricKey, PacketMetricTotal>()
        try {
            while (!stopping.get() || queue.isNotEmpty() || aggregates.isNotEmpty() || persistedDroppedSamples.get() < droppedSamples.get()) {
                if (stopping.get() && System.nanoTime() >= shutdownDeadlineNanos.get()) {
                    logger.error("Packet metrics shutdown deadline expired with {} pending aggregates and {} queued samples", aggregates.size, queue.size)
                    break
                }
                try {
                    var processed = 0
                    val sample = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                    if (sample != null) {
                        addSample(aggregates, sample)
                        processed++
                    }
                    while (processed < MAX_DRAIN_BATCH) {
                        val next = queue.poll() ?: break
                        addSample(aggregates, next)
                        processed++
                    }
                    val now = System.nanoTime()
                    if (now - lastFlush >= TimeUnit.MILLISECONDS.toNanos(flushIntervalMs) || (stopping.get() && queue.isEmpty())) {
                        flush(aggregates)
                        lastFlush = now
                    }
                    if (stopping.get() && System.nanoTime() >= shutdownDeadlineNanos.get()) {
                        logger.error("Packet metrics shutdown deadline expired with {} pending aggregates and {} queued samples", aggregates.size, queue.size)
                        break
                    }
                } catch (interrupted: InterruptedException) {
                    if (!stopping.get()) logger.warn("Packet metrics writer was interrupted", interrupted)
                } catch (error: Throwable) {
                    logger.error("Packet metrics writer failed; pending aggregates will be retried", error)
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (_: InterruptedException) {
                        if (!stopping.get()) Thread.currentThread().interrupt()
                    }
                }
            }
        } finally {
            runCatching { connection.close() }
                .onFailure { logger.error("Failed to close packet metrics database", it) }
        }
    }

    private fun addSample(aggregates: LinkedHashMap<PacketMetricKey, PacketMetricTotal>, sample: PacketMetricSample) {
        val key = boundedChannelKey(sample.key)
        val current = aggregates[key]
        if (current != null) {
            current.packetCount++
            current.compressedFrameBytes += sample.compressedFrameBytes
            return
        }
        if (aggregates.size >= AGGREGATE_CAPACITY) {
            droppedSamples.incrementAndGet()
            return
        }
        aggregates[key] = PacketMetricTotal(
            packetCount = 1,
            compressedFrameBytes = sample.compressedFrameBytes,
        )
    }

    private fun readPersistedTotal(key: PacketMetricKey): Pair<Long, Long> = connection.prepareStatement(
        """SELECT packet_count, sum_compressed_frame_bytes FROM packet_compressed_totals
           WHERE packet_type=? AND direction=? AND COALESCE(namespace, ':')=COALESCE(?, ':')
           AND COALESCE(path, ':')=COALESCE(?, ':')""".trimIndent(),
    ).use { statement ->
        statement.setString(1, key.packetType)
        statement.setString(2, key.direction.databaseValue)
        statement.setString(3, key.namespace)
        statement.setString(4, key.path)
        statement.executeQuery().use { result ->
            if (result.next()) result.getLong(1) to result.getLong(2) else 0L to 0L
        }
    }

    private fun flush(aggregates: LinkedHashMap<PacketMetricKey, PacketMetricTotal>) {
        if (aggregates.isEmpty()) {
            persistDroppedCount(droppedSamples.get())
            return
        }
        connection.autoCommit = false
        try {
            connection.prepareStatement(
                """INSERT INTO packet_compressed_totals(packet_type, direction, namespace, path, packet_count, sum_compressed_frame_bytes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT(packet_type, direction, COALESCE(namespace, ':'), COALESCE(path, ':')) DO UPDATE SET
                    packet_count=excluded.packet_count, sum_compressed_frame_bytes=excluded.sum_compressed_frame_bytes""".trimIndent(),
            ).use { statement ->
                aggregates.forEach { (key, total) ->
                    if (!total.baselineLoaded) {
                        beforeBaselineRead?.invoke()
                        val persisted = readPersistedTotal(key)
                        total.packetCount += persisted.first
                        total.compressedFrameBytes += persisted.second
                        total.baselineLoaded = true
                    }
                    statement.setString(1, key.packetType)
                    statement.setString(2, key.direction.databaseValue)
                    statement.setString(3, key.namespace)
                    statement.setString(4, key.path)
                    statement.setLong(5, total.packetCount)
                    statement.setLong(6, total.compressedFrameBytes)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            val droppedSnapshot = droppedSamples.get()
            writeDroppedCount(droppedSnapshot)
            connection.commit()
            aggregates.clear()
            persistedDroppedSamples.set(droppedSnapshot)
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun persistDroppedCount(droppedSnapshot: Long) {
        writeDroppedCount(droppedSnapshot)
        persistedDroppedSamples.set(droppedSnapshot)
    }

    private fun writeDroppedCount(droppedSnapshot: Long) {
        connection.prepareStatement("UPDATE packet_metrics_state SET dropped_samples=? WHERE id=1").use { statement ->
            statement.setLong(1, initialDroppedSamples + droppedSnapshot)
            statement.executeUpdate()
        }
    }

    internal companion object {
        const val MAX_CHANNEL_KEYS = 4_096
        const val MAX_CHANNEL_NAME_LENGTH = 256
        // ':' cannot occur inside a ResourceLocation component; distinct from SQL's NULL sentinel ':'.
        const val OVERFLOW_CHANNEL = ":overflow"
        const val QUEUE_CAPACITY = 65_536
        const val AGGREGATE_CAPACITY = 131_072
        const val MAX_DRAIN_BATCH = 8_192
        const val POLL_MS = 250L
        const val SHUTDOWN_WAIT_MS = 10_000L
        const val RETRY_DELAY_MS = 1_000L
    }
}
