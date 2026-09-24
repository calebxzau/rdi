package calebxzau.rdi.mc.syncchunk.network

import io.netty.buffer.Unpooled
import net.minecraft.core.RegistryAccess
import net.minecraft.network.RegistryFriendlyByteBuf
import net.neoforged.neoforge.network.connection.ConnectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RSyncChunksPayloadTest {
    @Test
    fun acceptsEmptyAndMaximumEntryCounts(): Unit {
        assertEquals(0, RSyncChunksPayload.validateEntryCount(0))
        assertEquals(65_536, RSyncChunksPayload.validateEntryCount(65_536))
    }

    @Test
    fun rejectsNegativeAndOverMaximumEntryCounts(): Unit {
        assertFailsWith<IllegalArgumentException> { RSyncChunksPayload.validateEntryCount(-1) }
        assertFailsWith<IllegalArgumentException> { RSyncChunksPayload.validateEntryCount(65_537) }
    }

    @Test
    fun wireFormatKeepsFirmSectionFourFieldLayout(): Unit {
        val entry = RSyncChunksPayload.Entry("minecraft:the_nether", -5, 12)
        val rawBuffer = Unpooled.buffer()
        try {
            val buffer = RegistryFriendlyByteBuf(rawBuffer, RegistryAccess.EMPTY, ConnectionType.OTHER)
            RSyncChunksPayload(listOf(entry)).write(buffer)

            assertEquals(1, buffer.readVarInt())
            assertEquals(entry.dimensionId, buffer.readUtf(512))
            assertEquals(entry.chunkX, buffer.readInt())
            assertEquals(0, buffer.readInt())
            assertEquals(entry.chunkZ, buffer.readInt())

            buffer.readerIndex(0)
            val decoded = RSyncChunksPayload(buffer)

            assertEquals(listOf(entry), decoded.entries)
            assertEquals(0, buffer.readableBytes())
        } finally {
            rawBuffer.release()
        }
    }
}
