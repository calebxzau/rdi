package calebxzau.rdi.mc.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.core.IdMapper
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.chunk.PalettedContainer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Uses a generic palette so 1.20.1 sizing can be checked without a Forge bootstrap. */
class ChunkTerrainCodec20Test {
    private val ids = IdMapper<String>().apply {
        addMapping("air", 0)
        addMapping("stone", 1)
        addMapping("dirt", 2)
    }

    private fun container(): PalettedContainer<String> =
        PalettedContainer(ids, "air", PalettedContainer.Strategy.SECTION_STATES)

    /** Mirrors ClientboundLevelChunkPacketData: a buffer sized by getSerializedSize, written from index zero. */
    private fun vanillaBytes(containers: List<PalettedContainer<String>>): Pair<ByteArray, Int> {
        val bytes = ByteArray(containers.sumOf { it.serializedSize })
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        buffer.writerIndex(0)
        try {
            containers.forEach { it.write(buffer) }
            return bytes to buffer.writerIndex()
        } finally {
            buffer.release()
        }
    }

    @Test
    fun singleValueContainersCarryZeroPaddingThatIsAccepted(): Unit {
        val uniform = container()
        val mixed = container().apply {
            getAndSetUnchecked(1, 2, 3, "stone")
            getAndSetUnchecked(15, 15, 15, "dirt")
        }
        val (bytes, written) = vanillaBytes(listOf(uniform, mixed))
        assertEquals(1, bytes.size - written, "1.20.1 over-estimates one VarInt byte per single-value container")
        assertTrue(bytes.copyOfRange(written, bytes.size).all { it == 0.toByte() })

        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        try {
            val decodedUniform = container().apply { read(buffer) }
            val decodedMixed = container().apply { read(buffer) }
            assertEquals("air", decodedUniform.get(0, 0, 0))
            assertEquals("stone", decodedMixed.get(1, 2, 3))
            assertEquals("dirt", decodedMixed.get(15, 15, 15))
            ChunkTerrainCodec.checkTrailingPadding(buffer, 1)
        } finally {
            buffer.release()
        }
    }

    @Test
    fun trailingDataBeyondPaddingIsRejected(): Unit {
        fun check(trailing: ByteArray, sectionCount: Int) {
            val buffer = Unpooled.wrappedBuffer(trailing)
            try {
                ChunkTerrainCodec.checkTrailingPadding(buffer, sectionCount)
            } finally {
                buffer.release()
            }
        }
        check(ByteArray(0), 1)
        check(ByteArray(2), 1)
        check(ByteArray(48), 24)
        assertFailsWith<IOException> { check(byteArrayOf(0, 1), 1) }
        assertFailsWith<IOException> { check(ByteArray(3), 1) }
    }
}
