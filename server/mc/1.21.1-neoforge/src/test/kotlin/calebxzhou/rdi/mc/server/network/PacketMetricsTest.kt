package calebxzhou.rdi.mc.server.network

import net.minecraft.network.protocol.common.CommonPacketTypes
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PacketMetricsTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `fresh schema has five compressed packet total columns and no per run tables`() {
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
                            "namespace" to 0,
                            "path" to 0,
                            "packet_count" to 1,
                            "sum_compressed_frame_bytes" to 1,
                        ),
                        columns,
                    )
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
    fun `lifetime totals merge across flushes and recorder restarts`() {
        val databasePath = tempDir.resolve("totals.sqlite")
        val keepAlive = packetMetricKey(ServerboundKeepAlivePacket(1L))
        val customPayload = packetMetricKey(ServerboundCustomPayloadPacket(payload("example", "channel")))
        val differentPathPayload = packetMetricKey(ServerboundCustomPayloadPacket(payload("example", "other")))
        val differentNamespacePayload = packetMetricKey(ServerboundCustomPayloadPacket(payload("another", "channel")))
        val emptyPathPayload = packetMetricKey(ServerboundCustomPayloadPacket(payload("example", "")))
        val first = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        try {
            first.offer(sample(keepAlive, 10))
            waitForTotal(databasePath, keepAlive, 1, 10)
            first.offer(sample(keepAlive, 20))
            waitForTotal(databasePath, keepAlive, 2, 30)
        } finally {
            first.close()
        }

        val second = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        try {
            second.offer(sample(keepAlive, 5))
            second.offer(sample(customPayload, 7))
            second.offer(sample(customPayload, 11))
            second.offer(sample(differentPathPayload, 13))
            second.offer(sample(differentNamespacePayload, 17))
            second.offer(sample(emptyPathPayload, 9))
            waitForTotal(databasePath, keepAlive, 3, 35)
            waitForTotal(databasePath, customPayload, 2, 18)
            waitForTotal(databasePath, differentPathPayload, 1, 13)
            waitForTotal(databasePath, differentNamespacePayload, 1, 17)
            waitForTotal(databasePath, emptyPathPayload, 1, 9)
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

        val key = packetMetricKey(ServerboundKeepAlivePacket(1L))
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
    fun `packet keys retain packet type and custom payload namespace and path`() {
        val clientKeepAlive = packetMetricKey(ClientboundKeepAlivePacket(1L))
        val serverKeepAlive = packetMetricKey(ServerboundKeepAlivePacket(2L))
        assertEquals(clientKeepAlive, serverKeepAlive)
        assertEquals("minecraft:keep_alive", clientKeepAlive.packetType)
        assertEquals(null, clientKeepAlive.namespace)
        assertEquals(null, clientKeepAlive.path)

        val serverPayload = packetMetricKey(ServerboundCustomPayloadPacket(payload("example", "same")))
        val differentPath = packetMetricKey(ServerboundCustomPayloadPacket(payload("example", "other")))
        val differentNamespace = packetMetricKey(ServerboundCustomPayloadPacket(payload("another", "same")))
        val emptyPath = packetMetricKey(ServerboundCustomPayloadPacket(payload("example", "")))

        assertEquals(
            CommonPacketTypes.CLIENTBOUND_CUSTOM_PAYLOAD.id(),
            CommonPacketTypes.SERVERBOUND_CUSTOM_PAYLOAD.id(),
        )
        assertEquals("minecraft:custom_payload", serverPayload.packetType)
        assertEquals("example", serverPayload.namespace)
        assertEquals("same", serverPayload.path)
        assertNotEquals(serverPayload, differentPath)
        assertNotEquals(serverPayload, differentNamespace)
        assertEquals("", emptyPath.path)
        assertNotEquals(clientKeepAlive, emptyPath)
    }

    @Test
    fun `temporary baseline read failure retries without losing or double counting totals`() {
        val databasePath = tempDir.resolve("retry.sqlite")
        val key = packetMetricKey(ServerboundKeepAlivePacket(1L))
        val setup = PacketMetricsRecorder(databasePath, flushIntervalMs = 20)
        setup.close()
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(
                "INSERT INTO packet_compressed_totals(packet_type, namespace, path, packet_count, sum_compressed_frame_bytes) VALUES (?, NULL, NULL, ?, ?)",
            ).use { statement ->
                statement.setString(1, key.packetType)
                statement.setLong(2, 7)
                statement.setLong(3, 100)
                statement.executeUpdate()
            }
        }

        val secondKey = packetMetricKey(ServerboundCustomPayloadPacket(payload("example", "second")))
        DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
            connection.prepareStatement(
                "INSERT INTO packet_compressed_totals(packet_type, namespace, path, packet_count, sum_compressed_frame_bytes) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, secondKey.packetType)
                statement.setString(2, secondKey.namespace)
                statement.setString(3, secondKey.path)
                statement.setLong(4, 11)
                statement.setLong(5, 200)
                statement.executeUpdate()
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
            recorder.offer(sample(key, 5))
            recorder.offer(sample(secondKey, 9))
            waitForTotal(databasePath, key, 8, 105)
            waitForTotal(databasePath, secondKey, 12, 209)
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
            val key = packetMetricKey(ServerboundKeepAlivePacket(it.toLong()))
            try {
                recorder.offer(sample(key, 3))
                val total = (it + 1).toLong()
                waitForTotal(databasePath, key, total, total * 3L)
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

    private fun waitForTotal(databasePath: Path, key: PacketMetricKey, count: Long, bytes: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            val total = DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                connection.prepareStatement(
                    """SELECT packet_count, sum_compressed_frame_bytes FROM packet_compressed_totals
                       WHERE packet_type=? AND COALESCE(namespace, ':')=COALESCE(?, ':')
                       AND COALESCE(path, ':')=COALESCE(?, ':')""".trimIndent(),
                ).use { statement ->
                    statement.setString(1, key.packetType)
                    statement.setString(2, key.namespace)
                    statement.setString(3, key.path)
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

    private fun sample(key: PacketMetricKey, bytes: Long) = PacketMetricSample(key, bytes)

    private fun payload(namespace: String, path: String): CustomPacketPayload {
        val id = ResourceLocation.fromNamespaceAndPath(namespace, path)
        return object : CustomPacketPayload {
            override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = CustomPacketPayload.Type(id)
        }
    }
}
