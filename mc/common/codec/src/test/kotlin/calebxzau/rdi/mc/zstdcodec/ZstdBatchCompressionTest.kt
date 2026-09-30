package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZstdBatchCompressionTest {
    @Test
    fun `tick flush writes one block that preserves every record`() {
        val (server, client) = connectedPair()
        try {
            val records = listOf(
                ByteArray(300) { (it * 7).toByte() },
                ByteArray(1) { 0x5A },
                ByteArray(1500) { (it * 13).toByte() },
            )
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            assertNull(server.readOutbound<ByteBuf>())

            ZstdCompressionPipeline.flushAtTickEnd(server)

            val block = assertNotNull(server.readOutbound<ByteBuf>())
            assertNull(server.readOutbound<ByteBuf>())
            assertRecords(records, decodeBlock(client, block))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `size target splits the block and keeps the order`() {
        val (server, client) = connectedPair(targetBytes = BATCH_TARGET)
        try {
            val records = (0 until 5).map { index -> ByteArray(2000) { index.toByte() } }
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            ZstdCompressionPipeline.flushAtTickEnd(server)

            val blocks = ArrayList<ByteBuf>()
            while (true) {
                blocks.add(server.readOutbound<ByteBuf>() ?: break)
            }
            assertEquals(3, blocks.size)

            val decoded = ArrayList<ByteArray>()
            blocks.forEach { decoded.addAll(decodeBlock(client, it)) }
            assertRecords(records, decoded)
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `single record larger than the target leaves alone in its block`() {
        val (server, client) = connectedPair(targetBytes = ZstdCompressionPipeline.MINIMUM_BATCH_TARGET_BYTES)
        try {
            val record = ByteArray(9000) { (it * 5).toByte() }
            server.writeOneOutbound(Unpooled.wrappedBuffer(record))

            val block = assertNotNull(server.readOutbound<ByteBuf>())
            assertRecords(listOf(record), decodeBlock(client, block))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `ordinary flush does not drain the batch`() {
        val (server, client) = connectedPair()
        try {
            val record = ByteArray(300) { it.toByte() }
            server.writeOneOutbound(Unpooled.wrappedBuffer(record))
            server.flush()
            assertNull(server.readOutbound<ByteBuf>())

            ZstdCompressionPipeline.flushBatched(server)

            val block = assertNotNull(server.readOutbound<ByteBuf>())
            assertRecords(listOf(record), decodeBlock(client, block))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `flush timeout emits the block without a tick request`() {
        val server = pipelineChannel()
        val client = pipelineChannel()
        val encoder = ZstdCompressionEncoder(128, TEST_VAR_INT, flushTimeoutMillis = 0)
        val decoder = ZstdCompressionDecoder(128, false, TEST_VAR_INT)
        server.pipeline().addAfter("prepender", "compress", encoder)
        client.pipeline().addAfter("splitter", "decompress", decoder)
        try {
            ZstdCompressionPipeline.setOutboundBatching(server, true)
            ZstdCompressionPipeline.setInboundBatching(client, true)

            val first = ByteArray(200) { it.toByte() }
            server.writeOneOutbound(Unpooled.wrappedBuffer(first))
            assertNull(server.readOutbound<ByteBuf>())

            server.runScheduledPendingTasks()
            assertRecords(listOf(first), decodeBlock(client, assertNotNull(server.readOutbound<ByteBuf>())))

            val second = ByteArray(210) { (it * 3).toByte() }
            server.writeOneOutbound(Unpooled.wrappedBuffer(second))
            server.runScheduledPendingTasks()
            assertRecords(listOf(second), decodeBlock(client, assertNotNull(server.readOutbound<ByteBuf>())))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `buffered records complete only when the block leaves`() {
        val (server, client) = connectedPair()
        try {
            val first = server.writeOneOutbound(Unpooled.wrappedBuffer(ByteArray(300) { it.toByte() }))
            val second = server.writeOneOutbound(Unpooled.wrappedBuffer(ByteArray(310) { (it * 3).toByte() }))
            assertFalse(first.isDone)
            assertFalse(second.isDone)

            ZstdCompressionPipeline.flushBatched(server)

            assertTrue(first.isSuccess)
            assertTrue(second.isSuccess)
            assertNotNull(server.readOutbound<ByteBuf>())
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `block header is explicitly marked versioned and flagged`() {
        val server = batchingServer()
        try {
            val records = List(20) { index -> ByteArray(200) { (it + index).toByte() } }
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            ZstdCompressionPipeline.flushAtTickEnd(server)

            val block = assertNotNull(server.readOutbound<ByteBuf>())
            try {
                assertContentEquals(
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
                    peek(block, 0, 5),
                )
                val reader = BlockReader(block, 5)
                assertEquals(ZstdBatchFormat.VERSION, reader.readVarInt())
                assertEquals(0, reader.readByte())
                assertEquals(4040, reader.readVarInt())
                assertEquals(20, reader.readVarInt())
                assertContentEquals(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()), reader.readBytes(4))
            } finally {
                block.release()
            }
        } finally {
            server.finishAndReleaseAll()
        }
    }

    @Test
    fun `small block stays raw and round trips`() {
        val (server, client) = connectedPair(threshold = RAW_THRESHOLD)
        try {
            val records = List(20) { index -> ByteArray(200) { (it + index).toByte() } }
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            ZstdCompressionPipeline.flushAtTickEnd(server)

            val block = assertNotNull(server.readOutbound<ByteBuf>())
            val reader = BlockReader(block, 5)
            assertEquals(ZstdBatchFormat.VERSION, reader.readVarInt())
            assertEquals(ZstdBatchFormat.FLAG_RAW_PAYLOAD, reader.readByte())
            assertEquals(4040, reader.readVarInt())
            assertEquals(20, reader.readVarInt())

            assertRecords(records, decodeBlock(client, block))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `jumbo record keeps the legacy envelope`() {
        val (server, client) = connectedPair()
        try {
            val record = ByteArray(ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH)
            server.writeOneOutbound(Unpooled.wrappedBuffer(record))

            val message = assertNotNull(server.readOutbound<ByteBuf>())
            assertEquals(ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH, BlockReader(message, 0).readVarInt())
            assertRecords(listOf(record), decodeBlock(client, message))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `disabling compression flushes the buffered block first`() {
        val (server, client) = connectedPair()
        try {
            val record = ByteArray(300) { (it * 9).toByte() }
            server.writeOneOutbound(Unpooled.wrappedBuffer(record))

            ZstdCompressionPipeline.setup(server, -1, true, TEST_VAR_INT)

            assertNull(server.pipeline().get("compress"))
            val block = assertNotNull(server.readOutbound<ByteBuf>())
            assertRecords(listOf(record), decodeBlock(client, block))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `close writes the buffered block before the channel goes away`() {
        val (server, client) = connectedPair()
        try {
            val record = ByteArray(300) { (it * 11).toByte() }
            val future = server.writeOneOutbound(Unpooled.wrappedBuffer(record))

            server.close()

            assertTrue(future.isSuccess)
            val block = assertNotNull(server.readOutbound<ByteBuf>())
            assertRecords(listOf(record), decodeBlock(client, block))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `peer without negotiated batching rejects the block`() {
        val negotiated = pipelineChannel()
        ZstdCompressionPipeline.setup(negotiated, 128, false, TEST_VAR_INT)
        ZstdCompressionPipeline.setInboundBatching(negotiated, true)
        try {
            val record = ByteArray(64) { it.toByte() }
            assertBlockRejected(rawBatchBlockBytes(record), negotiated = false)
            assertRecords(listOf(record), decodeBlock(negotiated, rawBatchBlock(record)))
        } finally {
            negotiated.finishAndReleaseAll()
        }
    }

    @Test
    fun `malformed blocks are rejected`() {
        assertBlockRejected(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F), negotiated = true)
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION + 1, 0, 8, 1, ByteArray(8)), negotiated = true)
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION, 0x02, 8, 1, ByteArray(8)), negotiated = true)
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION, 0, 0, 1, ByteArray(0)), negotiated = true)
        assertBlockRejected(
            blockBytes(ZstdBatchFormat.VERSION, 0, ZstdCompressionPipeline.MAXIMUM_UNCOMPRESSED_LENGTH + 1, 1, ByteArray(0)),
            negotiated = true,
        )
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION, 0, 8, 0, ByteArray(8)), negotiated = true)
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION, 0, 8, ZstdBatchFormat.MAXIMUM_RECORDS + 1, ByteArray(8)), negotiated = true)
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION, ZstdBatchFormat.FLAG_RAW_PAYLOAD, 10, 1, ByteArray(5)), negotiated = true)
        assertBlockRejected(
            blockBytes(ZstdBatchFormat.VERSION, ZstdBatchFormat.FLAG_RAW_PAYLOAD, 6, 1, byteArrayOf(20, 1, 2, 3, 4, 5)),
            negotiated = true,
        )
        assertBlockRejected(
            blockBytes(ZstdBatchFormat.VERSION, ZstdBatchFormat.FLAG_RAW_PAYLOAD, 7, 1, byteArrayOf(3, 1, 2, 3, 4, 5, 6)),
            negotiated = true,
        )
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION, ZstdBatchFormat.FLAG_RAW_PAYLOAD, 1, 1, byteArrayOf(0)), negotiated = true)
        assertBlockRejected(
            blockBytes(ZstdBatchFormat.VERSION, ZstdBatchFormat.FLAG_RAW_PAYLOAD, 5, 3, byteArrayOf(1, 65, 1, 66, 1)),
            negotiated = true,
        )
        assertBlockRejected(blockBytes(ZstdBatchFormat.VERSION, 0, 1, 1, byteArrayOf(7)), negotiated = true)
    }

    @Test
    fun `observer reports every record and one block`() {
        val (server, client) = connectedPair(targetBytes = 4096)
        try {
            val bufferedSizes = ArrayList<Int>()
            val samples = ArrayList<ZstdBatchSample>()
            ZstdCompressionPipeline.setBatchObserver(
                server,
                object : ZstdBatchObserver {
                    override fun recordBuffered(encodedBytes: Int) {
                        bufferedSizes.add(encodedBytes)
                    }

                    override fun batchFlushed(sample: ZstdBatchSample) {
                        samples.add(sample)
                    }
                },
            )

            val records = listOf(ByteArray(300) { 1 }, ByteArray(300) { 2 }, ByteArray(300) { 3 })
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            ZstdCompressionPipeline.flushAtTickEnd(server)

            assertContentEquals(intArrayOf(300, 300, 300), bufferedSizes.toIntArray())
            assertEquals(1, samples.size)
            val sample = samples.single()
            assertEquals(3, sample.recordCount)
            assertEquals(906, sample.payloadBytes)
            assertEquals(ZstdBatchFlushReason.TICK, sample.flushReason)
            assertFalse(sample.raw)
            assertTrue(sample.blockBytes > 0 && sample.blockBytes < sample.payloadBytes)
            assertTrue(sample.waitNanos >= 0L)
            assertTrue(sample.compressionNanos >= 0L)

            assertRecords(records, decodeBlock(client, assertNotNull(server.readOutbound<ByteBuf>())))
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `four tick record waits for its own deadline while one tick record shortens mixed queue`() {
        val (server, client) = connectedPair()
        try {
            val long = ByteArray(80) { 1 }
            server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(long), ZstdBatchPolicy.FourTicks, null))
            ZstdCompressionPipeline.flushAtTickEnd(server)
            assertNull(server.readOutbound<ByteBuf>())

            val short = ByteArray(90) { 2 }
            server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(short), ZstdBatchPolicy.OneTick, null))
            ZstdCompressionPipeline.flushAtTickEnd(server)

            val block = assertNotNull(server.readOutbound<ByteBuf>())
            assertRecords(listOf(long, short), decodeBlock(client, block))
            repeat(4) { ZstdCompressionPipeline.flushAtTickEnd(server) }
            assertNull(server.readOutbound<ByteBuf>())
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `immediate record drains earlier data and keeps both legacy frames ordered`() {
        val (server, client) = connectedPair()
        try {
            val buffered = ByteArray(80) { 3 }
            val immediate = ByteArray(90) { 4 }
            server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(buffered), ZstdBatchPolicy.OneTick, null))
            server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(immediate), ZstdBatchPolicy.Immediate, null))

            val decoded = listOf(
                decodeBlock(client, assertNotNull(server.readOutbound<ByteBuf>())).single(),
                decodeLegacy(client, assertNotNull(server.readOutbound<ByteBuf>())),
            )
            assertRecords(listOf(buffered, immediate), decoded)
            assertNull(server.readOutbound<ByteBuf>())
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `exact size target flushes including record length prefix`() {
        val server = batchingServer()
        try {
            val recordSize = (ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES - 6) / 2
            val records = listOf(ByteArray(recordSize) { 1 }, ByteArray(recordSize) { 2 })
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            val block = assertNotNull(server.readOutbound<ByteBuf>())
            try {
                assertEquals(ZstdBatchFormat.MARKER, BlockReader(block, 0).readVarInt())
                val reader = BlockReader(block, 0)
                reader.readVarInt()
                reader.readVarInt()
                reader.readByte()
                assertEquals(ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES, reader.readVarInt())
                assertEquals(2, reader.readVarInt())
            } finally {
                block.release()
            }
        } finally {
            server.finishAndReleaseAll()
        }
    }

    @Test
    fun `sparse raw batches fall back to separate legacy frames`() {
        val (server, client) = connectedPair(threshold = 4096)
        try {
            val records = listOf(byteArrayOf(1), byteArrayOf(2))
            records.forEach { server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
            ZstdCompressionPipeline.flushBatched(server)

            val decoded = ArrayList<ByteArray>()
            repeat(records.size) { decoded.add(decodeLegacy(client, assertNotNull(server.readOutbound<ByteBuf>()))) }
            assertRecords(records, decoded)
            assertNull(server.readOutbound<ByteBuf>())
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `fallback promises cannot reenter before every detached frame is ordered`() {
        val (server, client) = connectedPair(threshold = 4096)
        try {
            val firstBytes = byteArrayOf(1)
            val secondBytes = byteArrayOf(2)
            val reentrantBytes = byteArrayOf(3)
            val first = server.writeOneOutbound(Unpooled.wrappedBuffer(firstBytes))
            first.addListener {
                server.writeOneOutbound(
                    ZstdSendingRecord(Unpooled.wrappedBuffer(reentrantBytes), ZstdBatchPolicy.Immediate, null),
                )
            }
            server.writeOneOutbound(Unpooled.wrappedBuffer(secondBytes))
            ZstdCompressionPipeline.flushBatched(server)

            val decoded = ArrayList<ByteArray>()
            repeat(3) { decoded.add(decodeLegacy(client, assertNotNull(server.readOutbound<ByteBuf>()))) }
            assertRecords(listOf(firstBytes, secondBytes, reentrantBytes), decoded)
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    @Test
    fun `downstream write failure releases input and rejects later packets`() {
        val server = batchingServer()
        val failure = IllegalStateException("rejected frame")
        server.pipeline().addBefore("compress", "reject-frame", object : ChannelOutboundHandlerAdapter() {
            override fun write(context: io.netty.channel.ChannelHandlerContext, msg: Any, promise: io.netty.channel.ChannelPromise) {
                ReferenceCountUtil.release(msg)
                promise.tryFailure(failure)
            }
        })
        val input = Unpooled.wrappedBuffer(ByteArray(128) { 9 })
        try {
            val first = server.writeOneOutbound(ZstdSendingRecord(input, ZstdBatchPolicy.OneTick, null))
            ZstdCompressionPipeline.flushBatched(server)
            assertTrue(first.isDone)
            assertFalse(first.isSuccess)
            assertEquals(0, input.refCnt())

            val later = server.writeOneOutbound(Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3)))
            assertTrue(later.isDone)
            assertFalse(later.isSuccess)
            assertNull(server.readOutbound<ByteBuf>())
        } finally {
            server.finishAndReleaseAll()
        }
    }

    private fun batchingServer(
        threshold: Int = 128,
        targetBytes: Int = ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES,
    ): EmbeddedChannel {
        val server = pipelineChannel()
        ZstdCompressionPipeline.setup(server, threshold, true, TEST_VAR_INT)
        ZstdCompressionPipeline.setOutboundBatching(server, true, targetBytes)
        return server
    }

    private fun connectedPair(
        targetBytes: Int = ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES,
        threshold: Int = 128,
    ): Pair<EmbeddedChannel, EmbeddedChannel> {
        val server = batchingServer(threshold, targetBytes)
        val client = pipelineChannel()
        ZstdCompressionPipeline.setup(client, threshold, false, TEST_VAR_INT)
        ZstdCompressionPipeline.setInboundBatching(client, true)
        return server to client
    }

    private fun decodeBlock(client: EmbeddedChannel, block: ByteBuf): List<ByteArray> {
        assertTrue(client.writeInbound(block))
        val records = ArrayList<ByteArray>()
        while (true) {
            val record = client.readInbound<ByteBuf>() ?: break
            val bytes = ByteArray(record.readableBytes())
            record.readBytes(bytes)
            record.release()
            records.add(bytes)
        }
        return records
    }

    private fun decodeLegacy(client: EmbeddedChannel, frame: ByteBuf): ByteArray {
        assertTrue(client.writeInbound(frame))
        val record = assertNotNull(client.readInbound<ByteBuf>())
        return try {
            ByteArray(record.readableBytes()).also(record::readBytes)
        } finally {
            record.release()
        }
    }

    private fun assertRecords(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size, "record count")
        expected.forEachIndexed { index, bytes ->
            assertContentEquals(bytes, actual[index], "record $index")
        }
    }

    /** Feeds one block to an isolated decoder and expects a protocol error. */
    private fun assertBlockRejected(bytes: ByteArray, negotiated: Boolean) {
        val decoder = ZstdCompressionDecoder(128, false, TEST_VAR_INT)
        val channel = EmbeddedChannel(decoder)
        try {
            if (negotiated) {
                decoder.requestBatching(true)
            }
            assertFailsWith<Throwable> { channel.writeInbound(Unpooled.wrappedBuffer(bytes)) }
        } finally {
            channel.pipeline().remove(decoder)
            channel.finishAndReleaseAll()
        }
    }

    private fun rawBatchBlock(record: ByteArray): ByteBuf {
        val buffer = Unpooled.buffer()
        TEST_VAR_INT.write(buffer, ZstdBatchFormat.MARKER)
        TEST_VAR_INT.write(buffer, ZstdBatchFormat.VERSION)
        buffer.writeByte(ZstdBatchFormat.FLAG_RAW_PAYLOAD)
        TEST_VAR_INT.write(buffer, prefixSize(record) + record.size)
        TEST_VAR_INT.write(buffer, 1)
        TEST_VAR_INT.write(buffer, record.size)
        buffer.writeBytes(record)
        return buffer
    }

    private fun rawBatchBlockBytes(record: ByteArray): ByteArray {
        val block = rawBatchBlock(record)
        return try {
            ByteArray(block.readableBytes()).also { block.getBytes(block.readerIndex(), it) }
        } finally {
            block.release()
        }
    }

    private fun blockBytes(
        version: Int,
        flags: Int,
        payloadBytes: Int,
        recordCount: Int,
        payload: ByteArray,
    ): ByteArray {
        val buffer = Unpooled.buffer()
        return try {
            TEST_VAR_INT.write(buffer, ZstdBatchFormat.MARKER)
            TEST_VAR_INT.write(buffer, version)
            buffer.writeByte(flags)
            TEST_VAR_INT.write(buffer, payloadBytes)
            TEST_VAR_INT.write(buffer, recordCount)
            buffer.writeBytes(payload)
            ByteArray(buffer.readableBytes()).also { buffer.getBytes(buffer.readerIndex(), it) }
        } finally {
            buffer.release()
        }
    }

    private fun prefixSize(record: ByteArray): Int = ZstdBatchFormat.varIntSize(record.size)

    private fun peek(buffer: ByteBuf, index: Int, length: Int): ByteArray {
        val bytes = ByteArray(length)
        buffer.getBytes(index, bytes)
        return bytes
    }

    private fun pipelineChannel(): EmbeddedChannel {
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        return channel
    }

    private class BlockReader(private val buffer: ByteBuf, startIndex: Int) {
        private var index = startIndex

        fun readByte(): Int = buffer.getUnsignedByte(index++).toInt()

        fun readVarInt(): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                val current = readByte()
                value = value or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalStateException("VarInt too big")
        }

        fun readBytes(count: Int): ByteArray {
            val bytes = ByteArray(count)
            buffer.getBytes(index, bytes)
            index += count
            return bytes
        }
    }

    private object TEST_VAR_INT : MinecraftVarIntCodec {
        override fun read(buffer: ByteBuf): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                if (!buffer.isReadable) {
                    throw IllegalStateException("Unterminated VarInt")
                }
                val current = buffer.readUnsignedByte().toInt()
                value = value or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) {
                    return value
                }
                shift += 7
            }
            throw IllegalStateException("VarInt too big")
        }

        override fun write(buffer: ByteBuf, value: Int) {
            var current = value
            while (current and -0x80 != 0) {
                buffer.writeByte((current and 0x7F) or 0x80)
                current = current ushr 7
            }
            buffer.writeByte(current)
        }
    }

    private companion object {
        private const val RAW_THRESHOLD = 4096
        private const val BATCH_TARGET = 4096
    }
}
