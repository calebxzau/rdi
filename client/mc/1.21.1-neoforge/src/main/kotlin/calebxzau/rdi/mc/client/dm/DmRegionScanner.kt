package calebxzau.rdi.mc.client.dm

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.streams.toList

data class DmRegionSlot(val timestamp: Int, val sectors: Int)

data class DmRegionTable(val slots: List<DmRegionSlot>) {
    init { require(slots.size == 1024) }
    fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        slots.forEach { slot ->
            buffer.clear().putInt(slot.timestamp).putInt(slot.sectors).flip()
            digest.update(buffer)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

data class DmScannedRegion(val path: String, val table: DmRegionTable, val changes: List<DmRegionChange>)

object DmRegionScanner {
    private const val HEADER_BYTES = 8192L
    private const val SECTOR_BYTES = 4096

    fun scan(root: Path, onReadSlot: ((Path, Int) -> Unit)? = null): Result<List<DmScannedRegion>> = runCatching {
        val normalizedRoot = root.toAbsolutePath().normalize()
        require(Files.isDirectory(normalizedRoot)) { "世界目录不存在" }
        Files.walk(normalizedRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".mca") }
                .sorted()
                .map { scanRegion(normalizedRoot, it, onReadSlot) }
                .toList()
        }
    }

    fun table(path: Path): Result<DmRegionTable> = runCatching { readTable(path) }

    fun readEntry(path: Path, index: Int, onReadSlot: (() -> Unit)? = null): Result<DmRegionChange?> = runCatching {
        require(index in 0 until 1024) { "区块索引无效" }
        RandomAccessFile(path.toFile(), "r").use { file ->
            val before = readSlot(file, index)
            if (before.sectors == 0) return@runCatching null
            file.seek(index * 4L)
            val offsetWord = file.readInt()
            val sectorOffset = offsetWord ushr 8
            require(sectorOffset >= 2) { "区块偏移落在region头部" }
            file.seek(sectorOffset.toLong() * SECTOR_BYTES)
            val length = file.readInt()
            require(length >= 1 && length + 4 <= before.sectors * SECTOR_BYTES) { "区块长度无效" }
            val version = file.readUnsignedByte()
            require(version != 0) { "区块压缩版本无效" }
            val payload = ByteArray(length - 1)
            file.readFully(payload)
            val raw = if (version and 0x80 != 0) {
                val external = path.resolveSibling(externalChunkName(path, index))
                val externalBytes = Files.readAllBytes(external)
                val externalLength = externalBytes.size + 1
                ByteBuffer.allocate(externalLength + 4).order(ByteOrder.BIG_ENDIAN).putInt(externalLength).put((version and 0x7f).toByte()).put(externalBytes).array()
            } else {
                ByteBuffer.allocate(length + 4).order(ByteOrder.BIG_ENDIAN).putInt(length).put(version.toByte()).put(payload).array()
            }
            onReadSlot?.invoke()
            val after = readSlot(file, index)
            if (before != after) return@runCatching null
            DmRegionChange(DmRegionChunkKey("", index), after.timestamp, raw, before.sectors)
        }
    }

    fun diff(local: DmRegionTable?, remote: DmRegionTable, region: String, readLocal: (Int) -> DmRegionChange?): List<DmRegionChange> {
        val result = ArrayList<DmRegionChange>()
        for (index in 0 until 1024) {
            val localSlot = local?.slots?.get(index) ?: DmRegionSlot(0, 0)
            val remoteSlot = remote.slots[index]
            if (localSlot == remoteSlot) continue
            val localChange = readLocal(index)
            if (localChange != null) result += localChange.copy(key = DmRegionChunkKey(region, index))
            else result += DmRegionChange(DmRegionChunkKey(region, index), localSlot.timestamp, null, 0)
        }
        return result
    }

    private fun scanRegion(root: Path, path: Path, onReadSlot: ((Path, Int) -> Unit)?): DmScannedRegion {
        val table = readTable(path)
        val relative = root.relativize(path).toString().replace('\\', '/')
        val changes = ArrayList<DmRegionChange>()
        for (index in 0 until 1024) {
            val change = readEntry(path, index) { onReadSlot?.invoke(path, index) }.getOrThrow() ?: continue
            changes += change.copy(key = DmRegionChunkKey(relative, index))
        }
        return DmScannedRegion(relative, table, changes)
    }

    private fun readTable(path: Path): DmRegionTable {
        RandomAccessFile(path.toFile(), "r").use { file ->
            return DmRegionTable(List(1024) { index -> readSlot(file, index) })
        }
    }

    /**
     * Like vanilla `RegionFile`, a header shorter than [HEADER_BYTES] reads as zeros: vanilla
     * leaves empty region files at 0 bytes until a chunk is first written to them.
     */
    private fun readSlot(file: RandomAccessFile, index: Int): DmRegionSlot {
        val offset = readHeaderInt(file, index * 4L)
        val timestamp = readHeaderInt(file, SECTOR_BYTES + index * 4L)
        return DmRegionSlot(timestamp, offset and 0xff)
    }

    private fun readHeaderInt(file: RandomAccessFile, position: Long): Int {
        if (position + 4 > file.length()) return 0
        file.seek(position)
        return file.readInt()
    }

    /** Vanilla names an oversized chunk's external file after the chunk, not the region. */
    internal fun externalChunkName(region: Path, index: Int): String {
        val (regionX, regionZ) = coordinates(region)
        return "c.${regionX * 32 + (index and 31)}.${regionZ * 32 + (index ushr 5)}.mcc"
    }

    private fun coordinates(path: Path): Pair<Int, Int> {
        val stem = path.fileName.toString().removePrefix("r.").removeSuffix(".mca")
        val parts = stem.split('.')
        require(parts.size == 2)
        return parts[0].toInt() to parts[1].toInt()
    }
}
