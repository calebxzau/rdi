package calebxzau.rdi.mc.chunkcache

import io.netty.buffer.Unpooled
import net.minecraft.core.Registry
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
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
        if (minSection.toLong() + sections.size - 1 > Int.MAX_VALUE) throw IOException("Invalid section range")
        try {
            return TerrainHash.sha1(
                minSection,
                sections.size,
                blockStateAt = TerrainIdReader { section, x, y, z -> Block.getId(sections[section].getBlockState(x, y, z)) },
                biomeAt = TerrainIdReader { section, x, y, z ->
                    val id = biomes.getId(sections[section].getNoiseBiome(x, y, z).value())
                    if (id < 0) throw IllegalArgumentException("Biome is not in the current registry")
                    id
                },
            )
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid terrain section data", failure)
        }
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
