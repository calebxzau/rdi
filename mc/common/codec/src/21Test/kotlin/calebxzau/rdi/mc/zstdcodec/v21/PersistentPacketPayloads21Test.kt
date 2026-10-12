package calebxzau.rdi.mc.zstdcodec.v21

import calebxzau.rdi.mc.zstdcodec.PersistentPacketHash
import calebxzau.rdi.mc.zstdcodec.PersistentPacketKind
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistentPacketPayloads21Test {
    @Test
    fun `query and a full offer survive the configuration codec`() {
        val query = PersistentPacketQuery.encodeQuery()
        val decodedQuery = roundTrip(query) { buffer, body ->
            PersistentPacketPayloads21.Query.STREAM_CODEC.encode(buffer, PersistentPacketPayloads21.Query(body))
            PersistentPacketPayloads21.Query.STREAM_CODEC.decode(buffer).body
        }
        assertEquals(PersistentPacketQuery.VERSION, PersistentPacketQuery.decodeQuery(decodedQuery).getOrThrow())

        val entries = List(PersistentPacketOffer.MAXIMUM_ENTRIES) {
            PersistentPacketOffer.Entry(PersistentPacketKind.TAGS, PersistentPacketHash.of(byteArrayOf(it.toByte())))
        }
        val offer = PersistentPacketOffer(PersistentPacketQuery.VERSION, entries).encode()
        val decodedOffer = roundTrip(offer) { buffer, body ->
            PersistentPacketPayloads21.Answer.STREAM_CODEC.encode(buffer, PersistentPacketPayloads21.Answer(body))
            PersistentPacketPayloads21.Answer.STREAM_CODEC.decode(buffer).body
        }
        assertEquals(entries, PersistentPacketOffer.decode(decodedOffer).getOrThrow().entries)
    }

    @Test
    fun `an answer longer than the largest offer is rejected`() {
        val tooLong = ByteArray(4 + PersistentPacketOffer.MAXIMUM_ENTRIES * (1 + PersistentPacketHash.BYTES))
        val buffer = Unpooled.buffer()
        try {
            assertFailsWith<Exception> { PersistentPacketPayloads21.Answer.STREAM_CODEC.encode(buffer, PersistentPacketPayloads21.Answer(tooLong)) }
            // A peer writing it anyway is refused on decode.
            net.minecraft.network.FriendlyByteBuf.writeByteArray(buffer, tooLong)
            assertFailsWith<Exception> { PersistentPacketPayloads21.Answer.STREAM_CODEC.decode(buffer) }
        } finally {
            buffer.release()
        }
    }

    private fun roundTrip(body: ByteArray, codec: (io.netty.buffer.ByteBuf, ByteArray) -> ByteArray): ByteArray {
        val buffer = Unpooled.buffer()
        try {
            val decoded = codec(buffer, body)
            assertContentEquals(body, decoded)
            assertEquals(0, buffer.readableBytes())
            return decoded
        } finally {
            buffer.release()
        }
    }
}
