package calebxzau.rdi.anvilrw.remap

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RawRegionFileTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val target = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val patcher = NbtBytePatcher(mapOf(pcl to target))
    private val transform = ChunkTransform { _, _, nbt -> patcher.patch(nbt).getOrThrow().takeIf { it.changed }?.bytes }

    private fun chunkNbt(x: Int, z: Int, owner: UUID? = null, extra: Tag? = null): ByteArray {
        val entries = mutableListOf<Pair<String, Tag>>("xPos" to TInt(x), "zPos" to TInt(z), "label" to TString("区块😀"))
        owner?.let { entries += "Owner" to uuidInts(it) }
        extra?.let { entries += "extra" to it }
        return JavaNbt.write(TCompound(entries))
    }

    @Test
    fun unchangedRegionIsCopiedByteForByte() {
        val dir = createTempDirectory("raw-region-src")
        val out = createTempDirectory("raw-region-out")
        val region = TestRegion(dir, 0, 0)
            .chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0))
            .chunk(1, ChunkCompression.GZIP, chunkNbt(1, 0))
            .chunk(40, ChunkCompression.ZSTD, chunkNbt(8, 1), external = true)
            .write()

        val result = RawRegionFile.rewrite(region, out.resolve(region.name), transform).getOrThrow()

        assertTrue(result.copiedUnchanged)
        assertEquals(3, result.chunkCount)
        assertEquals(listOf("c.8.1.mcc"), result.externalChunkFiles)
        assertEquals(-1L, Files.mismatch(region, out.resolve(region.name)))
        assertEquals(-1L, Files.mismatch(dir.resolve("c.8.1.mcc"), out.resolve("c.8.1.mcc")))
        assertEquals(setOf(region.name, "c.8.1.mcc"), out.listDirectoryEntries().map { it.name }.toSet())
    }

    @Test
    fun changedRegionKeepsUntouchedChunksAndChunkMetadata() {
        val dir = createTempDirectory("raw-region-src")
        val out = createTempDirectory("raw-region-out")
        val builder = TestRegion(dir, -1, 2)
            .chunk(0, ChunkCompression.ZLIB, chunkNbt(-32, 64, pcl), timestamp = 111)
            .chunk(1, ChunkCompression.GZIP, chunkNbt(-31, 64), timestamp = 222)
            .chunk(5, ChunkCompression.NONE, chunkNbt(-27, 64), timestamp = 333)
            .chunk(7, ChunkCompression.ZSTD, chunkNbt(-25, 64, pcl), timestamp = 444)
            .chunk(33, ChunkCompression.ZLIB, chunkNbt(-31, 65, pcl), timestamp = 555, external = true)
        val region = builder.write()
        val before = readRegion(region)

        val result = RawRegionFile.rewrite(region, out.resolve(region.name), transform).getOrThrow()

        assertFalse(result.copiedUnchanged)
        assertEquals(3, result.changedChunkCount)
        val after = readRegion(out.resolve(region.name))
        assertEquals(before.keys, after.keys)
        for ((index, chunk) in after) {
            val original = before.getValue(index)
            assertEquals(original.typeByte, chunk.typeByte, "type of ${index}")
            assertEquals(original.timestamp, chunk.timestamp, "timestamp of ${index}")
        }
        assertContentEquals(before.getValue(1).compressed, after.getValue(1).compressed)
        assertContentEquals(before.getValue(5).compressed, after.getValue(5).compressed)
        for (index in listOf(0, 7, 33)) {
            val nbt = BoundedIo.decompressChunk(after.getValue(index).typeByte and 0x7f, after.getValue(index).compressed, Int.MAX_VALUE, "test")
            assertEquals(uuidInts(target), JavaNbt.read(nbt).entries.toMap()["Owner"], "owner of ${index}")
            assertEquals(TString("区块😀"), JavaNbt.read(nbt).entries.toMap()["label"])
        }
    }

    @Test
    fun externalChunkStaysExternal() {
        val dir = createTempDirectory("raw-region-src")
        val out = createTempDirectory("raw-region-out")
        val big = TByteArray(Random(1).nextBytes(1_100_000).toList())
        val region = TestRegion(dir, 0, 0)
            .chunk(3, ChunkCompression.NONE, chunkNbt(3, 0, pcl, big), external = true)
            .write()

        RawRegionFile.rewrite(region, out.resolve(region.name), transform).getOrThrow()

        val chunk = readRegion(out.resolve(region.name)).getValue(3)
        assertTrue(chunk.external)
        assertEquals(ChunkCompression.NONE, chunk.typeByte and 0x7f)
        assertEquals(uuidInts(target), JavaNbt.read(chunk.compressed).entries.toMap()["Owner"])
    }

    @Test
    fun changedChunkAtTheSectorThresholdBecomesExternal() {
        val dir = createTempDirectory("raw-region-src")
        val out = createTempDirectory("raw-region-out")
        val region = TestRegion(dir, 0, 0)
            .chunk(3, ChunkCompression.ZLIB, chunkNbt(3, 0, pcl, TByteArray(Random(2).nextBytes(10_000).toList())))
            .chunk(4, ChunkCompression.ZLIB, chunkNbt(4, 0, pcl))
            .write()

        RawRegionFile.rewrite(region, out.resolve(region.name), transform, RemapLimits.CHUNK_DECOMPRESSED_BYTES, RemapLimits.MCC_COMPRESSED_BYTES, 2, null) {}
            .getOrThrow()

        val chunks = readRegion(out.resolve(region.name))
        assertTrue(chunks.getValue(3).external, "a changed chunk at the threshold is stored externally")
        assertFalse(chunks.getValue(4).external)
        assertTrue(Files.exists(out.resolve("c.3.0.mcc")))
    }

    @Test
    fun strictFailures() {
        val cases = mapOf<String, (Path) -> Path>(
            "zero length" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), lengthOverride = 0).write() },
            "length past sectors" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), lengthOverride = 9000).write() },
            "offset in header" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), locationOverride = (1 shl 8) or 1).write() },
            "offset past end" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), locationOverride = (50 shl 8) or 1).write() },
            "unknown compression" to { dir -> TestRegion(dir, 0, 0).chunk(0, 5, chunkNbt(0, 0), rawPayload = byteArrayOf(1, 2, 3)).write() },
            "lz4" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.LZ4, chunkNbt(0, 0), rawPayload = byteArrayOf(1, 2, 3)).write() },
            "bad zlib" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), rawPayload = byteArrayOf(9, 9, 9, 9)).write() },
            "missing mcc" to { dir ->
                TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), external = true).write().also {
                    Files.delete(dir.resolve("c.0.0.mcc"))
                }
            },
            "invalid nbt without a hit" to { dir ->
                TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0).copyOf(20)).write()
            },
            "truncated header" to { dir -> dir.resolve("r.0.0.mca").also { Files.write(it, ByteArray(100)) } },
            "not a region name" to { dir -> dir.resolve("region.mca").also { Files.write(it, ByteArray(8192)) } },
        )
        cases.forEach { (name, build) ->
            val dir = createTempDirectory("raw-region-bad")
            val out = createTempDirectory("raw-region-bad-out")
            val region = build(dir)
            val failure = RawRegionFile.rewrite(region, out.resolve(region.name), transform).exceptionOrNull()
            assertIs<java.io.IOException>(failure, name)
            assertEquals(emptyList(), out.listDirectoryEntries(), "no output or temp files for ${name}")
        }
    }

    @Test
    fun chunkOverTheLimitFails() {
        val dir = createTempDirectory("raw-region-limit")
        val out = createTempDirectory("raw-region-limit-out")
        val region = TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0)).write()

        val failure = RawRegionFile.rewrite(region, out.resolve(region.name), transform, chunkLimit = 16).exceptionOrNull()

        assertIs<RegionFormatException>(failure)
        assertIs<RemapLimitExceededException>(failure.cause)
    }

    @Test
    fun emptyRegionFileIsCopied() {
        val dir = createTempDirectory("raw-region-empty")
        val out = createTempDirectory("raw-region-empty-out")
        val region = dir.resolve("r.0.0.mca").also { Files.write(it, ByteArray(0)) }

        val result = RawRegionFile.rewrite(region, out.resolve(region.name), transform).getOrThrow()

        assertTrue(result.copiedUnchanged)
        assertEquals(0L, Files.size(out.resolve(region.name)))
    }

    @Test
    fun droppedChunksAreNeverRead() {
        val dir = createTempDirectory("raw-region-keep")
        val out = createTempDirectory("raw-region-keep-out")
        val region = TestRegion(dir, 0, 0)
            .chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0, pcl))
            .chunk(1, ChunkCompression.ZLIB, chunkNbt(1, 0), rawPayload = byteArrayOf(9, 9, 9, 9))
            .chunk(2, ChunkCompression.LZ4, chunkNbt(2, 0), rawPayload = byteArrayOf(1, 2, 3))
            .chunk(3, ChunkCompression.NONE, chunkNbt(3, 0, extra = TByteArray(Random(3).nextBytes(10_000).toList())))
            .chunk(4, ChunkCompression.ZLIB, chunkNbt(4, 0), locationOverride = (1 shl 8) or 1)
            .chunk(5, ChunkCompression.ZLIB, chunkNbt(5, 0), locationOverride = (500 shl 8) or 1)
            .chunk(6, ChunkCompression.ZLIB, chunkNbt(6, 0).copyOf(20))
            .chunk(7, ChunkCompression.ZLIB, chunkNbt(7, 0), external = true)
            .write()
        Files.delete(dir.resolve("c.7.0.mcc"))

        val result = RawRegionFile.rewrite(
            region, out.resolve(region.name), transform, chunkLimit = 2_000, keep = ChunkFilter { x, z -> x == 0 && z == 0 },
        ).getOrThrow()

        assertTrue(result.written)
        assertEquals(1, result.chunkCount)
        assertEquals(1, result.changedChunkCount)
        assertEquals(7, result.droppedChunkCount)
        val after = readRegion(out.resolve(region.name))
        assertEquals(setOf(0), after.keys)
        val nbt = BoundedIo.decompressChunk(ChunkCompression.ZLIB, after.getValue(0).compressed, Int.MAX_VALUE, "test")
        assertEquals(uuidInts(target), JavaNbt.read(nbt).entries.toMap()["Owner"])
    }

    @Test
    fun regionWithoutKeptChunksWritesNothing() {
        val dir = createTempDirectory("raw-region-keep-none")
        val out = createTempDirectory("raw-region-keep-none-out")
        val region = TestRegion(dir, 1, -1)
            .chunk(0, ChunkCompression.ZLIB, chunkNbt(32, -32, pcl))
            .chunk(9, ChunkCompression.ZLIB, chunkNbt(41, -32), external = true)
            .write()
        val empty = createTempDirectory("raw-region-keep-empty").resolve("r.0.0.mca").also { Files.write(it, ByteArray(0)) }
        val headerOnly = createTempDirectory("raw-region-keep-header").resolve("r.0.0.mca").also { Files.write(it, ByteArray(8192)) }

        val result = RawRegionFile.rewrite(region, out.resolve(region.name), transform, keep = ChunkFilter { _, _ -> false }).getOrThrow()
        val emptyResult = RawRegionFile.rewrite(empty, out.resolve("r.0.0.mca"), transform, keep = ChunkFilter { _, _ -> true }).getOrThrow()
        val headerOnlyResult = RawRegionFile.rewrite(headerOnly, out.resolve("r.0.0.mca"), transform, keep = ChunkFilter { _, _ -> true }).getOrThrow()

        assertFalse(result.written)
        assertEquals(0, result.chunkCount)
        assertEquals(2, result.droppedChunkCount)
        assertFalse(emptyResult.written)
        assertFalse(headerOnlyResult.written)
        assertEquals(emptyList(), out.listDirectoryEntries(), "no output, .mcc or temp files")
    }

    @Test
    fun keptChunksKeepBytesIndexTimestampAndType() {
        val dir = createTempDirectory("raw-region-keep-meta")
        val out = createTempDirectory("raw-region-keep-meta-out")
        val region = TestRegion(dir, -1, 0)
            .chunk(0, ChunkCompression.GZIP, chunkNbt(-32, 0), timestamp = 111)
            .chunk(1, ChunkCompression.ZLIB, chunkNbt(-31, 0), timestamp = 222)
            .chunk(37, ChunkCompression.ZSTD, chunkNbt(-27, 1), timestamp = 333)
            .chunk(40, ChunkCompression.ZLIB, chunkNbt(-24, 1), timestamp = 444, external = true)
            .chunk(41, ChunkCompression.ZLIB, chunkNbt(-23, 1), timestamp = 555, external = true)
            .write()
        val before = readRegion(region)
        val kept = setOf(-32 to 0, -27 to 1, -24 to 1)

        val result = RawRegionFile.rewrite(region, out.resolve(region.name), transform, keep = ChunkFilter { x, z -> (x to z) in kept })
            .getOrThrow()

        assertFalse(result.copiedUnchanged, "dropping chunks rewrites the region")
        assertEquals(3, result.chunkCount)
        assertEquals(0, result.changedChunkCount)
        assertEquals(2, result.droppedChunkCount)
        assertEquals(listOf("c.-24.1.mcc"), result.externalChunkFiles)
        val bytes = Files.readAllBytes(out.resolve(region.name))
        val after = readRegion(out.resolve(region.name))
        assertEquals(setOf(0, 37, 40), after.keys)
        for ((index, chunk) in after) {
            val original = before.getValue(index)
            assertEquals(original.typeByte, chunk.typeByte, "type of ${index}")
            assertEquals(original.timestamp, chunk.timestamp, "timestamp of ${index}")
            assertEquals(original.external, chunk.external, "external flag of ${index}")
            assertContentEquals(original.compressed, chunk.compressed, "bytes of ${index}")
        }
        for (index in listOf(1, 41)) {
            assertEquals(0, ByteBuffer.wrap(bytes, index * 4, 4).int, "location of dropped ${index}")
            assertEquals(0, ByteBuffer.wrap(bytes, 4096 + index * 4, 4).int, "timestamp of dropped ${index}")
        }
        assertEquals(setOf(region.name, "c.-24.1.mcc"), out.listDirectoryEntries().map { it.name }.toSet(), "the dropped chunk's .mcc is not copied")
    }

    @Test
    fun keepingEveryChunkStillCopiesAnUnchangedRegion() {
        val dir = createTempDirectory("raw-region-keep-all")
        val out = createTempDirectory("raw-region-keep-all-out")
        val region = TestRegion(dir, 0, 0)
            .chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0))
            .chunk(1, ChunkCompression.GZIP, chunkNbt(1, 0))
            .write()

        val result = RawRegionFile.rewrite(region, out.resolve(region.name), transform, keep = ChunkFilter { _, _ -> true }).getOrThrow()

        assertTrue(result.copiedUnchanged)
        assertEquals(0, result.droppedChunkCount)
        assertEquals(-1L, Files.mismatch(region, out.resolve(region.name)))
    }

    @Test
    fun keptChunksStayStrict() {
        val cases = mapOf<String, (Path) -> Path>(
            "bad zlib" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), rawPayload = byteArrayOf(9, 9, 9, 9)).write() },
            "offset in header" to { dir -> TestRegion(dir, 0, 0).chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0), locationOverride = (1 shl 8) or 1).write() },
            "truncated header" to { dir -> dir.resolve("r.0.0.mca").also { Files.write(it, ByteArray(100)) } },
        )
        cases.forEach { (name, build) ->
            val dir = createTempDirectory("raw-region-keep-bad")
            val out = createTempDirectory("raw-region-keep-bad-out")
            val region = build(dir)
            val failure = RawRegionFile.rewrite(region, out.resolve(region.name), transform, keep = ChunkFilter { x, z -> x == 0 && z == 0 })
                .exceptionOrNull()
            assertIs<java.io.IOException>(failure, name)
            assertEquals(emptyList(), out.listDirectoryEntries(), "no output or temp files for ${name}")
        }
    }

    @Test
    fun compressionTypesAreReadFromChunkHeaders() {
        val dir = createTempDirectory("raw-region-types")
        val region = TestRegion(dir, 0, 0)
            .chunk(0, ChunkCompression.ZLIB, chunkNbt(0, 0))
            .chunk(1, ChunkCompression.ZSTD, chunkNbt(1, 0))
            .chunk(2, ChunkCompression.ZSTD, chunkNbt(2, 0), external = true)
            .chunk(3, ChunkCompression.LZ4, chunkNbt(3, 0), rawPayload = byteArrayOf(1, 2, 3))
            .chunk(4, ChunkCompression.ZLIB, chunkNbt(4, 0), locationOverride = (500 shl 8) or 1)
            .write()

        assertEquals(
            mapOf(ChunkCompression.ZLIB to 1, ChunkCompression.ZSTD to 2, ChunkCompression.LZ4 to 1),
            RawRegionFile.compressionTypes(region).getOrThrow(),
        )
        assertEquals(emptyMap(), RawRegionFile.compressionTypes(dir.resolve("r.1.0.mca").also { Files.write(it, ByteArray(0)) }).getOrThrow())
    }

    @Test
    fun parallelismIsBoundedByMemoryAndCpus() {
        val limit = RemapLimits.CHUNK_DECOMPRESSED_BYTES.toLong()
        assertEquals(1, RemapLimits.regionParallelism(memoryBudgetBytes = 0, cpuCount = 8))
        assertEquals(1, RemapLimits.regionParallelism(memoryBudgetBytes = limit, cpuCount = 8))
        assertEquals(4, RemapLimits.regionParallelism(memoryBudgetBytes = 8 * limit, cpuCount = 8))
        assertEquals(2, RemapLimits.regionParallelism(memoryBudgetBytes = 8 * limit, cpuCount = 2))
        assertEquals(1, RemapLimits.regionParallelism(memoryBudgetBytes = 8 * limit, cpuCount = 0))
    }

    private class RawChunk(val typeByte: Int, val compressed: ByteArray, val timestamp: Int, val external: Boolean)

    private fun readRegion(path: Path): Map<Int, RawChunk> {
        val bytes = Files.readAllBytes(path)
        val chunks = HashMap<Int, RawChunk>()
        val regionMatch = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""").matchEntire(path.name)!!
        val rx = regionMatch.groupValues[1].toInt()
        val rz = regionMatch.groupValues[2].toInt()
        for (i in 0 until 1024) {
            val location = ByteBuffer.wrap(bytes, i * 4, 4).int
            if (location == 0) continue
            val start = (location ushr 8) * 4096
            val length = ByteBuffer.wrap(bytes, start, 4).int
            val typeByte = bytes[start + 4].toInt() and 0xff
            val timestamp = ByteBuffer.wrap(bytes, 4096 + i * 4, 4).int
            val external = typeByte and 0x80 != 0
            val compressed = if (external) {
                Files.readAllBytes(path.resolveSibling("c.${rx * 32 + (i and 31)}.${rz * 32 + (i shr 5)}.mcc"))
            } else {
                bytes.copyOfRange(start + 5, start + 4 + length)
            }
            chunks[i] = RawChunk(typeByte, compressed, timestamp, external)
        }
        return chunks
    }
}
