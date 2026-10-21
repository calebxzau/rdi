package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.SharedConstants
import com.mojang.serialization.Lifecycle
import net.neoforged.fml.loading.LoadingModList
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
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
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.chunk.LevelChunkSection
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientChunkDeltaStoreTest {
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

    @TempDir
    lateinit var directory: Path

    @Test
    fun persistsCoalescedBlocksBiomesAndOrderedBlockEntityTransitionsAcrossReopen(): Unit {
        SharedConstants.tryDetectVersion()
        val key = key()
        val position = BlockPos.asLong(1, 0, 2)
        val biomes = biomeRegistry()
        val baseSection = LevelChunkSection(biomes)
        baseSection.setBlockState(1, 0, 2, Blocks.STONE.defaultBlockState())
        val base = base(key, 1, position, ClientChunkSnapshot.encodeSections(arrayOf(baseSection)))
        val biomeSection = LevelChunkSection(biomes)
        val biomeBuffer = FriendlyByteBuf(Unpooled.buffer())
        val changedBiomes = try {
            biomeSection.getBiomes().write(biomeBuffer)
            ByteArray(biomeBuffer.readableBytes()).also { biomeBuffer.readBytes(it) }
        } finally { biomeBuffer.release() }
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, base = base))
            store.accept(write(key, 2, updates = listOf(
                ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.AIR.defaultBlockState()), false),
            )))
            store.accept(write(key, 3, updates = listOf(
                ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.CHEST.defaultBlockState()), true),
            )))
            store.accept(write(key, 4, updates = listOf(
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply { putInt("partA", 1) }),
                ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.CHEST.defaultBlockState()), true),
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply { putInt("partB", 2) }),
                ClientChunkCacheWrite.Biomes(changedBiomes),
            )))
            store.flush()
        }

        ClientChunkDeltaStore(directory).use { reopened ->
            val combined = assertNotNull(reopened.read(key))
            assertEquals(Block.getId(Blocks.CHEST.defaultBlockState()), combined.blockStates.getValue(position).stateId)
            assertEquals(changedBiomes.toList(), combined.biomeData!!.toList())
            assertEquals(Blocks.CHEST.defaultBlockState(), combined.decodeSections(biomes).single().getBlockState(1, 0, 2))
            assertTrue(combined.heightmapsStale)
            // The latest full tag supersedes the removal, recreate and earlier tag; only the block it applied to remains.
            assertEquals(2, combined.replay.size)
            assertEquals(Block.getId(Blocks.CHEST.defaultBlockState()),
                assertIs<ClientChunkDeltaStore.ReplayEvent.Block>(combined.replay[0]).stateId)
            val latest = assertIs<ClientChunkDeltaStore.ReplayEvent.BlockEntity>(combined.replay[1])
            assertEquals(2, latest.tag.getInt("partB"))
            assertFalse(latest.tag.contains("partA"))
        }
    }

    @Test
    fun nullBlockEntityTagsPreservePriorDataAndLaterUpdatesAcrossReopen(): Unit {
        val key = key()
        val position = BlockPos.asLong(1, 0, 2)
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, base = base(key, 1, position)))
            store.accept(write(key, 2, updates = listOf(
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply { putInt("before", 1) }),
            )))
            store.accept(write(key, 3, updates = listOf(
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", null),
            )))
            store.accept(write(key, 4, updates = listOf(
                ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.CHEST.defaultBlockState()), true),
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", null),
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply { putInt("after", 2) }),
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag()),
            )))
            store.accept(write(key, 5, updates = listOf(
                ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.AIR.defaultBlockState()), false),
            )))
            store.flush()
        }

        ClientChunkDeltaStore(directory).use { reopened ->
            val combined = assertNotNull(reopened.read(key))
            // Empty tags never reach a block entity, so they and the superseded "before" tag are compacted away.
            assertEquals(3, combined.replay.size)
            assertIs<ClientChunkDeltaStore.ReplayEvent.Block>(combined.replay[0])
            assertEquals(2, assertIs<ClientChunkDeltaStore.ReplayEvent.BlockEntity>(combined.replay[1]).tag.getInt("after"))
            assertEquals(Block.getId(Blocks.AIR.defaultBlockState()),
                assertIs<ClientChunkDeltaStore.ReplayEvent.Block>(combined.replay[2]).stateId)
            assertEquals(Block.getId(Blocks.AIR.defaultBlockState()), combined.blockStates.getValue(position).stateId)
        }
    }

    @Test
    fun nullBlockEntityTagDoesNotStopWriterOrSubsequentTerrainReads(): Unit {
        val key = key()
        val position = BlockPos.asLong(1, 0, 2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = ClientChunkDeltaWriter(directory, failures::add, 8, 4096)
        try {
            assertTrue(writer.submit(write(key, 1, base = base(key, 1))))
            assertTrue(writer.submit(write(key, 2, updates = listOf(
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", null),
            ))))
            assertNotNull(writer.readTerrain(key).get(5, TimeUnit.SECONDS))
            assertTrue(writer.stats().accepting)
            assertTrue(writer.submit(write(key, 3, updates = listOf(
                ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.STONE.defaultBlockState()), false),
            ))))
            val terrain = assertNotNull(writer.readTerrain(key).get(5, TimeUnit.SECONDS))
            assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), terrain.blockStates.getValue(position).stateId)
        } finally {
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertTrue(failures.isEmpty(), failures.toString())
        assertEquals(3L, writer.stats().processed)
        ClientChunkDeltaStore(directory).use { reopened ->
            val terrain = assertNotNull(reopened.readTerrain(key))
            assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), terrain.blockStates.getValue(position).stateId)
        }
    }

    @Test
    fun newBaseGenerationIgnoresOldOverlayAndDeltasWithoutCurrentBaseAreIgnored(): Unit {
        SharedConstants.tryDetectVersion()
        val key = key()
        val position = BlockPos.asLong(3, 0, 4)
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, updates = listOf(ClientChunkCacheWrite.BlockChange(position,
                Block.getId(Blocks.STONE.defaultBlockState()), false))))
            assertNull(store.read(key))
            store.accept(write(key, 2, base = base(key, 2)))
            store.accept(write(key, 3, updates = listOf(ClientChunkCacheWrite.BlockChange(position,
                Block.getId(Blocks.STONE.defaultBlockState()), false))))
            store.flush()
            store.accept(write(key, 4, base = base(key, 4)))
            val combined = assertNotNull(store.read(key))
            assertTrue(combined.blockStates.isEmpty())
            assertTrue(combined.replay.isEmpty())
        }
        val deltaDir = directory.resolve("deltas/minecraft/overworld")
        assertTrue(Files.isDirectory(deltaDir))
        assertFalse(Files.isDirectory(directory.resolve("dimensions/minecraft/overworld/deltas")))
    }

    @Test
    fun terrainProjectionMatchesFullReadAcrossReopenAndDoesNotExposeOverlayStorage(): Unit {
        SharedConstants.tryDetectVersion()
        val key = key()
        val position = BlockPos.asLong(1, 0, 2)
        val biomes = biomeRegistry()
        val baseSection = LevelChunkSection(biomes)
        baseSection.setBlockState(1, 0, 2, Blocks.STONE.defaultBlockState())
        val biomeSection = LevelChunkSection(biomes)
        val biomeBuffer = FriendlyByteBuf(Unpooled.buffer())
        val changedBiomes = try {
            biomeSection.getBiomes().write(biomeBuffer)
            ByteArray(biomeBuffer.readableBytes()).also { biomeBuffer.readBytes(it) }
        } finally { biomeBuffer.release() }

        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, base = base(key, 1, sectionBytes = ClientChunkSnapshot.encodeSections(arrayOf(baseSection)))))
            store.accept(write(key, 2, updates = listOf(
                ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.CHEST.defaultBlockState()), true),
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply { putInt("x", 1) }),
                ClientChunkCacheWrite.Biomes(changedBiomes),
            )))
            store.flush()
        }

        ClientChunkDeltaStore(directory).use { reopened ->
            val full = assertNotNull(reopened.read(key))
            val terrain = assertNotNull(reopened.readTerrain(key))
            assertEquals(full.base.sequence(), terrain.base.sequence())
            assertEquals(full.blockStates, terrain.blockStates)
            assertEquals(full.biomeData!!.toList(), terrain.biomeData!!.toList())
            assertEquals(Blocks.CHEST.defaultBlockState(), terrain.decodeSections(biomes).single().getBlockState(1, 0, 2))
            assertTrue(ClientChunkSnapshot.encodeSections(full.decodeSections(biomes))
                .contentEquals(ClientChunkSnapshot.encodeSections(terrain.decodeSections(biomes))))

            (terrain.blockStates as MutableMap<*, *>).clear()
            val terrainBiomes = assertNotNull(terrain.biomeData)
            terrainBiomes[0] = (terrainBiomes[0].toInt() xor 1).toByte()
            val reread = assertNotNull(reopened.readTerrain(key))
            assertEquals(full.blockStates, reread.blockStates)
            assertEquals(changedBiomes.toList(), reread.biomeData!!.toList())
        }

        ClientChunkDeltaStore(directory).use { reopened ->
            reopened.accept(write(key, 3, base = base(key, 3, sectionBytes = ClientChunkSnapshot.encodeSections(arrayOf(baseSection)))))
            val terrain = assertNotNull(reopened.readTerrain(key))
            assertTrue(terrain.blockStates.isEmpty())
            assertNull(terrain.biomeData)
        }
    }

    @Test
    fun lruEvictionReloadsBaseBlockEntityTrackingBeforeAcceptingUpdates(): Unit {
        SharedConstants.tryDetectVersion()
        val first = key()
        val position = BlockPos.asLong(1, 0, 1)
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(first, 1, base = base(first, 1, position)))
            for (x in 1..128) {
                val other = ClientChunkRegionSink.Key(first.dimension(), x, 0)
                store.accept(write(other, x.toLong() + 1, base = base(other, x.toLong() + 1)))
            }
            store.accept(write(first, 200, updates = listOf(ClientChunkCacheWrite.BlockChange(position,
                Block.getId(Blocks.AIR.defaultBlockState()), false))))
            val events = assertNotNull(store.read(first)).replay
            assertEquals(1, events.size)
            assertIs<ClientChunkDeltaStore.ReplayEvent.Block>(events.single())
        }
    }

    @Test
    fun malformedBaseIsRejectedAndFailedAcceptDoesNotFlushPartialOverlayOnClose(): Unit {
        SharedConstants.tryDetectVersion()
        val key = key()
        ClientChunkRegionSink(directory).use { sink -> sink.writeEncoded(key, byteArrayOf(1, 2, 3, 4)) }
        ClientChunkDeltaStore(directory).use { store ->
            // A corrupt record drops only that chunk, on disk as well, so it is never offered.
            assertNull(store.read(key))
        }
        ClientChunkRegionSink(directory).use { sink -> assertNull(sink.readEncoded(key)) }

        val cleanDirectory = directory.resolve("failed-accept")
        val position = BlockPos.asLong(1, 0, 1)
        ClientChunkDeltaStore(cleanDirectory).use { store ->
            store.accept(write(key, 1, base = base(key, 1)))
            store.accept(write(key, 2, updates = listOf(ClientChunkCacheWrite.BlockChange(position,
                Block.getId(Blocks.STONE.defaultBlockState()), false))))
            store.flush()
            kotlin.test.assertFailsWith<IOException> {
                store.accept(write(key, 3, updates = listOf(
                    ClientChunkCacheWrite.BlockChange(position, Block.getId(Blocks.DIRT.defaultBlockState()), false),
                    ClientChunkCacheWrite.BlockChange(BlockPos.asLong(20, 0, 1), Block.getId(Blocks.DIRT.defaultBlockState()), false),
                )))
            }
            kotlin.test.assertFailsWith<IOException> { store.read(key) }
        }
        ClientChunkDeltaStore(cleanDirectory).use { store ->
            val combined = assertNotNull(store.read(key))
            assertEquals(Block.getId(Blocks.STONE.defaultBlockState()), combined.blockStates.getValue(position).stateId)
        }
    }

    @Test
    fun repeatedBlockEntitySyncStaysBoundedAndKeepsLatestTagAcrossReopen(): Unit {
        val key = key()
        val position = BlockPos.asLong(1, 0, 2)
        val payload = ByteArray(16 * 1024)
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, base = base(key, 1, position)))
            // 2000 x 16KiB of history exceeded the 16MiB per-chunk bound before compaction.
            for (sequence in 2L..2001L) {
                store.accept(write(key, sequence, updates = listOf(
                    ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply {
                        putLong("tick", sequence)
                        putByteArray("data", payload)
                    }),
                )))
            }
            store.flush()
        }

        ClientChunkDeltaStore(directory).use { reopened ->
            val tag = assertIs<ClientChunkDeltaStore.ReplayEvent.BlockEntity>(assertNotNull(reopened.read(key)).replay.single()).tag
            assertEquals(2001L, tag.getLong("tick"))
        }
    }

    @Test
    fun blockChangesAfterLatestTagKeepFinalStateAndLastTypeChange(): Unit {
        val key = key()
        val position = BlockPos.asLong(1, 0, 2)
        val chest = Blocks.CHEST.defaultBlockState()
        val chestEast = chest.setValue(ChestBlock.FACING, Direction.EAST)
        val chestWest = chest.setValue(ChestBlock.FACING, Direction.WEST)
        val air = Blocks.AIR.defaultBlockState()
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, base = base(key, 1, position)))
            store.accept(write(key, 2, updates = listOf(
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply { putInt("x", 1) }),
            )))
            var sequence = 3L
            repeat(100) {
                store.accept(write(key, sequence++, updates = listOf(ClientChunkCacheWrite.BlockChange(position, Block.getId(air), false))))
                store.accept(write(key, sequence++, updates = listOf(ClientChunkCacheWrite.BlockChange(position, Block.getId(chest), true))))
            }
            repeat(50) {
                store.accept(write(key, sequence++, updates = listOf(ClientChunkCacheWrite.BlockChange(position, Block.getId(chestWest), true))))
                store.accept(write(key, sequence++, updates = listOf(ClientChunkCacheWrite.BlockChange(position, Block.getId(chestEast), true))))
            }
            val replay = assertNotNull(store.read(key)).replay
            assertEquals(4, replay.size)
            assertEquals(1, assertIs<ClientChunkDeltaStore.ReplayEvent.BlockEntity>(replay[0]).tag.getInt("x"))
            // AIR -> CHEST is the last type change: the block entity is replaced, then only the facing changes.
            assertEquals(listOf(Block.getId(air), Block.getId(chest), Block.getId(chestEast)),
                replay.drop(1).map { assertIs<ClientChunkDeltaStore.ReplayEvent.Block>(it).stateId })
        }
    }

    @Test
    fun invalidDeltaDropsOnlyItsChunkAndTheStoreKeepsSaving(): Unit {
        val key = key()
        val other = ClientChunkRegionSink.Key(key.dimension(), 1, 0)
        val third = ClientChunkRegionSink.Key(key.dimension(), 2, 0)
        val stone = Block.getId(Blocks.STONE.defaultBlockState())
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, base = base(key, 1)))
            store.accept(write(other, 2, base = base(other, 2)))
            // A block above the cached sections, as a mod updating outside the world height would send.
            store.accept(write(key, 3, updates = listOf(ClientChunkCacheWrite.BlockChange(BlockPos.asLong(1, 40, 1), stone, false))))
            store.accept(write(other, 4, updates = listOf(ClientChunkCacheWrite.BlockChange(BlockPos.asLong(17, 0, 0), Int.MAX_VALUE, false))))
            assertNull(store.readTerrain(key))
            assertNull(store.readTerrain(other))
            store.accept(write(third, 5, base = base(third, 5)))
            store.accept(write(third, 6, updates = listOf(ClientChunkCacheWrite.BlockChange(BlockPos.asLong(33, 0, 0), stone, false))))
            store.flush()
        }
        ClientChunkDeltaStore(directory).use { reopened ->
            assertNull(reopened.readTerrain(key))
            assertNull(reopened.readTerrain(other))
            assertEquals(stone, assertNotNull(reopened.readTerrain(third)).blockStates.getValue(BlockPos.asLong(33, 0, 0)).stateId)
        }
    }

    @Test
    fun oversizedChunkIsDroppedWhileWriterKeepsSavingOtherChunks(): Unit {
        val key = key()
        val other = ClientChunkRegionSink.Key(key.dimension(), 1, 0)
        val position = BlockPos.asLong(1, 0, 2)
        val stone = Block.getId(Blocks.STONE.defaultBlockState())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = ClientChunkDeltaWriter(directory, failures::add, 8, 4096)
        try {
            assertTrue(writer.submit(write(key, 1, base = base(key, 1, position))))
            assertTrue(writer.submit(write(other, 2, base = base(other, 2))))
            assertTrue(writer.submit(write(key, 3, updates = listOf(
                ClientChunkCacheWrite.BlockEntityChange(position, "minecraft:chest", CompoundTag().apply {
                    putByteArray("data", ByteArray(ClientChunkSnapshot.MAX_SNAPSHOT_BYTES + 1))
                }),
            ))))
            assertNull(writer.readTerrain(key).get(5, TimeUnit.SECONDS))
            // Deltas for the dropped chunk have no base and are ignored; other chunks still save.
            assertTrue(writer.submit(write(key, 4, updates = listOf(
                ClientChunkCacheWrite.BlockChange(position, stone, false),
            ))))
            assertTrue(writer.submit(write(other, 5, updates = listOf(
                ClientChunkCacheWrite.BlockChange(BlockPos.asLong(17, 0, 0), stone, false),
            ))))
            assertNull(writer.readTerrain(key).get(5, TimeUnit.SECONDS))
            assertEquals(stone, assertNotNull(writer.readTerrain(other).get(5, TimeUnit.SECONDS)).blockStates.getValue(BlockPos.asLong(17, 0, 0)).stateId)
            assertTrue(writer.stats().accepting)
            // The next full chunk from the server starts it again.
            assertTrue(writer.submit(write(key, 6, base = base(key, 6, position))))
            assertNotNull(writer.readTerrain(key).get(5, TimeUnit.SECONDS))
        } finally {
            writer.closeAsync()
            assertTrue(writer.awaitClosed(5_000))
        }
        assertTrue(failures.isEmpty(), failures.toString())
        ClientChunkDeltaStore(directory).use { reopened ->
            assertNotNull(reopened.readTerrain(key))
            assertEquals(stone, assertNotNull(reopened.readTerrain(other)).blockStates.getValue(BlockPos.asLong(17, 0, 0)).stateId)
        }
    }

    @Test
    fun invalidateForgetsChunkOnDiskAndIgnoresUpdatesUntilNextBase(): Unit {
        val key = key()
        val other = ClientChunkRegionSink.Key(key.dimension(), 1, 0)
        val position = BlockPos.asLong(1, 0, 2)
        val stone = Block.getId(Blocks.STONE.defaultBlockState())
        ClientChunkDeltaStore(directory).use { store ->
            store.accept(write(key, 1, base = base(key, 1, position)))
            store.accept(write(other, 2, base = base(other, 2)))
            store.accept(write(key, 3, updates = listOf(ClientChunkCacheWrite.BlockChange(position, stone, false))))
            store.flush()
            store.accept(write(key, 4, updates = listOf(ClientChunkCacheWrite.Invalidate())))
            assertNull(store.readTerrain(key))
            store.accept(write(key, 5, updates = listOf(ClientChunkCacheWrite.BlockChange(position, stone, false))))
            assertNull(store.readTerrain(key))
            assertNotNull(store.readTerrain(other))
        }
        ClientChunkDeltaStore(directory).use { reopened ->
            assertNull(reopened.readTerrain(key))
            reopened.accept(write(key, 6, base = base(key, 6, position)))
            assertTrue(assertNotNull(reopened.readTerrain(key)).blockStates.isEmpty())
        }
    }

    private fun key() = ClientChunkRegionSink.Key(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 0, 0)

    private fun write(
        key: ClientChunkRegionSink.Key,
        sequence: Long,
        base: ClientChunkSnapshot.Snapshot? = null,
        updates: List<ClientChunkCacheWrite.Update> = emptyList(),
    ) = ClientChunkCacheWrite(key, sequence, sequence, base, updates, 128)

    private fun biomeRegistry(): MappedRegistry<Biome> {
        val registry = MappedRegistry(Registries.BIOME, Lifecycle.stable())
        val biome = Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
            .specialEffects(BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build()
        registry.register(Biomes.PLAINS, biome, RegistrationInfo.BUILT_IN)
        registry.freeze()
        return registry
    }

    private fun base(
        key: ClientChunkRegionSink.Key,
        sequence: Long,
        blockEntityPosition: Long? = null,
        sectionBytes: ByteArray = byteArrayOf(1, 2, 3),
    ): ClientChunkSnapshot.Snapshot {
        val entities = blockEntityPosition?.let { position ->
            val pos = BlockPos.of(position)
            listOf(ClientChunkSnapshot.BlockEntityData(pos.x, pos.y, pos.z, "minecraft:chest", CompoundTag()))
        } ?: emptyList()
        return ClientChunkSnapshot.Snapshot(key.dimension(), key.x(), key.z(), 0, 1, sequence, sequence,
            sectionBytes, CompoundTag(), entities)
    }
}
