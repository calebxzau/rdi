package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey

/**
 * What a rollback restores. Only the chunks and files the point holds are written; chunks and files
 * that appeared after it (e.g. a player who joined later) are left as they are.
 */
object SyncChunkRollbackPlan {
    /** The point [request] restores; fails when it belongs to another world or no longer exists. */
    fun target(store: SyncChunkBackupStore, request: SyncChunkRollbackRequest): Result<SyncChunkBackupPoint> = runCatching {
        require(request.worldToken == store.worldToken) { "回档点不属于当前世界，存档已被替换" }
        store.point(request.pointId) ?: throw IllegalArgumentException("回档点不存在或已过期")
    }

    /**
     * Chunks the before-rollback point covers: every chunk the rollback writes, plus those of the
     * latest point, so restoring it puts back the world as it was before the rollback.
     */
    fun beforeRollbackKeys(store: SyncChunkBackupStore, target: SyncChunkBackupPoint): List<SyncChunkKey> =
        (target.chunks.map { it.key } + store.latest()?.chunks?.map { it.key }.orEmpty())
            .distinct()
            .sortedWith(compareBy({ it.dimensionId }, { it.chunkX }, { it.chunkZ }))
}
