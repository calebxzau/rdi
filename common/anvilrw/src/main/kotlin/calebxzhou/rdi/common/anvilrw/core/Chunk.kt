package calebxzhou.rdi.common.anvilrw.core

import calebxzhou.rdi.common.anvilrw.util.AnvilUtils
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.BLOCKS_PER_CHUNK_SIDE
import calebxzhou.rdi.common.anvilrw.util.AnvilConstants.CHUNKS_PER_REGION_SIDE
import net.benwoodworth.knbt.*
import java.io.IOException

private val nbt = Nbt {
    variant = NbtVariant.Java
    compression = NbtCompression.None
}

class Chunk(
    val index: Int,
    val location: Location,
    val timestamp: Int,
    payload: ByteArray,
    val isExternalPayload: Boolean = false
) {
    val x: Int
    val z: Int
    val dataVersion: Int
    val payload: ChunkPayload = ChunkPayload(payload, isExternalPayload)

    private var cachedNbtData: NbtCompound? = null
    private var nbtLoaded = false

    init {
        if (this.payload.length > 0) {
            val coordData = parseCoordinatesAndVersion()
            x = coordData.x
            z = coordData.z
            dataVersion = coordData.dataVersion
        } else {
            x = 0
            z = 0
            dataVersion = 0
        }
    }

    private data class CoordinateData(val x: Int, val z: Int, val dataVersion: Int)

    private fun parseCoordinatesAndVersion(): CoordinateData {
        val decompressed = this.payload.getDecompressedData()
        val rawRoot = nbt.decodeFromByteArray(NbtCompound.serializer(), decompressed)
        val root = AnvilUtils.unwrapRoot(rawRoot)
        // Reuse this decoded NBT later in getNbtData() to avoid decompress/parse twice.
        cachedNbtData = root
        nbtLoaded = true

        val x: Int
        val z: Int

        when {
            "Position" in root -> {
                val coords = (root["Position"] as NbtIntArray)
                if (coords.size < 2) throw IOException("Invalid entity file format: Position array too short")
                x = coords[0]
                z = coords[1]
            }
            "pos" in root -> {
                val coords = (root["pos"] as NbtIntArray)
                if (coords.size < 3) throw IOException("Invalid POI file format: pos array too short")
                x = coords[0]
                z = coords[2]
            }
            "xPos" in root && "zPos" in root -> {
                x = (root["xPos"] as NbtInt).value
                z = (root["zPos"] as NbtInt).value
            }
            else -> throw IOException("Invalid chunk format: missing xPos/zPos tags")
        }

        val dataVersion = if ("DataVersion" in root) {
            (root["DataVersion"] as NbtInt).value
        } else {
            throw IOException("Invalid chunk format: missing DataVersion tag")
        }

        return CoordinateData(x, z, dataVersion)
    }

    fun getNbtData(): NbtCompound? {
        if (payload.length == 0) return null
        if (!nbtLoaded) loadNbtData()
        return cachedNbtData
    }

    fun releaseSurfaceReadData() {
        cachedNbtData = null
        nbtLoaded = false
        payload.releaseCompressedData()
    }

    fun setNbtData(nbtData: NbtCompound) {
        val wrapped = AnvilUtils.wrapRoot(nbtData)
        val nbtBytes = nbt.encodeToByteArray(NbtCompound.serializer(), wrapped)
        payload.compressAndSetData(nbtBytes)
        cachedNbtData = nbtData
        nbtLoaded = true
    }

    private fun loadNbtData() {
        val decompressed = payload.getDecompressedData()
        val rawRoot = nbt.decodeFromByteArray(NbtCompound.serializer(), decompressed)
        cachedNbtData = AnvilUtils.unwrapRoot(rawRoot)
        nbtLoaded = true
    }

    val isNbtLoaded: Boolean get() = nbtLoaded

    fun hasOwnableEntities(): Boolean {
        if (payload.length == 0) return false
        val root = getNbtData() ?: return false
        return "Owner" in root || "Target" in root
    }

    fun chunkToRegionCoordinate(): IntArray =
        intArrayOf(
            Math.floorDiv(x, CHUNKS_PER_REGION_SIDE),
            Math.floorDiv(z, CHUNKS_PER_REGION_SIDE)
        )

    fun isBlockInChunk(blockX: Int, blockZ: Int): Boolean {
        val chunkX = Math.floorDiv(blockX, BLOCKS_PER_CHUNK_SIDE)
        val chunkZ = Math.floorDiv(blockZ, BLOCKS_PER_CHUNK_SIDE)
        return chunkX == x && chunkZ == z
    }

    fun getStartingBlockCoordinates(): IntArray {
        val regionCoordinate = chunkToRegionCoordinate()
        val chunkX = regionCoordinate[0] + (index % CHUNKS_PER_REGION_SIDE * BLOCKS_PER_CHUNK_SIDE)
        val chunkZ = regionCoordinate[1] + (index % CHUNKS_PER_REGION_SIDE * BLOCKS_PER_CHUNK_SIDE)
        return intArrayOf(chunkX, chunkZ)
    }

    fun getBlockCoordinateRange(): IntArray {
        val startX = x * BLOCKS_PER_CHUNK_SIDE
        val endX = startX + (BLOCKS_PER_CHUNK_SIDE - 1)
        val startZ = z * BLOCKS_PER_CHUNK_SIDE
        val endZ = startZ + (BLOCKS_PER_CHUNK_SIDE - 1)
        return intArrayOf(startX, startZ, endX, endZ)
    }

    val isEmpty: Boolean get() = payload.length == 0

    val dataSize: Int get() = payload.length

    override fun toString(): String =
        "Chunk{location=$location, timestamp=$timestamp, chunkData (Bytes)=${payload.length}}"
}
