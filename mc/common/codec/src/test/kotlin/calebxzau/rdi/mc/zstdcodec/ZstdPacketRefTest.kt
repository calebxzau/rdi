package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.buffer.UnpooledHeapByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZstdPacketRefTest {
    @Test
    fun `random repeats round trip byte for byte while references replace hits`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.start(slots = 16, maxEntryBytes = 600)
            val sizes = intArrayOf(0, 1, 7, 8, 9, 10, 11, 63, 64, 65, 126, 127, 128, 300, 599, 600, 601, 1500)
            val pool = (0 until 48).map { index -> ByteArray(sizes[index % sizes.size]) { (it * 31 + index * 7).toByte() } }
            val random = Random(7)
            val sent = ArrayList<ByteArray>()
            val received = ArrayList<ByteArray>()

            repeat(3000) {
                val packet = pool[random.nextInt(pool.size)]
                val input = Unpooled.wrappedBuffer(packet)
                sent += packet
                pair.server.writeOutbound(input)
                if (packet.isNotEmpty()) assertEquals(0, input.refCnt())
                received += pair.transfer()
                pair.assertTablesAligned()
            }

            assertRecords(sent, received)
            assertTrue(pair.referenceFrames > 500, "references=${pair.referenceFrames}")
        } finally {
            pair.close()
        }
    }

    @Test
    fun `the smallest recorded packet already leaves as a reference`() {
        val pair = RefPair(threshold = 256)
        try {
            pair.start()
            val seven = ByteArray(PacketRefFormat.MINIMUM_ENTRY_BYTES - 1) { 7 }
            val eight = ByteArray(PacketRefFormat.MINIMUM_ENTRY_BYTES) { 8 }

            pair.server.writeOutbound(Unpooled.wrappedBuffer(seven))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(seven))
            val sevenFrames = pair.drain()
            assertEquals(listOf(0, 0), sevenFrames.map(::leadingVarInt))
            assertRecords(listOf(seven, seven), pair.deliver(sevenFrames))
            pair.assertTablesAligned()

            pair.server.writeOutbound(Unpooled.wrappedBuffer(eight))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(eight))
            val eightFrames = pair.drain()
            assertEquals(listOf(0, PacketRefFormat.REFERENCE_MARKER), eightFrames.map(::leadingVarInt))
            assertTrue(PacketRefFormat.withOuterPrefix(eightFrames[1].readableBytes()) < PacketRefFormat.rawLegacyFrameBytes(eight.size))
            assertRecords(listOf(eight, eight), pair.deliver(eightFrames))
            pair.assertTablesAligned()
        } finally {
            pair.close()
        }
    }

    @Test
    fun `references grow by one byte from slot 128`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.start()
            val packets = (0 until 129).map { index -> ByteArray(20) { (index * 3 + it).toByte() } }
            packets.forEach { pair.server.writeOutbound(Unpooled.wrappedBuffer(it)) }
            pair.transfer()

            pair.server.writeOutbound(Unpooled.wrappedBuffer(packets[127]))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packets[128]))
            val frames = pair.drain()

            assertEquals(listOf(6, 7), frames.map { it.readableBytes() })
            assertRecords(listOf(packets[127], packets[128]), pair.deliver(frames))
            pair.assertTablesAligned()
        } finally {
            pair.close()
        }
    }

    @Test
    fun `version one frames keep their exact bytes`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.prepare()
            ZstdCompressionPipeline.setOutboundPacketRefs(pair.server, 256, 1024)
            val start = pair.drain().single()
            assertContentEquals(hex("fdffffff0f0101800280 08"), bytes(start))
            pair.deliver(listOf(start))

            val vector = PacketRefFormatTest.KILOBYTE_VECTOR
            pair.server.writeOutbound(Unpooled.wrappedBuffer(vector))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(vector))
            val frames = pair.drain()
            assertContentEquals(hex("01 00 81d7cfa1"), bytes(frames[1]))
            assertRecords(listOf(vector, vector), pair.deliver(frames))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a one byte packet never collides with the reference marker below threshold 2`() {
        for (threshold in 0..1) {
            val pair = RefPair(threshold = threshold)
            try {
                val tiny = ByteArray(1) { 0x2A }
                val entry = ByteArray(PacketRefFormat.MINIMUM_ENTRY_BYTES) { 3 }

                // Before START a compressed one-byte packet declares the marker value as its size.
                pair.server.writeOutbound(Unpooled.wrappedBuffer(tiny))
                val idle = pair.drain()
                assertEquals(listOf(PacketRefFormat.REFERENCE_MARKER), idle.map(::leadingVarInt))
                assertRecords(listOf(tiny), pair.deliver(idle))

                pair.prepare()
                pair.server.writeOutbound(Unpooled.wrappedBuffer(tiny))
                val ready = pair.drain()
                assertEquals(listOf(PacketRefFormat.REFERENCE_MARKER), ready.map(::leadingVarInt))
                assertRecords(listOf(tiny), pair.deliver(ready))

                pair.start()
                pair.server.writeOutbound(Unpooled.wrappedBuffer(tiny))
                pair.server.writeOutbound(Unpooled.wrappedBuffer(entry))
                pair.server.writeOutbound(Unpooled.wrappedBuffer(entry))
                val active = pair.drain()
                assertEquals(listOf(0, entry.size, PacketRefFormat.REFERENCE_MARKER), active.map(::leadingVarInt))
                assertRecords(listOf(tiny, entry, entry), pair.deliver(active))
                pair.assertTablesAligned()
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `packets before START stay out of both tables`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.prepare()
            val packet = ByteArray(300) { (it * 5).toByte() }
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            val early = pair.drain()
            assertTrue(early.none { leadingVarInt(it) == PacketRefFormat.REFERENCE_MARKER })
            assertRecords(listOf(packet, packet), pair.deliver(early))

            pair.start()
            pair.assertTablesAligned()
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            val later = pair.drain()
            assertEquals(PacketRefFormat.REFERENCE_MARKER, leadingVarInt(later[1]))
            assertEquals(packet.size, leadingVarInt(later[0]))
            assertRecords(listOf(packet, packet), pair.deliver(later))
            pair.assertTablesAligned()
        } finally {
            pair.close()
        }
    }

    @Test
    fun `every batch exit records each packet once in wire order`() {
        val pair = RefPair(threshold = 512)
        try {
            ZstdCompressionPipeline.setOutboundBatching(pair.server, true)
            ZstdCompressionPipeline.setInboundBatching(pair.client, true)
            pair.start()
            val single = listOf(ByteArray(200) { 1 })
            val legacyFallback = listOf(ByteArray(20) { 2 }, ByteArray(21) { 3 })
            val rawBatch = (0 until 20).map { index -> ByteArray(15) { (index + 10).toByte() } }
            val zstdBatch = (0 until 4).map { index -> ByteArray(300) { (index * 7 + it).toByte() } }
            val sent = ArrayList<ByteArray>()

            for ((group, expectedMarkers) in listOf(
                single to listOf(0),
                legacyFallback to listOf(0, 0),
                rawBatch to listOf(ZstdBatchFormat.MARKER),
                zstdBatch to listOf(ZstdBatchFormat.MARKER),
            )) {
                group.forEach { pair.server.writeOneOutbound(Unpooled.wrappedBuffer(it)) }
                ZstdCompressionPipeline.flushBatched(pair.server)
                val frames = pair.drain()
                assertEquals(expectedMarkers, frames.map(::leadingVarInt))
                sent += group
                assertRecords(group, pair.deliver(frames))
                pair.assertTablesAligned()
            }
            assertEquals(sent.size, pair.serverSnapshot().size)

            val again = ArrayList<ByteArray>()
            for (packet in sent) {
                pair.server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), ZstdBatchPolicy.Immediate, null))
                val frames = pair.drain()
                assertEquals(listOf(PacketRefFormat.REFERENCE_MARKER), frames.map(::leadingVarInt))
                again += pair.deliver(frames)
                pair.assertTablesAligned()
            }
            assertRecords(sent, again)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `login START and first reference decode in order in one read and in split reads`() {
        for (splitReads in listOf(false, true)) {
            val pair = RefPair(threshold = 64, framed = true)
            try {
                val login = ByteArray(40) { 0x4C }
                pair.client.pipeline().addAfter("decompress", "login-hook", object : ChannelInboundHandlerAdapter() {
                    override fun channelRead(context: ChannelHandlerContext, msg: Any) {
                        if (msg is ByteBuf && bytes(msg).contentEquals(login)) {
                            ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(context.channel(), true)
                        }
                        context.fireChannelRead(msg)
                    }
                })
                val packet = ByteArray(300) { (it * 11).toByte() }
                pair.server.writeOutbound(Unpooled.wrappedBuffer(login))
                ZstdCompressionPipeline.setOutboundPacketRefs(pair.server, 256, 1024)
                pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
                pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
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
                pair.assertTablesAligned()
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `START again resets both tables`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.start()
            val packet = ByteArray(100) { 4 }
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.transfer()
            assertEquals(1, pair.serverSnapshot().size)

            pair.start()
            assertEquals(emptyList(), pair.serverSnapshot())
            pair.assertTablesAligned()

            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            val frames = pair.drain()
            assertEquals(packet.size, leadingVarInt(frames[0]))
            assertEquals(PacketRefFormat.REFERENCE_MARKER, leadingVarInt(frames[1]))
            assertRecords(listOf(packet, packet), pair.deliver(frames))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `preparing again keeps a table that START aligned`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.start()
            val packet = ByteArray(100) { 5 }
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.transfer()
            val before = pair.clientSnapshot()

            pair.prepare()

            assertEquals(before, pair.clientSnapshot())
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            assertRecords(listOf(packet), pair.transfer())
            assertEquals(1, pair.referenceFrames)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `leaving forgets the table and rejects later references`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.start()
            val packet = ByteArray(100) { 6 }
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.transfer()
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            val reference = pair.drain().single()

            assertTrue(ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(pair.client, false))

            assertNull(pair.clientSnapshot())
            assertFailsWith<DecoderException> { pair.client.writeInbound(reference) }
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a failed START closes the connection and never enables references`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.prepare()
            val encoder = pair.server.pipeline().get("compress") as ZstdCompressionEncoder
            val failing = FailingWrites { leadingVarInt(it) == PacketRefFormat.CONTROL_MARKER }
            pair.server.pipeline().addAfter("prepender", "fail-start", failing)

            ZstdCompressionPipeline.setOutboundPacketRefs(pair.server, 256, 1024)

            assertEquals(listOf(PacketRefFormat.CONTROL_MARKER), failing.rejected)
            assertFalse(encoder.isPacketRefsEnabled())
            assertNull(encoder.packetRefSnapshot())
            assertFalse(pair.server.isOpen)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a failed flush before START never writes START`() {
        val pair = RefPair(threshold = 64)
        try {
            ZstdCompressionPipeline.setOutboundBatching(pair.server, true)
            pair.prepare()
            val failing = FailingWrites { true }
            pair.server.pipeline().addAfter("prepender", "fail-all", failing)
            pair.server.writeOneOutbound(Unpooled.wrappedBuffer(ByteArray(100) { 8 }))

            ZstdCompressionPipeline.setOutboundPacketRefs(pair.server, 256, 1024)

            assertEquals(1, failing.rejected.size)
            assertTrue(failing.rejected.none { it == PacketRefFormat.CONTROL_MARKER })
            assertFalse(ZstdCompressionPipeline.isOutboundPacketRefsEnabled(pair.server))
            assertFalse(pair.server.isOpen)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `client rejects malformed and out of order extension frames`() {
        val entry = ByteArray(40) { 3 }
        val entryCheck = PacketRefFormat.check(PacketRefFormat.hash(entry))
        val cases = listOf(
            Case("reference before negotiation", ClientState.Idle, reference(0, entryCheck)),
            Case("START before negotiation", ClientState.Idle, start(1, 256, 1024)),
            Case("reference before START", ClientState.Ready, reference(0, entryCheck)),
            Case("control without opcode", ClientState.Ready, frame { it.writeVarInt(PacketRefFormat.CONTROL_MARKER) }),
            Case("unknown opcode", ClientState.Ready, frame { it.writeVarInt(PacketRefFormat.CONTROL_MARKER); it.writeByte(3) }),
            Case("unknown version", ClientState.Ready, start(2, 256, 1024)),
            Case("zero slots", ClientState.Ready, start(1, 0, 1024)),
            Case("too many slots", ClientState.Ready, start(1, 1025, 1024)),
            Case("entry limit below minimum", ClientState.Ready, start(1, 256, 7)),
            Case("entry limit above maximum", ClientState.Ready, start(1, 256, 2049)),
            Case("START trailing byte", ClientState.Ready, start(1, 256, 1024, trailing = true)),
            Case("START truncated", ClientState.Ready, frame {
                it.writeVarInt(PacketRefFormat.CONTROL_MARKER); it.writeByte(1); it.writeVarInt(1); it.writeVarInt(256)
            }),
            Case("START overlong VarInt", ClientState.Ready, frame {
                it.writeVarInt(PacketRefFormat.CONTROL_MARKER); it.writeByte(1); it.writeBytes(hex("ffffffffff01"))
            }),
            Case("reference without slot", ClientState.Active, frame { it.writeVarInt(PacketRefFormat.REFERENCE_MARKER) }),
            Case("empty slot", ClientState.Active, reference(1, entryCheck)),
            Case("slot out of range", ClientState.Active, reference(5000, entryCheck)),
            Case("check mismatch", ClientState.Active, reference(0, entryCheck xor 1)),
            Case("truncated check", ClientState.Active, frame {
                it.writeVarInt(PacketRefFormat.REFERENCE_MARKER); it.writeVarInt(0); it.writeMedium(entryCheck)
            }),
            Case("reference trailing byte", ClientState.Active, frame {
                it.writeVarInt(PacketRefFormat.REFERENCE_MARKER); it.writeVarInt(0); it.writeInt(entryCheck); it.writeByte(0)
            }),
        )

        for (case in cases) {
            val pair = RefPair(threshold = 64)
            try {
                if (case.state != ClientState.Idle) pair.prepare()
                if (case.state == ClientState.Active) {
                    pair.client.writeInbound(Unpooled.wrappedBuffer(start(1, 4, 64)))
                    pair.client.writeInbound(Unpooled.wrappedBuffer(frame { it.writeVarInt(0); it.writeBytes(entry) }))
                    readRecords(pair.client)
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
    fun `a desynchronized slot fails the 32-bit check even when the low byte matches`() {
        val first = ByteArray(300) { (it * 3).toByte() }
        val firstCheck = PacketRefFormat.check(PacketRefFormat.hash(first))
        val second = generateSequence(1) { it + 1 }
            .take(1_000_000)
            .map { seed -> ByteArray(300) { if (it < 4) (seed ushr (it * 8)).toByte() else (it * 3).toByte() } }
            .first { candidate ->
                val check = PacketRefFormat.check(PacketRefFormat.hash(candidate))
                check != firstCheck && (check and 0xFF) == (firstCheck and 0xFF)
            }
        val pair = RefPair(threshold = 64)
        try {
            pair.start()
            // A frame the server never sent shifts the client table: slot 0 holds the wrong packet.
            pair.client.writeInbound(Unpooled.wrappedBuffer(frame { it.writeVarInt(0); it.writeBytes(second) }))
            readRecords(pair.client)
            pair.server.writeOutbound(Unpooled.wrappedBuffer(first))
            assertRecords(listOf(first), pair.transfer())
            pair.server.writeOutbound(Unpooled.wrappedBuffer(first))
            val reference = pair.drain().single()

            val error = assertFailsWith<DecoderException> { pair.client.writeInbound(reference) }
            assertTrue(error.message.orEmpty().contains("out of sync"))
        } finally {
            runCatching { pair.close() }
        }
    }

    @Test
    fun `a decoded reference is a private copy of the table entry`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.start()
            val packet = ByteArray(100) { 9 }
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.transfer()
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.deliverRaw(pair.drain())
            val decoded = assertNotNull(pair.client.readInbound<ByteBuf>())
            decoded.setByte(0, 0x7F)
            decoded.release()

            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            assertRecords(listOf(packet), pair.transfer())
        } finally {
            pair.close()
        }
    }

    @Test
    fun `reference samples report the legacy frame they replaced`() {
        val pair = RefPair(threshold = 64)
        val samples = ArrayList<ZstdBatchSample>()
        try {
            ZstdCompressionPipeline.setBatchObserver(pair.server, object : ZstdBatchObserver {
                override fun batchFlushed(sample: ZstdBatchSample) {
                    samples += sample
                }
            })
            pair.start()
            val small = ByteArray(20) { 1 }
            val large = ByteArray(300) { (it % 7).toByte() }
            repeat(2) { pair.server.writeOutbound(Unpooled.wrappedBuffer(small)) }
            repeat(3) { pair.server.writeOutbound(Unpooled.wrappedBuffer(large)) }
            pair.transfer()

            val legacyLarge = samples[2]
            assertEquals(ZstdBatchFrameKind.Legacy, legacyLarge.frameKind)
            val largeFrameBytes = legacyLarge.blockBytes + legacyLarge.outerPrefixBytes
            val references = samples.filter { it.frameKind == ZstdBatchFrameKind.Ref }
            assertEquals(3, references.size)
            assertEquals(listOf(20, 300, 300), references.map { it.payloadBytes })
            assertEquals(listOf(6, 6, 6), references.map { it.blockBytes })
            assertEquals(listOf(1, 1, 1), references.map { it.outerPrefixBytes })
            assertEquals(
                listOf(PacketRefFormat.rawLegacyFrameBytes(20), largeFrameBytes, largeFrameBytes),
                references.map { it.replacedFrameBytes },
            )

            samples.clear()
            ZstdCompressionPipeline.setup(pair.server, 4096, false, TEST_VAR_INT)
            pair.server.writeOutbound(Unpooled.wrappedBuffer(large))
            pair.transfer()
            assertEquals(PacketRefFormat.rawLegacyFrameBytes(300), samples.single().replacedFrameBytes)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a batch inserted entry measures its replaced frame on the first reference`() {
        val pair = RefPair(threshold = 64)
        val samples = ArrayList<ZstdBatchSample>()
        try {
            ZstdCompressionPipeline.setOutboundBatching(pair.server, true)
            ZstdCompressionPipeline.setInboundBatching(pair.client, true)
            ZstdCompressionPipeline.setBatchObserver(pair.server, object : ZstdBatchObserver {
                override fun batchFlushed(sample: ZstdBatchSample) {
                    samples += sample
                }
            })
            pair.start()
            val packet = ByteArray(300) { (it % 5).toByte() }
            pair.server.writeOneOutbound(Unpooled.wrappedBuffer(packet))
            pair.server.writeOneOutbound(Unpooled.wrappedBuffer(ByteArray(300) { 2 }))
            ZstdCompressionPipeline.flushBatched(pair.server)
            pair.transfer()

            pair.server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), ZstdBatchPolicy.Immediate, null))
            pair.transfer()

            assertEquals(legacyFrameBytes(packet, threshold = 64), samples.last().replacedFrameBytes)
            assertEquals(ZstdBatchFrameKind.Ref, samples.last().frameKind)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a failed replaced frame measurement counts as no saving and is not retried`() {
        val pair = RefPair(threshold = 64)
        val samples = ArrayList<ZstdBatchSample>()
        var failDirect = false
        var failedMeasurements = 0
        pair.server.config().setAllocator(object : ByteBufAllocator by UnpooledByteBufAllocator.DEFAULT {
            override fun directBuffer(initialCapacity: Int, maxCapacity: Int): ByteBuf {
                if (failDirect) {
                    failedMeasurements++
                    throw IllegalStateException("injected measurement failure")
                }
                return UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity)
            }
        })
        try {
            ZstdCompressionPipeline.setOutboundBatching(pair.server, true)
            ZstdCompressionPipeline.setInboundBatching(pair.client, true)
            ZstdCompressionPipeline.setBatchObserver(pair.server, object : ZstdBatchObserver {
                override fun batchFlushed(sample: ZstdBatchSample) {
                    samples += sample
                }
            })
            pair.start()
            val packet = ByteArray(300) { (it % 5).toByte() }
            pair.server.writeOneOutbound(Unpooled.wrappedBuffer(packet))
            pair.server.writeOneOutbound(Unpooled.wrappedBuffer(ByteArray(300) { 2 }))
            ZstdCompressionPipeline.flushBatched(pair.server)
            pair.transfer()

            // The batch path left no measured size, so the first reference measures, and fails.
            failDirect = true
            repeat(2) {
                pair.server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), ZstdBatchPolicy.Immediate, null))
            }
            assertRecords(listOf(packet, packet), pair.transfer())

            val references = samples.takeLast(2)
            assertEquals(listOf(ZstdBatchFrameKind.Ref, ZstdBatchFrameKind.Ref), references.map { it.frameKind })
            references.forEach { assertEquals(it.blockBytes + it.outerPrefixBytes, it.replacedFrameBytes) }
            assertEquals(1, failedMeasurements)
            assertTrue(pair.server.isOpen)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a packet sent while START flushes follows START into both tables`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.prepare()
            val earlier = ByteArray(40) { 1 }
            val fromListener = ByteArray(50) { 2 }
            val packet = ByteArray(60) { 3 }
            // The earlier write completes during START's flush, and its listener sends at once.
            pair.server.write(Unpooled.wrappedBuffer(earlier)).addListener {
                pair.server.write(Unpooled.wrappedBuffer(fromListener))
            }

            ZstdCompressionPipeline.setOutboundPacketRefs(pair.server, 256, 1024)
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            val frames = pair.drain()

            assertEquals(
                listOf(0, PacketRefFormat.CONTROL_MARKER, 0, 0, PacketRefFormat.REFERENCE_MARKER),
                frames.map(::leadingVarInt),
            )
            assertRecords(listOf(earlier, fromListener, packet, packet), pair.deliver(frames))
            pair.assertTablesAligned()
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a failed table update closes the connection and releases the packet`() {
        val pair = RefPair(threshold = 64)
        try {
            pair.start()
            val packet = UncopyablePacket(ByteArray(100) { 7 })

            val future = pair.server.writeOneOutbound(packet)

            assertTrue(future.isDone)
            assertFalse(future.isSuccess)
            assertEquals(0, packet.refCnt())
            assertFalse(pair.server.isOpen)
        } finally {
            runCatching { pair.close() }
        }
    }

    @Test
    fun `a failed reference allocation closes the connection and releases the packet`() {
        val pair = RefPair(threshold = 64)
        val allocator = FailingFrameAllocator()
        pair.server.config().setAllocator(allocator)
        try {
            pair.start()
            val packet = ByteArray(100) { 6 }
            pair.server.writeOutbound(Unpooled.wrappedBuffer(packet))
            pair.transfer()

            allocator.armed = true
            val input = Unpooled.wrappedBuffer(packet)
            val future = pair.server.writeOneOutbound(input)

            assertTrue(future.isDone)
            assertFalse(future.isSuccess)
            assertEquals(0, input.refCnt())
            assertFalse(pair.server.isOpen)
            assertEquals(emptyList(), pair.drain())
        } finally {
            runCatching { pair.close() }
        }
    }

    @Test
    fun `a failed START allocation closes the connection and never enables references`() {
        val pair = RefPair(threshold = 64)
        val allocator = FailingFrameAllocator()
        pair.server.config().setAllocator(allocator)
        try {
            pair.prepare()
            allocator.armed = true

            ZstdCompressionPipeline.setOutboundPacketRefs(pair.server, 256, 1024)

            assertFalse(ZstdCompressionPipeline.isOutboundPacketRefsEnabled(pair.server))
            assertFalse(pair.server.isOpen)
            assertEquals(emptyList(), pair.drain())
        } finally {
            runCatching { pair.close() }
        }
    }

    @Test
    fun `a rejected extension frame leaves no bytes behind for the next frame`() {
        val entry = ByteArray(40) { 3 }
        val pair = RefPair(threshold = 64)
        try {
            pair.prepare()
            assertFailsWith<DecoderException> {
                pair.client.writeInbound(Unpooled.wrappedBuffer(reference(0, PacketRefFormat.check(PacketRefFormat.hash(entry)))))
            }

            pair.client.writeInbound(Unpooled.wrappedBuffer(frame { it.writeVarInt(0); it.writeBytes(entry) }))

            assertRecords(listOf(entry), readRecords(pair.client))
        } finally {
            runCatching { pair.close() }
        }
    }

    @Test
    fun `random batched traffic keeps both tables aligned through flushes, thresholds and restarts`() {
        // The scheduled timeout never fires on its own; time deadlines follow the virtual clock instead.
        var now = 0L
        val pair = RefPair(
            threshold = 64,
            serverEncoder = ZstdCompressionEncoder(64, TEST_VAR_INT, flushTimeoutMillis = Long.MAX_VALUE, nanoTime = { now }),
        )
        var observedReferences = 0
        val observer = object : ZstdBatchObserver {
            override fun batchFlushed(sample: ZstdBatchSample) {
                if (sample.frameKind == ZstdBatchFrameKind.Ref) observedReferences++
            }
        }
        try {
            val batchTarget = ZstdCompressionPipeline.MINIMUM_BATCH_TARGET_BYTES
            ZstdCompressionPipeline.setOutboundBatching(pair.server, true, batchTarget)
            ZstdCompressionPipeline.setInboundBatching(pair.client, true)
            pair.start(slots = 32, maxEntryBytes = 600)
            val sizes = intArrayOf(0, 3, 8, 9, 20, 63, 64, 65, 127, 200, 599, 600, 601, 1500, 3000)
            val pool = (0 until 30).map { index -> ByteArray(sizes[index % sizes.size]) { (it * 13 + index * 5).toByte() } }
            val thresholds = intArrayOf(32, 64, 256, 1024)
            val slotCounts = intArrayOf(1, 4, 32, 256)
            val entryLimits = intArrayOf(8, 64, 600, 2048)
            val policies = ZstdBatchPolicy.entries
            val random = Random(11)
            val sent = ArrayList<ByteArray>()
            val received = ArrayList<ByteArray>()

            repeat(4000) {
                when (random.nextInt(100)) {
                    in 0 until 70 -> {
                        val packet = pool[random.nextInt(pool.size)]
                        val policy = policies[random.nextInt(policies.size)]
                        sent += packet
                        pair.server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), policy, null))
                    }
                    in 70 until 80 -> ZstdCompressionPipeline.flushAtTickEnd(pair.server)
                    in 80 until 86 -> now += TimeUnit.MILLISECONDS.toNanos(random.nextInt(120).toLong())
                    in 86 until 90 -> ZstdCompressionPipeline.flushBatched(pair.server)
                    in 90 until 93 -> {
                        val threshold = thresholds[random.nextInt(thresholds.size)]
                        ZstdCompressionPipeline.setup(pair.server, threshold, false, TEST_VAR_INT)
                        ZstdCompressionPipeline.setup(pair.client, threshold, false, TEST_VAR_INT)
                    }
                    in 93 until 95 -> ZstdCompressionPipeline.setOutboundPacketRefs(
                        pair.server,
                        slotCounts[random.nextInt(slotCounts.size)],
                        entryLimits[random.nextInt(entryLimits.size)],
                    )
                    in 95 until 98 -> ZstdCompressionPipeline.setBatchObserver(
                        pair.server,
                        if (random.nextBoolean()) observer else null,
                    )
                    else -> ZstdCompressionPipeline.setOutboundBatching(pair.server, random.nextBoolean(), batchTarget)
                }
                // Direct frames written while batching is off wait for an explicit flush.
                pair.server.flushOutbound()
                received += pair.transfer()
                pair.assertTablesAligned()
            }
            ZstdCompressionPipeline.flushBatched(pair.server)
            received += pair.transfer()

            assertRecords(sent, received)
            val counts = "references=${pair.referenceFrames}, batches=${pair.batchFrames}, observed references=$observedReferences"
            assertTrue(pair.referenceFrames > 100, counts)
            assertTrue(pair.batchFrames > 100, counts)
            assertTrue(observedReferences > 0, counts)
        } finally {
            pair.close()
        }
    }

    private enum class ClientState { Idle, Ready, Active }

    private class Case(val name: String, val state: ClientState, val frame: ByteArray)

    /** Fails writes whose frame matches, recording each rejected frame's leading VarInt. */
    private class FailingWrites(private val matches: (ByteBuf) -> Boolean) : ChannelOutboundHandlerAdapter() {
        val rejected = ArrayList<Int>()

        override fun write(context: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is ByteBuf && matches(msg)) {
                rejected += leadingVarInt(msg)
                msg.release()
                promise.setFailure(IllegalStateException("injected write failure"))
                return
            }
            context.write(msg, promise)
        }
    }

    /** Fails the plain buffer allocations of START, reference frames and batch headers while [armed]. */
    private class FailingFrameAllocator : ByteBufAllocator by UnpooledByteBufAllocator.DEFAULT {
        var armed = false

        override fun buffer(initialCapacity: Int, maxCapacity: Int): ByteBuf {
            if (armed) throw IllegalStateException("injected frame allocation failure")
            return UnpooledByteBufAllocator.DEFAULT.buffer(initialCapacity, maxCapacity)
        }
    }

    /** A packet whose bytes cannot be copied into a table; every other read works. */
    private class UncopyablePacket(bytes: ByteArray) :
        UnpooledHeapByteBuf(UnpooledByteBufAllocator.DEFAULT, bytes.size, bytes.size) {
        init {
            writeBytes(bytes)
        }

        override fun getBytes(index: Int, dst: ByteArray, dstIndex: Int, length: Int): ByteBuf =
            throw IllegalStateException("injected table failure")
    }

    /**
     * One server and one client channel; [framed] adds a real VarInt length prefix and splitter, and
     * [serverEncoder] replaces the default server encoder, for example to control its clock.
     */
    private class RefPair(threshold: Int, framed: Boolean = false, serverEncoder: ZstdCompressionEncoder? = null) {
        val server = channel(framed)
        val client = channel(framed)
        var referenceFrames = 0
            private set
        var batchFrames = 0
            private set

        init {
            serverEncoder?.let { server.pipeline().addAfter("prepender", "compress", it) }
            ZstdCompressionPipeline.setup(server, threshold, false, TEST_VAR_INT)
            ZstdCompressionPipeline.setup(client, threshold, false, TEST_VAR_INT)
        }

        fun prepare() = assertTrue(ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(client, true))

        fun start(
            slots: Int = PacketRefFormat.DEFAULT_SLOTS,
            maxEntryBytes: Int = PacketRefFormat.DEFAULT_MAX_ENTRY_BYTES,
        ) {
            prepare()
            ZstdCompressionPipeline.setOutboundPacketRefs(server, slots, maxEntryBytes)
            assertTrue(ZstdCompressionPipeline.isOutboundPacketRefsEnabled(server))
            assertEquals(emptyList(), transfer())
        }

        fun drain(): List<ByteBuf> {
            val frames = ArrayList<ByteBuf>()
            while (true) frames += server.readOutbound<ByteBuf>() ?: break
            return frames
        }

        fun deliverRaw(frames: List<ByteBuf>) {
            frames.forEach { frame ->
                when (leadingVarInt(frame)) {
                    PacketRefFormat.REFERENCE_MARKER -> referenceFrames++
                    ZstdBatchFormat.MARKER -> batchFrames++
                }
                client.writeInbound(frame)
            }
        }

        fun deliver(frames: List<ByteBuf>): List<ByteArray> {
            deliverRaw(frames)
            return readRecords(client)
        }

        fun transfer(): List<ByteArray> = deliver(drain())

        fun serverSnapshot(): List<PacketRefCache.Entry> =
            assertNotNull((server.pipeline().get("compress") as ZstdCompressionEncoder).packetRefSnapshot())

        fun clientSnapshot(): List<PacketRefCache.Entry>? =
            (client.pipeline().get("decompress") as ZstdCompressionDecoder).packetRefSnapshot()

        fun assertTablesAligned() = assertEquals(serverSnapshot(), clientSnapshot())

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
        fun ByteBuf.writeVarInt(value: Int) = TEST_VAR_INT.write(this, value)

        fun frame(writer: (ByteBuf) -> Unit): ByteArray {
            val buffer = Unpooled.buffer()
            return try {
                writer(buffer)
                bytes(buffer)
            } finally {
                buffer.release()
            }
        }

        fun start(version: Int, slots: Int, maxEntryBytes: Int, trailing: Boolean = false): ByteArray = frame {
            it.writeVarInt(PacketRefFormat.CONTROL_MARKER)
            it.writeByte(PacketRefFormat.OPCODE_START)
            it.writeVarInt(version)
            it.writeVarInt(slots)
            it.writeVarInt(maxEntryBytes)
            if (trailing) it.writeByte(0)
        }

        fun reference(slot: Int, check: Int): ByteArray = frame {
            it.writeVarInt(PacketRefFormat.REFERENCE_MARKER)
            it.writeVarInt(slot)
            it.writeInt(check)
        }

        /** The leading VarInt of an inner frame from an unframed pair. */
        fun leadingVarInt(frame: ByteBuf): Int = TEST_VAR_INT.read(frame.duplicate())

        fun legacyFrameBytes(packet: ByteArray, threshold: Int): Int {
            val channel = EmbeddedChannel().apply {
                pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
                pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
            }
            try {
                ZstdCompressionPipeline.setup(channel, threshold, false, TEST_VAR_INT)
                channel.writeOutbound(Unpooled.wrappedBuffer(packet))
                val frame = assertNotNull(channel.readOutbound<ByteBuf>())
                try {
                    return PacketRefFormat.withOuterPrefix(frame.readableBytes())
                } finally {
                    frame.release()
                }
            } finally {
                channel.finishAndReleaseAll()
            }
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
