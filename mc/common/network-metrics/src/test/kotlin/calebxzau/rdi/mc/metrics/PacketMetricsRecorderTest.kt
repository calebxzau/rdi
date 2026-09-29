package calebxzau.rdi.mc.metrics

import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PacketMetricsRecorderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fresh schema has direction constrained compressed totals and no per run tables`() {
        val databasePath = tempDir.resolve("schema.sqlite")
        val recorder = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        recorder.close()

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(packet_compressed_totals)").use { result ->
                    val columns = mutableListOf<Pair<String, Int>>()
                    while (result.next()) columns += result.getString("name") to result.getInt("notnull")
                    assertEquals(
                        listOf(
                            "packet_type" to 1,
                            "direction" to 1,
                            "namespace" to 0,
                            "path" to 0,
                            "packet_count" to 1,
                            "sum_compressed_frame_bytes" to 1,
                        ),
                        columns,
                    )
                }
                statement.executeQuery(
                    "SELECT sql FROM sqlite_master WHERE type='table' AND name='packet_compressed_totals'",
                ).use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getString(1).contains("'s2c'"))
                    assertTrue(result.getString(1).contains("'c2s'"))
                }
                statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('runs', 'packet_minutes')",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(0, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `same packet type and channel remain separate by direction across flushes and restarts`() {
        val databasePath = tempDir.resolve("directions.sqlite")
        val s2c = key(PacketDirection.S2C, "example", "channel")
        val c2s = key(PacketDirection.C2S, "example", "channel")
        val differentPath = key(PacketDirection.C2S, "example", "other")
        val differentNamespace = key(PacketDirection.C2S, "another", "channel")
        val emptyPath = key(PacketDirection.C2S, "example", "")
        val first = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        try {
            first.offer(sample(s2c, 10))
            waitForTotal(databasePath, s2c, 1, 10)
            first.offer(sample(c2s, 20))
            waitForTotal(databasePath, c2s, 1, 20)
        } finally {
            first.close()
        }

        val second = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        try {
            second.offer(sample(s2c, 5))
            second.offer(sample(c2s, 7))
            second.offer(sample(differentPath, 13))
            second.offer(sample(differentNamespace, 17))
            second.offer(sample(emptyPath, 9))
            waitForTotal(databasePath, s2c, 2, 15)
            waitForTotal(databasePath, c2s, 2, 27)
            waitForTotal(databasePath, differentPath, 1, 13)
            waitForTotal(databasePath, differentNamespace, 1, 17)
            waitForTotal(databasePath, emptyPath, 1, 9)
        } finally {
            second.close()
        }

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM packet_compressed_totals").use { result ->
                    assertTrue(result.next())
                    assertEquals(5, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `compressed totals use a separate table and preserve legacy packet totals`() {
        val databasePath = tempDir.resolve("legacy.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE TABLE packet_totals (
                        packet_type TEXT NOT NULL,
                        namespace TEXT,
                        path TEXT,
                        packet_count INTEGER NOT NULL,
                        sum_encoded_bytes INTEGER NOT NULL
                    )""".trimIndent(),
                )
                statement.execute(
                    """INSERT INTO packet_totals VALUES ('minecraft:keep_alive', NULL, NULL, 4, 128)""",
                )
            }
        }

        val key = key(PacketDirection.C2S)
        val recorder = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        try {
            recorder.offer(sample(key, 7))
            waitForTotal(databasePath, key, 1, 7)
        } finally {
            recorder.close()
        }

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT packet_count, sum_encoded_bytes FROM packet_totals WHERE packet_type='minecraft:keep_alive'",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(4L, result.getLong(1))
                    assertEquals(128L, result.getLong(2))
                }
            }
        }
    }

    @Test
    fun `temporary baseline read failure retries without losing or double counting totals by direction`() {
        val databasePath = tempDir.resolve("retry.sqlite")
        val s2cKey = key(PacketDirection.S2C, "example", "channel")
        val c2sKey = key(PacketDirection.C2S, "example", "channel")
        val setup = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        setup.close()

        listOf(s2cKey to (7L to 100L), c2sKey to (11L to 200L)).forEach { (key, total) ->
            DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.prepareStatement(
                    """INSERT INTO packet_compressed_totals
                        (packet_type, direction, namespace, path, packet_count, sum_compressed_frame_bytes)
                        VALUES (?, ?, ?, ?, ?, ?)""".trimIndent(),
                ).use { statement ->
                    statement.setString(1, key.packetType)
                    statement.setString(2, key.direction.databaseValue)
                    statement.setString(3, key.namespace)
                    statement.setString(4, key.path)
                    statement.setLong(5, total.first)
                    statement.setLong(6, total.second)
                    statement.executeUpdate()
                }
            }
        }

        val attempts = AtomicInteger()
        val recorder = PacketMetricsRecorder(
            databasePath,
            flushIntervalMs = 20,
            beforeBaselineRead = {
                if (attempts.incrementAndGet() == 2) throw IllegalStateException("temporary baseline read failure")
            },
        )
        try {
            recorder.offer(sample(s2cKey, 5))
            recorder.offer(sample(c2sKey, 9))
            waitForTotal(databasePath, s2cKey, 8, 105)
            waitForTotal(databasePath, c2sKey, 12, 209)
        } finally {
            recorder.close()
        }
        assertTrue(attempts.get() >= 3)
    }

    @Test
    fun `dropped sample state survives reopening`() {
        val databasePath = tempDir.resolve("dropped-state.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE packet_metrics_state (id INTEGER PRIMARY KEY CHECK (id = 1), dropped_samples INTEGER NOT NULL)")
                statement.execute("INSERT INTO packet_metrics_state(id, dropped_samples) VALUES (1, 19)")
            }
        }

        repeat(2) {
            val recorder = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
            val metricKey = key(PacketDirection.C2S)
            try {
                recorder.offer(sample(metricKey, 3))
                val total = (it + 1).toLong()
                waitForTotal(databasePath, metricKey, total, total * 3L)
            } finally {
                recorder.close()
            }
        }

        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT dropped_samples FROM packet_metrics_state WHERE id=1").use { result ->
                    assertTrue(result.next())
                    assertEquals(19L, result.getLong(1))
                }
            }
        }
    }

    @Test
    fun `channel budget survives flushes and restarts while overflow keeps direction and totals`(): Unit {
        val databasePath = tempDir.resolve("channel-budget.sqlite")
        val known = key(PacketDirection.C2S, "example", "known")
        val second = key(PacketDirection.C2S, "example", "second")
        val c2sOverflow = overflowKey(PacketDirection.C2S)
        val s2cOverflow = overflowKey(PacketDirection.S2C)
        val first = PacketMetricsRecorder(databasePath, flushIntervalMs = 20, channelCapacity = 2)
        try {
            first.offer(sample(known, 10))
            waitForTotal(databasePath, known, 1, 10)
            first.offer(sample(second, 20))
            first.offer(sample(key(PacketDirection.C2S, "example", "third"), 30))
            first.offer(sample(key(PacketDirection.S2C, "example", "third"), 40))
            waitForTotal(databasePath, c2sOverflow, 1, 30)
        } finally {
            first.close()
        }

        val reopened = PacketMetricsRecorder(databasePath, flushIntervalMs = 20, channelCapacity = 2)
        try {
            reopened.offer(sample(known, 5))
            reopened.offer(sample(key(PacketDirection.C2S, "example", "fourth"), 7))
            reopened.offer(sample(key(PacketDirection.S2C, "another", "fifth"), 9))
            // Channel-less packets remain separate from overflow even when the budget is full.
            reopened.offer(sample(key(PacketDirection.C2S), 11))
        } finally {
            reopened.close()
        }
        waitForTotal(databasePath, known, 2, 15)
        waitForTotal(databasePath, second, 1, 20)
        waitForTotal(databasePath, c2sOverflow, 2, 37)
        waitForTotal(databasePath, s2cOverflow, 2, 49)
        waitForTotal(databasePath, key(PacketDirection.C2S), 1, 11)
        assertEquals(5, rowCount(databasePath))
    }

    @Test
    fun `oversized channels merge without consuming a slot or colliding with a real overflow name`(): Unit {
        val databasePath = tempDir.resolve("channel-length.sqlite")
        val maximum = key(PacketDirection.C2S, "a", "x".repeat(PacketMetricsRecorder.MAX_CHANNEL_NAME_LENGTH - 2))
        val realOverflowName = key(PacketDirection.C2S, "rdi", "overflow")
        val recorder = PacketMetricsRecorder(databasePath, channelCapacity = 2)
        try {
            recorder.offer(sample(key(PacketDirection.C2S, "a", "x".repeat(255)), 3))
            recorder.offer(sample(key(PacketDirection.C2S, "x".repeat(256), ""), 5))
            recorder.offer(sample(maximum, 7))
            recorder.offer(sample(realOverflowName, 11))
        } finally {
            recorder.close()
        }
        waitForTotal(databasePath, overflowKey(PacketDirection.C2S), 2, 8)
        waitForTotal(databasePath, maximum, 1, 7)
        waitForTotal(databasePath, realOverflowName, 1, 11)
        assertEquals(3, rowCount(databasePath))
    }

    @Test
    fun `legacy rows above the limit are preserved and do not grant new channel slots`(): Unit {
        val databasePath = tempDir.resolve("legacy-channel-budget.sqlite")
        val setup = PacketMetricsRecorder(databasePath, channelCapacity = 3)
        try {
            repeat(3) { index -> setup.offer(sample(key(PacketDirection.C2S, "old", "channel${index}"), 10)) }
        } finally {
            setup.close()
        }
        repeat(2) { restart ->
            val recorder = PacketMetricsRecorder(databasePath, channelCapacity = 1)
            try {
                recorder.offer(sample(key(PacketDirection.C2S, "old", "channel0"), 2))
                recorder.offer(sample(key(PacketDirection.C2S, "new", "channel${restart}"), 3))
            } finally {
                recorder.close()
            }
        }
        waitForTotal(databasePath, key(PacketDirection.C2S, "old", "channel0"), 3, 14)
        waitForTotal(databasePath, key(PacketDirection.C2S, "old", "channel1"), 1, 10)
        waitForTotal(databasePath, key(PacketDirection.C2S, "old", "channel2"), 1, 10)
        waitForTotal(databasePath, overflowKey(PacketDirection.C2S), 2, 6)
        assertEquals(4, rowCount(databasePath))
    }

    @Test
    fun `legacy oversized rows occupy the restored budget without being reused`(): Unit {
        val databasePath = tempDir.resolve("legacy-long-channel.sqlite")
        PacketMetricsRecorder(databasePath).close()
        val legacy = key(PacketDirection.C2S, "old", "x".repeat(1_024))
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(
                """INSERT INTO packet_compressed_totals
                   (packet_type, direction, namespace, path, packet_count, sum_compressed_frame_bytes)
                   VALUES (?, ?, ?, ?, 1, 10)""".trimIndent(),
            ).use { statement ->
                statement.setString(1, legacy.packetType)
                statement.setString(2, legacy.direction.databaseValue)
                statement.setString(3, legacy.namespace)
                statement.setString(4, legacy.path)
                statement.executeUpdate()
            }
        }
        val recorder = PacketMetricsRecorder(databasePath, channelCapacity = 1)
        try {
            recorder.offer(sample(legacy, 3))
            recorder.offer(sample(key(PacketDirection.C2S, "new", "channel"), 5))
        } finally {
            recorder.close()
        }
        waitForTotal(databasePath, legacy, 1, 10)
        waitForTotal(databasePath, overflowKey(PacketDirection.C2S), 2, 8)
        assertEquals(2, rowCount(databasePath))
    }

    @Test
    fun `overflow survives a failed flush without losing or duplicating samples`(): Unit {
        val databasePath = tempDir.resolve("overflow-retry.sqlite")
        val attempts = AtomicInteger()
        val recorder = PacketMetricsRecorder(
            databasePath,
            flushIntervalMs = 20,
            beforeBaselineRead = {
                if (attempts.incrementAndGet() == 1) throw IllegalStateException("temporary baseline read failure")
            },
            channelCapacity = 0,
        )
        try {
            recorder.offer(sample(key(PacketDirection.C2S, "example", "first"), 3))
            recorder.offer(sample(key(PacketDirection.C2S, "example", "second"), 5))
            waitForTotal(databasePath, overflowKey(PacketDirection.C2S), 2, 8)
        } finally {
            recorder.close()
        }
        assertTrue(attempts.get() >= 2)
        assertEquals(1, rowCount(databasePath))
    }

    private fun overflowKey(direction: PacketDirection): PacketMetricKey =
        key(direction, PacketMetricsRecorder.OVERFLOW_CHANNEL, PacketMetricsRecorder.OVERFLOW_CHANNEL)

    private fun rowCount(databasePath: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM packet_compressed_totals").use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun waitForTotal(databasePath: Path, key: PacketMetricKey, count: Long, bytes: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            val total = DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.prepareStatement(
                    """SELECT packet_count, sum_compressed_frame_bytes FROM packet_compressed_totals
                       WHERE packet_type=? AND direction=? AND COALESCE(namespace, ':')=COALESCE(?, ':')
                       AND COALESCE(path, ':')=COALESCE(?, ':')""".trimIndent(),
                ).use { statement ->
                    statement.setString(1, key.packetType)
                    statement.setString(2, key.direction.databaseValue)
                    statement.setString(3, key.namespace)
                    statement.setString(4, key.path)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.getLong(1) to result.getLong(2) else null
                    }
                }
            }
            if (total == (count to bytes)) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for packet total $key to become ($count, $bytes)")
    }

    private fun key(
        direction: PacketDirection,
        namespace: String? = null,
        path: String? = null,
    ) = PacketMetricKey("minecraft:custom_payload", direction, namespace, path)

    private fun sample(key: PacketMetricKey, bytes: Long) = PacketMetricSample(key, bytes)
}
