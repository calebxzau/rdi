package calebxzau.rdi.mc.server.chunkcache

import calebxzau.rdi.mc.chunkcache.ChunkCacheLimits
import calebxzau.rdi.mc.chunkcache.ChunkTerrainCodec
import calebxzau.rdi.mc.chunkcache.network.ChunkCacheReusePayload
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.LevelChunk
import org.slf4j.LoggerFactory

/**
 * Turns one full chunk send into a reuse when the client's candidate still matches the terrain being sent.
 * The version decides when a send may become a reuse and how its metadata is encoded. Owned by the server thread.
 */
internal object ChunkCacheReuseSender {
    private val logger = LoggerFactory.getLogger("rdi")

    /**
     * Returns the reuse message to send in place of [packet], or null to send [packet] in full; the caller then
     * retires [candidate]. [chunk] is the live chunk [packet] was built from on this thread, when the caller has it.
     */
    fun attempt(
        session: ChunkCacheServerSession,
        budget: ChunkCacheReuseBudget,
        level: ServerLevel,
        packet: ClientboundLevelChunkWithLightPacket,
        chunk: LevelChunk?,
        candidate: ChunkCacheOfferLedger.Token,
        tick: Long,
        playerName: String,
        encodeMetadata: (ClientboundLevelChunkWithLightPacket) -> ByteArray,
    ): ChunkCacheReusePayload? {
        val metrics = session.metrics
        if (!budget.available(tick, session.reuseBudgetNanos(System.currentTimeMillis()))) {
            metrics.recordBudgetFallbackFullSend()
            return null
        }
        val pos = ChunkPos(packet.x, packet.z)
        val budgetStart = System.nanoTime()
        try {
            val biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME)
            // A packet may be shared by every player loading this chunk; its read buffer is a fresh view.
            val sectionBuffer = packet.chunkData.readBuffer
            val sectionPayloadBytes = try {
                sectionBuffer.readableBytes()
            } finally {
                sectionBuffer.release()
            }
            if (sectionPayloadBytes > ChunkCacheLimits.MAX_SECTION_BYTES) {
                metrics.recordCandidateFallbackFullSend()
                return null
            }
            metrics.recordRawSectionPayloadBytes(sectionPayloadBytes)
            val semanticHashStart = System.nanoTime()
            val hash = try {
                if (chunk != null && chunk.pos == pos && chunk.level === level && chunk.sections.size == level.sectionsCount) {
                    // The packet was built from this chunk earlier on this thread. On 1.20.1, ChunkWatchEvent handlers
                    // for earlier players may have changed it since; later block updates correct that.
                    ServerTerrainHashes.chunkHash(chunk, level.minSection, biomeRegistry, metrics)
                } else {
                    decodedPacketHash(metrics, packet, level.sectionsCount, level.minSection, biomeRegistry)
                }
            } finally {
                metrics.recordSemanticHash(System.nanoTime() - semanticHashStart)
            }
            if (!hash.contentEquals(candidate.hash)) {
                metrics.recordMismatch()
                return null
            }
            val metadataStart = System.nanoTime()
            val metadata = try {
                encodeMetadata(packet)
            } finally {
                metrics.recordMetadataEncode(System.nanoTime() - metadataStart)
            }
            val reuse = ChunkCacheReusePayload(
                session.epoch,
                candidate.id,
                session.dimension,
                pos.x,
                pos.z,
                candidate.hash.copyOf(),
                metadata,
            )
            if (!session.ledger.trackReuse(candidate, System.currentTimeMillis() + ChunkCacheLimits.OFFER_TTL_MILLIS)) {
                metrics.recordCandidateFallbackFullSend()
                return null
            }
            metrics.recordReuseSent()
            metrics.recordReuseMetadataBytes(metadata.size)
            budget.recordReuse(System.nanoTime() - budgetStart)
            return reuse
        } catch (failure: Exception) {
            metrics.recordCandidateFallbackFullSend()
            logger.debug("Chunk cache candidate failed player={} chunk=({}, {}); sending full chunk", playerName, pos.x, pos.z, failure)
            return null
        } finally {
            budget.spend(System.nanoTime() - budgetStart)
        }
    }

    /** Fallback when no matching live chunk is available, such as a chunk packet nested in a foreign bundle. */
    private fun decodedPacketHash(
        metrics: ChunkCacheServerMetrics,
        packet: ClientboundLevelChunkWithLightPacket,
        sectionCount: Int,
        minSection: Int,
        biomeRegistry: Registry<Biome>,
    ): ByteArray {
        val sectionCopyDecodeStart = System.nanoTime()
        val sections = try {
            val sectionBuffer = packet.chunkData.readBuffer
            val sectionBytes = try {
                ByteArray(sectionBuffer.readableBytes()).also { sectionBuffer.readBytes(it) }
            } finally {
                sectionBuffer.release()
            }
            ChunkTerrainCodec.decode(sectionBytes, sectionCount, biomeRegistry)
        } finally {
            metrics.recordSectionCopyDecode(System.nanoTime() - sectionCopyDecodeStart)
        }
        return ChunkTerrainCodec.hash(minSection, sections, biomeRegistry)
    }
}
