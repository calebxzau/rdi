package calebxzau.rdi.mc.v20.server.region

import calebxzau.rdi.mc.v20.server.mixin.mRegionFileStorage
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionFileVersion

class RegionZstdCodecTest {
    @Test
    fun `NBT read validates checksum and keeps the vanilla stream contract`(): Unit {
        val tag = CompoundTag().apply { putString("rdi", "zstd") }
        val encoded = ByteArrayOutputStream()
        DataOutputStream(RegionZstdCodec.wrapOutput(encoded)).use { output ->
            NbtIo.write(tag, output)
        }
        val frame = encoded.toByteArray()

        DataInputStream(RegionZstdCodec.wrapInput(ByteArrayInputStream(frame))).use { input ->
            assertEquals(tag, NbtIo.read(input, NbtAccounter.UNLIMITED))
        }

        val damaged = frame.copyOf()
        damaged[damaged.lastIndex] = (damaged[damaged.lastIndex].toInt() xor 1).toByte()
        assertFailsWith<Exception> {
            DataInputStream(RegionZstdCodec.wrapInput(ByteArrayInputStream(damaged))).use { input ->
                NbtIo.read(input, NbtAccounter.UNLIMITED)
            }
        }
    }

    @Test
    fun `serialization failure after streamed bytes aborts commit and preserves original failure`(): Unit {
        val mixin = object : mRegionFileStorage() {}
        val handler = mRegionFileStorage::class.java.getDeclaredMethod(
            "rdi$" + "writeNbtWithAbort",
            CompoundTag::class.java,
            DataOutput::class.java,
            Operation::class.java
        ).apply { isAccessible = true }

        val failures = listOf(
            IOException("NBT serialization failed"),
            IllegalStateException("unchecked NBT serialization failure"),
            AssertionError("fatal NBT serialization failure")
        )
        for (expected in failures) {
            val target = CountingOutputStream()
            val id8Output = RegionZstdCodec.wrapOutput(target)
            val originalOutput = DataOutputStream(id8Output)
            val abortableOutput = RegionZstdCodec.wrapDataOutput(id8Output, originalOutput)
            val thrown = assertFailsWith<InvocationTargetException> {
                handler.invoke(
                    mixin,
                    CompoundTag(),
                    abortableOutput,
                    Operation<Void> { arguments ->
                        val partialChunk = ByteArray(256 * 1024).also { Random(55).nextBytes(it) }
                        (arguments[1] as DataOutput).write(partialChunk)
                        throw expected
                    }
                )
            }
            assertSame(expected, thrown.cause)
            assertTrue(target.bytes.size() > 0)
            abortableOutput.close()
            assertEquals(0, target.closeCount)
        }
    }

    @Test
    fun `region files read legacy codecs and write internal and external ID8 chunks`(): Unit {
        val root = Files.createTempDirectory("rdi-region-zstd-test")
        try {
            val zstdVersion = RegionZstdCodec.register()
            assertSame(zstdVersion, RegionFileVersion.fromId(RegionZstdCodec.COMPRESSION_ID))

            val legacyTag = CompoundTag().apply { putString("format", "legacy") }
            val legacyVersions = listOf(
                RegionFileVersion.VERSION_GZIP,
                RegionFileVersion.VERSION_DEFLATE,
                RegionFileVersion.VERSION_NONE,
            )
            for ((index, legacyVersion) in legacyVersions.withIndex()) {
                val directory = Files.createDirectory(root.resolve("legacy-$index"))
                val regionPath = directory.resolve("r.0.0.mca")
                val position = ChunkPos(index, 0)
                RegionFile(regionPath, directory, legacyVersion, false).use { region ->
                    DataOutputStream(region.getChunkDataOutputStream(position)).use { output ->
                        NbtIo.write(legacyTag, output)
                    }
                }
                // RegionFile uses the supplied version only for writes. Decoding
                // dispatches from each chunk's version byte in the region file.
                RegionFile(regionPath, directory, zstdVersion, false).use { region ->
                    val input = region.getChunkDataInputStream(position)
                    assertTrue(input != null, "legacy ID ${legacyVersion.getId()} chunk should be readable")
                    input!!.use { assertEquals(legacyTag, NbtIo.read(it)) }
                }
            }

            val directory = Files.createDirectory(root.resolve("zstd"))
            val regionPath = directory.resolve("r.0.0.mca")
            val internalPosition = ChunkPos(0, 0)
            val internalTag = CompoundTag().apply {
                putString("format", "zstd-internal")
                putInt("value", 42)
            }
            val externalPosition = ChunkPos(1, 0)
            val externalBytes = ByteArray(2 * 1024 * 1024).also { Random(947).nextBytes(it) }
            val externalTag = CompoundTag().apply {
                putString("format", "zstd-external")
                putByteArray("payload", externalBytes)
            }

            RegionFile(regionPath, directory, zstdVersion, false).use { region ->
                writeTag(region, internalPosition, internalTag)
                writeTag(region, externalPosition, externalTag)
            }
            assertEquals(0x08, versionByte(regionPath, internalPosition))
            assertEquals(0x88, versionByte(regionPath, externalPosition))
            assertTrue(Files.isRegularFile(directory.resolve("c.1.0.mcc")))

            RegionFile(regionPath, directory, zstdVersion, false).use { region ->
                readTag(region, internalPosition)?.let { assertEquals(internalTag, it) }
                    ?: throw AssertionError("internal ID8 chunk should be readable")
                readTag(region, externalPosition)?.let { assertEquals(externalTag, it) }
                    ?: throw AssertionError("external ID8 chunk should be readable")

                val chunkBufferClass = Class.forName(
                    "net.minecraft.world.level.chunk.storage.RegionFile\$ChunkBuffer"
                )
                val chunkBuffer = chunkBufferClass
                    .getDeclaredConstructor(RegionFile::class.java, ChunkPos::class.java)
                    .apply { isAccessible = true }
                    .newInstance(region, internalPosition) as OutputStream
                val streamingOutput = RegionZstdCodec.wrapOutput(chunkBuffer)
                val id8Output = RegionZstdCodec.wrapDataOutput(
                    streamingOutput,
                    DataOutputStream(streamingOutput),
                )
                id8Output.writeUTF("partial failed replacement")
                RegionZstdCodec.abort(id8Output)
                id8Output.close()
            }

            RegionFile(regionPath, directory, zstdVersion, false).use { region ->
                val restored = readTag(region, internalPosition)
                    ?: throw AssertionError("the original internal ID8 chunk should remain readable")
                assertEquals(internalTag, restored)
            }
        } finally {
            deleteTree(root)
        }
    }

    private class CountingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closeCount = 0
        override fun write(value: Int) = bytes.write(value)
        override fun write(buffer: ByteArray, offset: Int, length: Int) = bytes.write(buffer, offset, length)
        override fun close() { closeCount++ }
    }

    private fun writeTag(region: RegionFile, position: ChunkPos, tag: CompoundTag) {
        DataOutputStream(region.getChunkDataOutputStream(position)).use { output -> NbtIo.write(tag, output) }
    }

    private fun readTag(region: RegionFile, position: ChunkPos): CompoundTag? =
        region.getChunkDataInputStream(position)?.use { NbtIo.read(it) }

    private fun versionByte(regionPath: Path, position: ChunkPos): Int {
        val data = Files.readAllBytes(regionPath)
        val offsetIndex = position.regionLocalX + position.regionLocalZ * 32
        val sectorOffset = ByteBuffer.wrap(data, offsetIndex * Int.SIZE_BYTES, Int.SIZE_BYTES).int ushr 8
        return data[sectorOffset * 4096 + 4].toInt() and 0xff
    }

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
