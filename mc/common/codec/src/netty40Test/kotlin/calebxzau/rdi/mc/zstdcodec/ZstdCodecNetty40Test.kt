package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import io.netty.util.ReferenceCountUtil
import java.nio.channels.ClosedChannelException
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the shared codec with Netty 4.0.10 channels and real zstd-jni compression. */
class ZstdCodecNetty40Test {
    @Test
    fun `legacy envelopes round trip below at and above the threshold`() {
        val pair = CodecPair()
        try {
            val packets = listOf(1, 31, 63, 64, 65, 1024, 70_000).map(::packet)
            for (packet in packets) {
                pair.send(packet)
                val frame = pair.sender.readOutbound() as ByteBuf
                try {
                    assertEquals(if (packet.size < THRESHOLD) 0 else packet.size, TestVarInt.read(frame.duplicate()))
                    assertRecords(listOf(packet), pair.deliver(frame.retain()))
                } finally {
                    frame.release()
                }
            }
        } finally {
            pair.close()
        }
    }

    @Test
    fun `stream keeps repeated payloads byte exact and in order`() {
        val pair = CodecPair()
        try {
            pair.prepareStream()
            assertRecords(emptyList(), pair.transfer())
            val repeated = packet(4096)
            val packets = listOf(packet(1), packet(31), packet(32), packet(63), repeated, repeated, packet(70_000), repeated)
            val received = ArrayList<ByteArray>()
            for (packet in packets) {
                pair.send(packet)
                received += pair.transfer()
            }
            assertRecords(packets, received)
            assertTrue(pair.decoder.isStreamActive())
        } finally {
            pair.close()
        }
    }

    @Test
    fun `one read decodes all concatenated frames including stream start`() {
        for (stream in listOf(false, true)) {
            val pair = CodecPair(framed = true)
            try {
                if (stream) pair.prepareStream()
                val repeated = packet(2000)
                val packets = listOf(packet(1), packet(63), packet(64), repeated, repeated, packet(70_000))
                packets.forEach(pair::send)
                val wire = Unpooled.buffer()
                try {
                    while (true) {
                        val frame = pair.sender.readOutbound() as ByteBuf? ?: break
                        try {
                            wire.writeBytes(frame)
                        } finally {
                            frame.release()
                        }
                    }
                    assertTrue(wire.isReadable)
                    assertRecords(packets, pair.deliver(wire.retain()))
                    assertEquals(stream, pair.decoder.isStreamActive())
                } finally {
                    wire.release()
                }
            } finally {
                pair.close()
            }
        }
    }

    @Test
    fun `closing a failed transport does not rethrow its cause`() {
        val pair = CodecPair()
        try {
            // Netty 4.0 still decodes an empty buffer when the channel goes inactive.
            pair.closeFailedReceiver()
        } finally {
            pair.close()
        }
    }

    private class CodecPair(framed: Boolean = false) {
        val sender = channel(framed)
        private val receiver = channel(framed)
        val decoder: ZstdCompressionDecoder get() = receiver.pipeline().get("decompress") as ZstdCompressionDecoder

        init {
            ZstdCompressionPipeline.setup(sender, THRESHOLD, false, TestVarInt)
            ZstdCompressionPipeline.setup(receiver, THRESHOLD, true, TestVarInt)
        }

        fun prepareStream() {
            assertTrue(ZstdCompressionPipeline.setInboundStreamIfAvailable(receiver, true))
            ZstdCompressionPipeline.setOutboundStream(sender)
            assertTrue(ZstdCompressionPipeline.isOutboundStreamEnabled(sender))
        }

        fun send(packet: ByteArray) {
            sender.writeOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(packet), ZstdBatchPolicy.Immediate, null))
        }

        fun transfer(): List<ByteArray> {
            val received = ArrayList<ByteArray>()
            while (true) {
                val frame = sender.readOutbound() as ByteBuf? ?: break
                received += deliver(frame)
            }
            return received
        }

        fun deliver(frame: ByteBuf): List<ByteArray> {
            receiver.writeInbound(frame)
            val received = ArrayList<ByteArray>()
            while (true) {
                val record = receiver.readInbound() as ByteBuf? ?: break
                try {
                    received += ByteArray(record.readableBytes()).also { record.readBytes(it) }
                } finally {
                    record.release()
                }
            }
            return received
        }

        /** Fails the receiver like a write to a departed peer, then lets the closed channel go inactive. */
        fun closeFailedReceiver() {
            ZstdTransportGuard.get(receiver).fail(ClosedChannelException())
            receiver.finish()
        }

        fun close() {
            try {
                finish(sender)
            } finally {
                finish(receiver)
            }
        }

        private fun finish(channel: EmbeddedChannel) {
            try {
                channel.finish()
            } finally {
                while (true) ReferenceCountUtil.release(channel.readInbound() ?: break)
                while (true) ReferenceCountUtil.release(channel.readOutbound() ?: break)
            }
        }

        private fun channel(framed: Boolean): EmbeddedChannel = EmbeddedChannel(ChannelInboundHandlerAdapter()).apply {
            // Netty 4.0 already installed its inbound queue handler; decoding must precede it.
            pipeline().addFirst("splitter", if (framed) FrameDecoder() else ChannelInboundHandlerAdapter())
            pipeline().addAfter("splitter", "decoder", ChannelInboundHandlerAdapter())
            pipeline().addAfter("decoder", "prepender", if (framed) FrameEncoder() else ChannelOutboundHandlerAdapter())
            pipeline().addAfter("prepender", "encoder", ChannelOutboundHandlerAdapter())
        }
    }

    /** Copies each frame like Minecraft's `MessageDeserializer2`, without Netty 4.0's protobuf framing helpers. */
    private class FrameDecoder : ByteToMessageDecoder() {
        override fun decode(context: ChannelHandlerContext, input: ByteBuf, output: MutableList<Any>) {
            input.markReaderIndex()
            var length = 0
            repeat(5) { index ->
                if (!input.isReadable) {
                    input.resetReaderIndex()
                    return
                }
                val current = input.readUnsignedByte().toInt()
                length = length or ((current and 0x7F) shl (index * 7))
                if (current and 0x80 == 0) {
                    check(length >= 0) { "Negative frame length" }
                    if (input.readableBytes() < length) input.resetReaderIndex()
                    else output.add(input.readBytes(length))
                    return
                }
            }
            error("Frame length VarInt too big")
        }
    }

    private class FrameEncoder : MessageToByteEncoder<ByteBuf>() {
        override fun encode(context: ChannelHandlerContext, input: ByteBuf, output: ByteBuf) {
            TestVarInt.write(output, input.readableBytes())
            output.writeBytes(input, input.readerIndex(), input.readableBytes())
        }
    }

    /** Standard Minecraft VarInt encoding without loading any Minecraft classes. */
    private object TestVarInt : MinecraftVarIntCodec {
        override fun read(buffer: ByteBuf): Int {
            var value = 0
            repeat(5) { index ->
                check(buffer.isReadable) { "Unterminated VarInt" }
                val current = buffer.readUnsignedByte().toInt()
                value = value or ((current and 0x7F) shl (index * 7))
                if (current and 0x80 == 0) return value
            }
            error("VarInt too big")
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
        const val THRESHOLD = 64

        fun packet(size: Int): ByteArray = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

        fun assertRecords(expected: List<ByteArray>, actual: List<ByteArray>) {
            assertEquals(expected.size, actual.size, "record count")
            expected.zip(actual).forEachIndexed { index, (sent, received) ->
                assertContentEquals(sent, received, "record ${index}")
            }
        }
    }
}
