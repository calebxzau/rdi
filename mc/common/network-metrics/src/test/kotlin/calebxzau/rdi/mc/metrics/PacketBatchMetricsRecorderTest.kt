package calebxzau.rdi.mc.metrics

import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PacketBatchMetricsRecorderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `writes separate logical frame inbound and outcome measurements across reopened windows`() {
        val database = tempDir.resolve("batch.sqlite")
        val logicalKey = PacketMetricKey("example:message", PacketDirection.S2C, "example", "main")
        val inboundKey = PacketMetricKey("example:message", PacketDirection.C2S, "example", "main")

        PacketBatchMetricsRecorder(database, flushIntervalMs = 10).apply {
            offerLogical(logicalKey, 41)
            offerFrame(PacketBatchFrameSample("zstd_batch", 2, 83, 55, 2, "tick", 120, 30, false, bufferedFlush = true, firstFrameOfFlush = true))
            offerOutcome(true, 2)
            offerOutcome(false, 1)
            offerEncodingFailure(3)
            offerInbound(inboundKey, 19)
            close()
        }

        PacketBatchMetricsRecorder(database, flushIntervalMs = 10).apply {
            offerLogical(logicalKey, 7)
            offerFrame(PacketBatchFrameSample("legacy", 1, 7, 12, 1, "single_fallback", 4, 0, true))
            offerInbound(inboundKey, 5)
            close()
        }

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT window_id, start_utc, end_utc FROM packet_batch_windows ORDER BY window_id").use { rows ->
                    assertTrue(rows.next())
                    val firstWindow = rows.getLong(1)
                    assertTrue(rows.getString(2).endsWith("Z"))
                    assertTrue(rows.getString(3).endsWith("Z"))
                    assertTrue(rows.next())
                    val secondWindow = rows.getLong(1)
                    assertTrue(rows.getString(2).endsWith("Z"))
                    assertTrue(rows.getString(3).endsWith("Z"))
                    assertEquals(false, rows.next())

                    connection.prepareStatement(
                        "SELECT packet_count, sum_encoded_bytes FROM packet_batch_logical_totals WHERE window_id=?",
                    ).use { query ->
                        query.setLong(1, firstWindow)
                        query.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertEquals(1L, result.getLong(1))
                            assertEquals(41L, result.getLong(2))
                        }
                        query.setLong(1, secondWindow)
                        query.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertEquals(1L, result.getLong(1))
                            assertEquals(7L, result.getLong(2))
                        }
                    }

                    connection.prepareStatement(
                        "SELECT frame_kind, flush_reason, frame_count, record_count, sum_payload_bytes, sum_frame_bytes, sum_outer_prefix_bytes, sum_wait_nanos, sum_compression_nanos, single_record_fallbacks, buffered_flushes, barrier_interruptions FROM packet_batch_frame_totals WHERE window_id=?",
                    ).use { query ->
                        query.setLong(1, firstWindow)
                        query.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertEquals("zstd_batch", result.getString(1))
                            assertEquals("tick", result.getString(2))
                            assertEquals(listOf(1L, 2L, 83L, 55L, 2L, 120L, 30L, 0L, 1L, 0L), (3..12).map(result::getLong))
                        }
                        query.setLong(1, secondWindow)
                        query.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertEquals("legacy", result.getString(1))
                            assertEquals(1L, result.getLong("single_record_fallbacks"))
                        }
                    }

                    connection.prepareStatement(
                        "SELECT direction, packet_count, sum_compressed_frame_bytes FROM packet_batch_inbound_totals WHERE window_id=?",
                    ).use { query ->
                        query.setLong(1, firstWindow)
                        query.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertEquals("c2s", result.getString(1))
                            assertEquals(1L, result.getLong(2))
                            assertEquals(19L, result.getLong(3))
                        }
                    }
                    connection.prepareStatement(
                        "SELECT write_successes, write_failures, successful_records, failed_records, encoding_failures, encoding_failure_records FROM packet_batch_windows WHERE window_id=?",
                    ).use { query ->
                        query.setLong(1, firstWindow)
                        query.executeQuery().use { result ->
                            assertTrue(result.next())
                            assertEquals(listOf(1L, 1L, 2L, 1L, 1L, 3L), (1..6).map(result::getLong))
                        }
                    }
                }
                statement.executeQuery("PRAGMA table_info(packet_batch_frame_totals)").use { result ->
                    val frameColumns = mutableSetOf<String>()
                    while (result.next()) frameColumns += result.getString("name")
                    assertTrue("packet_type" !in frameColumns)
                    assertTrue("namespace" !in frameColumns)
                    assertTrue("path" !in frameColumns)
                }
            }
        }
    }

    @Test
    fun `queue drops and key overflow are persisted without retaining unbounded names`() {
        val database = tempDir.resolve("drops.sqlite")
        val enteredTransaction = CountDownLatch(1)
        val releaseTransaction = CountDownLatch(1)
        val hookCalls = AtomicInteger()
        val recorder = PacketBatchMetricsRecorder(
            database,
            flushIntervalMs = 5,
            queueCapacity = 1,
            keyCapacity = 1,
            beforeTransaction = {
                if (hookCalls.incrementAndGet() == 1) {
                    enteredTransaction.countDown()
                    check(releaseTransaction.await(2, TimeUnit.SECONDS))
                }
            },
        )
        try {
            recorder.offerLogical(PacketMetricKey("one", PacketDirection.S2C, null, null), 1)
            assertTrue(enteredTransaction.await(2, TimeUnit.SECONDS))
            recorder.offerLogical(PacketMetricKey("two", PacketDirection.S2C, null, null), 2)
            recorder.offerLogical(PacketMetricKey("three", PacketDirection.S2C, null, null), 3)
        } finally {
            releaseTransaction.countDown()
            recorder.close()
        }

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT dropped_samples FROM packet_batch_windows").use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getLong(1) >= 1L)
                }
                statement.executeQuery("SELECT COUNT(*) FROM packet_batch_logical_totals").use { result ->
                    assertTrue(result.next())
                    assertEquals(1, result.getInt(1))
                }
            }
        }
    }

    @Test
    fun `buffered multi frame barrier counts one interruption and one wait while standalone barrier counts neither`() {
        val database = tempDir.resolve("barrier-metrics.sqlite")
        PacketBatchMetricsRecorder(database, flushIntervalMs = 10).apply {
            offerFrame(PacketBatchFrameSample("Uncompressed", 1, 10, 14, 1, "BARRIER", 100, 5, false, bufferedFlush = true, firstFrameOfFlush = true))
            offerFrame(PacketBatchFrameSample("Uncompressed", 1, 11, 15, 1, "BARRIER", 100, 7, false, bufferedFlush = true, firstFrameOfFlush = false))
            offerFrame(PacketBatchFrameSample("Uncompressed", 1, 12, 16, 1, "BARRIER", 0, 0, false))
            close()
        }

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT frame_count, record_count, sum_payload_bytes, sum_frame_bytes, sum_outer_prefix_bytes, sum_wait_nanos, sum_compression_nanos, buffered_flushes, barrier_interruptions FROM packet_batch_frame_totals",
                ).use { result ->
                    assertTrue(result.next())
                    assertEquals(listOf(3L, 3L, 33L, 45L, 3L, 100L, 12L, 1L, 1L), (1..9).map(result::getLong))
                    assertEquals(false, result.next())
                }
            }
        }
    }

    @Test
    fun `adds flush counters when reopening a database with the earlier frame schema`() {
        val database = tempDir.resolve("old-frame-schema.sqlite")
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """CREATE TABLE packet_batch_frame_totals (
                        window_id INTEGER NOT NULL,
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
                        PRIMARY KEY (window_id, frame_kind, flush_reason)
                    )""".trimIndent(),
                )
            }
        }

        PacketBatchMetricsRecorder(database, flushIntervalMs = 10).apply {
            offerFrame(PacketBatchFrameSample("legacy", 1, 5, 8, 1, "BARRIER", 20, 0, false, bufferedFlush = true, firstFrameOfFlush = true))
            close()
        }

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT buffered_flushes, barrier_interruptions, sum_wait_nanos FROM packet_batch_frame_totals").use { result ->
                    assertTrue(result.next())
                    assertEquals(listOf(1L, 1L, 20L), (1..3).map(result::getLong))
                }
            }
        }
    }

    @Test
    fun `shutdown retries multiple failed transactions before completing without double counting`() {
        val database = tempDir.resolve("retry.sqlite")
        val attempts = AtomicInteger()
        val recorder = PacketBatchMetricsRecorder(
            database,
            flushIntervalMs = 60_000,
            beforeTransaction = {
                if (attempts.incrementAndGet() <= 2) error("temporary transaction failure ${attempts.get()}")
            },
        )
        recorder.offerLogical(PacketMetricKey("minecraft:keep_alive", PacketDirection.C2S, null, null), 23)
        recorder.offerFrame(PacketBatchFrameSample("zstd_batch", 2, 50, 30, 1, "size", 5, 7, false))
        recorder.close()
        assertTrue(attempts.get() >= 3)

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT packet_count, sum_encoded_bytes, namespace FROM packet_batch_logical_totals").use { result ->
                    assertTrue(result.next())
                    assertEquals(1L, result.getLong(1))
                    assertEquals(23L, result.getLong(2))
                    assertNull(result.getObject(3))
                }
                statement.executeQuery("SELECT frame_count, sum_frame_bytes FROM packet_batch_frame_totals").use { result ->
                    assertTrue(result.next())
                    assertEquals(1L, result.getLong(1))
                    assertEquals(30L, result.getLong(2))
                    assertEquals(false, result.next())
                }
                statement.executeQuery("SELECT end_utc FROM packet_batch_windows").use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getString(1).endsWith("Z"))
                }
            }
        }
    }

    @Test
    fun `close drains an offer already admitted before stop`() {
        val database = tempDir.resolve("close-race.sqlite")
        val beforeOffer = CountDownLatch(1)
        val releaseOffer = CountDownLatch(1)
        val recorder = PacketBatchMetricsRecorder(
            database,
            flushIntervalMs = 60_000,
            beforeQueueOffer = {
                beforeOffer.countDown()
                check(releaseOffer.await(2, TimeUnit.SECONDS))
            },
        )
        val producer = Thread {
            recorder.offerLogical(PacketMetricKey("minecraft:keep_alive", PacketDirection.S2C, null, null), 17)
        }
        producer.start()
        assertTrue(beforeOffer.await(2, TimeUnit.SECONDS))

        val closer = Thread(recorder::close)
        closer.start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (!recorder.isStopping && System.nanoTime() < deadline) Thread.yield()
            assertTrue(recorder.isStopping)
        } finally {
            releaseOffer.countDown()
        }
        producer.join(2_000)
        closer.join(2_000)
        assertEquals(false, producer.isAlive)
        assertEquals(false, closer.isAlive)

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT packet_count, sum_encoded_bytes FROM packet_batch_logical_totals").use { result ->
                    assertTrue(result.next())
                    assertEquals(1L, result.getLong(1))
                    assertEquals(17L, result.getLong(2))
                }
                statement.executeQuery("SELECT end_utc FROM packet_batch_windows").use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getString(1).endsWith("Z"))
                }
            }
        }
    }

    @Test
    fun `public recorder API starts records and closes a complete window`() {
        val database = tempDir.resolve("public-api.sqlite")
        PacketBatchMetrics.start(database)
        assertTrue(PacketBatchMetrics.isActive)
        PacketBatchMetrics.recordLogical(
            PacketMetricKey("minecraft:keep_alive", PacketDirection.S2C, null, null),
            8,
        )
        PacketBatchMetrics.recordFrame(PacketBatchFrameSample("legacy", 1, 8, 12, 1, "immediate", 0, 0, true))
        PacketBatchMetrics.recordWriteOutcome(true, 1)
        PacketBatchMetrics.stop()
        assertEquals(false, PacketBatchMetrics.isActive)

        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT end_utc, write_successes FROM packet_batch_windows").use { result ->
                    assertTrue(result.next())
                    assertTrue(result.getString(1).endsWith("Z"))
                    assertEquals(1L, result.getLong(2))
                }
            }
        }
    }
}
