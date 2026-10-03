package calebxzau.rdi.mc.chunkcache

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

/** Minecraft 1.21.1 section-palette adapter for the shared semantic terrain hash. */
object ChunkTerrainCodec {
    private const val MAX_SECTION_COUNT = 256

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
            if (buffer.isReadable) throw IOException("Trailing section data")
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
            val bytes = ByteArray(size.toInt())
            val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes))
            buffer.writerIndex(0)
            try {
                sections.forEach { it.write(buffer) }
                if (buffer.writerIndex() != bytes.size) throw IOException("Section encoding length changed")
            } finally {
                buffer.release()
            }
            return PreparedTerrain(minSection, sections.size, bytes, hash)
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid terrain section data", failure)
        }
    }

    private fun checkSectionBytes(length: Int, sectionCount: Int) {
        if (sectionCount !in 1..MAX_SECTION_COUNT) throw IOException("Invalid section count")
        if (length > ChunkCacheLimits.MAX_SECTION_BYTES) throw IOException("Section data exceeds cache limit")
    }
}
