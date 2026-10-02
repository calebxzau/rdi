package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import com.mojang.serialization.Lifecycle
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.biome.BiomeSpecialEffects
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunkSection
import net.neoforged.fml.loading.LoadingModList
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClientChunkReusePersistenceTest {
    companion object {
        init {
            if (LoadingModList.get() == null) LoadingModList.of(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            for (block in BuiltInRegistries.BLOCK) for (state in block.stateDefinition.possibleStates) {
                if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) Block.BLOCK_STATE_REGISTRY.add(state)
            }
        }
    }

    @TempDir lateinit var directory: Path

    @Test
    fun restoredTerrainBecomesCurrentSessionBaseBeforeLaterDeltasAndReopensCorrectly(): Unit {
        val biome = Biome.BiomeBuilder().hasPrecipitation(true).temperature(.8f).downfall(.4f)
            .specialEffects(BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build()
        val biomes = MappedRegistry(Registries.BIOME, Lifecycle.stable()).apply {
            register(Biomes.PLAINS, biome, RegistrationInfo.BUILT_IN)
            freeze()
        }
        val key = ClientChunkRegionSink.Key(ResourceLocation.parse("minecraft:overworld"), 0, 0)
        val position = BlockPos.asLong(1, 0, 2)
        val section = LevelChunkSection(biomes)
        section.setBlockState(1, 0, 2, Blocks.STONE.defaultBlockState())
        val initial = ClientChunkSnapshot.Snapshot(key.dimension(), 0, 0, 0, 1, 1, 1,
            ClientChunkSnapshot.encodeSections(arrayOf(section)), CompoundTag().apply { putInt("old", 1) }, emptyList())
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(ClientChunkCacheWrite(key, 1, 1, initial, emptyList(), initial.estimatedBytes()))
            store.accept(ClientChunkCacheWrite(key, 2, 2, null,
                listOf(ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.GOLD_BLOCK.defaultBlockState()), false)), 128))
        }
        val restored = ClientChunkDeltaStore(directory).use { store ->
            ChunkTerrainCodec.prepare(0, assertNotNull(store.read(key)).decodeSections(biomes), biomes)
        }
        val fresh = ClientChunkSnapshot.Snapshot(key.dimension(), 0, 0, 0, 1, 100, 1,
            restored.sections, CompoundTag().apply { putInt("fresh", 9) }, emptyList())
        val writer = ClientChunkDeltaWriter(directory, { throw AssertionError(it) }, 8, 1024 * 1024)
        try {
            assertTrue(writer.submit(ClientChunkCacheWrite(key, 1, 100, fresh, emptyList(), fresh.estimatedBytes())))
            assertTrue(writer.submit(ClientChunkCacheWrite(key, 2, 101, null,
                listOf(ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.DIAMOND_BLOCK.defaultBlockState()), false)), 128)))
            val combined = assertNotNull(writer.read(key).get(5, TimeUnit.SECONDS))
            assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), combined.decodeSections(biomes).single().getBlockState(1, 0, 2))
            assertEquals(9, combined.base.heightmaps().getInt("fresh"))
        } finally {
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        ClientChunkDeltaStore(directory).use { store ->
            val combined = assertNotNull(store.read(key))
            assertEquals(Blocks.DIAMOND_BLOCK.defaultBlockState(), combined.decodeSections(biomes).single().getBlockState(1, 0, 2))
            assertEquals(9, combined.base.heightmaps().getInt("fresh"))
        }
    }
}
