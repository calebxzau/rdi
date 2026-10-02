package calebxzau.rdi.mc.chunkcache

import com.mojang.serialization.Lifecycle
import net.minecraft.SharedConstants
import net.minecraft.core.Holder
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.neoforged.fml.loading.LoadingModList
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.biome.BiomeSpecialEffects
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.StairBlock
import net.minecraft.core.Direction
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import java.io.IOException

class ChunkTerrainCodecTest {
    companion object {
        init {
            if (LoadingModList.get() == null) LoadingModList.of(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            for (block in BuiltInRegistries.BLOCK) for (state in block.stateDefinition.possibleStates) {
                if (net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(state) < 0) {
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.add(state)
                }
            }
        }
    }

    @Test
    fun semanticHashIgnoresPaletteInsertionHistory(): Unit {
        val biomes = biomeRegistry()
        val first = LevelChunkSection(biomes)
        first.setBlockState(1, 1, 1, Blocks.STONE.defaultBlockState())
        first.setBlockState(2, 1, 1, Blocks.DIRT.defaultBlockState())
        val second = LevelChunkSection(biomes)
        second.setBlockState(2, 1, 1, Blocks.DIRT.defaultBlockState())
        second.setBlockState(1, 1, 1, Blocks.STONE.defaultBlockState())

        val firstPrepared = ChunkTerrainCodec.prepare(-4, arrayOf(first), biomes)
        val secondPrepared = ChunkTerrainCodec.prepare(-4, arrayOf(second), biomes)

        assertFalse(firstPrepared.sections.contentEquals(secondPrepared.sections), "The fixture must retain different palette encodings")
        assertContentEquals(firstPrepared.hash, secondPrepared.hash)
        assertContentEquals(firstPrepared.hash, ChunkTerrainCodec.hash(-4, arrayOf(first), biomes))
        assertContentEquals(firstPrepared.hash, ChunkTerrainCodec.hash(-4, arrayOf(second), biomes))
    }

    @Test
    fun blockPropertiesAndBiomeChangesAffectSemanticHash(): Unit {
        val biomes = biomeRegistry()
        val north = LevelChunkSection(biomes)
        north.setBlockState(5, 6, 7, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH))
        val south = LevelChunkSection(biomes)
        south.setBlockState(5, 6, 7, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH))
        val northHash = ChunkTerrainCodec.prepare(0, arrayOf(north), biomes).hash
        val southHash = ChunkTerrainCodec.prepare(0, arrayOf(south), biomes).hash
        kotlin.test.assertNotEquals(northHash.toList(), southHash.toList())

        val plains = LevelChunkSection(biomes)
        val other = LevelChunkSection(biomes)
        @Suppress("UNCHECKED_CAST")
        val palette = other.getBiomes() as PalettedContainer<Holder<Biome>>
        palette.getAndSetUnchecked(0, 0, 0, biomes.getHolderOrThrow(otherBiomeKey()))
        val plainsHash = ChunkTerrainCodec.prepare(0, arrayOf(plains), biomes).hash
        val otherHash = ChunkTerrainCodec.prepare(0, arrayOf(other), biomes).hash
        kotlin.test.assertNotEquals(plainsHash.toList(), otherHash.toList())
    }

    @Test
    fun prepareRecalculatesCountsAndEncodingRoundTrips(): Unit {
        val biomes = biomeRegistry()
        val section = LevelChunkSection(biomes)
        section.getStates().getAndSetUnchecked(3, 4, 5, Blocks.STONE.defaultBlockState())
        assertEquals(true, section.hasOnlyAir(), "The unchecked mutation intentionally leaves the cached counter stale")

        val hash = ChunkTerrainCodec.hash(-2, arrayOf(section), biomes)
        assertEquals(true, section.hasOnlyAir(), "Hash-only must not recalculate or mutate section counters")
        val prepared = ChunkTerrainCodec.prepare(-2, arrayOf(section), biomes)
        assertContentEquals(hash, prepared.hash)
        val decoded = ChunkTerrainCodec.decode(prepared.sections, prepared.sectionCount, biomes)

        assertEquals(-2, prepared.minSection)
        assertEquals(1, prepared.sectionCount)
        assertEquals(false, section.hasOnlyAir())
        assertEquals(Blocks.STONE.defaultBlockState(), decoded.single().getBlockState(3, 4, 5))
        assertEquals(false, decoded.single().hasOnlyAir())
        assertFailsWith<java.io.IOException> { ChunkTerrainCodec.decode(prepared.sections.copyOf(prepared.sections.size - 1), 1, biomes) }
        assertFailsWith<java.io.IOException> { ChunkTerrainCodec.decode(prepared.sections + byteArrayOf(0), 1, biomes) }
    }

    @Test
    fun hashIncludesSectionRangeAndRejectsInvalidRanges(): Unit {
        val biomes = biomeRegistry()
        val section = LevelChunkSection(biomes)
        val original = ChunkTerrainCodec.hash(-4, arrayOf(section), biomes)
        assertFalse(original.contentEquals(ChunkTerrainCodec.hash(-3, arrayOf(section), biomes)))
        assertFalse(original.contentEquals(ChunkTerrainCodec.hash(-4, arrayOf(section, section), biomes)))
        assertFailsWith<IOException> { ChunkTerrainCodec.hash(0, emptyArray(), biomes) }
        assertFailsWith<IOException> { ChunkTerrainCodec.hash(Int.MAX_VALUE, arrayOf(section, section), biomes) }
    }

    private fun biomeRegistry(): MappedRegistry<Biome> {
        val registry = MappedRegistry(Registries.BIOME, Lifecycle.stable())
        registry.register(Biomes.PLAINS, createBiome(), RegistrationInfo.BUILT_IN)
        registry.register(otherBiomeKey(), createBiome(), RegistrationInfo.BUILT_IN)
        registry.freeze()
        return registry
    }

    private fun createBiome(): Biome = Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
        .specialEffects(BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
        .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build()

    private fun otherBiomeKey(): ResourceKey<Biome> = ResourceKey.create(
        Registries.BIOME,
        ResourceLocation.fromNamespaceAndPath("rdi_test", "other_biome"),
    )
}
