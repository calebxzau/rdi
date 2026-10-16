package calebxzau.rdi.mc.chunkcache

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Canonical SHA-1 over semantic block state and biome registry IDs, independent of palette layout.
 *
 * Version 2 hashes each section separately, then hashes the ordered section digests. A section whose
 * block IDs are all equal uses a short uniform form; uniformity is decided from the IDs themselves,
 * never from palette layout, so every producer of the same IDs yields the same digest.
 *
 * ID arrays use Minecraft's container index order: blocks `y * 256 + z * 16 + x`, biomes `y * 16 + z * 4 + x`.
 */
object TerrainHash {
    private const val CHUNK_MAGIC = 0x52445448 // RDTH
    private const val SECTION_MAGIC = 0x52445453 // RDTS
    const val VERSION = 2
    const val HASH_BYTES = 20
    const val BLOCKS_PER_SECTION = 16 * 16 * 16
    const val BIOMES_PER_SECTION = 4 * 4 * 4
    private const val SECTION_COUNT_LIMIT = 256
    private const val UNIFORM_BLOCKS: Byte = 0
    private const val FULL_BLOCKS: Byte = 1
    /** Block IDs start after the section magic, version and form byte. */
    private const val BLOCKS_OFFSET = 4 + 4 + 1
    private const val SECTION_BUFFER_BYTES = BLOCKS_OFFSET + BLOCKS_PER_SECTION * 4 + BIOMES_PER_SECTION * 4

    private val sectionBuffers = ThreadLocal.withInitial {
        ByteBuffer.allocate(SECTION_BUFFER_BYTES).order(ByteOrder.BIG_ENDIAN)
    }

    /** Hashes one section; detects the uniform form from [blockIds]. */
    @JvmStatic
    fun sectionSha1(blockIds: IntArray, biomeIds: IntArray): ByteArray {
        require(blockIds.size == BLOCKS_PER_SECTION) { "Invalid block ID count" }
        val first = blockIds[0]
        var uniform = true
        for (id in blockIds) {
            require(id >= 0) { "Invalid block state ID" }
            if (id != first) uniform = false
        }
        return if (uniform) uniformSectionSha1(first, biomeIds) else encodeSection(blockIds, -1, biomeIds)
    }

    /**
     * Hashes one section from block IDs written in container index order, straight into this thread's hash buffer
     * instead of an intermediate array; [SectionHasher.finish] equals [sectionSha1] of the same IDs. Finish it before
     * hashing anything else on this thread.
     */
    @JvmStatic
    fun sectionHasher(): SectionHasher = SectionHasher(sectionBuffers.get())

    class SectionHasher internal constructor(private val buffer: ByteBuffer) {
        private var count = 0
        private var first = 0
        private var uniform = true

        fun add(blockId: Int) {
            require(blockId >= 0) { "Invalid block state ID" }
            require(count < BLOCKS_PER_SECTION) { "Invalid block ID count" }
            if (count == 0) first = blockId else if (blockId != first) uniform = false
            buffer.putInt(BLOCKS_OFFSET + count * 4, blockId)
            count++
        }

        fun finish(biomeIds: IntArray): ByteArray {
            require(count == BLOCKS_PER_SECTION) { "Invalid block ID count" }
            return if (uniform) uniformSectionSha1(first, biomeIds) else digestSection(buffer, FULL_BLOCKS, biomeIds)
        }
    }

    /** Equal to [sectionSha1] for a block array whose 4,096 entries are all [blockId]. */
    @JvmStatic
    fun uniformSectionSha1(blockId: Int, biomeIds: IntArray): ByteArray {
        require(blockId >= 0) { "Invalid block state ID" }
        return encodeSection(null, blockId, biomeIds)
    }

    @JvmStatic
    fun chunkSha1(minSection: Int, sectionHashes: Array<ByteArray>): ByteArray {
        require(sectionHashes.size in 1..SECTION_COUNT_LIMIT) { "Invalid section count" }
        require(minSection.toLong() + sectionHashes.size - 1 <= Int.MAX_VALUE) { "Invalid section range" }
        val buffer = ByteBuffer.allocate(16 + sectionHashes.size * HASH_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(CHUNK_MAGIC).putInt(VERSION).putInt(minSection).putInt(sectionHashes.size)
        for (hash in sectionHashes) {
            require(hash.size == HASH_BYTES) { "Invalid section hash" }
            buffer.put(hash)
        }
        return MessageDigest.getInstance("SHA-1").digest(buffer.array())
    }

    /**
     * Reference composition over coordinate callbacks. Block callbacks use 0..15 coordinates; biome callbacks
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
        val blocks = IntArray(BLOCKS_PER_SECTION)
        val biomes = IntArray(BIOMES_PER_SECTION)
        val hashes = Array(sectionCount) { section ->
            var index = 0
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) blocks[index++] = blockStateAt.get(section, x, y, z)
            index = 0
            for (y in 0 until 4) for (z in 0 until 4) for (x in 0 until 4) biomes[index++] = biomeAt.get(section, x, y, z)
            sectionSha1(blocks, biomes)
        }
        return chunkSha1(minSection, hashes)
    }

    private fun encodeSection(blockIds: IntArray?, uniformId: Int, biomeIds: IntArray): ByteArray {
        val buffer = sectionBuffers.get()
        if (blockIds == null) {
            buffer.clear()
            buffer.position(BLOCKS_OFFSET)
            buffer.putInt(uniformId)
            return digestSection(buffer, UNIFORM_BLOCKS, biomeIds)
        }
        buffer.clear()
        buffer.position(BLOCKS_OFFSET)
        buffer.asIntBuffer().put(blockIds)
        buffer.position(BLOCKS_OFFSET + BLOCKS_PER_SECTION * 4)
        return digestSection(buffer, FULL_BLOCKS, biomeIds)
    }

    /**
     * Completes a section whose block part is already in [buffer] after [BLOCKS_OFFSET]: one uniform ID, or all
     * [BLOCKS_PER_SECTION] IDs. Writes the header and biomes around it and hashes the whole section.
     */
    private fun digestSection(buffer: ByteBuffer, form: Byte, biomeIds: IntArray): ByteArray {
        require(biomeIds.size == BIOMES_PER_SECTION) { "Invalid biome ID count" }
        for (id in biomeIds) require(id >= 0) { "Invalid biome ID" }
        buffer.putInt(0, SECTION_MAGIC).putInt(4, VERSION).put(8, form)
        val biomesOffset = BLOCKS_OFFSET + if (form == UNIFORM_BLOCKS) 4 else BLOCKS_PER_SECTION * 4
        buffer.position(biomesOffset)
        buffer.asIntBuffer().put(biomeIds)
        val length = biomesOffset + BIOMES_PER_SECTION * 4
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(buffer.array(), 0, length)
        return digest.digest()
    }
}
