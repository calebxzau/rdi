package calebxzau.rdi.mc.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import java.io.IOException
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkReuseCodec20Test {
    private val sections = ByteArray(4096) { (it * 31).toByte() }

    /** Wire form of a 1.20.1 chunk packet; light is non-empty to prove it is not carried. */
    private fun encode(x: Int, z: Int, sectionBytes: ByteArray, withLight: Boolean): ByteArray {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            buffer.writeInt(x)
            buffer.writeInt(z)
            buffer.writeNbt(CompoundTag().apply { putLongArray("MOTION_BLOCKING", LongArray(37) { it.toLong() }) })
            buffer.writeVarInt(sectionBytes.size)
            buffer.writeBytes(sectionBytes)
            buffer.writeVarInt(0) // Block entities
            val mask = BitSet().apply { if (withLight) set(1) }
            buffer.writeBitSet(mask)
            buffer.writeBitSet(mask)
            buffer.writeBitSet(BitSet())
            buffer.writeBitSet(BitSet())
            val layers = if (withLight) 1 else 0
            buffer.writeVarInt(layers)
            repeat(layers) { buffer.writeByteArray(ByteArray(2048) { 0x7f }) }
            buffer.writeVarInt(layers)
            repeat(layers) { buffer.writeByteArray(ByteArray(2048) { 0x0f }) }
            return ByteArray(buffer.readableBytes()).also(buffer::readBytes)
        } finally {
            buffer.release()
        }
    }

    private fun packet(bytes: ByteArray): ClientboundLevelChunkWithLightPacket {
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
        try {
            return ClientboundLevelChunkWithLightPacket(buffer)
        } finally {
            buffer.release()
        }
    }

    private fun bytes(packet: ClientboundLevelChunkWithLightPacket): ByteArray {
        val buffer = FriendlyByteBuf(Unpooled.buffer())
        try {
            packet.write(buffer)
            return ByteArray(buffer.readableBytes()).also(buffer::readBytes)
        } finally {
            buffer.release()
        }
    }

    @Test
    fun metadataOmitsSectionsAndRestoresWithoutServerLight() {
        val original = packet(encode(-3, 11, sections, withLight = true))
        val metadata = ChunkReuseCodec.metadata(original)
        assertTrue(metadata.size < sections.size, "metadata must not carry section bytes")
        val restored = ChunkReuseCodec.restore(metadata, sections)
        assertEquals(-3, restored.x)
        assertEquals(11, restored.z)
        assertContentEquals(encode(-3, 11, sections, withLight = false), bytes(restored))
    }

    @Test
    fun restoreUsesTheCallerSections() {
        val metadata = ChunkReuseCodec.metadata(packet(encode(0, 0, sections, withLight = false)))
        val other = ByteArray(100) { 9 }
        assertContentEquals(encode(0, 0, other, withLight = false), bytes(ChunkReuseCodec.restore(metadata, other)))
    }

    @Test
    fun corruptMetadataIsRejected() {
        val metadata = ChunkReuseCodec.metadata(packet(encode(1, 2, sections, withLight = false)))
        assertFailsWith<IOException> { ChunkReuseCodec.restore(metadata.copyOf(metadata.size - 1), sections) }
        assertFailsWith<IOException> { ChunkReuseCodec.restore(byteArrayOf(0x7f), sections) }
        assertFailsWith<IOException> { ChunkReuseCodec.restore(metadata, ByteArray(ChunkCacheLimits.MAX_SECTION_BYTES + 1)) }
    }
}
