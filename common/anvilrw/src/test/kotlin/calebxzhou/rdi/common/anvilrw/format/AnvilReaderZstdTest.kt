package calebxzhou.rdi.common.anvilrw.format

import com.github.luben.zstd.Zstd
import calebxzhou.rdi.common.anvilrw.core.ChunkPayload
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.io.RandomAccessFile

class AnvilReaderZstdTest {
    @Test
    fun readsInternalId8Chunk() {
        val directory = createTempDirectory("anvilrw-zstd").toFile()
        val region = File(directory, "r.0.0.mca")
        writeRegion(region, 0x08, Zstd.compress(zstdFixture(), 6))

        AnvilReader(region).use { reader ->
            val chunk = reader.readChunk(0, 0)!!
            assertEquals(0, chunk.x)
            assertEquals(0, chunk.z)
            assertEquals(3953, chunk.dataVersion)
        }
    }

    @Test
    fun readsExternalId8Chunk() {
        val directory = createTempDirectory("anvilrw-zstd-external").toFile()
        val region = File(directory, "r.0.0.mca")
        val compressed = Zstd.compress(zstdFixture(), 6)
        writeRegion(region, 0x88, compressed, external = true)
        Files.write(File(directory, "c.0.0.mcc").toPath(), compressed)

        AnvilReader(region).use { reader ->
            val chunk = reader.readChunk(0, 0)!!
            assertEquals(0, chunk.x)
            assertEquals(0, chunk.z)
            assertEquals(3953, chunk.dataVersion)
        }
    }

    @Test
    fun missingExternalSidecarPropagatesFromReadChunk() {
        val directory = createTempDirectory("anvilrw-zstd-missing").toFile()
        val region = File(directory, "r.0.0.mca")
        writeRegion(region, 0x88, ByteArray(0), external = true)

        AnvilReader(region).use { reader ->
            assertFailsWith<Exception> { reader.readChunk(0, 0) }
        }
    }

    @Test
    fun oversizedExternalSidecarIsRejectedBeforeReading() {
        val directory = createTempDirectory("anvilrw-zstd-oversized").toFile()
        val region = File(directory, "r.0.0.mca")
        writeRegion(region, 0x88, ByteArray(0), external = true)
        RandomAccessFile(File(directory, "c.0.0.mcc"), "rw").use { sidecar ->
            sidecar.setLength(ChunkPayload.MAX_ZSTD_COMPRESSED_BYTES.toLong() + 1)
        }

        assertFailsWith<Exception> {
            AnvilReader(region).use { it.readChunk(0, 0) }
        }
    }

    @Test
    fun zstdDecompressedBoundIsEnforced() {
        val compressed = Zstd.compress(ByteArray(64) { 1 }, 6)
        assertFailsWith<Exception> {
            ChunkPayload.decompressZstd(compressed, 8)
        }
    }

    @Test
    fun externallyReadChunkCannotBeWritten() {
        val directory = createTempDirectory("anvilrw-zstd-write").toFile()
        val regionFile = File(directory, "r.0.0.mca")
        val compressed = Zstd.compress(zstdFixture(), 6)
        writeRegion(regionFile, 0x88, compressed, external = true)
        Files.write(File(directory, "c.0.0.mcc").toPath(), compressed)
        val region = AnvilReader(regionFile).use { it.readRegion() }
        val destination = File(directory, "rewritten.mca")
        assertFailsWith<IllegalArgumentException> {
            AnvilWriter(destination).use { it.writeRegion(region) }
        }
    }

    private fun writeRegion(region: File, compression: Int, sidecar: ByteArray, external: Boolean = false) {
        val bytes = ByteArray(8192 + 4096)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        header.putInt(2 shl 8 or 1)
        header.position(8192)
        if (external) {
            header.putInt(1)
            header.put(compression.toByte())
        } else {
            header.putInt(sidecar.size + 1)
            header.put(compression.toByte())
            header.put(sidecar)
        }
        Files.write(region.toPath(), bytes)
    }

    private fun zstdFixture(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeByte(10)
            output.writeUTF("")
            output.writeByte(3); output.writeUTF("xPos"); output.writeInt(0)
            output.writeByte(3); output.writeUTF("zPos"); output.writeInt(0)
            output.writeByte(3); output.writeUTF("DataVersion"); output.writeInt(3953)
            output.writeByte(0)
        }
        bytes.toByteArray()
    }
}
