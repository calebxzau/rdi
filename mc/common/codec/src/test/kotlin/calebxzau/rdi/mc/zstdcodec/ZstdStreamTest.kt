package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZstdStreamTest {
    @Test
    fun `legacy and batch frames round trip byte for byte across many segments`() {
        for (windowLog in listOf(ZstdStreamFormat.MINIMUM_WINDOW_LOG, ZstdStreamFormat.MAXIMUM_WINDOW_LOG)) {
            val pair = StreamPair(threshold = 64)
            try {
                pair.enableBatching()
                pair.start(windowLog)
                val random = Random(windowLog.toLong())
                val sizes = intArrayOf(0, 1, 2, 63, 64, 65, 127, 128, 300, 1000, 4096, 20_000, 70_000, 300_000)
                val pool = (0 until 48).map { index ->
                    val size = sizes[index % sizes.size]
                    if (index % 2 == 0) randomBytes(random, size) else ByteArray(size) { (it * 31 + index * 7).toByte() }
                }
                val sent = ArrayList<ByteArray>()
                val received = ArrayList<ByteArray>()

                repeat(1500) { step ->
                    val packet = pool[random.nextInt(pool.size)]
                    sent += packet
                    if (random.nextBoolean()) pair.sendImmediate(packet) else pair.sendBuffered(packet)
                    if (step % 5 == 4) {
                        ZstdCompressionPipeline.flushBatched(pair.server)
                        received += pair.transfer()
                    }
                }
                ZstdCompressionPipeline.flushBatched(pair.server)
                received += pair.transfer()

                assertRecords(sent, received)
                assertTrue(pair.batchFrames > 100, "batches=${pair.batchFrames}")
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `a repeated payload shrinks only once the stream runs`() {
        val pair = StreamPair(threshold = 64)
        try {
            val packet = randomBytes(Random(3), 2000)

            repeat(2) { pair.sendImmediate(packet) }
            val independent = pair.drain()
            assertTrue(independent.all { it.readableBytes() > 2000 }, "independent=${independent.map { it.readableBytes() }}")
            assertTrue(independent.all(::hasZstdMagic))
            assertRecords(listOf(packet, packet), pair.deliver(independent))

            pair.start()
            repeat(2) { pair.sendImmediate(packet) }
            val streamed = pair.drain()
            assertTrue(streamed.none(::hasZstdMagic))
            assertTrue(streamed[1].readableBytes() < 32, "second segment=${streamed[1].readableBytes()}")
            assertRecords(listOf(packet, packet), pair.deliver(streamed))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `payloads from 32 bytes leave compressed below the threshold once the stream runs`() {
        for (validateDecompressed in listOf(false, true)) {
            val pair = StreamPair(threshold = 256, validateDecompressed = validateDecompressed)
            try {
                val below = ByteArray(ZstdStreamFormat.MINIMUM_SEGMENT_BYTES - 1) { (it * 5).toByte() }
                val floor = ByteArray(ZstdStreamFormat.MINIMUM_SEGMENT_BYTES) { (it * 7).toByte() }
                val medium = randomBytes(Random(23), 100)

                pair.sendImmediate(medium)
                val independent = pair.drain()
                assertEquals(listOf(0), independent.map(::leadingVarInt))
                assertRecords(listOf(medium), pair.deliver(independent))

                pair.start()
                listOf(below, floor, medium, medium).forEach(pair::sendImmediate)
                val streamed = pair.drain()
                assertEquals(listOf(0, floor.size, medium.size, medium.size), streamed.map(::leadingVarInt))
                assertTrue(streamed[3].readableBytes() < 16, "repeated small segment=${streamed[3].readableBytes()}")
                assertRecords(listOf(below, floor, medium, medium), pair.deliver(streamed))
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `a small batch is compressed once the stream runs`() {
        val pair = StreamPair(threshold = 256)
        try {
            pair.enableBatching()
            val record = ByteArray(40) { (it * 3).toByte() }

            repeat(3) { pair.sendBuffered(record) }
            ZstdCompressionPipeline.flushBatched(pair.server)
            val independent = pair.drain()
            assertEquals(listOf(0, 0, 0), independent.map(::leadingVarInt))
            assertRecords(List(3) { record }, pair.deliver(independent))

            pair.start()
            repeat(3) { pair.sendBuffered(record) }
            ZstdCompressionPipeline.flushBatched(pair.server)
            val block = pair.drain().single()
            assertEquals(0, batchFlags(block))
            assertRecords(List(3) { record }, pair.deliver(listOf(block)))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `version one STREAM_START keeps its exact bytes and clamps the window`() {
        val cases = listOf(
            ZstdStreamFormat.DEFAULT_WINDOW_LOG to "fdffffff0f 02 01 19",
            40 to "fdffffff0f 02 01 19",
            1 to "fdffffff0f 02 01 14",
        )
        for ((requested, expected) in cases) {
            val pair = StreamPair(threshold = 64)
            try {
                pair.prepare()
                ZstdCompressionPipeline.setOutboundStream(pair.server, requested)
                val start = pair.drain().single()
                assertContentEquals(hex(expected), bytes(start), "requested window log $requested")
                assertEquals(emptyList(), pair.deliver(listOf(start)))
                assertTrue(pair.decoder.isStreamActive())
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `starting again writes nothing and keeps the stream`() {
        val pair = StreamPair(threshold = 64)
        try {
            pair.start()
            val packet = randomBytes(Random(5), 500)
            pair.sendImmediate(packet)
            ZstdCompressionPipeline.setOutboundStream(pair.server, ZstdStreamFormat.MINIMUM_WINDOW_LOG)
            pair.sendImmediate(packet)

            val frames = pair.drain()
            assertEquals(2, frames.size)
            assertRecords(listOf(packet, packet), pair.deliver(frames))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `records buffered before STREAM_START leave first as an independent block`() {
        val pair = StreamPair(threshold = 64)
        try {
            pair.enableBatching()
            pair.prepare()
            val before = ByteArray(300) { (it % 9).toByte() }
            val after = ByteArray(300) { (it % 11).toByte() }
            repeat(2) { pair.sendBuffered(before) }

            ZstdCompressionPipeline.setOutboundStream(pair.server)
            pair.sendImmediate(after)

            val frames = pair.drain()
            assertEquals(listOf(ZstdBatchFormat.MARKER, PacketRefFormat.CONTROL_MARKER, 300), frames.map(::leadingVarInt))
            assertRecords(listOf(before, before, after), pair.deliver(frames))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `login STREAM_START and first segments decode in order in one read and in split reads`() {
        for (splitReads in listOf(false, true)) {
            val pair = StreamPair(threshold = 64, framed = true)
            try {
                val login = ByteArray(40) { 0x4C }
                pair.client.pipeline().addAfter("decompress", "login-hook", object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(context: ChannelHandlerContext, msg: Any) {
                        if (msg is ByteBuf && bytes(msg).contentEquals(login)) {
                            ZstdCompressionPipeline.setInboundStreamIfAvailable(context.channel(), true)
                        }
                        context.fireChannelRead(msg)
                    }
                })
                val packet = ByteArray(300) { (it * 11).toByte() }
                pair.server.writeOutbound(Unpooled.wrappedBuffer(login))
                ZstdCompressionPipeline.setOutboundStream(pair.server)
                pair.sendImmediate(packet)
                pair.sendImmediate(packet)
                val wire = Unpooled.buffer()
                pair.drain().forEach { frame ->
                    wire.writeBytes(frame)
                    frame.release()
                }

                if (splitReads) {
                    while (wire.isReadable) pair.client.writeInbound(wire.readRetainedSlice(1))
                    wire.release()
                } else {
                    pair.client.writeInbound(wire)
                }

                assertRecords(listOf(login, packet, packet), readRecords(pair.client))
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `client rejects malformed and out of order STREAM_START frames`() {
        val cases = listOf(
            Case("STREAM_START before negotiation", ClientState.Idle, streamStart(1, 25)),
            Case("unknown version", ClientState.Ready, streamStart(2, 25)),
            Case("window below minimum", ClientState.Ready, streamStart(1, 19)),
            Case("window above maximum", ClientState.Ready, streamStart(1, 26)),
            Case("trailing byte", ClientState.Ready, streamStart(1, 25, trailing = true)),
            Case("truncated", ClientState.Ready, frame {
                it.writeVarInt(PacketRefFormat.CONTROL_MARKER); it.writeByte(ZstdStreamFormat.OPCODE_STREAM_START); it.writeVarInt(1)
            }),
            Case("overlong VarInt", ClientState.Ready, frame {
                it.writeVarInt(PacketRefFormat.CONTROL_MARKER); it.writeByte(ZstdStreamFormat.OPCODE_STREAM_START)
                it.writeBytes(hex("ffffffffff01"))
            }),
            Case("second STREAM_START", ClientState.Active, streamStart(1, 25)),
        )

        for (case in cases) {
            val pair = StreamPair(threshold = 64)
            try {
                if (case.state != ClientState.Idle) pair.prepare()
                if (case.state == ClientState.Active) {
                    pair.client.writeInbound(Unpooled.wrappedBuffer(streamStart(1, 25)))
                    assertTrue(pair.decoder.isStreamActive())
                }
                assertFailsWith<DecoderException>(case.name) {
                    pair.client.writeInbound(Unpooled.wrappedBuffer(case.frame))
                }
            } finally {
                runCatching { pair.close() }
            }
        }
    }

    @Test
    fun `a broken segment ends the stream for every later compressed payload`() {
        val pair = StreamPair(threshold = 64)
        try {
            pair.start()
            val first = randomBytes(Random(11), 500)
            val second = randomBytes(Random(12), 500)
            pair.sendImmediate(first)
            pair.sendImmediate(second)
            val frames = pair.drain()
            val truncated = Unpooled.wrappedBuffer(bytes(frames[0]).copyOf(frames[0].readableBytes() - 1))
            frames[0].release()

            val broken = assertFailsWith<DecoderException> { pair.client.writeInbound(truncated) }
            assertTrue(broken.message.orEmpty().contains("out of sync"), broken.message)
            val later = assertFailsWith<DecoderException> { pair.client.writeInbound(frames[1]) }
            assertTrue(later.message.orEmpty().contains("already failed"), later.message)
        } finally {
            runCatching { pair.close() }
        }
    }

    @Test
    fun `preparing again keeps a running stream and leaving forgets it`() {
        val pair = StreamPair(threshold = 64)
        try {
            pair.start()
            val packet = randomBytes(Random(13), 400)
            pair.sendImmediate(packet)
            assertTrue(ZstdCompressionPipeline.setInboundStreamIfAvailable(pair.client, true))
            pair.sendImmediate(packet)
            assertRecords(listOf(packet, packet), pair.transfer())

            assertTrue(ZstdCompressionPipeline.setInboundStreamIfAvailable(pair.client, false))
            assertFalse(pair.decoder.isStreamActive())
            pair.sendImmediate(packet)
            assertFailsWith<DecoderException> { pair.transfer() }
        } finally {
            runCatching { pair.close() }
        }
    }

    @Test
    fun `a threshold change keeps the stream`() {
        val pair = StreamPair(threshold = 64)
        try {
            pair.start()
            val large = randomBytes(Random(17), 400)
            val medium = randomBytes(Random(18), 100)
            pair.sendImmediate(large)
            pair.sendImmediate(medium)

            ZstdCompressionPipeline.setup(pair.server, 128, false, TEST_VAR_INT)
            ZstdCompressionPipeline.setup(pair.client, 128, false, TEST_VAR_INT)
            pair.sendImmediate(large)
            pair.sendImmediate(medium)

            val frames = pair.drain()
            assertEquals(listOf(400, 100, 400, 100), frames.map(::leadingVarInt))
            assertTrue(frames[2].readableBytes() < 32, "repeat after threshold change=${frames[2].readableBytes()}")
            assertTrue(frames[3].readableBytes() < 32, "small repeat below the new threshold=${frames[3].readableBytes()}")
            assertRecords(listOf(large, medium, large, medium), pair.deliver(frames))
        } finally {
            pair.close()
        }
    }

    /**
     * The replaced frame measurement must not advance the stream: the repeat of [anchor] reaches back across
     * the measurement point, so an extra segment in the server's history alone would decode wrong bytes.
     */
    @Test
    fun `measuring a replaced frame for telemetry leaves the stream aligned`() {
        val pair = StreamPair(threshold = 64)
        val samples = ArrayList<ZstdBatchSample>()
        try {
            ZstdCompressionPipeline.setBatchObserver(pair.server, object : ZstdBatchObserver {
                override fun batchFlushed(sample: ZstdBatchSample) {
                    samples += sample
                }
            })
            pair.enableBatching()
            assertTrue(ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(pair.client, true))
            ZstdCompressionPipeline.setOutboundPacketRefs(pair.server, 16, 600)
            pair.start()

            val anchor = randomBytes(Random(19), 1500)
            val entry = randomBytes(Random(20), 300)
            pair.sendImmediate(anchor)
            pair.sendBuffered(entry)
            ZstdCompressionPipeline.flushBatched(pair.server)
            pair.sendImmediate(entry)
            pair.sendImmediate(anchor)

            assertRecords(listOf(anchor, entry, entry, anchor), pair.transfer())
            val reference = samples.single { it.frameKind == ZstdBatchFrameKind.Ref }
            assertTrue(reference.replacedFrameBytes > 300, "replaced=${reference.replacedFrameBytes}")
            assertTrue(samples.last().blockBytes < 32, "anchor repeat=${samples.last().blockBytes}")
        } finally {
            pair.close()
        }
    }

    private enum class ClientState { Idle, Ready, Active }

    private class Case(val name: String, val state: ClientState, val frame: ByteArray)

    private class StreamPair(threshold: Int, framed: Boolean = false, validateDecompressed: Boolean = false) {
        val server = channel(framed)
        val client = channel(framed)
        var batchFrames = 0
            private set

        init {
            ZstdCompressionPipeline.setup(server, threshold, false, TEST_VAR_INT)
            ZstdCompressionPipeline.setup(client, threshold, validateDecompressed, TEST_VAR_INT)
        }

        val decoder: ZstdCompressionDecoder get() = client.pipeline().get("decompress") as ZstdCompressionDecoder

        fun prepare() = assertTrue(ZstdCompressionPipeline.setInboundStreamIfAvailable(client, true))

        fun start(windowLog: Int = ZstdStreamFormat.DEFAULT_WINDOW_LOG) {
            prepare()
            ZstdCompressionPipeline.setOutboundStream(server, windowLog)
            assertTrue(ZstdCompressionPipeline.isOutboundStreamEnabled(server))
            assertEquals(emptyList(), transfer())
            assertTrue(decoder.isStreamActive())
        }

        fun enableBatching() {
            ZstdCompressionPipeline.setOutboundBatching(server, true)
            ZstdCompressionPipeline.setInboundBatching(client, true)
        }

        fun sendImmediate(packet: ByteArray) {
            server.writeOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), ZstdBatchPolicy.Immediate, null))
        }

        fun sendBuffered(packet: ByteArray) {
            server.writeOneOutbound(Unpooled.wrappedBuffer(packet))
        }

        fun drain(): List<ByteBuf> {
            val frames = ArrayList<ByteBuf>()
            while (true) frames += server.readOutbound<ByteBuf>() ?: break
            return frames
        }

        fun deliver(frames: List<ByteBuf>): List<ByteArray> {
            frames.forEach { frame ->
                if (leadingVarInt(frame) == ZstdBatchFormat.MARKER) batchFrames++
                client.writeInbound(frame)
            }
            return readRecords(client)
        }

        fun transfer(): List<ByteArray> = deliver(drain())

        fun close() {
            runCatching { server.finishAndReleaseAll() }
            client.finishAndReleaseAll()
        }

        private fun channel(framed: Boolean): EmbeddedChannel = EmbeddedChannel().apply {
            pipeline().addLast("splitter", if (framed) ProtobufVarint32FrameDecoder() else ChannelInboundHandlerAdapter())
            pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
            pipeline().addLast("prepender", if (framed) ProtobufVarint32LengthFieldPrepender() else ChannelOutboundHandlerAdapter())
            pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
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
        val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

        fun ByteBuf.writeVarInt(value: Int) = TEST_VAR_INT.write(this, value)

        fun randomBytes(random: Random, size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

        fun frame(writer: (ByteBuf) -> Unit): ByteArray {
            val buffer = Unpooled.buffer()
            return try {
                writer(buffer)
                bytes(buffer)
            } finally {
                buffer.release()
            }
        }

        fun streamStart(version: Int, windowLog: Int, trailing: Boolean = false): ByteArray = frame {
            it.writeVarInt(PacketRefFormat.CONTROL_MARKER)
            it.writeByte(ZstdStreamFormat.OPCODE_STREAM_START)
            it.writeVarInt(version)
            it.writeVarInt(windowLog)
            if (trailing) it.writeByte(0)
        }

        /** The leading VarInt of an inner frame from an unframed pair. */
        fun leadingVarInt(frame: ByteBuf): Int = TEST_VAR_INT.read(frame.duplicate())

        /** The flags byte of a batch block; 0 means its payload is compressed. */
        fun batchFlags(frame: ByteBuf): Int {
            val block = frame.duplicate()
            assertEquals(ZstdBatchFormat.MARKER, TEST_VAR_INT.read(block))
            assertEquals(ZstdBatchFormat.VERSION, TEST_VAR_INT.read(block))
            return block.readUnsignedByte().toInt()
        }

        /** Whether a compressed legacy envelope carries a standalone Zstd frame after its size. */
        fun hasZstdMagic(frame: ByteBuf): Boolean {
            val body = frame.duplicate()
            TEST_VAR_INT.read(body)
            if (body.readableBytes() < ZSTD_MAGIC.size) return false
            return ByteArray(ZSTD_MAGIC.size).also { body.getBytes(body.readerIndex(), it) }.contentEquals(ZSTD_MAGIC)
        }

        fun bytes(buffer: ByteBuf): ByteArray =
            ByteArray(buffer.readableBytes()).also { buffer.getBytes(buffer.readerIndex(), it) }

        fun hex(value: String): ByteArray = value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        fun readRecords(channel: EmbeddedChannel): List<ByteArray> {
            val records = ArrayList<ByteArray>()
            while (true) {
                val record = channel.readInbound<ByteBuf>() ?: break
                try {
                    records += bytes(record)
                } finally {
                    record.release()
                }
            }
            return records
        }

        fun assertRecords(expected: List<ByteArray>, actual: List<ByteArray>) {
            assertEquals(expected.size, actual.size, "record count")
            expected.zip(actual).forEachIndexed { index, (expectedRecord, actualRecord) ->
                assertContentEquals(expectedRecord, actualRecord, "record $index")
            }
        }
    }
}
