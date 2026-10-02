package calebxzau.rdi.mc.client.chunkcache

import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import org.junit.jupiter.api.io.TempDir
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClientChunkRegionSinkTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun writesId8AndOverwritesNegativeCoordinateChunkWithoutLosingNeighbor(): Unit {
        SharedConstants.tryDetectVersion()
        val dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        val key = ClientChunkRegionSink.Key(dimension, -1, 0)
        val neighbor = ClientChunkRegionSink.Key(dimension, -2, 0)
        ClientChunkRegionSink(directory).use { sink ->
            sink.write(key, snapshot(-1, 0, "old"))
            sink.write(neighbor, snapshot(-2, 0, "neighbor"))
            sink.write(key, snapshot(-1, 0, "new"))
        }
        val folder = dimensionPath(dimension)
        val file = folder.resolve("r.-1.0.mca")
        assertTrue(Files.isRegularFile(file))
        val bytes = Files.readAllBytes(file)
        val offset = (31 and 31) * 4
        val sector = ((bytes[offset].toInt() and 255) shl 16) or
                ((bytes[offset + 1].toInt() and 255) shl 8) or (bytes[offset + 2].toInt() and 255)
        assertEquals(8, bytes[sector * 4096 + 4].toInt() and 255)
        RegionFile(RegionStorageInfo("test", Level.OVERWORLD, "chunk"), file, folder, false).use { region ->
            assertEquals("new", read(region, ChunkPos(-1, 0)).blockEntities().single().tag().getString("marker"))
            assertEquals("neighbor", read(region, ChunkPos(-2, 0)).blockEntities().single().tag().getString("marker"))
        }
    }

    @Test
    fun oversizedChunkUsesZstdSidecarAndReopens(): Unit {
        SharedConstants.tryDetectVersion()
        val dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        val payload = Random(42).nextBytes(1_100_000)
        val value = snapshot(0, 0, "large", payload)
        ClientChunkRegionSink(directory).use { sink ->
            sink.write(ClientChunkRegionSink.Key(dimension, 0, 0), value)
        }
        val folder = dimensionPath(dimension)
        val file = folder.resolve("r.0.0.mca")
        assertTrue(Files.isRegularFile(folder.resolve("c.0.0.mcc")))
        val bytes = Files.readAllBytes(file)
        val sector = ((bytes[0].toInt() and 255) shl 16) or
                ((bytes[1].toInt() and 255) shl 8) or (bytes[2].toInt() and 255)
        assertEquals(0x88, bytes[sector * 4096 + 4].toInt() and 255)
        RegionFile(RegionStorageInfo("test", Level.OVERWORLD, "chunk"), file, folder, false).use { region ->
            assertContentEquals(payload, read(region, ChunkPos(0, 0)).sections())
        }
    }

    @Test
    fun dimensionDirectoriesUseNamespaceAndNestedPath(): Unit {
        val overworld = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        val custom = ResourceLocation.fromNamespaceAndPath("example", "deep/caves")
        ClientChunkRegionSink(directory).use { sink ->
            assertEquals(directory.toAbsolutePath().resolve("dimensions/minecraft/overworld"), sink.directory(overworld))
            assertEquals(directory.toAbsolutePath().resolve("dimensions/example/deep/caves"), sink.directory(custom))
            assertNotEquals(sink.directory(overworld), sink.directory(custom))
            for (path in listOf("../overworld", "deep/../../overworld", "/overworld", "deep//caves", "deep/./caves", "overworld.")) {
                assertFailsWith<IOException> { sink.directory(ResourceLocation.fromNamespaceAndPath("example", path)) }
            }
            assertFailsWith<IOException> { sink.directory(ResourceLocation.fromNamespaceAndPath("..", "overworld")) }
        }
    }

    @Test
    fun failedRecordEncodingDoesNotReplaceExistingChunk(): Unit {
        SharedConstants.tryDetectVersion()
        val dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        val key = ClientChunkRegionSink.Key(dimension, 4, -5)
        ClientChunkRegionSink(directory).use { sink ->
            sink.write(key, snapshot(4, -5, "preserved"))
            val invalid = snapshot(4, -5, "invalid").copyWithBlockEntityType("x".repeat(2_049))
            assertFailsWith<IOException> { sink.write(key, invalid) }
        }
        val folder = dimensionPath(dimension)
        RegionFile(RegionStorageInfo("test", Level.OVERWORLD, "chunk"), folder.resolve("r.0.-1.mca"), folder, false).use { region ->
            assertEquals("preserved", read(region, ChunkPos(4, -5)).blockEntities().single().tag().getString("marker"))
        }
    }

    @Test
    fun keyMustMatchSnapshotBeforeOpeningRegionFile(): Unit {
        SharedConstants.tryDetectVersion()
        val dimension = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")
        ClientChunkRegionSink(directory).use { sink ->
            assertFailsWith<IOException> {
                sink.write(ClientChunkRegionSink.Key(dimension, 1, 2), snapshot(1, 3, "mismatch"))
            }
        }
        assertFalse(Files.exists(directory.resolve("dimensions")))
    }

    private fun dimensionPath(dimension: ResourceLocation): Path =
        directory.toAbsolutePath().resolve("dimensions/${dimension.namespace}/${dimension.path}")

    private fun read(region: RegionFile, position: ChunkPos): ClientChunkSnapshot.Snapshot =
        region.getChunkDataInputStream(position)!!.use { ClientChunkBinaryCodec.read(DataInputStream(it)) }

    private fun snapshot(x: Int, z: Int, marker: String, sections: ByteArray = byteArrayOf(1, 2, 3)): ClientChunkSnapshot.Snapshot {
        val tag = CompoundTag().apply {
            putString("marker", marker)
        }
        val blockEntity = ClientChunkSnapshot.BlockEntityData(x * 16, 64, z * 16, "minecraft:chest", tag)
        return ClientChunkSnapshot.Snapshot(
            ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
            x,
            z,
            0,
            1,
            123L,
            1L,
            sections,
            CompoundTag(),
            listOf(blockEntity),
        )
    }

    private fun ClientChunkSnapshot.Snapshot.copyWithBlockEntityType(type: String): ClientChunkSnapshot.Snapshot =
        ClientChunkSnapshot.Snapshot(
            dimension(), x(), z(), minSection(), sectionCount(), gameTime(), sequence(), sections(), heightmaps(),
            blockEntities().map { ClientChunkSnapshot.BlockEntityData(it.x(), it.y(), it.z(), type, it.tag()) },
        )

}
