package calebxzau.rdi.mc.zstdcodec

import calebxzau.rdi.mc.zstdcodec.ZlibTailTest.Companion.CHANNEL
import calebxzau.rdi.mc.zstdcodec.ZlibTailTest.Companion.channelHeader
import calebxzau.rdi.mc.zstdcodec.ZlibTailTest.Companion.modPacket
import calebxzau.rdi.mc.zstdcodec.ZlibTailTest.Companion.readModPacket
import calebxzau.rdi.mc.zstdcodec.ZlibTailTest.Companion.snapshotJson
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZstdZlibTailTest {
    @Test
    fun `configured snapshots leave inflated and the mod reads the same content`() {
        for (stream in listOf(false, true)) {
            val pair = TailPair(threshold = 256)
            try {
                if (stream) pair.startStream()
                pair.startZlibTail(setOf(NAMESPACE))
                val snapshots = (0 until 6).map { snapshotJson(60, seed = it) }

                val frames = snapshots.map { json ->
                    pair.send(modPacket(CHANNEL, ByteArray(0), json, ByteArray(0)), NAMESPACE)
                    pair.drain().single()
                }
                assertTrue(frames.all { leadingVarInt(it) == ZlibTailFormat.MARKER })
                val frameSizes = frames.map { it.readableBytes() }
                val received = pair.deliver(frames)

                assertEquals(snapshots.size, received.size)
                snapshots.zip(received).forEach { (json, packet) ->
                    assertContentEquals(json, readModPacket(packet, channelHeader(CHANNEL).size).json)
                }
                if (stream) {
                    val zlibSize = modPacket(CHANNEL, ByteArray(0), snapshots.last(), ByteArray(0)).size
                    assertTrue(frameSizes.last() * 4 < zlibSize, "streamed=${frameSizes} zlib=${zlibSize}")
                }
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `other namespaces and other packets keep their exact bytes`() {
        val pair = TailPair(threshold = 256)
        try {
            pair.startStream()
            pair.startZlibTail(setOf(NAMESPACE))
            val packet = modPacket(CHANNEL, ByteArray(0), snapshotJson(60), ByteArray(0))

            pair.send(packet, "othermod")
            pair.send(packet, NAMESPACE, packetType = "minecraft:level_chunk_with_light")
            pair.send(packet, null)
            val frames = pair.drain()
            assertTrue(frames.none { leadingVarInt(it) == ZlibTailFormat.MARKER })
            pair.deliver(frames).forEach { assertContentEquals(packet, it) }

            ZstdCompressionPipeline.setOutboundZlibTail(pair.server, setOf("*"))
            pair.send(packet, "othermod")
            val wildcard = pair.drain()
            assertEquals(listOf(ZlibTailFormat.MARKER), wildcard.map(::leadingVarInt))
            pair.deliver(wildcard)

            ZstdCompressionPipeline.setOutboundZlibTail(pair.server, emptySet())
            assertFalse(ZstdCompressionPipeline.isOutboundZlibTailEnabled(pair.server))
            pair.send(packet, NAMESPACE)
            assertContentEquals(packet, pair.transfer().single())
        } finally {
            pair.close()
        }
    }

    @Test
    fun `buffered records leave before a zlib tail and the reference tables stay aligned`() {
        val pair = TailPair(threshold = 64)
        try {
            pair.startStream()
            pair.startRefs()
            pair.enableBatching()
            pair.startZlibTail(setOf(NAMESPACE))
            val small = ByteArray(200) { (it * 3).toByte() }
            // Small enough for the reference table, so a wrongly recorded tail would shift its slots.
            val tailPacket = modPacket(CHANNEL, ByteArray(0), snapshotJson(4), ByteArray(0))
            assertTrue(tailPacket.size <= PacketRefFormat.DEFAULT_MAX_ENTRY_BYTES)

            val sent = ArrayList<ByteArray>()
            repeat(3) { round ->
                pair.sendBuffered(small)
                sent += small
                pair.send(tailPacket, NAMESPACE)
                sent += tailPacket
                pair.sendBuffered(small)
                sent += small
                ZstdCompressionPipeline.flushBatched(pair.server)
            }
            val received = pair.transfer()

            assertEquals(sent.size, received.size)
            sent.zip(received).forEachIndexed { index, (expected, actual) ->
                if (expected === tailPacket) {
                    assertContentEquals(snapshotJson(4), readModPacket(actual, channelHeader(CHANNEL).size).json, "record ${index}")
                } else {
                    assertContentEquals(expected, actual, "record ${index}")
                }
            }
            assertEquals(pair.serverRefs(), pair.clientRefs())
            assertTrue(pair.serverRefs().none { it.content.size == tailPacket.size })
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a decoder that did not negotiate zlib tails rejects the frame`() {
        val pair = TailPair(threshold = 256)
        try {
            ZstdCompressionPipeline.setOutboundZlibTail(pair.server, setOf(NAMESPACE))
            pair.send(modPacket(CHANNEL, ByteArray(0), snapshotJson(60), ByteArray(0)), NAMESPACE)
            val frames = pair.drain()
            assertEquals(listOf(ZlibTailFormat.MARKER), frames.map(::leadingVarInt))
            assertFailsWith<DecoderException> { pair.deliver(frames) }
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a corrupt tail offset is a protocol error`() {
        val pair = TailPair(threshold = 256)
        try {
            assertTrue(ZstdCompressionPipeline.setInboundZlibTailIfAvailable(pair.client, true))
            val unwrapped = ByteArray(100) { 0x80.toByte() }
            val frame = Unpooled.buffer()
            TEST_VAR_INT.write(frame, ZlibTailFormat.MARKER)
            frame.writeByte(ZlibTailFormat.VERSION)
            TEST_VAR_INT.write(frame, 3)
            TEST_VAR_INT.write(frame, 0)
            frame.writeBytes(unwrapped)
            assertFailsWith<DecoderException> { pair.deliver(listOf(frame)) }
        } finally {
            pair.close()
        }
    }

    private class TailPair(threshold: Int) {
        val server = channel()
        val client = channel()

        init {
            ZstdCompressionPipeline.setup(server, threshold, false, TEST_VAR_INT)
            ZstdCompressionPipeline.setup(client, threshold, false, TEST_VAR_INT)
        }

        fun startStream() {
            assertTrue(ZstdCompressionPipeline.setInboundStreamIfAvailable(client, true))
            ZstdCompressionPipeline.setOutboundStream(server)
            assertEquals(emptyList(), transfer())
        }

        fun startRefs() {
            assertTrue(ZstdCompressionPipeline.setInboundPacketRefsIfAvailable(client, true))
            ZstdCompressionPipeline.setOutboundPacketRefs(server)
            assertEquals(emptyList(), transfer())
        }

        fun enableBatching() {
            ZstdCompressionPipeline.setOutboundBatching(server, true)
            ZstdCompressionPipeline.setInboundBatching(client, true)
        }

        fun startZlibTail(namespaces: Set<String>) {
            assertTrue(ZstdCompressionPipeline.setInboundZlibTailIfAvailable(client, true))
            ZstdCompressionPipeline.setOutboundZlibTail(server, namespaces)
            assertTrue(ZstdCompressionPipeline.isOutboundZlibTailEnabled(server))
        }

        fun send(packet: ByteArray, namespace: String?, packetType: String = "minecraft:custom_payload") {
            val identity = ZstdPacketIdentity(packetType, namespace, namespace?.let { "shop_reply" })
            server.writeOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), ZstdBatchPolicy.Immediate, identity))
        }

        fun sendBuffered(packet: ByteArray) {
            server.writeOneOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), ZstdBatchPolicy.OneTick, null))
        }

        fun drain(): List<ByteBuf> {
            val frames = ArrayList<ByteBuf>()
            while (true) frames += server.readOutbound<ByteBuf>() ?: break
            return frames
        }

        fun deliver(frames: List<ByteBuf>): List<ByteArray> {
            frames.forEach(client::writeInbound)
            val records = ArrayList<ByteArray>()
            while (true) {
                val record = client.readInbound<ByteBuf>() ?: break
                try {
                    records += ByteBufUtil.getBytes(record)
                } finally {
                    record.release()
                }
            }
            return records
        }

        fun transfer(): List<ByteArray> = deliver(drain())

        fun serverRefs(): List<PacketRefCache.Entry> =
            requireNotNull((server.pipeline().get("compress") as ZstdCompressionEncoder).packetRefSnapshot())

        fun clientRefs(): List<PacketRefCache.Entry> =
            requireNotNull((client.pipeline().get("decompress") as ZstdCompressionDecoder).packetRefSnapshot())

        fun close() {
            runCatching { server.finishAndReleaseAll() }
            runCatching { client.finishAndReleaseAll() }
        }

        private fun channel(): EmbeddedChannel = EmbeddedChannel().apply {
            pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
            pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
            pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
            pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        }
    }

    private object TEST_VAR_INT : MinecraftVarIntCodec {
        override fun read(buffer: ByteBuf): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                check(buffer.isReadable) { "Unterminated VarInt" }
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

    private companion object {
        const val NAMESPACE = "wonder_shop_story_core"

        fun leadingVarInt(frame: ByteBuf): Int = TEST_VAR_INT.read(frame.duplicate())
    }
}
