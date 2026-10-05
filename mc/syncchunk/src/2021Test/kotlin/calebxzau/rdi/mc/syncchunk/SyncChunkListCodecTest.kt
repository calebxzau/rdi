package calebxzau.rdi.mc.syncchunk

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncChunkListCodecTest {
    private fun buffer() = FriendlyByteBuf(Unpooled.buffer())

    private fun roundTrip(list: SyncChunkList): SyncChunkList {
        val buf = buffer()
        SyncChunkListCodec.write(list, buf)
        return SyncChunkListCodec.read(buf).getOrThrow()
    }

    private fun assertRejected(buf: FriendlyByteBuf) {
        assertTrue(SyncChunkListCodec.read(buf).isFailure)
        assertEquals(0, buf.readableBytes(), "a rejected body must be skipped whole")
    }

    @Test
    fun writesTheFourFieldLayoutWithAZeroReservedField() {
        val buf = buffer()
        SyncChunkListCodec.write(SyncChunkList(listOf(SyncChunkKey("minecraft:the_nether", -3, 7))), buf)

        assertEquals(1, buf.readVarInt())
        assertEquals("minecraft:the_nether", buf.readUtf())
        assertEquals(-3, buf.readInt())
        assertEquals(0, buf.readInt())
        assertEquals(7, buf.readInt())
        assertEquals(0, buf.readableBytes())
    }

    @Test
    fun roundTripsEmptyAndFullListsInOrder() {
        assertEquals(emptyList(), roundTrip(SyncChunkList(emptyList())).chunks)

        val longDimension = "rdi:" + "a".repeat(SyncChunkList.MAX_DIMENSION_ID_LENGTH - 4)
        val chunks = List(SyncChunkList.MAX_ENTRY_COUNT) { index ->
            SyncChunkKey(if (index == 0) longDimension else "minecraft:overworld", -index, index)
        }
        assertEquals(chunks, roundTrip(SyncChunkList(chunks)).chunks)
    }

    @Test
    fun rejectsOutOfRangeCounts() {
        assertRejected(buffer().apply { writeVarInt(-1) })
        assertRejected(buffer().apply { writeVarInt(SyncChunkList.MAX_ENTRY_COUNT + 1) })
    }

    @Test
    fun rejectsTruncatedTrailingAndMalformedEntries() {
        assertRejected(buffer().apply {
            writeVarInt(1)
            writeUtf("minecraft:overworld")
            writeInt(1)
        })
        assertRejected(buffer().apply {
            SyncChunkListCodec.write(SyncChunkList(emptyList()), this)
            writeByte(0)
        })

        fun entry(dimensionId: String) = buffer().apply {
            writeVarInt(1)
            writeUtf(dimensionId)
            writeInt(0)
            writeInt(0)
            writeInt(0)
        }
        assertRejected(entry("Invalid Dimension"))
        assertRejected(entry("rdi:" + "a".repeat(SyncChunkList.MAX_DIMENSION_ID_LENGTH)))

        assertRejected(buffer().apply {
            writeVarInt(2)
            repeat(2) {
                writeUtf("minecraft:overworld")
                writeInt(4)
                writeInt(0)
                writeInt(5)
            }
        })
    }

    @Test
    fun dimensionRulesMatchMinecraftResourceLocations() {
        listOf(
            "minecraft:overworld", "removed_mod:old_dimension", "a.b-c_d:path/x.y", "overworld", ":overworld",
            "Minecraft:overworld", "minecraft:Overworld", "mine/craft:x", "minecraft:over world", "minecraft:a:b",
            "rdi:end#", "ns:", "-:_",
        ).forEach { id ->
            assertEquals(ResourceLocation.tryParse(id) != null, SyncChunkList.isValidDimensionId(id), id)
        }
    }
}
