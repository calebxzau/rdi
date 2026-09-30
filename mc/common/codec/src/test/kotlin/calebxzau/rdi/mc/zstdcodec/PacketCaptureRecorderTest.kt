package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.ZstdInputStream
import io.netty.buffer.Unpooled
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.random.Random

class PacketCaptureRecorderTest {
    @Test
    fun `captures exact bytes without consuming source and attributes reconnects`(): Unit {
        val directory = Files.createTempDirectory("rdi-packbatch-basic")
        val player = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
        val recorder = PacketCaptureRecorder(directory)
        val first = recorder.connection(player)
        val source = Unpooled.wrappedBuffer(byteArrayOf(99, 1, 2, 3, 88)).apply {
            readerIndex(1)
            writerIndex(4)
        }
        try {
            first.record(source, ZstdPacketIdentity("demo:state", "demo", "state"))
            first.record(source, null)
            val second = recorder.connection(player)
            second.record(source, ZstdPacketIdentity("demo:other"))
            assertEquals(1, source.readerIndex())
            assertEquals(4, source.writerIndex())
            recorder.close().getOrThrow()

            val part = listParts(directory).single()
            assertTrue(Files.size(part) <= PacketCaptureRecorder.MAX_FILE_BYTES)
            val decoded = readPart(part)
            assertEquals(1, decoded.partNumber)
            assertEquals(recorder.runId, decoded.runId)
            assertEquals(3, decoded.records.size)
            assertEquals(listOf(1L, 2L), decoded.records.take(2).map { it.sequence })
            assertEquals(first.connectionId, decoded.records[0].connectionId)
            assertEquals(first.connectionId, decoded.records[1].connectionId)
            assertTrue(second.connectionId > first.connectionId)
            assertEquals(second.connectionId, decoded.records[2].connectionId)
            assertEquals(player, decoded.records[0].playerId)
            assertEquals("demo:state", decoded.records[0].packetType)
            assertEquals("demo:state", decoded.records[0].channel)
            assertEquals(":unknown-encoded", decoded.records[1].packetType)
            assertEquals("", decoded.records[1].channel)
            decoded.records.forEach { assertContentEquals(byteArrayOf(1, 2, 3), it.payload) }
            assertTrue(decoded.final)
        } finally {
            source.release()
            deleteTree(directory)
        }
    }

    @Test
    fun `rotates incompressible frames without crossing the configured hard cap`(): Unit {
        val directory = Files.createTempDirectory("rdi-packbatch-rotation")
        val recorder = PacketCaptureRecorder(
            directory,
            PacketCaptureLimits(maxFileBytes = 1800, maxPendingBytes = 1_000_000, targetFrameBytes = 256),
        )
        val connection = recorder.connection(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        val random = Random(9182)
        val payload = ByteArray(240).also(random::nextBytes)
        val source = Unpooled.wrappedBuffer(payload)
        try {
            repeat(24) { connection.record(source, ZstdPacketIdentity("test:noise")) }
            recorder.close().getOrThrow()
            val parts = listParts(directory)
            assertTrue(parts.size > 1)
            assertTrue(parts.all { Files.size(it) <= 1800L })
            val decoded = parts.map(::readPart)
            assertEquals((1..parts.size).toList(), decoded.map { it.partNumber })
            assertTrue(decoded.dropLast(1).all { !it.final })
            assertTrue(decoded.last().final)
            assertEquals(24, decoded.sumOf { it.records.size })
            decoded.flatMap { it.records }.forEach { assertContentEquals(payload, it.payload) }
        } finally {
            source.release()
            deleteTree(directory)
        }
    }

    @Test
    fun `queue budget drops attempts and resumes after pending frame is written`(): Unit {
        val directory = Files.createTempDirectory("rdi-packbatch-budget")
        val reserved = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val recorder = PacketCaptureRecorder(
            directory,
            PacketCaptureLimits(
                maxFileBytes = 16_000,
                maxPendingBytes = 64,
                maxEvents = 1,
                targetFrameBytes = 1,
                idleFlushMillis = 30,
            ),
            afterReservation = {
                reserved.countDown()
                releaseCopy.await(5, TimeUnit.SECONDS)
            },
        )
        val connection = recorder.connection(UUID.fromString("10000000-0000-0000-0000-000000000001"))
        val source = Unpooled.wrappedBuffer(byteArrayOf(7))
        try {
            val firstProducer = Thread { connection.record(source, ZstdPacketIdentity("test:one")) }
            firstProducer.start()
            assertTrue(reserved.await(5, TimeUnit.SECONDS))
            connection.record(source, ZstdPacketIdentity("test:two"))
            releaseCopy.countDown()
            firstProducer.join()
            awaitPartialDataFrame(directory)
            connection.record(source, ZstdPacketIdentity("test:three"))
            recorder.close().getOrThrow()

            val decoded = listParts(directory).map(::readPart)
            val records = decoded.flatMap { it.records }
            assertEquals(listOf(1L, 3L), records.map { it.sequence })
            assertEquals(1L, recorder.droppedSamples)
            assertEquals(1L, decoded.last().dropped)
        } finally {
            source.release()
            deleteTree(directory)
        }
    }

    @Test
    fun `idle flush makes a sub target packet readable before close`(): Unit {
        val directory = Files.createTempDirectory("rdi-packbatch-idle-flush")
        val recorder = PacketCaptureRecorder(
            directory,
            PacketCaptureLimits(targetFrameBytes = 1024, idleFlushMillis = 40),
        )
        val player = UUID.fromString("30000000-0000-0000-0000-000000000003")
        val source = Unpooled.wrappedBuffer(byteArrayOf(0x31, 0x32, 0x33))
        try {
            recorder.connection(player).record(source, ZstdPacketIdentity("test:idle"))
            val partial = awaitPartialDataFrame(directory)
            val decoded = ZstdInputStream(Files.newInputStream(partial)).use { it.readAllBytes() }
            DataInputStream(decoded.inputStream()).use { input ->
                assertEquals(1, input.readUnsignedByte())
                input.skipBytes(4 + 2 + 16 + 8 + 4)
                assertEquals(2, input.readUnsignedByte())
                assertEquals(1, input.readInt())
                input.readLong()
                assertEquals(player, UUID(input.readLong(), input.readLong()))
                input.readLong()
                assertEquals(1L, input.readLong())
                assertEquals("test:idle", input.readString())
                assertEquals("", input.readString())
                assertEquals(3, input.readInt())
                assertContentEquals(byteArrayOf(0x31, 0x32, 0x33), ByteArray(3).also(input::readFully))
            }
            recorder.close().getOrThrow()
        } finally {
            source.release()
            deleteTree(directory)
        }
    }

    @Test
    fun `writer failure calls callback once and preserves the partial file`(): Unit {
        val root = Files.createTempDirectory("rdi-packbatch-failure")
        val blocker = root.resolve("not-a-directory")
        Files.write(blocker, byteArrayOf(1))
        var callbacks = 0
        val recorder = PacketCaptureRecorder(blocker.resolve("capture"), onFailure = { callbacks++ })
        Thread.sleep(100)
        val result = recorder.close()
        assertTrue(result.isFailure)
        assertEquals(1, callbacks)
        assertFalse(Files.exists(blocker.resolve("capture")))
        deleteTree(root)
    }

    @Test
    fun `close stops admission and drains records accepted before shutdown`(): Unit {
        val directory = Files.createTempDirectory("rdi-packbatch-close-race")
        val copyReserved = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val admissionClosed = CountDownLatch(1)
        val recorder = PacketCaptureRecorder(
            directory,
            afterReservation = {
                copyReserved.countDown()
                releaseCopy.await(5, TimeUnit.SECONDS)
            },
            afterAdmissionClosed = admissionClosed::countDown,
        )
        val connection = recorder.connection(UUID.fromString("20000000-0000-0000-0000-000000000002"))
        val source = Unpooled.wrappedBuffer(byteArrayOf(4, 5, 6))
        try {
            val producer = Thread { connection.record(source, ZstdPacketIdentity("test:accepted")) }
            producer.start()
            assertTrue(copyReserved.await(5, TimeUnit.SECONDS))
            var closeResult: Result<Unit>? = null
            val closer = Thread { closeResult = recorder.close() }
            closer.start()
            assertTrue(admissionClosed.await(5, TimeUnit.SECONDS))
            connection.record(source, ZstdPacketIdentity("test:rejected"))
            releaseCopy.countDown()
            producer.join()
            closer.join()
            closeResult!!.getOrThrow()

            val decoded = listParts(directory).map(::readPart)
            val records = decoded.flatMap { it.records }
            assertEquals(1, records.size)
            assertEquals(1L, records.single().sequence)
            assertEquals("test:accepted", records.single().packetType)
            assertEquals(1L, recorder.droppedSamples)
            assertEquals(records.size.toLong(), decoded.sumOf { it.recordsInPart })
            assertTrue(decoded.last().final)
        } finally {
            source.release()
            deleteTree(directory)
        }
    }

    @Test
    fun `exports a Kotlin writer fixture for Python tooling`(): Unit {
        val configuredDirectory = System.getProperty("rdi.packbatch.fixtureDir")?.let { Path.of(it) }
        val directory = configuredDirectory ?: Files.createTempDirectory("rdi-packbatch-fixture")
        Files.createDirectories(directory)
        val player = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val payload = byteArrayOf(0x24, 0x01, 0x02, 0x03, 0x7f)
        val recorder = PacketCaptureRecorder(directory)
        val source = Unpooled.wrappedBuffer(payload)
        try {
            recorder.connection(player).record(
                source,
                ZstdPacketIdentity("minecraft:fixture", "fixture", "sample"),
            )
            recorder.close().getOrThrow()
            val part = listParts(directory).single { it.fileName.toString().startsWith(recorder.runId.toString()) }
            val expected = """
                {
                  "run_id": "${recorder.runId}",
                  "player_id": "$player",
                  "connection_id": 1,
                  "sequence": 1,
                  "packet_type": "minecraft:fixture",
                  "channel": "fixture:sample",
                  "payload_hex": "${payload.joinToString("") { "%02x".format(it) }}",
                  "part": "${part.fileName}"
                }
            """.trimIndent()
            Files.writeString(directory.resolve("fixture-manifest.json"), expected)
        } finally {
            source.release()
            // Fixture output is retained when the destination is explicitly configured.
            if (configuredDirectory == null) deleteTree(directory)
        }
    }

    private data class CapturedRecord(
        val playerId: UUID,
        val connectionId: Long,
        val sequence: Long,
        val packetType: String,
        val channel: String,
        val payload: ByteArray,
    )

    private data class DecodedPart(
        val runId: UUID,
        val partNumber: Int,
        val records: List<CapturedRecord>,
        val recordsInPart: Long,
        val dropped: Long,
        val final: Boolean,
    )

    private fun readPart(path: Path): DecodedPart {
        val decoded = ZstdInputStream(Files.newInputStream(path)).use { it.readAllBytes() }
        DataInputStream(decoded.inputStream()).use { input ->
            assertEquals(1, input.readUnsignedByte())
            assertContentEquals(byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte()), ByteArray(4).also(input::readFully))
            assertEquals(1, input.readUnsignedShort())
            val runId = UUID(input.readLong(), input.readLong())
            input.readLong() // Run start epoch millis.
            val partNumber = input.readInt()
            val records = ArrayList<CapturedRecord>()
            while (true) {
                when (input.readUnsignedByte()) {
                    2 -> {
                        val count = input.readInt()
                        repeat(count) {
                            input.readLong() // Monotonic elapsed nanoseconds.
                            val player = UUID(input.readLong(), input.readLong())
                            val connection = input.readLong()
                            val sequence = input.readLong()
                            val packetType = input.readString()
                            val channel = input.readString()
                            val payloadSize = input.readInt()
                            records += CapturedRecord(player, connection, sequence, packetType, channel, ByteArray(payloadSize).also(input::readFully))
                        }
                    }
                    3 -> {
                        val fileRecordCount = input.readLong()
                        val dropped = input.readLong()
                        val final = input.readUnsignedByte() == 1
                        input.readLong()
                        assertEquals(fileRecordCount, records.size.toLong())
                        assertEquals(-1, input.read())
                        return DecodedPart(runId, partNumber, records, fileRecordCount, dropped, final)
                    }
                    else -> error("Unexpected packet-capture frame kind")
                }
            }
        }
    }

    private fun DataInputStream.readString(): String {
        val length = readUnsignedShort()
        return String(ByteArray(length).also(::readFully), Charsets.UTF_8)
    }

    private fun listParts(directory: Path): List<Path> = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".rdibatch.zst") }.sorted().toList()
    }

    private fun awaitPartialDataFrame(directory: Path): Path {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val partial = Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".rdibatch.zst.partial") }
                    .filter { runCatching { Files.size(it) > 0 }.getOrDefault(false) }
                    .findFirst()
                    .orElse(null)
            }
            if (partial != null) return partial
            Thread.sleep(5)
        }
        error("Packet capture writer did not flush a data frame before timeout")
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}
