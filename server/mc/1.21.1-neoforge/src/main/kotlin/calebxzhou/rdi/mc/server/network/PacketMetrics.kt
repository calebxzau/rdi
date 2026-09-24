package calebxzhou.rdi.mc.server.network

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
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
    /** Uncompressed packet-body bytes. */
    val encodedBytes: Long,
)

internal data class PacketMetricKey(
    val packetType: String,
    val namespace: String?,
    val path: String?,
)

internal data class PacketMetricTotal(
    var packetCount: Long,
    var encodedBytes: Long,
    var baselineLoaded: Boolean = false,
)

internal fun packetMetricKey(packet: Packet<*>): PacketMetricKey {
    val channel = when (packet) {
        is ClientboundCustomPayloadPacket -> packet.payload().type().id()
        is ServerboundCustomPayloadPacket -> packet.payload().type().id()
        else -> null
    }
    return PacketMetricKey(
        packetType = packet.type().id().toString(),
        namespace = channel?.namespace,
        path = channel?.path,
    )
}

/** Thread-safe ingress; the writer thread exclusively owns the SQLite connection and aggregates. */
object PacketMetrics {
    private val logger = LogManager.getLogger("rdi.packet-metrics")
    private val active = AtomicReference<PacketMetricsRecorder?>(null)

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
    fun record(packet: Packet<*>, encodedBytes: Int) {
        val recorder = active.get() ?: return
        runCatching {
            recorder.offer(
                PacketMetricSample(
                    key = packetMetricKey(packet),
                    encodedBytes = encodedBytes.toLong(),
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
) {
    private val logger = LogManager.getLogger("rdi.packet-metrics")
    private val queue = ArrayBlockingQueue<PacketMetricSample>(QUEUE_CAPACITY)
    private val droppedSamples = AtomicLong()
    private val stopping = AtomicBoolean(false)
    private val shutdownDeadlineNanos = AtomicLong(Long.MAX_VALUE)
    private val persistedDroppedSamples = AtomicLong()
    private val connection: Connection
    private val initialDroppedSamples: Long
    private val writer: Thread

    init {
        Files.createDirectories(databasePath.parent)
        connection = DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}")
        try {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute(
                    """CREATE TABLE IF NOT EXISTS packet_totals (
                        packet_type TEXT NOT NULL,
                        namespace TEXT,
                        path TEXT,
                        packet_count INTEGER NOT NULL,
                        sum_encoded_bytes INTEGER NOT NULL,
                        CHECK ((namespace IS NULL AND path IS NULL) OR (namespace IS NOT NULL AND path IS NOT NULL))
                    )""".trimIndent(),
                )
                // ':' is invalid in ResourceLocation components, so NULL stays distinct from an empty component.
                statement.execute(
                    """CREATE UNIQUE INDEX IF NOT EXISTS packet_totals_key
                        ON packet_totals(packet_type, COALESCE(namespace, ':'), COALESCE(path, ':'))""".trimIndent(),
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
        if (stopping.get() || !queue.offer(sample)) {
            droppedSamples.incrementAndGet()
        }
    }

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
        val current = aggregates[sample.key]
        if (current != null) {
            current.packetCount++
            current.encodedBytes += sample.encodedBytes
            return
        }
        if (aggregates.size >= AGGREGATE_CAPACITY) {
            droppedSamples.incrementAndGet()
            return
        }
        aggregates[sample.key] = PacketMetricTotal(
            packetCount = 1,
            encodedBytes = sample.encodedBytes,
        )
    }

    private fun readPersistedTotal(key: PacketMetricKey): Pair<Long, Long> = connection.prepareStatement(
        """SELECT packet_count, sum_encoded_bytes FROM packet_totals
           WHERE packet_type=? AND COALESCE(namespace, ':')=COALESCE(?, ':')
           AND COALESCE(path, ':')=COALESCE(?, ':')""".trimIndent(),
    ).use { statement ->
        statement.setString(1, key.packetType)
        statement.setString(2, key.namespace)
        statement.setString(3, key.path)
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
                """INSERT INTO packet_totals(packet_type, namespace, path, packet_count, sum_encoded_bytes)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(packet_type, COALESCE(namespace, ':'), COALESCE(path, ':')) DO UPDATE SET
                    packet_count=excluded.packet_count, sum_encoded_bytes=excluded.sum_encoded_bytes""".trimIndent(),
            ).use { statement ->
                aggregates.forEach { (key, total) ->
                    if (!total.baselineLoaded) {
                        beforeBaselineRead?.invoke()
                        val persisted = readPersistedTotal(key)
                        total.packetCount += persisted.first
                        total.encodedBytes += persisted.second
                        total.baselineLoaded = true
                    }
                    statement.setString(1, key.packetType)
                    statement.setString(2, key.namespace)
                    statement.setString(3, key.path)
                    statement.setLong(4, total.packetCount)
                    statement.setLong(5, total.encodedBytes)
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

    private companion object {
        const val QUEUE_CAPACITY = 65_536
        const val AGGREGATE_CAPACITY = 131_072
        const val MAX_DRAIN_BATCH = 8_192
        const val POLL_MS = 250L
        const val SHUTDOWN_WAIT_MS = 10_000L
        const val RETRY_DELAY_MS = 1_000L
    }
}
