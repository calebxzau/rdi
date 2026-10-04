package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import net.minecraft.core.Registry
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.network.FriendlyByteBuf
import io.netty.buffer.Unpooled
import org.slf4j.LoggerFactory
import java.io.IOException

/** Main-thread terrain hashing of live chunks, reusing per-section hashes until their containers change. */
internal object ServerTerrainHashes {
    private val logger = LoggerFactory.getLogger("rdi")
    // Recompute an occasional cache hit in full; catches mods that write palette storage directly.
    private const val VERIFY_EVERY_HITS = 256
    private var hitsSinceVerify = 0
    // Fail closed: once a cached hash is proven stale, untracked writes exist and no cached hash is trusted.
    private var cacheDisabled = false
    private val trackingApplied: Boolean by lazy { verifyTracking() }

    @Throws(IOException::class)
    fun chunkHash(chunk: LevelChunk, minSection: Int, biomes: Registry<Biome>, metrics: ChunkCacheServerMetrics): ByteArray {
        val sections = chunk.sections
        return ChunkTerrainCodec.chunkHash(minSection, Array(sections.size) { sectionHash(sections[it], biomes, metrics) })
    }

    @Throws(IOException::class)
    private fun sectionHash(section: LevelChunkSection, biomes: Registry<Biome>, metrics: ChunkCacheServerMetrics): ByteArray {
        val states = section.states
        val biomeContainer = section.biomes
        val statesStamp = (states as? ChunkCacheContainerStamp)?.`rdi$modCount`()
        val biomesStamp = (biomeContainer as? ChunkCacheContainerStamp)?.`rdi$modCount`()
        val cache = (section as? ChunkCacheSectionHashHolder)?.`rdi$hashCache`()
        if (cacheDisabled || !trackingApplied || cache == null || statesStamp == null || biomesStamp == null) {
            metrics.recordSectionHashMiss()
            return ChunkTerrainCodec.sectionHash(section, biomes)
        }
        val cached = cache.get(states, statesStamp, biomeContainer, biomesStamp)
        if (cached != null) {
            metrics.recordSectionHashHit()
            if (++hitsSinceVerify < VERIFY_EVERY_HITS) return cached
            hitsSinceVerify = 0
            val fresh = ChunkTerrainCodec.sectionHash(section, biomes)
            if (fresh.contentEquals(cached)) return cached
            metrics.recordSectionHashStale()
            cacheDisabled = true
            logger.error("Chunk cache section hash was stale; a mod changes terrain without tracked container writes. " +
                "Section hash caching is disabled until the server restarts")
            return fresh
        }
        metrics.recordSectionHashMiss()
        val fresh = ChunkTerrainCodec.sectionHash(section, biomes)
        cache.store(states, statesStamp, biomeContainer, biomesStamp, fresh)
        return fresh
    }

    /** Another mod's @Overwrite can silently drop the counter injections; never cache without them. */
    private fun verifyTracking(): Boolean {
        val applied = runCatching {
            val container = PalettedContainer(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES)
            val stamp = container as? ChunkCacheContainerStamp ?: return@runCatching false
            var last = stamp.`rdi$modCount`()
            fun changed(): Boolean = stamp.`rdi$modCount`().let { now -> (now != last).also { last = now } }
            container.set(0, 0, 0, Blocks.STONE.defaultBlockState())
            val set = changed()
            container.getAndSet(1, 0, 0, Blocks.DIRT.defaultBlockState())
            val getAndSet = changed()
            container.getAndSetUnchecked(2, 0, 0, Blocks.STONE.defaultBlockState())
            val unchecked = changed()
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                container.write(buffer)
                container.read(buffer)
            } finally {
                buffer.release()
            }
            set && getAndSet && unchecked && changed()
        }.getOrElse { failure ->
            logger.error("Chunk cache could not verify terrain write tracking", failure)
            false
        }
        if (!applied) logger.error("Chunk cache terrain write tracking is not active; section hash caching is disabled")
        return applied
    }
}
