package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.mixin.AccessorChunkMap
import calebxzau.rdi.mc.client.mixin.AccessorEntityStorage
import calebxzau.rdi.mc.client.mixin.AccessorPersistentEntitySectionManager
import calebxzau.rdi.mc.client.mixin.AccessorSectionStorage
import calebxzau.rdi.mc.client.mixin.AccessorServerLevel
import net.minecraft.core.SectionPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.level.ChunkHolder
import net.minecraft.server.level.ServerLevel
import net.minecraft.SharedConstants
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.village.poi.PoiSection
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ImposterProtoChunk
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import net.minecraft.world.level.chunk.storage.ChunkSerializer
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.level.ChunkDataEvent
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Server-thread half of the memory benchmark. It only serializes live objects
 * and submits reads to Minecraft's own storage mailboxes; the returned plans
 * contain no live level or entity objects.
 */
object DmSnapshotMemoryCapture {
    data class Plan(
        val column: DmSnapshotArchive.Column,
        var terrain: CompoundTag?,
        var terrainRead: CompletableFuture<Optional<CompoundTag>>?,
        var entities: CompoundTag?,
        var entitiesRead: CompletableFuture<Optional<CompoundTag>>?,
        val entitiesAbsent: Boolean,
        var poiOverlays: List<DmSnapshotPoiOverlay>,
        var poiRead: CompletableFuture<Optional<CompoundTag>>?,
        val poiDataVersion: Int,
        val terrainStored: Boolean,
        val entityStored: Boolean,
        val poiStored: Boolean,
    )

    data class Batch(
        val plans: List<Plan>,
        val budget: DmSnapshotMemoryBudget,
        val gameTimes: Map<String, Long>,
        val memoryColumns: Int,
        val storedColumns: Int,
        val mixedColumns: Int,
        val memoryEntityColumns: Int,
        val storedEntityColumns: Int,
        val captureTerrainMs: Long,
        val captureEntitiesMs: Long,
        val capturePoiMs: Long,
        val captureOtherMs: Long,
    )

    data class ReadinessBlocker(val coordinate: String, val reason: String) {
        override fun toString(): String = "$coordinate: $reason"
    }

    class Deferred(val blockers: List<ReadinessBlocker>, message: String) : IllegalStateException(message) {
        val coordinates: List<String> = blockers.map { it.coordinate }
    }

    private data class PoiState(val sectionY: Int, val knownEmpty: Boolean, val section: PoiSection?)

    private data class ColumnState(
        val column: DmSnapshotArchive.Column,
        val level: ServerLevel,
        val pos: ChunkPos,
        val holder: ChunkHolder?,
        val terrainChunk: LevelChunk?,
        val terrainStored: Boolean,
        val entityLoaded: Boolean,
        val entityStored: Boolean,
        val entitySections: List<net.minecraft.world.level.entity.EntitySection<Entity>>,
        val entityStorage: SimpleRegionStorage?,
        val poiStates: List<PoiState>,
        val poiStorage: SimpleRegionStorage?,
        val poiStored: Boolean,
    )

    private data class TerrainResolution(
        val source: DmSnapshotTerrainSource,
        val chunk: LevelChunk?,
        val reason: String,
    )

    /** Performs the complete no-load readiness pass before any payload is serialized. */
    fun preflight(
        owner: net.minecraft.client.server.IntegratedServer,
        columns: List<DmSnapshotArchive.Column>,
    ) {
        val wait = ArrayList<ReadinessBlocker>()
        columns.forEach { column ->
            val level = owner.getLevel(
                net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(column.dimensionId),
                ),
            ) ?: error("维度不存在：${column.dimensionId}")
            val pos = ChunkPos(column.chunkX, column.chunkZ)
            val key = pos.toLong()
            val chunkMap = level.chunkSource.chunkMap
            val mapAccessor = chunkMap as AccessorChunkMap
            val visible = chunkMap.getVisibleChunkIfPresent(key)
            val updating = mapAccessor.`rdi$getUpdatingChunkMap`()[key]
            val pending = mapAccessor.`rdi$getPendingUnloads`().containsKey(key)
            val terrain = resolveTerrain(level, column, visible, updating, pending)
            when (terrain.source) {
                DmSnapshotTerrainSource.Wait -> wait += ReadinessBlocker(describe(column), terrain.reason)
                DmSnapshotTerrainSource.Failure -> error("SyncChunk地形保存状态失败：${describe(column)} ${terrain.reason}")
                else -> Unit
            }
            val manager = (level as AccessorServerLevel).`rdi$getEntityManager`()
            val managerAccessor = manager as AccessorPersistentEntitySectionManager
            val loaded = level.areEntitiesLoaded(key)
            val sections = managerAccessor.`rdi$getSectionStorage`().getExistingSectionsInChunk(key).toList()
            val stableStored = !loaded && !managerAccessor.`rdi$getChunkLoadStatuses`().containsKey(key) &&
                !managerAccessor.`rdi$getChunksToUnload`().contains(key) && sections.none { !it.isEmpty }
            val unloading = managerAccessor.`rdi$getChunksToUnload`().contains(key)
            val resident = sections.any { !it.isEmpty }
            if (unloading) {
                wait += ReadinessBlocker(
                    describe(column),
                    "entities loaded=$loaded statusKnown=${managerAccessor.`rdi$getChunkLoadStatuses`().containsKey(key)} unloading=true resident=$resident",
                )
            } else if (!loaded && !stableStored) {
                wait += ReadinessBlocker(
                    describe(column),
                    "entities loaded=$loaded statusKnown=${managerAccessor.`rdi$getChunkLoadStatuses`().containsKey(key)} unloading=false resident=$resident",
                )
            }
        }
        if (wait.isNotEmpty()) {
            val blockers = compactBlockers(wait)
            throw Deferred(blockers, "选中列仍在过渡状态：${blockers.joinToString()}")
        }
    }

    fun capture(
        owner: net.minecraft.client.server.IntegratedServer,
        columns: List<DmSnapshotArchive.Column>,
        cancelled: () -> Boolean,
        transformExecutor: Executor,
    ): Batch {
        val levels = columns.map { column ->
            val key = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(column.dimensionId),
            )
            column to (owner.getLevel(key) ?: error("维度不存在：${column.dimensionId}"))
        }
        val gameTimes = levels.associate { it.second.dimension().location().toString() to it.second.gameTime }
        val plans = ArrayList<Plan>(columns.size)
        val wait = ArrayList<ReadinessBlocker>()
        var terrainMs = 0L
        var entityMs = 0L
        var poiMs = 0L
        var memory = 0
        var stored = 0
        var mixed = 0
        var memoryEntities = 0
        var storedEntities = 0
        val budget = DmSnapshotMemoryBudget()

        val states = levels.map { (column, level) ->
            check(!cancelled()) { "快照测试已取消" }
            val pos = ChunkPos(column.chunkX, column.chunkZ)
            val key = pos.toLong()
            val chunkMap = level.chunkSource.chunkMap
            val chunkAccessor = chunkMap as AccessorChunkMap
            val pending = chunkAccessor.`rdi$getPendingUnloads`().containsKey(key)
            val visible = chunkMap.getVisibleChunkIfPresent(key)
            val updating = chunkAccessor.`rdi$getUpdatingChunkMap`()[key]
            val terrain = resolveTerrain(level, column, visible, updating, pending)
            if (terrain.source == DmSnapshotTerrainSource.Wait) wait += ReadinessBlocker(describe(column), terrain.reason)
            if (terrain.source == DmSnapshotTerrainSource.Failure) error("SyncChunk地形保存状态失败：${describe(column)} ${terrain.reason}")
            val entityManager = (level as AccessorServerLevel).`rdi$getEntityManager`()
            val entityAccessor = entityManager as AccessorPersistentEntitySectionManager
            val entityLoaded = level.areEntitiesLoaded(key)
            val entitySections = entityAccessor.`rdi$getSectionStorage`().getExistingSectionsInChunk(key).toList()
            val statusKnown = entityAccessor.`rdi$getChunkLoadStatuses`().containsKey(key)
            val unloading = entityAccessor.`rdi$getChunksToUnload`().contains(key)
            val resident = entitySections.any { !it.isEmpty }
            val entityKind = DmSnapshotMemoryHelpers.classifyEntities(entityLoaded, statusKnown, unloading, resident)
            if (entityKind == "wait") wait += ReadinessBlocker(describe(column), "entities not ready")
            var entityStorage: SimpleRegionStorage? = null
            if (entityKind == "stored") {
                val storage = entityAccessor.`rdi$getPermanentStorage`() as? net.minecraft.world.level.chunk.storage.EntityStorage
                    ?: error("实体存储适配器不受支持：${entityAccessor.`rdi$getPermanentStorage`().javaClass.name}")
                entityStorage = (storage as AccessorEntityStorage).`rdi$getSimpleRegionStorage`()
            }
            val poiStorageAccessor = level.poiManager as AccessorSectionStorage
            val poiMap = poiStorageAccessor.`rdi$getStorage`()
            val poiStates = ArrayList<PoiState>()
            var poiUnknown = false
            for (sectionY in level.minSection until level.maxSection) {
                val sectionKey = SectionPos.asLong(column.chunkX, sectionY, column.chunkZ)
                if (!poiMap.containsKey(sectionKey)) {
                    poiUnknown = true
                } else {
                    val cached = poiMap[sectionKey]
                    poiStates += PoiState(sectionY, cached == null || cached.isEmpty, cached?.orElse(null))
                }
            }
            ColumnState(column, level, pos, visible ?: updating, terrain.chunk, terrain.source == DmSnapshotTerrainSource.Stored, entityKind == "memory", entityKind == "stored", entitySections, entityStorage, poiStates, if (poiUnknown) poiStorageAccessor.`rdi$getSimpleRegionStorage`() else null, poiUnknown)
        }
        if (wait.isNotEmpty()) {
            val blockers = compactBlockers(wait)
            throw Deferred(blockers, "选中列仍在过渡状态：${blockers.joinToString()}")
        }
        val opsByLevel = states.associate { it.level to it.level.registryAccess().createSerializationContext(NbtOps.INSTANCE) }
        states.forEach { state ->
            check(!cancelled()) { "快照测试已取消" }
            var terrain: CompoundTag? = null
            var terrainRead: CompletableFuture<Optional<CompoundTag>>? = null
            if (state.terrainStored) {
                terrainRead = dispatchRead(state.level.chunkSource.chunkMap.read(state.pos), transformExecutor, budget, "terrain ${describe(state.column)}")
            } else {
                val chunk = state.terrainChunk ?: error("捕获期间terrain未就绪：${describe(state.column)}")
                val started = System.nanoTime()
                val serialized = ChunkSerializer.write(state.level, chunk)
                NeoForge.EVENT_BUS.post(ChunkDataEvent.Save(chunk, state.level, serialized))
                terrain = DmSnapshotArchive.prepareTerrain(serialized)
                validateTerrain(terrain, state.column)
                budget.reserve(terrain, "terrain ${describe(state.column)}")
                terrainMs += (System.nanoTime() - started) / 1_000_000L
            }
            var entities: CompoundTag? = null
            var entitiesRead: CompletableFuture<Optional<CompoundTag>>? = null
            val entitiesAbsent: Boolean
            if (state.entityLoaded) {
                val started = System.nanoTime()
                entities = serializeEntities(state.entitySections, state.column)
                entitiesAbsent = entities == null
                entities?.let { budget.reserve(it, "entities ${describe(state.column)}") }
                entityMs += (System.nanoTime() - started) / 1_000_000L
            } else {
                entitiesRead = dispatchRead(state.entityStorage!!.read(state.pos), transformExecutor, budget, "entities ${describe(state.column)}")
                entitiesAbsent = false
            }
            val poiOverlays = ArrayList<DmSnapshotPoiOverlay>()
            var poiRead: CompletableFuture<Optional<CompoundTag>>? = null
            val startedPoi = System.nanoTime()
            val ops = opsByLevel[state.level]!!
            state.poiStates.forEach { poi ->
                if (poi.knownEmpty) poiOverlays += DmSnapshotPoiOverlay(poi.sectionY, null, true)
                else {
                    val encoded = PoiSection.codec {}.encodeStart(ops, poi.section!!).result()
                        .orElseThrow { IllegalStateException("无法编码POI：${describe(state.column)} sectionY=${poi.sectionY}") } as CompoundTag
                    budget.reserve(encoded, "poi ${describe(state.column)} sectionY=${poi.sectionY}")
                    poiOverlays += DmSnapshotPoiOverlay(poi.sectionY, encoded, false)
                }
            }
            if (state.poiStorage != null) poiRead = dispatchRead(state.poiStorage.read(state.pos), transformExecutor, budget, "poi ${describe(state.column)}")
            poiMs += (System.nanoTime() - startedPoi) / 1_000_000L
            when (DmSnapshotMemoryHelpers.classifyColumn(state.terrainStored, state.entityStored, state.poiStored)) {
                "memory" -> memory++
                "stored" -> stored++
                else -> mixed++
            }
            if (state.entityLoaded) memoryEntities++ else storedEntities++
            plans += Plan(state.column, terrain, terrainRead, entities, entitiesRead, entitiesAbsent, poiOverlays, poiRead, currentDataVersion(), state.terrainStored, state.entityStored, state.poiStored)
        }
        return Batch(plans, budget, gameTimes, memory, stored, mixed, memoryEntities, storedEntities, terrainMs, entityMs, poiMs, 0L)
    }

    private fun dispatchRead(
        raw: CompletableFuture<Optional<CompoundTag>>,
        transformExecutor: Executor,
        budget: DmSnapshotMemoryBudget,
        description: String,
    ): CompletableFuture<Optional<CompoundTag>> = raw.thenApplyAsync({ optional ->
        optional.map { tag ->
            budget.reserve(tag, description)
            tag
        }
    }, transformExecutor)

    private fun resolveTerrain(
        level: ServerLevel,
        column: DmSnapshotArchive.Column,
        visible: ChunkHolder?,
        updating: ChunkHolder?,
        pendingUnload: Boolean,
    ): TerrainResolution {
        val holder = visible ?: updating
        val holdersAgree = visible == null || updating == null || visible === updating
        val saveFuture = holder?.getSaveSyncFuture()
        val saveFailed = saveFuture?.isCompletedExceptionally == true || saveFuture?.isCancelled == true
        var retained: ChunkAccess? = null
        var retainedError: Throwable? = null
        try {
            retained = holder?.getLatestChunk()
        } catch (error: Throwable) {
            retainedError = error
        }
        val normalized = when (retained) {
            is LevelChunk -> retained
            is ImposterProtoChunk -> retained.getWrapped()
            else -> null
        }
        val coordinateMatches = normalized?.pos == ChunkPos(column.chunkX, column.chunkZ)
        val levelMatches = normalized?.getLevel() === level
        val retainedStatus = normalized?.takeIf { coordinateMatches && levelMatches }?.persistedStatus
        val decision = DmSnapshotMemoryHelpers.decideTerrainSource(
            hasResidentHolder = holder != null,
            holdersAgree = holdersAgree,
            pendingUnload = pendingUnload,
            readyForSaving = holder?.isReadyForSaving() == true,
            saveFailed = saveFailed,
            hasRetainedFullChunk = retainedStatus == ChunkStatus.FULL,
            retainedDirty = normalized?.isUnsaved == true,
        )
        val futureReason = if (decision.source == DmSnapshotTerrainSource.Memory || decision.source == DmSnapshotTerrainSource.Stored) {
            "fullFuture=not-required"
        } else {
            val fullFuture = holder?.getFullChunkFuture()
            when {
                fullFuture == null -> "fullFuture=notReady"
                fullFuture.isCancelled -> "fullFuture=cancelled"
                fullFuture.isCompletedExceptionally -> {
                    val error = fullFuture.handle { _, failure -> failure?.cause?.message ?: failure?.message }
                        .getNow(null)
                    "fullFuture=notSuccessful error=${error ?: "unknown"}"
                }
                else -> runCatching {
                    val result = fullFuture.getNow(null)
                    when {
                        result == null -> "fullFuture=notReady"
                        !result.isSuccess -> "fullFuture=notSuccessful error=${result.error ?: "unknown"}"
                        result.orElse(null) == null -> "fullFuture=unloaded"
                        else -> "fullFuture=accessible"
                    }
                }.getOrElse { "fullFuture=unavailable error=${it.message ?: it.javaClass.simpleName}" }
            }
        }
        val reason = when {
            decision.source == DmSnapshotTerrainSource.Failure ->
                "${decision.reason}; $futureReason; retainedError=${retainedError?.message ?: "none"}; saveSyncDone=${saveFuture?.isDone}"
            decision.source == DmSnapshotTerrainSource.Wait ->
                    "${decision.reason}; $futureReason; ticketStatus=${holder?.getFullStatus()}; generationRefs=${holder?.generationRefCount}; " +
                    "retained=${normalized?.javaClass?.simpleName ?: "missing"}; retainedError=${retainedError?.message ?: "none"}; persistedStatus=${retainedStatus ?: normalized?.persistedStatus ?: "missing"}; " +
                    "coordinateMatches=$coordinateMatches levelMatches=$levelMatches saveReady=${holder?.isReadyForSaving()}"
            else -> decision.reason
        }
        return TerrainResolution(decision.source, normalized?.takeIf { decision.source == DmSnapshotTerrainSource.Memory }, reason)
    }

    private fun compactBlockers(blockers: List<ReadinessBlocker>): List<ReadinessBlocker> = blockers
        .groupBy { it.coordinate }
        .map { (coordinate, entries) ->
            ReadinessBlocker(coordinate, entries.map { it.reason }.distinct().joinToString("; "))
        }

    private fun serializeEntities(sections: List<net.minecraft.world.level.entity.EntitySection<Entity>>, column: DmSnapshotArchive.Column): CompoundTag? {
        val list = ListTag()
        sections.flatMap { it.getEntities().toList() }.forEach { entity ->
            if (entity is net.minecraft.server.level.ServerPlayer || !entity.shouldBeSaved() || entity.isPassenger) return@forEach
            val tag = CompoundTag()
            try {
                if (entity.save(tag)) list.add(tag)
            } catch (error: Throwable) {
                throw IllegalStateException("实体序列化失败：${describe(column)}", error)
            }
        }
        if (list.isEmpty()) return null
        return NbtUtils.addCurrentDataVersion(CompoundTag()).apply {
            put("Position", IntArrayTag(intArrayOf(column.chunkX, column.chunkZ)))
            put("Entities", list)
        }
    }

    private fun validateTerrain(tag: CompoundTag, column: DmSnapshotArchive.Column) {
        require(tag.contains("xPos", 3) && tag.contains("zPos", 3) && tag.getInt("xPos") == column.chunkX && tag.getInt("zPos") == column.chunkZ) {
            "terrain坐标不匹配：${describe(column)}"
        }
        require(tag.getString("Status") == "minecraft:full") { "terrain不是FULL状态：${describe(column)}" }
    }

    private fun currentDataVersion(): Int = SharedConstants.getCurrentVersion().dataVersion.version

    private fun describe(column: DmSnapshotArchive.Column) = "${column.dimensionId},${column.chunkX},${column.chunkZ}"
}
