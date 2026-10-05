package calebxzau.rdi.mc.syncchunk.network

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.SyncChunkList
import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.neoforged.neoforge.network.connection.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RSyncChunksPayloadTest {
    private fun withBuffer(block: (RegistryFriendlyByteBuf) -> Unit) {
        val rawBuffer = Unpooled.buffer()
        try {
            block(RegistryFriendlyByteBuf(rawBuffer, RegistryAccess.EMPTY, ConnectionType.OTHER))
        } finally {
            rawBuffer.release()
        }
    }

    @Test
    fun usesTheSyncChunksChannel(): Unit {
        assertEquals("rdi:sync_chunks", RSyncChunksPayload.TYPE.id().toString())
    }

    @Test
    fun streamCodecRoundTripsTheSharedListLayout(): Unit = withBuffer { buffer ->
        val chunk = SyncChunkKey("minecraft:the_nether", -5, 12)
        RSyncChunksPayload.STREAM_CODEC.encode(buffer, RSyncChunksPayload.of(SyncChunkList(listOf(chunk))))

        assertEquals(1, buffer.readVarInt())
        assertEquals(chunk.dimensionId, buffer.readUtf(512))
        assertEquals(chunk.chunkX, buffer.readInt())
        assertEquals(0, buffer.readInt())
        assertEquals(chunk.chunkZ, buffer.readInt())

        buffer.readerIndex(0)
        val decoded = RSyncChunksPayload.STREAM_CODEC.decode(buffer)
        assertEquals(listOf(chunk), decoded.list.getOrThrow().chunks)
        assertEquals(0, buffer.readableBytes())
    }

    @Test
    fun malformedListArrivesAsAFailureWithItsBytesConsumed(): Unit = withBuffer { buffer ->
        buffer.writeVarInt(SyncChunkList.MAX_ENTRY_COUNT + 1)
        buffer.writeInt(7)

        val decoded = RSyncChunksPayload.STREAM_CODEC.decode(buffer)

        assertTrue(decoded.list.isFailure)
        assertEquals(0, buffer.readableBytes())
    }
}
