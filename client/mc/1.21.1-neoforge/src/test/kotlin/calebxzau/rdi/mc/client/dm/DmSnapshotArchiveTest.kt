package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.ListTag
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DmSnapshotArchiveTest {
    @Test
    fun columnsDeduplicateChunkKeysAndSortDeterministically(): Unit {
        val columns = DmSnapshotArchive.columns(
            listOf(
                SyncChunkKey("minecraft:the_nether", 3, -1),
                SyncChunkKey("minecraft:overworld", -2, 5),
                SyncChunkKey("minecraft:overworld", -2, 5),
                SyncChunkKey("minecraft:overworld", 1, 0),
            ),
        )

        assertEquals(
            listOf(
                DmSnapshotArchive.Column("minecraft:overworld", -2, 5),
                DmSnapshotArchive.Column("minecraft:overworld", 1, 0),
                DmSnapshotArchive.Column("minecraft:the_nether", 3, -1),
            ),
            columns,
        )
    }

    @Test
    fun dimensionPathsUseValidatedNamespaceAndPath(): Unit {
        assertEquals("minecraft/overworld", DmSnapshotArchive.dimensionPath("minecraft:overworld"))
        assertEquals("mod/worlds/moon", DmSnapshotArchive.dimensionPath("mod:worlds/moon"))
        assertEquals("mod/worlds/moon.v2/a..b", DmSnapshotArchive.dimensionPath("mod:worlds/moon.v2/a..b"))
    }

    @Test
    fun dimensionPathsRejectTraversalMalformedSeparatorsAndInvalidCharacters(): Unit {
        val invalid = listOf(
            ":overworld", "minecraft:", "minecraft:one:two", "minecraft", "minecraft/overworld",
            "../overworld", "minecraft:../overworld", "minecraft:overworld/..", "minecraft:overworld/.",
            "minecraft:overworld//moon", "minecraft:/overworld", "minecraft:overworld/", "minecraft:over\\world",
            "Minecraft:overworld", "minecraft:世界", "minecraft:over%20world", ".:overworld", "..:overworld",
        )
        invalid.forEach { dimension ->
            assertTrue(runCatching { DmSnapshotArchive.dimensionPath(dimension) }.isFailure, dimension)
        }
    }

    @Test
    fun readRecordSupportsNegativeCoordinatesAndExternalSidecars(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-read-")
        try {
            val internalTag = CompoundTag().apply { putString("kind", "internal") }
            writeAnvilRecord(root, -1, -1, 2, compress(internalTag))
            val regionBytes = Files.readAllBytes(root.resolve("r.-1.-1.mca"))
            assertEquals("internal", DmSnapshotArchive.readRecord(root, -1, -1).getOrThrow()?.getString("kind"))
            assertContentEquals(regionBytes, Files.readAllBytes(root.resolve("r.-1.-1.mca")))

            val externalRoot = root.resolve("external")
            Files.createDirectories(externalRoot)
            val externalTag = CompoundTag().apply { putString("kind", "external") }
            writeAnvilRecord(externalRoot, -2, 3, 0x82, compress(externalTag), external = true)
            val externalRegionBytes = Files.readAllBytes(externalRoot.resolve("r.-1.0.mca"))
            val sidecarBytes = Files.readAllBytes(externalRoot.resolve("c.-2.3.mcc"))
            assertEquals("external", DmSnapshotArchive.readRecord(externalRoot, -2, 3).getOrThrow()?.getString("kind"))
            assertContentEquals(externalRegionBytes, Files.readAllBytes(externalRoot.resolve("r.-1.0.mca")))
            assertContentEquals(sidecarBytes, Files.readAllBytes(externalRoot.resolve("c.-2.3.mcc")))
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun readRecordAcceptsUnpaddedFinalInternalAndExternalRecords(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-unpadded-")
        try {
            val internalTag = CompoundTag().apply { putString("kind", "internal-unpadded") }
            writeAnvilRecord(root, -33, 31, 2, compress(internalTag), padToAllocation = false)
            val internalRegion = root.resolve("r.-2.0.mca")
            val internalBytes = Files.readAllBytes(internalRegion)
            assertEquals("internal-unpadded", DmSnapshotArchive.readRecord(root, -33, 31).getOrThrow()?.getString("kind"))
            assertContentEquals(internalBytes, Files.readAllBytes(internalRegion))

            val externalRoot = root.resolve("external")
            val externalTag = CompoundTag().apply { putString("kind", "external-unpadded") }
            writeAnvilRecord(externalRoot, -32, -33, 0x82, compress(externalTag), external = true, padToAllocation = false)
            val externalRegion = externalRoot.resolve("r.-1.-2.mca")
            val externalRegionBytes = Files.readAllBytes(externalRegion)
            val externalSidecar = externalRoot.resolve("c.-32.-33.mcc")
            val externalSidecarBytes = Files.readAllBytes(externalSidecar)
            assertEquals("external-unpadded", DmSnapshotArchive.readRecord(externalRoot, -32, -33).getOrThrow()?.getString("kind"))
            assertContentEquals(externalRegionBytes, Files.readAllBytes(externalRegion))
            assertContentEquals(externalSidecarBytes, Files.readAllBytes(externalSidecar))
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun readRecordRejectsTruncatedHeadersPayloadsAndInvalidAllocationLengths(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-truncated-")
        try {
            val payload = compress(CompoundTag().apply { putString("kind", "truncated") })
            writeAnvilRecord(root, 0, 0, 2, payload)
            val region = root.resolve("r.0.0.mca")
            val recordStart = 2L * 4096L

            java.io.RandomAccessFile(region.toFile(), "rw").use { file -> file.setLength(recordStart + 4L) }
            assertTrue(DmSnapshotArchive.readRecord(root, 0, 0).isFailure)

            writeAnvilRecord(root, 0, 0, 2, payload)
            java.io.RandomAccessFile(region.toFile(), "rw").use { file -> file.setLength(recordStart + 4L + 1L + payload.size - 1L) }
            assertTrue(DmSnapshotArchive.readRecord(root, 0, 0).isFailure)

            Files.write(region, ByteArray(8192))
            java.io.RandomAccessFile(region.toFile(), "rw").use { file ->
                file.seek(0)
                file.writeInt((3 shl 8) or 1)
            }
            assertTrue(DmSnapshotArchive.readRecord(root, 0, 0).isFailure)

            Files.write(region, ByteArray(8192 + 4 + 4093))
            java.io.RandomAccessFile(region.toFile(), "rw").use { file ->
                file.seek(0)
                file.writeInt((2 shl 8) or 1)
                file.seek(recordStart)
                file.writeInt(4093)
                file.writeByte(2)
            }
            val invalidLength = DmSnapshotArchive.readRecord(root, 0, 0)
            assertTrue(invalidLength.isFailure)
            assertTrue(invalidLength.exceptionOrNull()?.message?.contains("Invalid Anvil record length") == true)
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun readRecordRejectsMissingTruncatedAndUnknownRecords(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-invalid-")
        try {
            assertEquals(null, DmSnapshotArchive.readRecord(root, 1, 1).getOrThrow())

            Files.write(root.resolve("r.0.0.mca"), ByteArray(8191))
            val truncated = DmSnapshotArchive.readRecord(root, 1, 1)
            assertTrue(truncated.isFailure)
            assertTrue(truncated.exceptionOrNull()?.message?.contains("(1,1)") == true)

            writeAnvilRecord(root, 1, 1, 99, byteArrayOf(1))
            val unknown = DmSnapshotArchive.readRecord(root, 1, 1)
            assertTrue(unknown.isFailure)
            assertTrue(unknown.exceptionOrNull()?.message?.contains("(1,1)") == true)

            val externalRoot = root.resolve("missing-sidecar")
            writeAnvilRecord(externalRoot, 2, 2, 0x82, byteArrayOf(1), external = true)
            Files.delete(externalRoot.resolve("c.2.2.mcc"))
            val missingSidecar = DmSnapshotArchive.readRecord(externalRoot, 2, 2)
            assertTrue(missingSidecar.isFailure)
            assertTrue(missingSidecar.exceptionOrNull()?.message?.contains("(2,2)") == true)
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun prepareTerrainPreservesUnknownPayloadAndDoesNotMutateSource(): Unit {
        val section = CompoundTag().apply {
            putInt("Y", 0)
            putByte("BlockLight", 3)
            putByte("SkyLight", 4)
            putString("mod:unknown", "preserve")
        }
        val sections = ListTag().apply { add(section) }
        val source = CompoundTag().apply {
            put("Heightmaps", CompoundTag().apply { putLong("WORLD_SURFACE", 1L) })
            putByte("isLightOn", 1)
            put("sections", sections)
            put("attachments", CompoundTag().apply { putString("mod:data", "keep") })
        }
        val before = source.toString()

        val prepared = DmSnapshotArchive.prepareTerrain(source)

        assertEquals(before, source.toString())
        assertFalse(prepared.contains("Heightmaps"))
        assertFalse(prepared.contains("isLightOn"))
        val preparedSection = prepared.getList("sections", 10).getCompound(0)
        assertFalse(preparedSection.contains("BlockLight"))
        assertFalse(preparedSection.contains("SkyLight"))
        assertEquals("preserve", preparedSection.getString("mod:unknown"))
        assertEquals("keep", prepared.getCompound("attachments").getString("mod:data"))
    }

    @Test
    fun writeAndPackProduceReadableLocalArchiveWithoutOverwriting(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-pack-")
        try {
            val staging = root.resolve("staging")
            val column = DmSnapshotArchive.Column("minecraft:overworld", -1, 2)
            val tag = CompoundTag().apply { putString("payload", "local") }
            val size = DmSnapshotArchive.writeRecord(staging, column, "terrain", tag).getOrThrow()
            assertTrue(size > 0)
            val target = staging.resolve("dimensions").resolve(DmSnapshotArchive.dimensionPath(column.dimensionId))
                .resolve("terrain/-1.2.nbt")
            val bytesBefore = Files.readAllBytes(target)
            assertTrue(DmSnapshotArchive.writeRecord(staging, column, "terrain", tag).isFailure)
            assertContentEquals(bytesBefore, Files.readAllBytes(target))
            Files.writeString(staging.resolve("metadata.json"), "{}")

            val existingArchive = root.resolve("existing.zip")
            Files.writeString(existingArchive, "preserve")
            val existingBytes = Files.readAllBytes(existingArchive)
            assertTrue(DmSnapshotArchive.pack(staging, existingArchive).isFailure)
            assertContentEquals(existingBytes, Files.readAllBytes(existingArchive))

            val archive = root.resolve("snapshot.zip")
            val archiveSize = DmSnapshotArchive.pack(staging, archive).getOrThrow()
            assertEquals(Files.size(archive), archiveSize)
            val names = mutableListOf<String>()
            ZipInputStream(Files.newInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names += entry.name
                    zip.readBytes()
                    zip.closeEntry()
                }
            }
            assertEquals(names.sorted(), names)
            assertTrue(names.contains("metadata.json"))
            assertTrue(names.any { it.endsWith("/terrain/-1.2.nbt") })
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun writeRecordUsesLiteralNestedDimensionPathAndRejectsInvalidDimensionBeforeCreatingFiles(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-dimension-path-")
        try {
            val staging = root.resolve("staging")
            val column = DmSnapshotArchive.Column("mod:worlds/moon", 1, 2)
            val tag = CompoundTag().apply { putString("payload", "nested") }
            DmSnapshotArchive.writeRecord(staging, column, "terrain", tag).getOrThrow()
            val archive = root.resolve("nested.zip")
            DmSnapshotArchive.pack(staging, archive).getOrThrow()
            val entries = mutableListOf<String>()
            ZipInputStream(Files.newInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += entry.name
                    zip.readBytes()
                    zip.closeEntry()
                }
            }
            assertTrue(entries.contains("dimensions/mod/worlds/moon/terrain/1.2.nbt"))

            val invalidStaging = root.resolve("invalid-staging")
            val result = DmSnapshotArchive.writeRecord(
                invalidStaging,
                DmSnapshotArchive.Column("mod:worlds/../moon", 1, 2),
                "terrain",
                tag,
            )
            assertTrue(result.isFailure)
            assertFalse(Files.exists(invalidStaging))
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun writeRecordHonorsCallerAggregateLimit(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-bound-")
        try {
            val staging = root.resolve("staging")
            val column = DmSnapshotArchive.Column("minecraft:overworld", 0, 0)
            val tag = CompoundTag().apply { putString("payload", "larger-than-one-byte") }
            val result = DmSnapshotArchive.writeRecord(staging, column, "terrain", tag, maxBytesRemaining = 1)
            assertTrue(result.isFailure)
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    @Test
    fun selectedPackingExcludesUnchangedColumnPayloads(): Unit {
        val root = Files.createTempDirectory("dm-snapshot-selected-pack-")
        try {
            val staging = root.resolve("staging")
            val first = DmSnapshotArchive.Column("minecraft:overworld", 1, 2)
            val second = DmSnapshotArchive.Column("minecraft:overworld", 3, 4)
            DmSnapshotArchive.writeRecord(staging, first, "terrain", CompoundTag().apply { putString("payload", "first") }).getOrThrow()
            DmSnapshotArchive.writeRecord(staging, second, "terrain", CompoundTag().apply { putString("payload", "second") }).getOrThrow()
            Files.writeString(staging.resolve("metadata.json"), "{}")
            val firstPath = "dimensions/${DmSnapshotArchive.dimensionPath(first.dimensionId)}/terrain/1.2.nbt"
            val archive = root.resolve("selected.zip")
            DmSnapshotArchive.pack(staging, archive, setOf("metadata.json", firstPath)).getOrThrow()

            val entries = mutableListOf<String>()
            ZipInputStream(Files.newInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries += entry.name
                    zip.readBytes()
                    zip.closeEntry()
                }
            }
            assertEquals(listOf(firstPath, "metadata.json").sorted(), entries)
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }

    private fun compress(tag: CompoundTag): ByteArray {
        val uncompressed = ByteArrayOutputStream()
        DataOutputStream(uncompressed).use { output -> NbtIo.write(tag, output) }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { output -> output.write(uncompressed.toByteArray()) }
        return compressed.toByteArray()
    }

    private fun writeAnvilRecord(
        directory: Path,
        x: Int,
        z: Int,
        version: Int,
        payload: ByteArray,
        external: Boolean = false,
        padToAllocation: Boolean = true,
    ) {
        Files.createDirectories(directory)
        val regionX = Math.floorDiv(x, 32)
        val regionZ = Math.floorDiv(z, 32)
        val localX = Math.floorMod(x, 32)
        val localZ = Math.floorMod(z, 32)
        val sectorOffset = 2
        val recordLength = if (external) 1 else payload.size + 1
        val sectorCount = (recordLength + 4 + 4095) / 4096
        val region = directory.resolve("r.$regionX.$regionZ.mca")
        java.io.RandomAccessFile(region.toFile(), "rw").use { file ->
            val fileLength = if (padToAllocation) {
                (sectorOffset + sectorCount).toLong() * 4096L
            } else {
                sectorOffset.toLong() * 4096L + 4L + recordLength
            }
            file.setLength(fileLength)
            file.seek((localX + localZ * 32L) * 4L)
            file.writeInt((sectorOffset shl 8) or sectorCount)
            file.seek(sectorOffset.toLong() * 4096L)
            file.writeInt(recordLength)
            file.writeByte(version)
            if (!external) file.write(payload)
        }
        if (external) {
            Files.write(
                directory.resolve("c.$x.$z.mcc"),
                payload,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
    }
}
