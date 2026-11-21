package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.v20.protocol.PacketExtensions20
import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketExtensions20Test {
    @Test
    fun `each valid mask round trips on the login channel`() {
        for (mask in 0..PacketExtensions20.SUPPORTED) {
            val buffer = PacketExtensions20.encode(mask)
            try {
                assertEquals(mask, PacketExtensions20.decode(buffer))
            } finally {
                buffer.release()
            }
        }
    }

    @Test
    fun `unsupported versions and malformed messages select no extensions`() {
        for (body in listOf(
            byteArrayOf(),
            byteArrayOf(1),
            byteArrayOf(1, 1, 0),
            byteArrayOf(2, 1),
            byteArrayOf(1, ZstdInboundPreparation.ZLIB_TAIL.toByte()),
        )) {
            val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(body))
            try {
                assertEquals(0, PacketExtensions20.decode(buffer))
            } finally {
                buffer.release()
            }
        }
    }

    @Test
    fun `only an acknowledged subset of the offer is accepted`() {
        val batch = ZstdInboundPreparation.BATCH
        val stream = ZstdInboundPreparation.STREAM
        val references = ZstdInboundPreparation.REFERENCES
        assertEquals(batch, PacketExtensions20.accepted(batch or stream, batch))
        assertEquals(0, PacketExtensions20.accepted(batch or stream, 0))
        assertEquals(0, PacketExtensions20.accepted(batch or stream, batch or references))
    }

    @Test
    fun `stream is required even if the peer accepts other extensions`() {
        val stream = ZstdInboundPreparation.STREAM
        val batch = ZstdInboundPreparation.BATCH
        val offer = PacketExtensions20.SUPPORTED
        assertEquals(false, PacketExtensions20.supportsStream(PacketExtensions20.accepted(offer, 0)))
        assertEquals(false, PacketExtensions20.supportsStream(PacketExtensions20.accepted(offer, batch)))
        assertEquals(true, PacketExtensions20.supportsStream(PacketExtensions20.accepted(offer, stream)))
    }
}
