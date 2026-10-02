package calebxzau.rdi.mc.chunkcache.network

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.connection.ConnectionType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChunkCachePayloadsTest {
    @Test
    fun allPayloadsRoundTripTheirFields(): Unit {
        val epoch = UUID(12, 34)
        val dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        val hash = ByteArray(20) { it.toByte() }
        val metadata = byteArrayOf(3, 5, 7, 9)

        val context = roundTrip(ChunkCacheContextPayload(epoch, dimension), ChunkCacheContextPayload.STREAM_CODEC)
        assertEquals(epoch, context.epoch)
        assertEquals(dimension, context.dimension)

        val offer = roundTrip(
            ChunkCacheOfferPayload(epoch, dimension, listOf(ChunkCacheOffer(1, -2, 7, hash))),
            ChunkCacheOfferPayload.STREAM_CODEC,
        )
        assertEquals(epoch, offer.epoch)
        assertEquals(dimension, offer.dimension)
        assertEquals(1, offer.entries.size)
        assertEquals(1L, offer.entries.single().id)
        assertEquals(-2, offer.entries.single().x)
        assertEquals(7, offer.entries.single().z)
        assertContentEquals(hash, offer.entries.single().hash)

        assertEquals(listOf(1L, 2L), roundTrip(ChunkCacheRetirePayload(epoch, listOf(1, 2)), ChunkCacheRetirePayload.STREAM_CODEC).ids)
        assertEquals(listOf(3L), roundTrip(ChunkCacheCancelPayload(epoch, listOf(3)), ChunkCacheCancelPayload.STREAM_CODEC).ids)

        val result = roundTrip(ChunkCacheResultPayload(epoch, 4, 8, -3, hash, true), ChunkCacheResultPayload.STREAM_CODEC)
        assertEquals(epoch, result.epoch)
        assertEquals(4L, result.id)
        assertEquals(8, result.x)
        assertEquals(-3, result.z)
        assertContentEquals(hash, result.hash)
        assertEquals(true, result.success)

        val reuse = roundTrip(
            ChunkCacheReusePayload(epoch, 5, dimension, 11, 12, hash, metadata),
            ChunkCacheReusePayload.STREAM_CODEC,
        )
        assertEquals(epoch, reuse.epoch)
        assertEquals(5L, reuse.id)
        assertEquals(dimension, reuse.dimension)
        assertEquals(11, reuse.x)
        assertEquals(12, reuse.z)
        assertContentEquals(hash, reuse.hash)
        assertContentEquals(metadata, reuse.metadata)
    }

    @Test
    fun constructorsAndWireReadersRejectOutOfBoundsBeforeAllocatingPayloadListsOrMetadata(): Unit {
        val epoch = UUID(1, 2)
        val dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        assertFailsWith<IllegalArgumentException> { ChunkCacheOffer(0, 0, 0, ByteArray(20)) }
        assertFailsWith<IllegalArgumentException> { ChunkCacheOffer(1, 0, 0, ByteArray(19)) }
        assertFailsWith<IllegalArgumentException> {
            ChunkCacheOfferPayload(epoch, dimension, List(65) { ChunkCacheOffer(it + 1L, 0, 0, ByteArray(20)) })
        }
        assertFailsWith<IllegalArgumentException> { ChunkCacheRetirePayload(epoch, List(129) { it + 1L }) }

        val oversizedOfferBuffer = buffer()
        try {
            oversizedOfferBuffer.writeLong(epoch.mostSignificantBits)
            oversizedOfferBuffer.writeLong(epoch.leastSignificantBits)
            oversizedOfferBuffer.writeUtf(dimension.toString(), 256)
            oversizedOfferBuffer.writeVarInt(ChunkCacheLimits.MAX_OFFER_BATCH + 1)
            assertFailsWith<IllegalArgumentException> { ChunkCacheOfferPayload(oversizedOfferBuffer) }
        } finally {
            oversizedOfferBuffer.release()
        }

        val oversizedMetadataBuffer = buffer()
        try {
            oversizedMetadataBuffer.writeLong(epoch.mostSignificantBits)
            oversizedMetadataBuffer.writeLong(epoch.leastSignificantBits)
            oversizedMetadataBuffer.writeLong(10)
            oversizedMetadataBuffer.writeUtf(dimension.toString(), 256)
            oversizedMetadataBuffer.writeInt(0)
            oversizedMetadataBuffer.writeInt(0)
            oversizedMetadataBuffer.writeBytes(ByteArray(20))
            oversizedMetadataBuffer.writeVarInt(ChunkCacheLimits.MAX_METADATA_BYTES + 1)
            assertFailsWith<IllegalArgumentException> { ChunkCacheReusePayload(oversizedMetadataBuffer) }
            assertEquals(0, oversizedMetadataBuffer.readableBytes(), "The rejected length must fail before allocating or reading metadata bytes")
        } finally {
            oversizedMetadataBuffer.release()
        }
    }

    private fun <T : Any> roundTrip(payload: T, codec: StreamCodec<RegistryFriendlyByteBuf, T>): T {
        val buffer = buffer()
        try {
            codec.encode(buffer, payload)
            buffer.readerIndex(0)
            val decoded = codec.decode(buffer)
            assertEquals(0, buffer.readableBytes())
            return decoded
        } finally {
            buffer.release()
        }
    }

    private fun buffer(): RegistryFriendlyByteBuf =
        RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY, ConnectionType.OTHER)

}
