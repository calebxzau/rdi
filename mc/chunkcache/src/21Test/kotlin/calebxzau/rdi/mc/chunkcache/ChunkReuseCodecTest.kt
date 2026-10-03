package calebxzau.rdi.mc.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.SharedConstants
import net.minecraft.core.RegistryAccess
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.fml.loading.LoadingModList
import net.neoforged.neoforge.network.connection.ConnectionType
import net.neoforged.neoforge.registries.RegistryBuilder
import java.util.BitSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkReuseCodecTest {
    companion object {
        init {
            if (LoadingModList.get() == null) LoadingModList.of(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }

    @Test
    fun metadataRestorePreservesHeightmapsAndBlockEntitiesAndOmitsLight(): Unit {
        val registries = blockEntityRegistries()
        val originalSections = byteArrayOf(1, 3, 5, 7)
        val packet = packet(registries, 4, -9, originalSections, marker = 81)
        val replacementSections = byteArrayOf(9, 8, 7, 6, 5)

        val metadata = ChunkReuseCodec.metadata(packet, registries)
        val restored = ChunkReuseCodec.restore(metadata, replacementSections, registries)

        assertEquals(4, restored.x)
        assertEquals(-9, restored.z)
        val sections = restored.chunkData.getReadBuffer()
        try {
            assertContentEquals(replacementSections, ByteArray(sections.readableBytes()).also(sections::readBytes))
        } finally {
            sections.release()
        }
        assertEquals(packet.chunkData.heightmaps, restored.chunkData.heightmaps)
        assertEquals(1, readBlockEntities(restored))
        // RDI clients compute light locally, so reuse metadata never carries the server's light arrays.
        assertTrue(metadata.size < 2048)
        assertTrue(restored.lightData.skyYMask.isEmpty)
        assertTrue(restored.lightData.blockYMask.isEmpty)
        assertTrue(restored.lightData.emptySkyYMask.isEmpty)
        assertTrue(restored.lightData.emptyBlockYMask.isEmpty)
        assertTrue(restored.lightData.skyUpdates.isEmpty())
        assertTrue(restored.lightData.blockUpdates.isEmpty())
    }

    @Test
    fun restoreRejectsMalformedTrailingAndOversizedInputs(): Unit {
        val registries = blockEntityRegistries()
        val metadata = ChunkReuseCodec.metadata(packet(registries, 1, 2, byteArrayOf(4, 5), marker = 21), registries)

        assertFailsWith<java.io.IOException> { ChunkReuseCodec.restore(metadata + byteArrayOf(0), byteArrayOf(1), registries) }
        assertFailsWith<java.io.IOException> { ChunkReuseCodec.restore(metadata.copyOf(metadata.size - 1), byteArrayOf(1), registries) }
        assertFailsWith<java.io.IOException> {
            ChunkReuseCodec.restore(metadata, ByteArray(ChunkCacheLimits.MAX_SECTION_BYTES + 1), registries)
        }
        assertFailsWith<java.io.IOException> {
            ChunkReuseCodec.restore(ByteArray(ChunkCacheLimits.MAX_METADATA_BYTES + 1), byteArrayOf(1), registries)
        }
    }

    private fun packet(
        registries: RegistryAccess,
        x: Int,
        z: Int,
        sections: ByteArray,
        marker: Int,
    ): ClientboundLevelChunkWithLightPacket {
        val raw = Unpooled.buffer()
        try {
            val buffer = RegistryFriendlyByteBuf(raw, registries, ConnectionType.NEOFORGE)
            buffer.writeInt(x)
            buffer.writeInt(z)
            buffer.writeNbt(CompoundTag().apply { putInt("WORLD_SURFACE", marker) })
            buffer.writeVarInt(sections.size)
            buffer.writeBytes(sections)
            buffer.writeVarInt(1) // One block-entity update tag.
            buffer.writeByte(0x32)
            buffer.writeShort(64 + marker)
            ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE).encode(buffer, BlockEntityType.CHEST)
            buffer.writeNbt(CompoundTag().apply { putInt("cacheMarker", marker) })

            buffer.writeBitSet(BitSet.valueOf(longArrayOf(0b1010)))
            buffer.writeBitSet(BitSet.valueOf(longArrayOf(0b1100)))
            buffer.writeBitSet(BitSet.valueOf(longArrayOf(0b0010)))
            buffer.writeBitSet(BitSet.valueOf(longArrayOf(0b0001)))
            buffer.writeVarInt(1)
            buffer.writeByteArray(ByteArray(2048) { marker.toByte() })
            buffer.writeVarInt(1)
            buffer.writeByteArray(ByteArray(2048) { (marker + 1).toByte() })

            buffer.readerIndex(0)
            return ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer)
        } finally {
            raw.release()
        }
    }

    private fun readBlockEntities(packet: ClientboundLevelChunkWithLightPacket): Int {
        var count = 0
        packet.chunkData.getBlockEntitiesTagsConsumer(packet.x, packet.z).accept { position, type, tag ->
            count++
            assertEquals(16 * packet.x + 3, position.x)
            assertEquals(64 + 81, position.y)
            assertEquals(16 * packet.z + 2, position.z)
            assertEquals(BlockEntityType.CHEST, type)
            assertEquals(81, tag!!.getInt("cacheMarker"))
        }
        return count
    }

    private fun blockEntityRegistries(): RegistryAccess {
        val entityTypes = RegistryBuilder(Registries.BLOCK_ENTITY_TYPE).sync(true).disableRegistrationCheck().create()
        Registry.register(entityTypes, "minecraft:chest", BlockEntityType.CHEST)
        entityTypes.freeze()
        return RegistryAccess.ImmutableRegistryAccess(listOf(entityTypes))
    }
}
