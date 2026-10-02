package calebxzau.rdi.mc.client.chunkcache

import com.mojang.serialization.Lifecycle
import io.netty.buffer.Unpooled
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.ChunkPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.server.Bootstrap
import net.minecraft.core.SectionPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.biome.BiomeSpecialEffects
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.block.entity.BlockEntityType
import net.neoforged.fml.loading.LoadingModList
import net.neoforged.neoforge.network.connection.ConnectionType
import net.neoforged.neoforge.registries.RegistryBuilder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

class ClientChunkDeltaCaptureTest {
    companion object {
        init {
            if (LoadingModList.get() == null) LoadingModList.of(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            for (block in BuiltInRegistries.BLOCK) for (state in block.stateDefinition.possibleStates) {
                if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) Block.BLOCK_STATE_REGISTRY.add(state)
            }
        }

        private fun biomeRegistry(): MappedRegistry<Biome> {
            val registry = MappedRegistry(Registries.BIOME, Lifecycle.stable())
            val plains = Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
                .specialEffects(BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
                .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build()
            registry.register(Biomes.PLAINS, plains, RegistrationInfo.BUILT_IN)
            registry.freeze()
            return registry
        }
    }

    @Test
    fun blockUpdateIsDetachedOnNetworkThreadAndConsumedInOrder(): Unit {
        val handoff = ClientChunkDeltaPacketHandoff(1, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong())
        val packet = ClientboundBlockUpdatePacket(BlockPos(-17, 64, 31), Blocks.STONE.defaultBlockState())
        val network = Executors.newSingleThreadExecutor()

        try {
            assertTrue(network.submit<Long> {
                handoff.capture(packet, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong())
            }.get(5, TimeUnit.SECONDS) > 0)
            assertEquals(1, handoff.pendingEntries())
            assertTrue(handoff.pendingBytes() > 0)

            val detached = assertNotNull(handoff.consume(packet))
            assertEquals(0, handoff.pendingEntries())
            assertEquals(0L, handoff.pendingBytes())
            assertNull(handoff.consume(packet))
            assertEquals(1, detached.updates().size)

            val update = assertIs<ClientChunkCacheWrite.BlockChange>(detached.updates().single().updates().single())
            assertEquals(-2, detached.updates().single().x())
            assertEquals(1, detached.updates().single().z())
            assertEquals(BlockPos(-17, 64, 31).asLong(), update.position())
            assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), update.stateId())
            assertFalse(update.hasBlockEntity())
        } finally {
            handoff.close()
            network.shutdownNow()
        }
    }

    @Test
    fun capacityRejectsAnotherUpdateUntilTheFirstIsConsumed(): Unit {
        val handoff = ClientChunkDeltaPacketHandoff(1, 1024)
        val first = ClientboundBlockUpdatePacket(BlockPos(0, 64, 0), Blocks.STONE.defaultBlockState())
        val second = ClientboundBlockUpdatePacket(BlockPos(1, 64, 0), Blocks.DIRT.defaultBlockState())

        try {
            assertTrue(handoff.capture(first, 1024) > 0)
            assertEquals(-1, handoff.capture(second, 1024))
            assertNotNull(handoff.consume(first))
            assertTrue(handoff.capture(second, 1024) > 0)
            assertNotNull(handoff.consume(second))
        } finally {
            handoff.close()
        }
    }

    @Test
    fun blockEntityPacketTagIsCopiedBeforeVanillaCanMutateIt(): Unit {
        val tag = CompoundTag().apply { putString("marker", "before") }
        val entityTypes = RegistryBuilder(Registries.BLOCK_ENTITY_TYPE).sync(true).disableRegistrationCheck().create()
        Registry.register(entityTypes, "minecraft:chest", BlockEntityType.CHEST)
        entityTypes.freeze()
        val raw = Unpooled.buffer()
        val buffer = RegistryFriendlyByteBuf(raw,
            net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(listOf(entityTypes)), ConnectionType.OTHER)
        val packet = try {
            BlockPos.STREAM_CODEC.encode(buffer, BlockPos(-33, 71, 48))
            ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE).encode(buffer, BlockEntityType.CHEST)
            ByteBufCodecs.TRUSTED_COMPOUND_TAG.encode(buffer, tag)
            ClientboundBlockEntityDataPacket.STREAM_CODEC.decode(buffer)
        } finally {
            buffer.release()
        }
        val handoff = ClientChunkDeltaPacketHandoff(1, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong())

        try {
            assertTrue(handoff.capture(packet, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong()) > 0)
            packet.tag.putString("marker", "after")
            val captured = assertNotNull(handoff.consume(packet)).updates().single()
            assertEquals(-3, captured.x())
            assertEquals(3, captured.z())
            val update = assertIs<ClientChunkCacheWrite.BlockEntityChange>(captured.updates().single())
            assertEquals("minecraft:chest", update.type())
            assertEquals("before", assertNotNull(update.tag()).getString("marker"))
        } finally {
            handoff.close()
        }
    }

    @Test
    fun sectionPacketGroupsItsMutablePositionCallbacksIntoOneChunkDelta(): Unit {
        val section = LevelChunkSection(biomeRegistry())
        section.setBlockState(0, 0, 0, Blocks.STONE.defaultBlockState())
        section.setBlockState(1, 0, 0, Blocks.DIRT.defaultBlockState())
        val positions = it.unimi.dsi.fastutil.shorts.ShortOpenHashSet().apply {
            add(SectionPos.sectionRelativePos(BlockPos(0, 0, 0)))
            add(SectionPos.sectionRelativePos(BlockPos(1, 0, 0)))
        }
        val packet = ClientboundSectionBlocksUpdatePacket(SectionPos.of(2, 4, -3), positions, section)
        val handoff = ClientChunkDeltaPacketHandoff(1, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong())

        try {
            assertTrue(handoff.capture(packet, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong()) > 0)
            val detached = assertNotNull(handoff.consume(packet))
            assertEquals(1, detached.updates().size)
            val chunk = detached.updates().single()
            assertEquals(2, chunk.x())
            assertEquals(-3, chunk.z())
            assertEquals(2, chunk.updates().size)
            val byPosition = chunk.updates().map { assertIs<ClientChunkCacheWrite.BlockChange>(it) }
                .associateBy { it.position() }
            val first = assertNotNull(byPosition[BlockPos(32, 64, -48).asLong()])
            val second = assertNotNull(byPosition[BlockPos(33, 64, -48).asLong()])
            assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), first.stateId())
            assertEquals(Block.getId(Blocks.DIRT.defaultBlockState()), second.stateId())
        } finally {
            handoff.close()
        }
    }

    @Test
    fun biomeBuffersAreCopiedAndGroupedPerChunkInPacketOrder(): Unit {
        val first = byteArrayOf(1, 2)
        val middle = byteArrayOf(3)
        val last = byteArrayOf(4, 5, 6)
        val packet = ClientboundChunksBiomesPacket(listOf(
            ClientboundChunksBiomesPacket.ChunkBiomeData(ChunkPos(4, -2), first),
            ClientboundChunksBiomesPacket.ChunkBiomeData(ChunkPos(-1, 8), middle),
            ClientboundChunksBiomesPacket.ChunkBiomeData(ChunkPos(4, -2), last),
        ))
        val handoff = ClientChunkDeltaPacketHandoff(1, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong())

        try {
            assertTrue(handoff.capture(packet, ClientChunkSnapshot.MAX_SNAPSHOT_BYTES.toLong()) > 0)
            first[0] = 99
            last[0] = 99
            val grouped = assertNotNull(handoff.consume(packet)).updates()
            assertEquals(2, grouped.size)
            assertEquals(4, grouped[0].x())
            assertEquals(-2, grouped[0].z())
            assertEquals(-1, grouped[1].x())
            assertEquals(8, grouped[1].z())
            assertContentEquals(byteArrayOf(1, 2), assertIs<ClientChunkCacheWrite.Biomes>(grouped[0].updates()[0]).data())
            assertContentEquals(byteArrayOf(4, 5, 6), assertIs<ClientChunkCacheWrite.Biomes>(grouped[0].updates()[1]).data())
            assertContentEquals(byteArrayOf(3), assertIs<ClientChunkCacheWrite.Biomes>(grouped[1].updates().single()).data())
        } finally {
            handoff.close()
        }
    }
}
