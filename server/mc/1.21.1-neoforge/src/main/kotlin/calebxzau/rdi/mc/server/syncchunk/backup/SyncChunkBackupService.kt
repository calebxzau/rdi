package calebxzau.rdi.mc.server.syncchunk.backup

import calebxzau.rdi.mc.server.mixin.AEntityStorage
import calebxzau.rdi.mc.server.mixin.AMinecraftServer
import calebxzau.rdi.mc.server.mixin.APersistentEntitySectionManager
import calebxzau.rdi.mc.server.mixin.ASectionStorage
import calebxzau.rdi.mc.server.mixin.AServerLevel
import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupFileRules
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIds
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupKind
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupPart
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupStore
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupWorldToken
import calebxzau.rdi.mc.syncchunk.server.SyncChunkService
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.ChunkStorage
import net.minecraft.world.level.chunk.storage.EntityStorage
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.common.IOUtilities
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Takes a backup point (回档点) of every sync chunk and of player/world data right after Minecraft's
 * 5-minute autosave. The server thread only collects storage handles; reading, hashing and writing
 * run on one backup thread. Chunks are read through Minecraft's own IO workers, which return data
 * still queued for writing, so a point matches what the autosave has just saved. NeoForge writes saved data
 * (`data/`) on an IO thread too, so files are collected only after the autosave's queued writes finish.
 */
@EventBusSubscriber(modid = "rdi")
object SyncChunkBackupService {
    private val logger = LoggerFactory.getLogger(SyncChunkBackupService::class.java)

    private const val CHUNKS_PER_BATCH = 8
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val STOP_WAIT_SECONDS = 15L
    private const val FILE_RETRY_DELAY_MS = 200L
    private const val SAVED_DATA_WAIT_SECONDS = 30L

    private val enabled = System.getProperty("rdi.syncChunkBackup") != "false"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rdi-sync-chunk-backup").apply { isDaemon = true }
    }

    /** Server thread only. */
    private var current: Future<*>? = null

    /** Server thread only. `ticksUntilAutosave` counts down and is reset in the tick that autosaves. */
    private var lastTicksUntilAutosave = Int.MAX_VALUE

    @Volatile
    private var stopping = false

    /** Backup thread only. */
    private var store: SyncChunkBackupStore? = null

    private class ChunkSource(
        val key: SyncChunkKey,
        val pos: ChunkPos,
        val terrain: ChunkStorage,
        val entities: SimpleRegionStorage?,
        val poi: SimpleRegionStorage?,
    )

    private class Plan(
        val worldDir: Path,
        val hostDir: Path,
        /** Completes after the saved data writes this autosave queued on NeoForge's IO worker. */
        val savedDataWritten: CompletableFuture<Void?>,
        val createdAt: Long,
        val gameTime: Long,
        val chunks: List<ChunkSource>,
        /** Chunks whose dimension is not loaded; the previous point's copy is kept. */
        val unloadedDimensionChunks: List<SyncChunkKey>,
    )

    private class Reads(
        val terrain: CompletableFuture<Optional<CompoundTag>>,
        val entities: CompletableFuture<Optional<CompoundTag>>?,
        val poi: CompletableFuture<Optional<CompoundTag>>?,
    )

    @SubscribeEvent
    @JvmStatic
    fun onServerStarting(event: ServerStartingEvent) {
        stopping = false
        lastTicksUntilAutosave = Int.MAX_VALUE
    }

    @SubscribeEvent
    @JvmStatic
    fun onServerTick(event: ServerTickEvent.Post) {
        if (!enabled) return
        val server = event.server as? DedicatedServer ?: return
        if (!autosavedThisTick(server) || stopping) return
        if (current?.isDone == false) {
            logger.warn("Skipping a sync chunk backup point: the previous one is still being written")
            return
        }
        val plan = runCatching { plan(server) }.getOrElse { exception ->
            logger.error("Failed to prepare a sync chunk backup point", exception)
            return
        } ?: return
        current = executor.submit { write(plan) }
    }

    /**
     * `MinecraftServer.tickServer` autosaves when its countdown reaches zero and then resets it, before the
     * post-tick event; the countdown otherwise only decreases (a lower tick rate may shorten it).
     */
    private fun autosavedThisTick(server: MinecraftServer): Boolean {
        val ticks = (server as AMinecraftServer).`rdi$ticksUntilAutosave`()
        val autosaved = ticks > lastTicksUntilAutosave
        lastTicksUntilAutosave = ticks
        return autosaved
    }

    /** Lets a running backup stop at its next batch; the storage it reads closes after this event. */
    @SubscribeEvent
    @JvmStatic
    fun onServerStopping(event: ServerStoppingEvent) {
        stopping = true
        val running = current ?: return
        try {
            running.get(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: TimeoutException) {
            logger.warn("Sync chunk backup did not stop within {}s", STOP_WAIT_SECONDS, exception)
        } catch (exception: ExecutionException) {
            logger.error("Sync chunk backup failed while the server was stopping", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        current = null
    }

    /**
     * Server thread: resolves storage handles and flushes POI sections that are only in memory. The saved data
     * marker joins NeoForge's IO worker chain from the same thread the autosave queued its writes on.
     */
    private fun plan(server: MinecraftServer): Plan? {
        val entries = SyncChunkService.store(server).getOrElse { exception ->
            logger.error("Failed to load sync chunks for a backup point", exception)
            return null
        }.snapshot()
        val chunks = ArrayList<ChunkSource>(entries.size)
        val unloaded = ArrayList<SyncChunkKey>()
        entries.forEach { entry ->
            val level = levelOf(server, entry.key.dimensionId)
            if (level == null) {
                unloaded += entry.key
                return@forEach
            }
            val pos = ChunkPos(entry.key.chunkX, entry.key.chunkZ)
            level.poiManager.flush(pos)
            chunks += ChunkSource(entry.key, pos, level.chunkSource.chunkMap, entityStorage(level), (level.poiManager as ASectionStorage).`rdi$simpleRegionStorage`())
        }
        val savedDataWritten = CompletableFuture<Void?>()
        IOUtilities.withIOWorker { savedDataWritten.complete(null) }
        return Plan(
            worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(),
            hostDir = server.serverDirectory.toAbsolutePath().normalize(),
            savedDataWritten = savedDataWritten,
            createdAt = System.currentTimeMillis(),
            gameTime = server.overworld().gameTime,
            chunks = chunks,
            unloadedDimensionChunks = unloaded,
        )
    }

    private fun levelOf(server: MinecraftServer, dimensionId: String): ServerLevel? {
        val location = ResourceLocation.tryParse(dimensionId) ?: return null
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, location))
    }

    /** Null when a mod replaced the vanilla entity storage; entity parts then keep the previous copy. */
    private fun entityStorage(level: ServerLevel): SimpleRegionStorage? {
        val manager = (level as AServerLevel).`rdi$entityManager`()
        val storage = (manager as APersistentEntitySectionManager).`rdi$permanentStorage`() as? EntityStorage ?: return null
        return (storage as AEntityStorage).`rdi$simpleRegionStorage`()
    }

    /** Backup thread. */
    private fun write(plan: Plan) {
        val started = System.nanoTime()
        val store = openStore(plan).getOrElse { exception ->
            logger.error("Failed to open the sync chunk backup store", exception)
            return
        }
        val session = store.begin(SyncChunkBackupIds.newUuidV7(plan.createdAt).toString(), SyncChunkBackupKind.Auto, plan.createdAt, plan.gameTime)
        var unavailableParts = 0
        var skippedChunks = 0
        var reusedFiles = 0
        try {
            plan.unloadedDimensionChunks.forEach { key -> if (!session.reuseChunk(key)) skippedChunks++ }
            plan.chunks.chunked(CHUNKS_PER_BATCH).forEach { batch ->
                if (stopping) throw CancellationException("server is stopping")
                val reads = batch.map { source ->
                    source to Reads(source.terrain.read(source.pos), source.entities?.read(source.pos), source.poi?.read(source.pos))
                }
                reads.forEach { (source, read) ->
                    val terrain = part(source.key, read.terrain, CanonicalNbtHash.VOLATILE_TERRAIN_KEYS)
                    val entities = read.entities?.let { part(source.key, it, emptySet()) } ?: SyncChunkBackupPart.Unavailable
                    val poi = read.poi?.let { part(source.key, it, emptySet()) } ?: SyncChunkBackupPart.Unavailable
                    unavailableParts += listOf(terrain, entities, poi).count { it == SyncChunkBackupPart.Unavailable }
                    if (!session.putChunk(source.key, terrain, entities, poi).getOrThrow()) skippedChunks++
                }
            }
            awaitSavedData(plan)
            SyncChunkBackupFileRules.collect(plan.worldDir).getOrThrow().forEach { path ->
                if (stopping) throw CancellationException("server is stopping")
                when (val read = readFile(plan.worldDir, path)) {
                    is FileRead.Read -> session.putFile(path, read.bytes).getOrThrow()
                    FileRead.Gone -> Unit
                    FileRead.Unreadable -> if (session.reuseFile(path)) reusedFiles++
                }
            }
            val result = session.commit(System.currentTimeMillis()).getOrThrow()
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            if (result == null) {
                logger.info("Sync chunk backup: nothing changed since the previous point ({}ms)", tookMs)
            } else {
                val point = result.point
                logger.info(
                    "Sync chunk backup point {}: chunks={} terrainChanged={} entityChanged={} players={} playersChanged={} filesChanged={} added={}B used={}B dropped={} {}ms",
                    point.id, point.chunkCount, point.changed.terrainChunks, point.changed.entityChunks, point.playerCount,
                    point.changed.players, point.changed.files, point.addedBytes, result.usedBytes, result.droppedPoints, tookMs,
                )
            }
            if (unavailableParts > 0 || skippedChunks > 0 || reusedFiles > 0) {
                logger.warn(
                    "Sync chunk backup kept earlier copies: unreadableParts={} skippedChunks={} reusedFiles={}",
                    unavailableParts, skippedChunks, reusedFiles,
                )
            }
        } catch (exception: Throwable) {
            session.abort().onFailure(exception::addSuppressed)
            if (exception is CancellationException) {
                logger.info("Sync chunk backup point abandoned because the server is stopping")
            } else {
                logger.error("Failed to write a sync chunk backup point", exception)
            }
        }
    }

    /** Files are still collected after a timeout; a file being replaced then keeps its previous copy. */
    private fun awaitSavedData(plan: Plan) {
        try {
            plan.savedDataWritten.get(SAVED_DATA_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("interrupted")
        } catch (exception: TimeoutException) {
            logger.warn("Saved data writes did not finish within {}s; backing up the files on disk now", SAVED_DATA_WAIT_SECONDS, exception)
        }
    }

    private fun openStore(plan: Plan): Result<SyncChunkBackupStore> = runCatching {
        val token = SyncChunkBackupWorldToken.readOrCreate(plan.worldDir).getOrThrow()
        store?.takeIf { it.worldToken == token }?.let { return@runCatching it }
        val (opened, report) = SyncChunkBackupStore.open(SyncChunkBackupStore.rootOf(plan.hostDir), token).getOrThrow()
        if (report.discardedForOtherWorld) logger.warn("Discarded sync chunk backup points of a previous world")
        report.invalidPoints.forEach { logger.warn("Removed invalid sync chunk backup point {}: {}", it.name, it.reason) }
        if (report.orphanObjects > 0) logger.info("Removed {} unreferenced sync chunk backup objects", report.orphanObjects)
        store = opened
        opened
    }

    private fun part(key: SyncChunkKey, read: CompletableFuture<Optional<CompoundTag>>, ignoredRootKeys: Set<String>): SyncChunkBackupPart {
        val tag = try {
            read.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).orElse(null) ?: return SyncChunkBackupPart.Absent
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("interrupted")
        } catch (exception: ExecutionException) {
            logger.warn("Failed to read sync chunk {} for backup", key, exception)
            return SyncChunkBackupPart.Unavailable
        } catch (exception: TimeoutException) {
            logger.warn("Timed out reading sync chunk {} for backup", key, exception)
            return SyncChunkBackupPart.Unavailable
        }
        val raw = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { NbtIo.write(tag, it) } }.toByteArray()
        return SyncChunkBackupPart.Present(CanonicalNbtHash.sha1(tag, ignoredRootKeys), raw)
    }

    private sealed interface FileRead {
        class Read(val bytes: ByteArray) : FileRead
        /** Deleted after it was listed; it is left out of the point. */
        data object Gone : FileRead
        /** Kept changing (a mod was writing it) or could not be read; the previous point's copy is kept. */
        data object Unreadable : FileRead
    }

    private fun readFile(worldDir: Path, path: String): FileRead {
        val file = worldDir.resolve(path)
        return SyncChunkBackupFileRules.readStable(file).recoverCatching { exception ->
            if (exception is NoSuchFileException) throw exception
            Thread.sleep(FILE_RETRY_DELAY_MS)
            SyncChunkBackupFileRules.readStable(file).getOrThrow()
        }.fold({ FileRead.Read(it) }) { exception ->
            when (exception) {
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    throw CancellationException("interrupted")
                }
                is NoSuchFileException -> {
                    logger.debug("{} was deleted before the sync chunk backup read it", file)
                    FileRead.Gone
                }
                else -> {
                    logger.warn("Failed to read {} for sync chunk backup", file, exception)
                    FileRead.Unreadable
                }
            }
        }
    }
}
