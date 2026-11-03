package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey

/**
 * The chunks a DM checkpoint keeps on the master: the sync chunks plus a 3x3 area around every
 * player's last known position. Everything else regenerates after the room changes hosts.
 */
object DmCheckpointKeepSet {
    /** Vanilla region folders whose files are indexed by chunk position. */
    private val CHUNK_FOLDERS = setOf("region", "entities", "poi")
    const val PLAYER_RADIUS = 1
    private const val OVERWORLD = "minecraft:overworld"
    private const val NETHER = "minecraft:the_nether"
    private const val END = "minecraft:the_end"

    fun build(syncChunks: Collection<SyncChunkKey>, players: Collection<SyncChunkKey>): Set<SyncChunkKey> {
        val keep = HashSet<SyncChunkKey>(syncChunks)
        players.forEach { player ->
            for (dx in -PLAYER_RADIUS..PLAYER_RADIUS) for (dz in -PLAYER_RADIUS..PLAYER_RADIUS) {
                keep += SyncChunkKey(player.dimensionId, player.chunkX + dx, player.chunkZ + dz)
            }
        }
        return keep
    }

    /** True when [region] is a vanilla chunk region file, whose slots follow the keep set. */
    fun isChunkRegion(region: String): Boolean = locate(region) != null

    /** The chunk stored in slot [index] of [region], or null when [region] is not a vanilla chunk region file. */
    fun chunkOf(region: String, index: Int): SyncChunkKey? {
        require(index in 0 until 1024) { "区块索引无效" }
        val (dimension, regionX, regionZ) = locate(region) ?: return null
        return SyncChunkKey(dimension, regionX * 32 + (index and 31), regionZ * 32 + (index ushr 5))
    }

    /** The region files and slot that can hold [chunk], one per chunk folder. */
    fun slotsOf(chunk: SyncChunkKey): List<Pair<String, Int>> {
        val base = when (chunk.dimensionId) {
            OVERWORLD -> ""
            NETHER -> "DIM-1/"
            END -> "DIM1/"
            else -> "dimensions/${chunk.dimensionId.substringBefore(':')}/${chunk.dimensionId.substringAfter(':')}/"
        }
        val name = "r.${Math.floorDiv(chunk.chunkX, 32)}.${Math.floorDiv(chunk.chunkZ, 32)}.mca"
        val index = (Math.floorMod(chunk.chunkZ, 32) shl 5) or Math.floorMod(chunk.chunkX, 32)
        return CHUNK_FOLDERS.sorted().map { folder -> "$base$folder/$name" to index }
    }

    /** True when slot [index] of [region] belongs on the master; files that are not vanilla chunk regions always do. */
    fun keeps(keep: Set<SyncChunkKey>, region: String, index: Int): Boolean = chunkOf(region, index)?.let { it in keep } ?: true

    private fun locate(region: String): Triple<String, Int, Int>? {
        val parts = region.split('/')
        if (parts.size < 2 || parts[parts.size - 2] !in CHUNK_FOLDERS) return null
        val coordinates = parts.last().removePrefix("r.").removeSuffix(".mca").split('.')
        if (!parts.last().startsWith("r.") || !parts.last().endsWith(".mca") || coordinates.size != 2) return null
        val regionX = coordinates[0].toIntOrNull() ?: return null
        val regionZ = coordinates[1].toIntOrNull() ?: return null
        val prefix = parts.subList(0, parts.size - 2)
        val dimension = when {
            prefix.isEmpty() -> OVERWORLD
            prefix == listOf("DIM-1") -> NETHER
            prefix == listOf("DIM1") -> END
            prefix.size >= 3 && prefix[0] == "dimensions" -> "${prefix[1]}:${prefix.drop(2).joinToString("/")}"
            else -> return null
        }
        return Triple(dimension, regionX, regionZ)
    }
}
