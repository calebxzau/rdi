package calebxzau.rdi.mc.client.dm

import net.minecraft.nbt.CompoundTag
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Shared worker-side writer for detached memory batches. */
object DmSnapshotBatchWriter {
    private const val MAX_STAGING_BYTES = 256L * 1024L * 1024L

    data class Record(
        val column: DmSnapshotArchive.Column,
        val terrainPath: String,
        val entitiesPath: String?,
        val poiPath: String?,
        val sourceMode: String,
    )

    data class Output(val records: List<Record>, val storageWaitMs: Long, val writeMs: Long)

    fun write(
        batch: DmSnapshotMemoryCapture.Batch,
        staging: Path,
        cancelled: () -> Boolean,
        deadlineNanos: Long = System.nanoTime() + 30_000_000_000L,
    ): Output {
        var stagingBytes = 0L
        var storageWaitNanos = 0L
        val records = ArrayList<Record>(batch.plans.size)
        val started = System.nanoTime()
        fun check() {
            check(!cancelled()) { "SyncChunk同步已取消" }
            require(deadlineNanos - System.nanoTime() > 0L) { "SyncChunk同步本地写入超时" }
        }
        fun read(future: CompletableFuture<java.util.Optional<CompoundTag>>?, description: String): CompoundTag? {
            if (future == null) return null
            val waitStart = System.nanoTime()
            return try {
                DmSnapshotMemoryHelpers.awaitOptional(future, deadlineNanos - System.nanoTime(), description).getOrThrow().orElse(null)
            } finally {
                storageWaitNanos += System.nanoTime() - waitStart
            }
        }
        fun writeRecord(column: DmSnapshotArchive.Column, kind: String, tag: CompoundTag) {
            check()
            val remaining = MAX_STAGING_BYTES - stagingBytes
            require(remaining > 0L) { "快照暂存数据超过256MiB" }
            stagingBytes = Math.addExact(stagingBytes, DmSnapshotArchive.writeRecord(staging, column, kind, tag, remaining).getOrThrow())
            require(stagingBytes <= MAX_STAGING_BYTES) { "快照暂存数据超过256MiB" }
        }
        try {
            batch.plans.forEach { plan ->
                check()
                val terrainFromStorage = plan.terrain == null
                val terrain = plan.terrain ?: read(plan.terrainRead, "terrain ${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}")?.let(DmSnapshotArchive::prepareTerrain)
                    ?: error("缺少terrain记录：${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}")
                if (terrainFromStorage) batch.budget.reserve(terrain, "prepared terrain ${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}")
                require(terrain.contains("xPos", 3) && terrain.contains("zPos", 3) && terrain.getInt("xPos") == plan.column.chunkX && terrain.getInt("zPos") == plan.column.chunkZ) {
                    "terrain坐标不匹配：${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}"
                }
                require(terrain.getString("Status") == "minecraft:full") { "terrain不是FULL状态：${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}" }
                writeRecord(plan.column, "terrain", terrain)
                val entities = plan.entities ?: read(plan.entitiesRead, "entities ${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}")
                entities?.let {
                    val position = it.getIntArray("Position")
                    require(position.size == 2 && position[0] == plan.column.chunkX && position[1] == plan.column.chunkZ) {
                        "实体记录坐标不匹配：${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}"
                    }
                    writeRecord(plan.column, "entities", it)
                }
                val diskPoi = read(plan.poiRead, "poi ${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}")
                val poi = DmSnapshotMemoryHelpers.mergePoiOverlay(diskPoi, plan.poiOverlays, plan.poiDataVersion)
                poi?.let {
                    if (diskPoi != null) batch.budget.reserve(it, "merged poi ${plan.column.dimensionId},${plan.column.chunkX},${plan.column.chunkZ}")
                    writeRecord(plan.column, "poi", it)
                }
                val base = "dimensions/${DmSnapshotArchive.dimensionPath(plan.column.dimensionId)}"
                records += Record(
                    plan.column,
                    "$base/terrain/${plan.column.chunkX}.${plan.column.chunkZ}.nbt",
                    entities?.let { "$base/entities/${plan.column.chunkX}.${plan.column.chunkZ}.nbt" },
                    poi?.let { "$base/poi/${plan.column.chunkX}.${plan.column.chunkZ}.nbt" },
                    DmSnapshotMemoryHelpers.classifyColumn(plan.terrainStored, plan.entityStored, plan.poiStored),
                )
            }
            val totalNanos = System.nanoTime() - started
            return Output(records.toList(), storageWaitNanos / 1_000_000L, ((totalNanos - storageWaitNanos).coerceAtLeast(0L)) / 1_000_000L)
        } finally {
            // Clear every plan, including plans not reached after a failed write.
            batch.plans.forEach { plan ->
                plan.terrain = null
                plan.entities = null
                plan.terrainRead = null
                plan.entitiesRead = null
                plan.poiRead = null
                plan.poiOverlays = emptyList()
            }
        }
    }
}
