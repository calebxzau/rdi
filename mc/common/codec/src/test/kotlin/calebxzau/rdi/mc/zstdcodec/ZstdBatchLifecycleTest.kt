package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZstdBatchLifecycleTest {
    @Test
    fun `a continuing stream cannot extend the first record time deadline`() {
        var now = 0L
        val encoder = ZstdCompressionEncoder(128, TEST_VAR_INT, flushTimeoutMillis = Long.MAX_VALUE, nanoTime = { now })
        val channel = channel(encoder)
        try {
            ZstdCompressionPipeline.setOutboundBatching(channel, true)
            val promises = ArrayList<io.netty.channel.ChannelFuture>()
            repeat(6) { index ->
                now = TimeUnit.MILLISECONDS.toNanos(index * 10L)
                promises.add(channel.writeOneOutbound(Unpooled.wrappedBuffer(byteArrayOf(index.toByte()))))
            }

            repeat(5) { index ->
                assertTrue(promises[index].isDone)
                assertTrue(promises[index].isSuccess)
                assertNotNull(channel.readOutbound<ByteBuf>()).release()
            }
            assertFalse(promises.last().isDone)
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `multi record area below exact and above size target flushes at correct boundary`() {
        val firstRecord = ByteArray(32_765) { (it * 3).toByte() }
        val firstAreaBytes = firstRecord.size + ZstdBatchFormat.varIntSize(firstRecord.size)
        assertEquals(32_768, firstAreaBytes)

        verifyBatchAreaCase(
            secondRecord = ByteArray(32_764) { (it * 5).toByte() },
            expectImmediateFirstFrame = false,
        )
        verifyBatchAreaCase(
            secondRecord = ByteArray(32_765) { (it * 7).toByte() },
            expectImmediateFirstFrame = true,
        )
        verifyBatchAreaCase(
            secondRecord = ByteArray(32_766) { (it * 11).toByte() },
            expectImmediateFirstFrame = true,
        )
    }

    private fun verifyBatchAreaCase(secondRecord: ByteArray, expectImmediateFirstFrame: Boolean) {
        val server = channel()
        server.freezeTime()
        val client = EmbeddedChannel().also { inbound ->
            inbound.freezeTime()
            inbound.pipeline().addLast("decompress", ZstdCompressionDecoder(128, false, TEST_VAR_INT))
        }
        ZstdCompressionPipeline.setup(server, 128, true, TEST_VAR_INT)
        ZstdCompressionPipeline.setOutboundBatching(server, true)
        ZstdCompressionPipeline.setInboundBatching(client, true)
        val firstRecord = ByteArray(32_765) { (it * 3).toByte() }
        val firstArea = firstRecord.size + ZstdBatchFormat.varIntSize(firstRecord.size)
        val secondArea = secondRecord.size + ZstdBatchFormat.varIntSize(secondRecord.size)
        val totalArea = firstArea + secondArea
        assertEquals(totalArea >= ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES, expectImmediateFirstFrame)
        val decoded = ArrayList<ByteArray>()
        try {
            val first = server.writeOneOutbound(Unpooled.wrappedBuffer(firstRecord))
            val second = server.writeOneOutbound(Unpooled.wrappedBuffer(secondRecord))

            when (totalArea) {
                ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES - 1 -> {
                    assertFalse(first.isDone)
                    assertFalse(second.isDone)
                    assertNull(server.readOutbound<ByteBuf>())
                    ZstdCompressionPipeline.flushBatched(server)
                }

                ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES -> {
                    assertTrue(first.isSuccess)
                    assertTrue(second.isSuccess)
                    decoded.addAll(decode(client, assertNotNull(server.readOutbound<ByteBuf>())))
                }

                ZstdCompressionPipeline.DEFAULT_BATCH_TARGET_BYTES + 1 -> {
                    assertTrue(first.isSuccess)
                    assertFalse(second.isDone)
                    val firstFrame = assertNotNull(server.readOutbound<ByteBuf>())
                    decoded.addAll(decode(client, firstFrame))
                    assertEquals(1, decoded.size)
                    assertContentEquals(firstRecord, decoded.single())
                    ZstdCompressionPipeline.flushBatched(server)
                    assertTrue(second.isSuccess)
                }

                else -> error("Unexpected record area size $totalArea")
            }

            while (true) {
                val frame = server.readOutbound<ByteBuf>() ?: break
                decoded.addAll(decode(client, frame))
            }
            assertEquals(2, decoded.size)
            assertContentEquals(firstRecord, decoded[0])
            assertContentEquals(secondRecord, decoded[1])
        } finally {
            server.finishAndReleaseAll()
            client.finishAndReleaseAll()
        }
    }

    private fun decode(client: EmbeddedChannel, frame: ByteBuf): List<ByteArray> {
        client.writeInbound(frame)
        val decoded = ArrayList<ByteArray>()
        while (true) {
            val record = client.readInbound<ByteBuf>() ?: break
            try {
                decoded.add(ByteArray(record.readableBytes()).also { record.readBytes(it) })
            } finally {
                record.release()
            }
        }
        return decoded
    }

    @Test
    fun `zero delay timeout drains without waiting for tick progress`() {
        val encoder = ZstdCompressionEncoder(128, TEST_VAR_INT, flushTimeoutMillis = 0)
        val channel = channel(encoder)
        try {
            ZstdCompressionPipeline.setOutboundBatching(channel, true)
            channel.writeOneOutbound(Unpooled.wrappedBuffer(byteArrayOf(7, 8)))
            assertNull(channel.readOutbound<ByteBuf>())

            channel.runScheduledPendingTasks()

            assertNotNull(channel.readOutbound<ByteBuf>()).release()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `allocator failure releases accepted buffers and fails their promises`() {
        val failure = IllegalStateException("injected allocator failure")
        val channel = channel()
        channel.config().setAllocator(object : ByteBufAllocator by UnpooledByteBufAllocator.DEFAULT {
            override fun directBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf = throw failure
        })
        try {
            ZstdCompressionPipeline.setup(channel, 128, true, TEST_VAR_INT)
            ZstdCompressionPipeline.setOutboundBatching(channel, true)
            val firstInput = Unpooled.wrappedBuffer(ByteArray(200) { 1 })
            val secondInput = Unpooled.wrappedBuffer(ByteArray(200) { 2 })
            val first = channel.writeOneOutbound(firstInput)
            val second = channel.writeOneOutbound(secondInput)

            ZstdCompressionPipeline.flushBatched(channel)

            assertTrue(first.isDone)
            assertFalse(first.isSuccess)
            assertTrue(second.isDone)
            assertFalse(second.isSuccess)
            assertEquals(0, firstInput.refCnt())
            assertEquals(0, secondInput.refCnt())
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `scratch allocation failure releases the already allocated legacy output`() {
        val failure = IllegalStateException("injected scratch allocation failure")
        val channel = channel()
        val allocated = ArrayList<ByteBuf>()
        var directAllocations = 0
        channel.config().setAllocator(object : ByteBufAllocator by UnpooledByteBufAllocator.DEFAULT {
            override fun directBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf {
                directAllocations++
                if (directAllocations == 2) throw failure
                return UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity)
                    .also { allocated.add(it) }
            }
        })
        try {
            ZstdCompressionPipeline.setup(channel, 128, true, TEST_VAR_INT)
            ZstdCompressionPipeline.setOutboundBatching(channel, true)
            val input = Unpooled.wrappedBuffer(ByteArray(200) { 33 })
            val promise = channel.writeOneOutbound(ZstdSendingRecord(input, ZstdBatchPolicy.Immediate, null))

            assertTrue(promise.isDone)
            assertFalse(promise.isSuccess)
            assertEquals(0, input.refCnt())
            assertEquals(1, allocated.size)
            assertEquals(0, allocated.single().refCnt())
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `removing armed encoder fails buffered packets without flushing`() {
        val channel = channel()
        try {
            ZstdCompressionPipeline.setup(channel, 128, true, TEST_VAR_INT)
            ZstdCompressionPipeline.setOutboundBatching(channel, true)
            val input = Unpooled.wrappedBuffer(byteArrayOf(11, 12, 13))
            val promise = channel.writeOneOutbound(input)

            channel.pipeline().remove("compress")

            assertTrue(promise.isDone)
            assertFalse(promise.isSuccess)
            assertEquals(0, input.refCnt())
            assertNull(channel.readOutbound<ByteBuf>())
            assertFalse(channel.isActive)
            assertNull(channel.pipeline().get("compress"))
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    @Test
    fun `batch construction failure releases all records and fails all promises`() {
        val failure = IllegalStateException("injected varint failure")
        val throwingCodec = object : MinecraftVarIntCodec {
            override fun read(buffer: ByteBuf): Int = TEST_VAR_INT.read(buffer)
            override fun write(buffer: ByteBuf, value: Int) {
                throw failure
            }
        }
        val encoder = ZstdCompressionEncoder(128, throwingCodec)
        val channel = channel(encoder)
        try {
            ZstdCompressionPipeline.setOutboundBatching(channel, true)
            val firstInput = Unpooled.wrappedBuffer(ByteArray(200) { 21 })
            val secondInput = Unpooled.wrappedBuffer(ByteArray(200) { 22 })
            val first = channel.writeOneOutbound(firstInput)
            val second = channel.writeOneOutbound(secondInput)

            ZstdCompressionPipeline.flushBatched(channel)

            assertTrue(first.isDone)
            assertFalse(first.isSuccess)
            assertTrue(second.isDone)
            assertFalse(second.isSuccess)
            assertEquals(0, firstInput.refCnt())
            assertEquals(0, secondInput.refCnt())
        } finally {
            runCatching { channel.finishAndReleaseAll() }
        }
    }

    @Test
    fun `unassociated buffers wait for the tick by default and go at once when the adapter asks`() {
        val delayed = channel()
        delayed.freezeTime()
        try {
            ZstdCompressionPipeline.setOutboundBatching(delayed, true)
            val future = delayed.writeOneOutbound(Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3)))
            delayed.flushOutbound()
            assertFalse(future.isDone)
            assertNull(delayed.readOutbound<ByteBuf>())
        } finally {
            runCatching { delayed.finishAndReleaseAll() }
        }

        val immediate = channel()
        immediate.freezeTime()
        try {
            ZstdCompressionPipeline.setOutboundBatching(immediate, true, delayUnassociated = false)
            val future = immediate.writeOneOutbound(Unpooled.wrappedBuffer(byteArrayOf(1, 2, 3)))
            immediate.flushOutbound()
            assertTrue(future.isDone)
            assertTrue(future.isSuccess)
            assertNotNull(immediate.readOutbound<ByteBuf>()).release()
        } finally {
            runCatching { immediate.finishAndReleaseAll() }
        }
    }

    private fun channel(encoder: ZstdCompressionEncoder? = null): EmbeddedChannel {
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
        channel.pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
        if (encoder == null) {
            channel.pipeline().addLast("compress", ZstdCompressionEncoder(128, TEST_VAR_INT))
        } else {
            channel.pipeline().addLast("compress", encoder)
        }
        channel.pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        channel.pipeline().addLast("exception-sink", object : ChannelInboundHandlerAdapter() {
            override fun exceptionCaught(context: ChannelHandlerContext, cause: Throwable) = Unit
        })
        return channel
    }

    private object TEST_VAR_INT : MinecraftVarIntCodec {
        override fun read(buffer: ByteBuf): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                val current = buffer.readUnsignedByte().toInt()
                value = value or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) return value
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
}
