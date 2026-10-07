package calebxzau.rdi.anvilrw.remap

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Rewrites the NBT of one region chunk. Returns null to keep the chunk unchanged. */
fun interface ChunkTransform {
    /**
     * Called for every chunk, so it must validate the NBT structurally even when it changes nothing.
     * Throwing aborts the rewrite.
     */
    fun transform(chunkX: Int, chunkZ: Int, nbt: ByteArray): ByteArray?
}

/** Decides from the region header alone whether a chunk is kept. Dropped chunks are never read. */
fun interface ChunkFilter {
    fun keep(chunkX: Int, chunkZ: Int): Boolean
}

data class RegionRewriteResult(
    /** Kept chunks present in the source. Without a [ChunkFilter], every chunk present. */
    val chunkCount: Int,
    val changedChunkCount: Int,
    /** True when the region and its external chunk files were copied byte-for-byte. */
    val copiedUnchanged: Boolean,
    /**
     * Names of the `c.<x>.<z>.mcc` files of kept chunks, written next to the target. The `.mcc`
     * files of dropped chunks are unknown, because dropped chunks are never read.
     */
    val externalChunkFiles: List<String>,
    /** Chunks present in the source that the [ChunkFilter] dropped. */
    val droppedChunkCount: Int = 0,
    /** False when no kept chunk is present; then nothing is written to the target. */
    val written: Boolean = true,
)

/**
 * Strict, payload-level reading and writing of Anvil region files (`r.<x>.<z>.mca`).
 *
 * Unlike `AnvilReader`, nothing is ever replaced with an empty chunk: an invalid location entry, a
 * bad length, an unsupported or unknown compression, a decompression failure, a decompressed size
 * over the limit, a missing external chunk file, or a failing [ChunkTransform] all fail the rewrite.
 * Overlapping sectors and trailing partial sectors are tolerated, as Minecraft tolerates them.
 *
 * Unchanged chunks keep their original compressed bytes; changed chunks keep their index, timestamp
 * and compression type. If no chunk changes, the region and its `.mcc` files are copied unchanged.
 *
 * With a [ChunkFilter], strictness applies to kept chunks only: a dropped chunk is never read,
 * decompressed or validated, not even its location entry, and its `.mcc` file is not copied. The
 * header itself must still be complete.
 */
object RawRegionFile {
    private const val SECTOR = 4096
    private const val HEADER = SECTOR * 2
    private const val CHUNKS = 1024
    private const val EXTERNAL_FLAG = 0x80
    private const val EXTERNAL_THRESHOLD_SECTORS = 256
    private val REGION_NAME = Regex("""r\.(-?\d+)\.(-?\d+)\.mca""")

    /**
     * Reads [source] strictly, passes every kept chunk through [transform], and writes the result to
     * [target]. External chunk files are written next to [target]. Without a [keep] filter every
     * chunk is kept. If no kept chunk is present, nothing is written. [ensureActive] is called between
     * chunks and may throw to cancel.
     */
    fun rewrite(
        source: Path,
        target: Path,
        transform: ChunkTransform,
        chunkLimit: Int = RemapLimits.CHUNK_DECOMPRESSED_BYTES,
        mccLimit: Int = RemapLimits.MCC_COMPRESSED_BYTES,
        keep: ChunkFilter? = null,
        ensureActive: () -> Unit = {},
    ): Result<RegionRewriteResult> =
        rewrite(source, target, transform, chunkLimit, mccLimit, EXTERNAL_THRESHOLD_SECTORS, keep, ensureActive)

    /** [externalThresholdSectors] is Minecraft's 256 in production; tests lower it. */
    internal fun rewrite(
        source: Path,
        target: Path,
        transform: ChunkTransform,
        chunkLimit: Int,
        mccLimit: Int,
        externalThresholdSectors: Int,
        keep: ChunkFilter?,
        ensureActive: () -> Unit,
    ): Result<RegionRewriteResult> = remapResult {
        val match = REGION_NAME.matchEntire(source.fileName.toString())
            ?: throw RegionFormatException("Not a region file name: ${source.fileName}")
        val regionX = match.groupValues[1].toInt()
        val regionZ = match.groupValues[2].toInt()
        val fileSize = Files.size(source)
        if (fileSize == 0L) {
            if (keep != null) return@remapResult notWritten(0)
            copyReplacing(source, target)
            return@remapResult RegionRewriteResult(0, 0, true, emptyList())
        }
        if (fileSize < HEADER) {
            throw RegionFormatException("${source}: header is truncated (${fileSize} bytes)")
        }

        FileChannel.open(source, StandardOpenOption.READ).use { channel ->
            val header = readExactly(channel, 0, HEADER, source)
            val (entries, dropped) = parseHeader(header, fileSize, source, regionX, regionZ, keep)
            if (entries.isEmpty() && keep != null) return@remapResult notWritten(dropped)
            val spill = Files.createTempFile(target.parent, ".${target.fileName}.", ".changed")
            try {
                val changed = transformChunks(channel, entries, source, transform, chunkLimit, mccLimit, spill, ensureActive)
                val externals = entries.filter { it.external }.map { it.mccName }
                if (changed.isEmpty() && dropped == 0) {
                    copyReplacing(source, target)
                    for (name in externals) copyReplacing(source.resolveSibling(name), target.resolveSibling(name))
                    RegionRewriteResult(entries.size, 0, true, externals)
                } else {
                    writeRegion(channel, entries, changed, spill, source, target, externalThresholdSectors)
                    RegionRewriteResult(entries.size, changed.size, false, externals, dropped)
                }
            } finally {
                Files.deleteIfExists(spill)
            }
        }
    }

    /**
     * The chunks a region file contains, from its header alone. Nothing else is read or validated, so
     * this only tells whether a chunk was ever generated.
     */
    fun presentChunks(source: Path): Result<List<Pair<Int, Int>>> = remapResult {
        val match = REGION_NAME.matchEntire(source.fileName.toString())
            ?: throw RegionFormatException("Not a region file name: ${source.fileName}")
        val regionX = match.groupValues[1].toInt()
        val regionZ = match.groupValues[2].toInt()
        if (Files.size(source) < HEADER) return@remapResult emptyList()
        val header = FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { readExactly(it, 0, SECTOR, source) }
        (0 until CHUNKS).filter { NbtBytes.s4(header, it * 4) != 0 }
            .map { (regionX * 32 + (it and 31)) to (regionZ * 32 + (it shr 5)) }
    }

    /**
     * The compression type of every chunk in a region file (the low 7 bits of each chunk's type byte),
     * read from the header and the 5-byte chunk headers only. Entries that point outside the file are
     * skipped; Minecraft treats such chunks as missing too.
     */
    fun compressionTypes(source: Path): Result<Map<Int, Int>> = remapResult {
        val counts = HashMap<Int, Int>()
        FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size < HEADER) return@remapResult counts
            val header = readExactly(channel, 0, SECTOR, source)
            for (i in 0 until CHUNKS) {
                val location = NbtBytes.s4(header, i * 4)
                if (location == 0) continue
                val start = (location ushr 8).toLong() * SECTOR
                if ((location ushr 8) < 2 || start + 5 > size) continue
                val type = readExactly(channel, start + 4, 1, source)[0].toInt() and 0x7f
                counts.merge(type, 1, Int::plus)
            }
        }
        counts
    }

    private class Entry(
        val index: Int,
        val chunkX: Int,
        val chunkZ: Int,
        val sectorOffset: Int,
        val sectorCount: Int,
        val timestamp: Int,
    ) {
        var external = false
        var typeByte = 0
        /** Length of the inline record (length field + type byte + data) in bytes. */
        var inlineRecordLength = 0
        val mccName: String get() = "c.${chunkX}.${chunkZ}.mcc"
    }

    /** Where a changed chunk's recompressed payload sits in the spill file. */
    private class Spilled(val offset: Long, val length: Int)

    private fun notWritten(dropped: Int) = RegionRewriteResult(0, 0, false, emptyList(), dropped, written = false)

    /** Returns the kept entries, validated, and the number of dropped ones, which are not validated. */
    private fun parseHeader(
        header: ByteArray,
        fileSize: Long,
        source: Path,
        regionX: Int,
        regionZ: Int,
        keep: ChunkFilter?,
    ): Pair<List<Entry>, Int> {
        val entries = ArrayList<Entry>()
        var dropped = 0
        for (i in 0 until CHUNKS) {
            val location = NbtBytes.s4(header, i * 4)
            if (location == 0) continue
            val chunkX = regionX * 32 + (i and 31)
            val chunkZ = regionZ * 32 + (i shr 5)
            if (keep != null && !keep.keep(chunkX, chunkZ)) {
                dropped++
                continue
            }
            val offset = location ushr 8
            val count = location and 0xff
            if (offset < 2 || count == 0) {
                throw RegionFormatException("${source}: chunk (${chunkX},${chunkZ}) has an invalid location (sector ${offset}, count ${count})")
            }
            if (offset.toLong() * SECTOR >= fileSize) {
                throw RegionFormatException("${source}: chunk (${chunkX},${chunkZ}) starts past the end of the file")
            }
            entries += Entry(i, chunkX, chunkZ, offset, count, NbtBytes.s4(header, SECTOR + i * 4))
        }
        return entries to dropped
    }

    private fun transformChunks(
        channel: FileChannel,
        entries: List<Entry>,
        source: Path,
        transform: ChunkTransform,
        chunkLimit: Int,
        mccLimit: Int,
        spill: Path,
        ensureActive: () -> Unit,
    ): Map<Int, Spilled> {
        val changed = HashMap<Int, Spilled>()
        FileChannel.open(spill, StandardOpenOption.WRITE).use { spillChannel ->
            for (entry in entries) {
                ensureActive()
                val where = "${source}: chunk (${entry.chunkX},${entry.chunkZ})"
                val compressed = readChunkPayload(channel, entry, source, mccLimit, where)
                val type = entry.typeByte and 0x7f
                checkSupported(type, where)
                val nbt = try {
                    BoundedIo.decompressChunk(type, compressed, chunkLimit, where)
                } catch (error: IOException) {
                    throw RegionFormatException("${where}: cannot decompress: ${error.message}", error)
                }
                val rewritten = try {
                    transform.transform(entry.chunkX, entry.chunkZ, nbt)
                } catch (error: IOException) {
                    throw RegionFormatException("${where}: ${error.message}", error)
                }
                if (rewritten != null) {
                    val recompressed = BoundedIo.compressChunk(type, rewritten)
                    changed[entry.index] = Spilled(spillChannel.position(), recompressed.size)
                    writeFully(spillChannel, ByteBuffer.wrap(recompressed))
                }
            }
        }
        return changed
    }

    /** Reads the compressed data of one chunk, inline or external, and records its header fields. */
    private fun readChunkPayload(channel: FileChannel, entry: Entry, source: Path, mccLimit: Int, where: String): ByteArray {
        val start = entry.sectorOffset.toLong() * SECTOR
        val available = minOf(entry.sectorCount.toLong() * SECTOR, channel.size() - start).toInt()
        if (available < 5) throw RegionFormatException("${where}: chunk header is truncated")
        val head = readExactly(channel, start, 5, source)
        val length = NbtBytes.s4(head, 0)
        entry.typeByte = head[4].toInt() and 0xff
        if (entry.typeByte and EXTERNAL_FLAG != 0) {
            entry.external = true
            val mcc = source.resolveSibling(entry.mccName)
            if (!Files.isRegularFile(mcc, LinkOption.NOFOLLOW_LINKS)) {
                throw RegionFormatException("${where}: external chunk file ${entry.mccName} is missing")
            }
            val size = Files.size(mcc)
            if (size > mccLimit) throw RemapLimitExceededException("${where}: ${entry.mccName} exceeds ${mccLimit} bytes")
            return Files.readAllBytes(mcc).also {
                if (it.size.toLong() != size) throw RegionFormatException("${where}: ${entry.mccName} changed while reading")
            }
        }
        if (length <= 0) throw RegionFormatException("${where}: invalid chunk length ${length}")
        if (length.toLong() + 4 > available) {
            throw RegionFormatException("${where}: chunk length ${length} exceeds its ${available} available bytes")
        }
        entry.inlineRecordLength = length + 4
        return readExactly(channel, start + 5, length - 1, source)
    }

    private fun checkSupported(type: Int, where: String) {
        when (type) {
            ChunkCompression.GZIP, ChunkCompression.ZLIB, ChunkCompression.NONE, ChunkCompression.ZSTD -> Unit
            ChunkCompression.LZ4 -> throw RegionFormatException("${where}: LZ4 chunk compression is not supported")
            ChunkCompression.CUSTOM -> throw RegionFormatException("${where}: custom chunk compression is not supported")
            else -> throw RegionFormatException("${where}: unknown chunk compression type ${type}")
        }
    }

    private fun writeRegion(
        channel: FileChannel,
        entries: List<Entry>,
        changed: Map<Int, Spilled>,
        spill: Path,
        source: Path,
        target: Path,
        externalThresholdSectors: Int,
    ) {
        val directory = target.parent
        val temporary = Files.createTempFile(directory, ".${target.fileName}.", ".tmp")
        try {
            val header = ByteBuffer.allocate(HEADER)
            FileChannel.open(spill, StandardOpenOption.READ).use { spillChannel ->
                FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                    var nextSector = 2
                    output.position(HEADER.toLong())
                    for (entry in entries) {
                        val spilled = changed[entry.index]
                        val record: ByteArray = when {
                            spilled != null -> {
                                val payload = readExactly(spillChannel, spilled.offset, spilled.length, spill)
                                val type = entry.typeByte and 0x7f
                                val inlineSectors = sectorsFor(payload.size + 5)
                                if (entry.external || inlineSectors >= externalThresholdSectors) {
                                    writeReplacing(target.resolveSibling(entry.mccName), payload)
                                    externalStub(type)
                                } else {
                                    inlineRecord(type, payload)
                                }
                            }
                            entry.external -> {
                                copyReplacing(source.resolveSibling(entry.mccName), target.resolveSibling(entry.mccName))
                                externalStub(entry.typeByte and 0x7f)
                            }
                            else -> readExactly(channel, entry.sectorOffset.toLong() * SECTOR, entry.inlineRecordLength, source)
                        }
                        val sectors = sectorsFor(record.size)
                        if (sectors > 255 || nextSector + sectors > 0xFFFFFF) {
                            throw RegionFormatException("${target}: chunk (${entry.chunkX},${entry.chunkZ}) does not fit the region format")
                        }
                        writeFully(output, ByteBuffer.wrap(record))
                        val padding = sectors * SECTOR - record.size
                        if (padding > 0) writeFully(output, ByteBuffer.allocate(padding))
                        header.putInt(entry.index * 4, (nextSector shl 8) or sectors)
                        header.putInt(SECTOR + entry.index * 4, entry.timestamp)
                        nextSector += sectors
                    }
                    header.position(0)
                    output.position(0)
                    writeFully(output, header)
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun inlineRecord(type: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(payload.size + 5).putInt(payload.size + 1).put(type.toByte()).put(payload).array()

    private fun externalStub(type: Int): ByteArray =
        ByteBuffer.allocate(5).putInt(1).put((type or EXTERNAL_FLAG).toByte()).array()

    private fun sectorsFor(bytes: Int): Int = (bytes + SECTOR - 1) / SECTOR

    private fun readExactly(channel: FileChannel, position: Long, length: Int, file: Path): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw RegionFormatException("${file}: unexpected end of file at ${at}")
            at += read
        }
        return buffer.array()
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun copyReplacing(source: Path, target: Path) {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS)
    }

    private fun writeReplacing(target: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
