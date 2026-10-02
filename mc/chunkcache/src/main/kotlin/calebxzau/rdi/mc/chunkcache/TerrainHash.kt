package calebxzau.rdi.mc.chunkcache

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Canonical SHA-1 over semantic block state and biome registry IDs, independent of palette layout. */
object TerrainHash {
    private const val MAGIC = 0x52445448 // RDTH
    private const val VERSION = 1
    private const val SECTION_COUNT_LIMIT = 256
    private const val BLOCKS_PER_SECTION = 16 * 16 * 16
    private const val BIOMES_PER_SECTION = 4 * 4 * 4
    private const val DIGEST_BUFFER_SIZE = 16 * 1024

    /**
     * Hashes one semantic terrain snapshot. Block callbacks use 0..15 coordinates; biome callbacks
     * use 0..3 coordinates. For both, iteration order is section, Y, Z, X (X changes fastest).
     */
    @JvmStatic
    fun sha1(
        minSection: Int,
        sectionCount: Int,
        blockStateAt: TerrainIdReader,
        biomeAt: TerrainIdReader,
    ): ByteArray {
        require(sectionCount in 1..SECTION_COUNT_LIMIT) { "Invalid section count" }
        require(minSection.toLong() + sectionCount - 1 <= Int.MAX_VALUE) { "Invalid section range" }

        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteBuffer.allocate(DIGEST_BUFFER_SIZE).order(ByteOrder.BIG_ENDIAN)
        fun writeInt(value: Int) {
            buffer.putInt(value)
            if (!buffer.hasRemaining()) {
                digest.update(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
        }

        writeInt(MAGIC)
        writeInt(VERSION)
        writeInt(minSection)
        writeInt(sectionCount)
        for (section in 0 until sectionCount) {
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) {
                val id = blockStateAt.get(section, x, y, z)
                require(id >= 0) { "Invalid block state ID" }
                writeInt(id)
            }
            for (y in 0 until 4) for (z in 0 until 4) for (x in 0 until 4) {
                val id = biomeAt.get(section, x, y, z)
                require(id >= 0) { "Invalid biome ID" }
                writeInt(id)
            }
        }
        if (buffer.position() > 0) digest.update(buffer.array(), 0, buffer.position())
        return digest.digest()
    }
}
