package calebxzau.rdi.mc.chunkcache.network

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkCachePayloads20Test {
    private val epoch = UUID(12, 34)
    private val dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
    private val hash = ByteArray(20) { it.toByte() }

    private fun <T : ChunkCachePayload> roundTrip(payload: T, decode: (FriendlyByteBuf) -> T): T {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            payload.write(buffer)
            return decode(buffer).also { assertEquals(0, buffer.readableBytes()) }
        } finally {
            buffer.release()
        }
    }

    @Test
    fun allPayloadsRoundTripTheirFields() {
        val context = roundTrip(ChunkCacheContextPayload(epoch, dimension)) { ChunkCacheContextPayload(it) }
        assertEquals(epoch, context.epoch)
        assertEquals(dimension, context.dimension)

        val offer = roundTrip(ChunkCacheOfferPayload(epoch, dimension, listOf(ChunkCacheOffer(1, -2, 7, hash)))) {
            ChunkCacheOfferPayload(it)
        }
        assertEquals(epoch, offer.epoch)
        assertEquals(dimension, offer.dimension)
        val entry = offer.entries.single()
        assertEquals(1L, entry.id)
        assertEquals(-2, entry.x)
        assertEquals(7, entry.z)
        assertContentEquals(hash, entry.hash)

        assertEquals(listOf(1L, 2L), roundTrip(ChunkCacheRetirePayload(epoch, listOf(1, 2))) { ChunkCacheRetirePayload(it) }.ids)
        assertEquals(listOf(3L), roundTrip(ChunkCacheCancelPayload(epoch, listOf(3))) { ChunkCacheCancelPayload(it) }.ids)

        val result = roundTrip(ChunkCacheResultPayload(epoch, 4, -5, 6, hash, false)) { ChunkCacheResultPayload(it) }
        assertEquals(4L, result.id)
        assertEquals(-5, result.x)
        assertEquals(6, result.z)
        assertContentEquals(hash, result.hash)
        assertEquals(false, result.success)

        val metadata = byteArrayOf(3, 5, 7, 9)
        val reuse = roundTrip(ChunkCacheReusePayload(epoch, 8, dimension, 9, -10, hash, metadata)) { ChunkCacheReusePayload(it) }
        assertEquals(8L, reuse.id)
        assertEquals(dimension, reuse.dimension)
        assertEquals(9, reuse.x)
        assertEquals(-10, reuse.z)
        assertContentEquals(hash, reuse.hash)
        assertContentEquals(metadata, reuse.metadata)
    }

    @Test
    fun oversizedListsAreRejectedOnRead() {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ChunkCachePayloads.writeEpoch(buffer, epoch)
            ChunkCachePayloads.writeDimension(buffer, dimension)
            buffer.writeVarInt(ChunkCacheLimits.MAX_OFFER_BATCH + 1)
            assertFailsWith<IllegalArgumentException> { ChunkCacheOfferPayload(buffer) }
        } finally {
            buffer.release()
        }
    }

    @Test
    fun invalidIdsAndHashesAreRejected() {
        assertFailsWith<IllegalArgumentException> { ChunkCacheOffer(0, 0, 0, hash) }
        assertFailsWith<IllegalArgumentException> { ChunkCacheOffer(1, 0, 0, ByteArray(19)) }
        assertFailsWith<IllegalArgumentException> { ChunkCacheRetirePayload(epoch, List(ChunkCacheLimits.MAX_OFFERS + 1) { it + 1L }) }
        assertFailsWith<IllegalArgumentException> {
            ChunkCacheReusePayload(epoch, 1, dimension, 0, 0, hash, ByteArray(ChunkCacheLimits.MAX_METADATA_BYTES + 1))
        }
    }

    @Test
    fun largestServerboundMessagesFitVanillaLimit() {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            ChunkCacheOfferPayload(epoch, dimension, List(ChunkCacheLimits.MAX_OFFER_BATCH) { ChunkCacheOffer(it + 1L, it, -it, hash) }).write(buffer)
            // Serverbound custom payloads are limited to 32767 bytes in 1.20.1.
            assertTrue(buffer.readableBytes() < 32767)
            buffer.clear()
            ChunkCacheCancelPayload(epoch, List(ChunkCacheLimits.MAX_OFFERS) { it + 1L }).write(buffer)
            assertTrue(buffer.readableBytes() < 32767)
        } finally {
            buffer.release()
        }
    }
}
