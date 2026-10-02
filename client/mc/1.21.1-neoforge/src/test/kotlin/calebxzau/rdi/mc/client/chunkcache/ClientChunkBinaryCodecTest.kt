package calebxzau.rdi.mc.client.chunkcache

import com.mojang.serialization.Lifecycle
import io.netty.buffer.Unpooled
import net.neoforged.fml.loading.LoadingModList
import net.neoforged.neoforge.network.connection.ConnectionType
import net.minecraft.SharedConstants
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSpecialEffects
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.biome.MobSpawnSettings
import net.minecraft.world.level.block.Block
import net.minecraft.core.Registry
import net.neoforged.neoforge.registries.RegistryBuilder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.chunk.LevelChunkSection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientChunkBinaryCodecTest {
    companion object {
        init {
            // Plain JUnit has no loader discovery; vanilla fixtures use an explicit empty mod list.
            if (LoadingModList.get() == null) LoadingModList.of(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
            // NeoForge normally assigns these IDs in its registry callbacks, absent from plain JUnit.
            for (block in BuiltInRegistries.BLOCK) for (state in block.stateDefinition.possibleStates) {
                if (Block.BLOCK_STATE_REGISTRY.getId(state) < 0) Block.BLOCK_STATE_REGISTRY.add(state)
            }
        }
    }

    @Test
    fun networkCaptureOwnsPacketDataBeforeMainThreadBindsWorldMetadata(): Unit {
        val packet = packetData(byteArrayOf(3, 5, 7), twoEntities = true)
        val network = Executors.newSingleThreadExecutor { task -> Thread(task, "test-chunk-network") }
        val captured = try {
            network.submit<ClientChunkSnapshot.PacketData> {
                assertEquals("test-chunk-network", Thread.currentThread().name)
                ClientChunkSnapshot.capturePacketData(-2, 3, packet)
            }.get(5, TimeUnit.SECONDS)
        } finally {
            network.shutdownNow()
        }
        packet.heightmaps.putLongArray("WORLD_SURFACE", longArrayOf(999))
        packet.getBlockEntitiesTagsConsumer(-2, 3).accept { _, _, tag -> tag?.putString("marker", "changed") }
        packet.readBuffer.let { buffer ->
            try { buffer.setByte(0, 99) } finally { buffer.release() }
        }
        // Vanilla/Mod processing may mutate the original packet before metadata is bound.
        // A dimension transition is resolved on the main thread, after network capture.
        val snapshot = ClientChunkSnapshot.bindPacketData(
            captured, ResourceLocation.parse("minecraft:the_nether"), 0, 16, 120L, 9L,
        )
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), snapshot.dimension())
        assertEquals(0, snapshot.minSection())
        assertEquals(16, snapshot.sectionCount())
        assertEquals(9L, snapshot.sequence())
        assertEquals(snapshot.estimatedBytes(), captured.estimatedBytes())
        assertContentEquals(byteArrayOf(3, 5, 7), snapshot.sections())
        assertContentEquals(longArrayOf(1, 2), snapshot.heightmaps().getLongArray("WORLD_SURFACE"))
        assertEquals(-31, snapshot.blockEntities()[0].x())
        assertEquals(50, snapshot.blockEntities()[0].z())
        assertEquals(-28, snapshot.blockEntities()[1].x())
        assertEquals(53, snapshot.blockEntities()[1].z())
        assertEquals("original", snapshot.blockEntities()[0].tag().getString("marker"))
        assertNull(snapshot.blockEntities()[1].tag())
        val decoded = decode(encode(snapshot))
        assertEquals(snapshot.dimension(), decoded.dimension())
        assertEquals(snapshot.minSection(), decoded.minSection())
        assertContentEquals(snapshot.sections(), decoded.sections())
        assertEquals(snapshot.blockEntities(), decoded.blockEntities())
    }

    @Test
    fun handoffBoundsCopiesAndConsumesEachPacketExactlyOnce(): Unit {
        val first = packetData(byteArrayOf(1), twoEntities = false)
        val second = packetData(byteArrayOf(2), twoEntities = false)
        val weight = ClientChunkSnapshot.estimatePacket(first, 0, 0)
        val handoff = ClientChunkPacketHandoff(1, weight * 2)
        assertTrue(handoff.capture(first, 0, 0))
        assertFalse(handoff.capture(first, 0, 0))
        assertFalse(handoff.capture(second, 0, 0))
        assertEquals(1, handoff.pendingEntries())
        assertEquals(weight, handoff.pendingBytes())
        assertContentEquals(byteArrayOf(1), assertNotNull(handoff.consume(first)).sections())
        assertNull(handoff.consume(first))
        assertEquals(0, handoff.pendingEntries())
        assertEquals(0L, handoff.pendingBytes())
        assertTrue(handoff.capture(second, 0, 0))
        handoff.close()
        assertNull(handoff.consume(second))
        assertFalse(handoff.capture(first, 0, 0))
        assertTrue(handoff.isClosed())
        assertEquals(0L, handoff.pendingBytes())
    }

    @Test
    fun handoffByteLimitAndConnectionOwnershipAreIndependentOfCoordinates(): Unit {
        val first = packetData(byteArrayOf(1), twoEntities = false)
        val second = packetData(byteArrayOf(2), twoEntities = false)
        val weight = ClientChunkSnapshot.estimatePacket(first, 0, 0)
        val oldConnection = ClientChunkPacketHandoff(2, weight)
        val newConnection = ClientChunkPacketHandoff(2, weight * 2)
        assertTrue(oldConnection.capture(first, 0, 0))
        assertFalse(oldConnection.capture(second, 0, 0))
        assertNull(newConnection.consume(first))
        assertTrue(newConnection.capture(second, 0, 0))
        oldConnection.close()
        assertNull(oldConnection.consume(first))
        assertContentEquals(byteArrayOf(2), assertNotNull(newConnection.consume(second)).sections())
        assertEquals(0L, newConnection.pendingBytes())
        newConnection.close()
    }

    @Test
    fun failedNetworkCopyReleasesCapacityAndPropagatesFailure(): Unit {
        val packet = packetData(byteArrayOf(1), twoEntities = false, onCopy = {
            throw IllegalStateException("copy fixture failed")
        })
        val handoff = ClientChunkPacketHandoff(1, 1024 * 1024)
        val failure = assertFailsWith<IllegalStateException> { handoff.capture(packet, 0, 0) }
        assertEquals("copy fixture failed", failure.message)
        assertEquals(0, handoff.pendingEntries())
        assertEquals(0L, handoff.pendingBytes())
        assertTrue(handoff.capture(packetData(byteArrayOf(2), twoEntities = false), 0, 0))
        handoff.close()
    }

    @Test
    fun packetsAtSameCoordinatesStaySeparateUntilOrderedWorldBinding(): Unit {
        val first = packetData(byteArrayOf(1), twoEntities = false)
        val second = packetData(byteArrayOf(2), twoEntities = false)
        val handoff = ClientChunkPacketHandoff(2, 1024 * 1024)
        assertTrue(handoff.capture(first, 0, 0))
        assertTrue(handoff.capture(second, 0, 0))
        // The network has already seen both packets; main processes a respawn between them.
        val beforeRespawn = ClientChunkSnapshot.bindPacketData(
            assertNotNull(handoff.consume(first)), ResourceLocation.parse("minecraft:overworld"), -4, 24, 10L, 1L,
        )
        val afterRespawn = ClientChunkSnapshot.bindPacketData(
            assertNotNull(handoff.consume(second)), ResourceLocation.parse("minecraft:the_nether"), 0, 16, 11L, 2L,
        )
        assertContentEquals(byteArrayOf(1), beforeRespawn.sections())
        assertContentEquals(byteArrayOf(2), afterRespawn.sections())
        assertEquals(ResourceLocation.parse("minecraft:overworld"), beforeRespawn.dimension())
        assertEquals(ResourceLocation.parse("minecraft:the_nether"), afterRespawn.dimension())
        assertEquals(1L, beforeRespawn.sequence())
        assertEquals(2L, afterRespawn.sequence())
        assertEquals(0, handoff.pendingEntries())
        handoff.close()
    }

    @Test
    fun disconnectDuringNetworkCopyPreventsPublishingAndReleasesReservation(): Unit {
        val copying = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val packet = packetData(byteArrayOf(1), twoEntities = false, onCopy = {
            copying.countDown()
            assertTrue(releaseCopy.await(5, TimeUnit.SECONDS))
        })
        val handoff = ClientChunkPacketHandoff(1, 1024 * 1024)
        val network = Executors.newSingleThreadExecutor()
        try {
            val capture = network.submit<Boolean> { handoff.capture(packet, 0, 0) }
            assertTrue(copying.await(5, TimeUnit.SECONDS))
            assertEquals(1, handoff.pendingEntries())
            assertTrue(handoff.pendingBytes() > 0)
            handoff.close()
            releaseCopy.countDown()
            assertFalse(capture.get(5, TimeUnit.SECONDS))
            assertNull(handoff.consume(packet))
            assertEquals(0, handoff.pendingEntries())
            assertEquals(0L, handoff.pendingBytes())
        } finally {
            releaseCopy.countDown()
            network.shutdownNow()
        }
    }

    @Test
    fun realSectionEncodingPreservesStatesAndBiomesWithoutNbtRepacking(): Unit {
        val biomes = MappedRegistry(Registries.BIOME, Lifecycle.stable())
        val biome = Biome.BiomeBuilder().hasPrecipitation(true).temperature(0.8f).downfall(0.4f)
            .specialEffects(BiomeSpecialEffects.Builder().fogColor(0).waterColor(0).waterFogColor(0).skyColor(0).build())
            .mobSpawnSettings(MobSpawnSettings.EMPTY).generationSettings(BiomeGenerationSettings.EMPTY).build()
        biomes.register(Biomes.PLAINS, biome, RegistrationInfo.BUILT_IN)
        biomes.freeze()
        val section = LevelChunkSection(biomes)
        section.setBlockState(1, 2, 3, Blocks.STONE.defaultBlockState())
        section.setBlockState(4, 5, 6, Blocks.OAK_LOG.defaultBlockState())
        val bytes = ClientChunkSnapshot.encodeSections(arrayOf(section))
        val snapshot = fixture(bytes)
        val restored = ClientChunkBinaryCodec.decodeSections(decode(encode(snapshot)), biomes).single()
        for (y in 0..15) for (z in 0..15) for (x in 0..15) {
            assertEquals(section.getBlockState(x, y, z), restored.getBlockState(x, y, z))
        }
        assertEquals(section.getNoiseBiome(0, 0, 0), restored.getNoiseBiome(0, 0, 0))
        assertFailsWith<IOException> { ClientChunkBinaryCodec.decodeSections(fixture(bytes + byteArrayOf(1)), biomes) }
    }

    @Test
    fun rejectsLegacyWrongVersionTruncationAndOversizedLength(): Unit {
        val valid = encode(fixture(byteArrayOf(1)))
        assertFailsWith<IOException> { decode(byteArrayOf(10, 0, 0, 0)) }
        val wrongVersion = valid.copyOf().apply { this[5] = 99 }
        assertFailsWith<IOException> { decode(wrongVersion) }
        assertFailsWith<IOException> { decode(valid.copyOf(valid.size - 1)) }
        val malformed = ByteArrayOutputStream()
        DataOutputStream(malformed).use { out ->
            out.writeInt(ClientChunkBinaryCodec.MAGIC)
            out.writeShort(ClientChunkBinaryCodec.VERSION)
            val dimension = "minecraft:overworld".toByteArray()
            out.writeShort(dimension.size)
            out.write(dimension)
            out.writeInt(0)
            out.writeInt(0)
            out.writeInt(-4)
            out.writeInt(24)
            out.writeLong(0)
            out.writeLong(0)
            out.writeInt(Int.MAX_VALUE)
        }
        assertFailsWith<IOException> { decode(malformed.toByteArray()) }
    }

    @Test
    fun refusesOverweightAndDeepMetadataBeforeCopying(): Unit {
        val packet = packetData(byteArrayOf(1), twoEntities = false)
        packet.heightmaps.putByteArray("large", ByteArray(ClientChunkSnapshot.MAX_SNAPSHOT_BYTES))
        assertFailsWith<ClientChunkSnapshot.TooLargeException> { ClientChunkSnapshot.estimatePacket(packet, 0, 0) }
        var nested = CompoundTag()
        repeat(130) { nested = CompoundTag().apply { put("child", nested) } }
        assertFailsWith<ClientChunkSnapshot.TooLargeException> { ClientChunkSnapshot.estimateTag(nested) }
        assertTrue(ClientChunkSnapshot.estimateTag(CompoundTag()) > 0)
    }

    private fun packetData(sections: ByteArray, twoEntities: Boolean, onCopy: (() -> Unit)? = null): ClientboundLevelChunkPacketData {
        val entityTypes = RegistryBuilder(Registries.BLOCK_ENTITY_TYPE).sync(true).disableRegistrationCheck().create()
        Registry.register(entityTypes, "minecraft:chest", BlockEntityType.CHEST)
        Registry.register(entityTypes, "minecraft:sign", BlockEntityType.SIGN)
        entityTypes.freeze()
        val access = RegistryAccess.ImmutableRegistryAccess(listOf(entityTypes))
        val buffer = RegistryFriendlyByteBuf(Unpooled.buffer(), access, ConnectionType.OTHER)
        try {
            buffer.writeNbt(CompoundTag().apply { putLongArray("WORLD_SURFACE", longArrayOf(1, 2)) })
            buffer.writeVarInt(sections.size)
            buffer.writeBytes(sections)
            buffer.writeVarInt(if (twoEntities) 2 else 0)
            if (twoEntities) {
                buffer.writeByte(0x12)
                buffer.writeShort(64)
                buffer.writeVarInt(entityTypes.getId(BlockEntityType.CHEST))
                buffer.writeNbt(CompoundTag().apply { putString("marker", "original") })
                buffer.writeByte(0x45)
                buffer.writeShort(65)
                buffer.writeVarInt(entityTypes.getId(BlockEntityType.SIGN))
                buffer.writeNbt(null)
            }
            return if (onCopy == null) ClientboundLevelChunkPacketData(buffer, -2, 3)
            else object : ClientboundLevelChunkPacketData(buffer, -2, 3) {
                private var reads = 0
                override fun getReadBuffer(): net.minecraft.network.FriendlyByteBuf {
                    if (++reads == 2) onCopy()
                    return super.getReadBuffer()
                }
            }
        } finally { buffer.release() }
    }

    private fun fixture(sections: ByteArray) = ClientChunkSnapshot.Snapshot(
        ResourceLocation.parse("minecraft:overworld"), -2, 3, -4, 1, 120L, 9L,
        sections, CompoundTag(), emptyList(),
    )

    private fun encode(snapshot: ClientChunkSnapshot.Snapshot): ByteArray = ByteArrayOutputStream().also {
        ClientChunkBinaryCodec.write(DataOutputStream(it), snapshot)
    }.toByteArray()

    private fun decode(bytes: ByteArray): ClientChunkSnapshot.Snapshot =
        ClientChunkBinaryCodec.read(DataInputStream(ByteArrayInputStream(bytes)))
}
