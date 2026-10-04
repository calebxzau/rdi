package calebxzau.rdi.mc.chunkcache

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import net.minecraft.core.Registry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.GlobalPalette
import net.minecraft.world.level.chunk.SingleValuePalette
import net.minecraft.world.level.chunk.LevelChunkSection
import java.io.IOException

/** Section-palette adapter for the shared semantic terrain hash; the APIs are identical in 1.20.1 and 1.21.1. */
object ChunkTerrainCodec {
    private const val MAX_SECTION_COUNT = 256
    /** One block-state container and one biome container per section. */
    private const val MAX_PADDING_PER_SECTION = 2

    data class PreparedTerrain(
        val minSection: Int,
        val sectionCount: Int,
        val sections: ByteArray,
        val hash: ByteArray,
    )

    @JvmStatic
    @Throws(IOException::class)
    fun decode(sectionBytes: ByteArray, sectionCount: Int, biomes: Registry<Biome>): Array<LevelChunkSection> {
        checkSectionBytes(sectionBytes.size, sectionCount)
        val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(sectionBytes))
        try {
            val sections = Array(sectionCount) { LevelChunkSection(biomes) }
            for (section in sections) section.read(buffer)
            checkTrailingPadding(buffer, sectionCount)
            return sections
        } catch (failure: RuntimeException) {
            throw IOException("Invalid section palette data", failure)
        } finally {
            buffer.release()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun hash(minSection: Int, sections: Array<LevelChunkSection>, biomes: Registry<Biome>): ByteArray {
        checkSectionBytes(0, sections.size)
        return chunkHash(minSection, Array(sections.size) { sectionHash(sections[it], biomes) })
    }

    @JvmStatic
    @Throws(IOException::class)
    fun chunkHash(minSection: Int, sectionHashes: Array<ByteArray>): ByteArray {
        checkSectionBytes(0, sectionHashes.size)
        if (minSection.toLong() + sectionHashes.size - 1 > Int.MAX_VALUE) throw IOException("Invalid section range")
        try {
            return TerrainHash.chunkSha1(minSection, sectionHashes)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid terrain section hashes", failure)
        }
    }

    /** Reads palette storage once per section instead of resolving every block through the state registry. */
    @JvmStatic
    @Throws(IOException::class)
    fun sectionHash(section: LevelChunkSection, biomes: Registry<Biome>): ByteArray {
        try {
            val biomeIds = biomeIds(section, biomes)
            val data = section.states.data
            val palette = data.palette()
            val storage = data.storage()
            if (storage.size != TerrainHash.BLOCKS_PER_SECTION) throw IOException("Invalid block storage size")
            if (palette is SingleValuePalette<*>) {
                return TerrainHash.uniformSectionSha1(Block.getId(palette.valueFor(0) as BlockState), biomeIds)
            }
            val ids = IntArray(TerrainHash.BLOCKS_PER_SECTION)
            var index = 0
            if (palette is GlobalPalette<*>) {
                var lastRaw = -1
                var lastId = -1
                storage.getAll { raw ->
                    if (raw != lastRaw) {
                        lastRaw = raw
                        lastId = Block.getId(palette.valueFor(raw) as BlockState)
                    }
                    ids[index++] = lastId
                }
            } else {
                val paletteIds = IntArray(palette.size) { Block.getId(palette.valueFor(it)) }
                storage.getAll { raw -> ids[index++] = paletteIds[raw] }
            }
            return TerrainHash.sectionSha1(ids, biomeIds)
        } catch (failure: RuntimeException) {
            throw IOException("Invalid terrain section data", failure)
        }
    }

    private fun biomeIds(section: LevelChunkSection, biomes: Registry<Biome>): IntArray {
        val ids = IntArray(TerrainHash.BIOMES_PER_SECTION)
        var index = 0
        var last: Biome? = null
        var lastId = -1
        for (y in 0 until 4) for (z in 0 until 4) for (x in 0 until 4) {
            val biome = section.getNoiseBiome(x, y, z).value()
            if (biome !== last) {
                last = biome
                lastId = biomes.getId(biome)
                if (lastId < 0) throw IllegalArgumentException("Biome is not in the current registry")
            }
            ids[index++] = lastId
        }
        return ids
    }

    @JvmStatic
    @Throws(IOException::class)
    fun prepare(minSection: Int, sections: Array<LevelChunkSection>, biomes: Registry<Biome>): PreparedTerrain {
        val hash = hash(minSection, sections, biomes)
        try {
            for (section in sections) section.recalcBlockCounts()
            val size = sections.sumOf { it.getSerializedSize().toLong() }
            if (size > ChunkCacheLimits.MAX_SECTION_BYTES) throw IOException("Section data exceeds cache limit")
            val buffer = FriendlyByteBuf(Unpooled.buffer(size.toInt()))
            val bytes = try {
                sections.forEach { it.write(buffer) }
                // 1.20.1 over-estimates single-value containers, so keep only the bytes actually written.
                if (buffer.writerIndex() > size) throw IOException("Section encoding length changed")
                ByteArray(buffer.writerIndex()).also { buffer.getBytes(0, it) }
            } finally {
                buffer.release()
            }
            return PreparedTerrain(minSection, sections.size, bytes, hash)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid terrain section data", failure)
        }
    }

    /**
     * 1.20.1 sizes each paletted container with VarInt(storage size) but writes VarInt(raw length), so vanilla
     * chunk data ends with one zero byte per single-value container. Any other data after the last section is invalid.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun checkTrailingPadding(buffer: ByteBuf, sectionCount: Int) {
        val trailing = buffer.readableBytes()
        if (trailing == 0) return
        if (trailing.toLong() > sectionCount.toLong() * MAX_PADDING_PER_SECTION) throw IOException("Trailing section data")
        for (index in buffer.readerIndex() until buffer.writerIndex()) {
            if (buffer.getByte(index) != 0.toByte()) throw IOException("Trailing section data")
        }
    }

    private fun checkSectionBytes(length: Int, sectionCount: Int) {
        if (sectionCount !in 1..MAX_SECTION_COUNT) throw IOException("Invalid section count")
        if (length > ChunkCacheLimits.MAX_SECTION_BYTES) throw IOException("Section data exceeds cache limit")
    }
}
