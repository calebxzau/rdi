package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.syncchunk.SyncChunkKey

/**
 * Decides the region entries a checkpoint uploads so that the master mirrors the local world for the
 * kept chunks and holds nothing else. Region files that are not vanilla chunk regions mirror whole.
 */
object DmCheckpointReconcile {
    private val EMPTY = DmRegionSlot(0, 0)

    /**
     * [logged] are writes the change log captured; [readLocal] returns null when the slot changed while
     * it was read, and that chunk waits for the next checkpoint instead of being cleared.
     */
    fun plan(
        keep: Set<SyncChunkKey>,
        logged: List<DmRegionChange>,
        remote: Map<String, DmRegionTable>,
        localRegions: Collection<String>,
        localTable: (String) -> DmRegionTable?,
        readLocal: (String, Int) -> DmRegionChange?,
    ): List<DmRegionChange> {
        val result = LinkedHashMap<DmRegionChunkKey, DmRegionChange>()
        logged.filter { DmCheckpointKeepSet.keeps(keep, it.key.region, it.key.index) }.forEach { result[it.key] = it }
        val compared = LinkedHashMap<String, MutableSet<Int>>()
        keep.forEach { chunk -> DmCheckpointKeepSet.slotsOf(chunk).forEach { (region, index) -> compared.getOrPut(region) { sortedSetOf() } += index } }
        (localRegions + remote.keys).filterNot(DmCheckpointKeepSet::isChunkRegion).distinct().forEach { region ->
            compared.getOrPut(region) { sortedSetOf() } += 0 until 1024
        }
        for ((region, indices) in compared) {
            val local = localTable(region)
            val remoteTable = remote[region]
            for (index in indices) {
                val key = DmRegionChunkKey(region, index)
                if (key in result) continue
                val localSlot = local?.slots?.get(index) ?: EMPTY
                if (localSlot == (remoteTable?.slots?.get(index) ?: EMPTY)) continue
                if (localSlot.sectors == 0) {
                    result[key] = DmRegionChange(key, localSlot.timestamp, null, 0)
                    continue
                }
                result[key] = readLocal(region, index)?.copy(key = key) ?: continue
            }
        }
        remote.forEach { (region, table) ->
            if (!DmCheckpointKeepSet.isChunkRegion(region)) return@forEach
            table.slots.forEachIndexed { index, slot ->
                if (slot.sectors > 0 && !DmCheckpointKeepSet.keeps(keep, region, index)) {
                    result.putIfAbsent(DmRegionChunkKey(region, index), DmRegionChange(DmRegionChunkKey(region, index), 0, null, 0))
                }
            }
        }
        return result.values.toList()
    }
}
