package calebxzau.rdi.mc.client.dm

import net.minecraft.nbt.CompoundTag
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** The two intentionally local benchmark implementations. */
enum class DmSnapshotMode(val wireName: String, val displayName: String) {
    Disk("disk", "disk"),
    Memory("memory", "memory"),
    ;

    companion object {
        fun parse(value: String): DmSnapshotMode? = entries.firstOrNull { it.wireName == value }
    }
}

data class DmSnapshotPoiOverlay(
    val sectionY: Int,
    val value: CompoundTag?,
    val knownEmpty: Boolean,
)

class DmSnapshotReadiness {
    @Volatile
    var blockers: List<String> = emptyList()
        private set

    fun update(values: List<String>) {
        blockers = values.distinct().toList()
    }

    fun clear() {
        blockers = emptyList()
    }

    fun describe(): String = blockers.takeIf { it.isNotEmpty() }?.joinToString() ?: "未观察到就绪检查结果"
}

enum class DmSnapshotTerrainSource {
    Memory,
    Stored,
    Wait,
    Failure,
}

data class DmSnapshotTerrainDecision(
    val source: DmSnapshotTerrainSource,
    val reason: String,
)

/** Small, Minecraft-independent operations used by the memory capture worker. */
object DmSnapshotMemoryHelpers {
    const val MAX_MEMORY_BYTES: Long = 256L * 1024L * 1024L

    fun isFullChunkReady(status: net.minecraft.server.level.FullChunkStatus?): Boolean =
        status?.isOrAfter(net.minecraft.server.level.FullChunkStatus.FULL) == true

    fun decideTerrainSource(
        hasResidentHolder: Boolean,
        holdersAgree: Boolean,
        pendingUnload: Boolean,
        readyForSaving: Boolean,
        saveFailed: Boolean,
        hasRetainedFullChunk: Boolean,
        retainedDirty: Boolean = false,
    ): DmSnapshotTerrainDecision = when {
        pendingUnload -> DmSnapshotTerrainDecision(DmSnapshotTerrainSource.Wait, "pendingUnload=true")
        !hasResidentHolder -> DmSnapshotTerrainDecision(DmSnapshotTerrainSource.Stored, "no resident holder")
        !holdersAgree -> DmSnapshotTerrainDecision(DmSnapshotTerrainSource.Wait, "visible/updating holder identity mismatch")
        saveFailed -> DmSnapshotTerrainDecision(DmSnapshotTerrainSource.Failure, "saveSync failed or cancelled")
        !readyForSaving -> DmSnapshotTerrainDecision(DmSnapshotTerrainSource.Wait, "holder is not ready for saving")
        !hasRetainedFullChunk -> DmSnapshotTerrainDecision(
            DmSnapshotTerrainSource.Wait,
            "retained FULL LevelChunk missing or incomplete",
        )
        else -> DmSnapshotTerrainDecision(DmSnapshotTerrainSource.Memory, "retained FULL LevelChunk dirty=$retainedDirty")
    }

    fun checkedAggregate(current: Long, tag: net.minecraft.nbt.Tag, description: String): Long {
        val size = checkedSize(tag)
        val next = Math.addExact(current, size)
        require(next <= MAX_MEMORY_BYTES) { "内存捕获数据超过256MiB：$description" }
        return next
    }

    fun classifyColumn(terrainStored: Boolean, entityStored: Boolean, poiStored: Boolean): String = when {
        !terrainStored && !entityStored && !poiStored -> "memory"
        terrainStored && entityStored && poiStored -> "stored"
        else -> "mixed"
    }

    fun classifyTerrain(hasVisible: Boolean, hasUpdating: Boolean, hasPendingUnload: Boolean, fullReady: Boolean): String = when {
        hasPendingUnload -> "wait"
        !hasVisible && !hasUpdating -> "stored"
        fullReady -> "memory"
        else -> "wait"
    }

    fun classifyEntities(loaded: Boolean, statusKnown: Boolean, hasUnload: Boolean, hasResidentContent: Boolean): String = when {
        hasUnload -> "wait"
        loaded -> "memory"
        !statusKnown && !hasResidentContent -> "stored"
        else -> "wait"
    }

    fun mergePoiOverlay(
        disk: CompoundTag?,
        overlays: List<DmSnapshotPoiOverlay>,
        dataVersion: Int,
    ): CompoundTag? {
        if (disk == null && overlays.isEmpty()) return null
        val result = disk?.copy() ?: CompoundTag().apply { putInt("DataVersion", dataVersion) }
        if (disk != null) {
            require(disk.contains("DataVersion", 3)) { "POI记录缺少DataVersion" }
            require(disk.getInt("DataVersion") == dataVersion) {
                "POI DataVersion不兼容：${disk.getInt("DataVersion")} != $dataVersion"
            }
        }
        val sections = if (result.contains("Sections", 10)) result.getCompound("Sections").copy() else CompoundTag()
        overlays.forEach { overlay ->
            val key = overlay.sectionY.toString()
            if (overlay.knownEmpty || overlay.value == null) sections.remove(key)
            else sections.put(key, overlay.value.copy())
        }
        result.put("Sections", sections)
        if (!result.contains("DataVersion", 3)) result.putInt("DataVersion", dataVersion)
        return result
    }

    fun awaitOptional(
        future: CompletableFuture<Optional<CompoundTag>>,
        timeoutNanos: Long,
        description: String,
    ): Result<Optional<CompoundTag>> = runCatching {
        require(timeoutNanos > 0L) { "Read deadline expired before $description" }
        future.get(timeoutNanos, TimeUnit.NANOSECONDS)
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(IllegalStateException("Failed to read $description: ${it.message}", it)) },
    )

    fun checkedSize(tag: net.minecraft.nbt.Tag): Long {
        val size = tag.sizeInBytes().toLong()
        require(size >= 0L) { "Negative NBT size estimate" }
        return size
    }
}

/** Shared reservation coordinator for detached live payloads and queued reads. */
class DmSnapshotMemoryBudget(
    private val limit: Long = DmSnapshotMemoryHelpers.MAX_MEMORY_BYTES,
) {
    private val reserved = AtomicLong(0L)

    fun reserve(tag: net.minecraft.nbt.Tag, description: String): Long {
        val size = tag.sizeInBytes().toLong()
        require(size >= 0L) { "Negative NBT size estimate: $description" }
        while (true) {
            val current = reserved.get()
            val next = try {
                Math.addExact(current, size)
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("内存捕获数据大小溢出：$description", overflow)
            }
            require(next <= limit) { "内存捕获数据超过256MiB：$description" }
            if (reserved.compareAndSet(current, next)) return next
        }
    }

    fun reservedBytes(): Long = reserved.get()
}
