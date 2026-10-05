package calebxzau.rdi.mc.syncchunk

/**
 * Full sync chunk list sent to clients; each list replaces the previous one. Owners stay on the server,
 * which is the only place that checks them.
 */
class SyncChunkList(chunks: List<SyncChunkKey>) {
    val chunks: List<SyncChunkKey> = chunks.toList()

    init {
        require(this.chunks.size <= MAX_ENTRY_COUNT) { "同步区块数量超出范围：${this.chunks.size}" }
        val seen = HashSet<SyncChunkKey>(this.chunks.size)
        this.chunks.forEach { key ->
            require(isValidDimensionId(key.dimensionId)) { "同步区块维度ID无效：${key.dimensionId}" }
            require(seen.add(key)) { "同步区块重复：${key}" }
        }
    }

    companion object {
        const val MAX_ENTRY_COUNT = SyncChunkState.DEFAULT_MAX_TOTAL
        const val MAX_DIMENSION_ID_LENGTH = 512

        fun of(entries: List<SyncChunkEntry>): SyncChunkList = SyncChunkList(entries.map { it.key })

        /** Minecraft resource location rules: `[namespace:]path`, namespace `[a-z0-9_.-]`, path `[a-z0-9/._-]`. */
        fun isValidDimensionId(dimensionId: String): Boolean {
            if (dimensionId.isBlank() || dimensionId.length > MAX_DIMENSION_ID_LENGTH) return false
            val separator = dimensionId.indexOf(':')
            val namespace = if (separator > 0) dimensionId.substring(0, separator) else ""
            val path = if (separator >= 0) dimensionId.substring(separator + 1) else dimensionId
            return namespace.all { it in 'a'..'z' || it in '0'..'9' || it in "_-." } &&
                path.all { it in 'a'..'z' || it in '0'..'9' || it in "_-./" }
        }
    }
}
