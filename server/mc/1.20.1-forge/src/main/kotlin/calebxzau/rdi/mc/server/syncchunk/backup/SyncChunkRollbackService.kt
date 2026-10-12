package calebxzau.rdi.mc.server.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupFileRules
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupIds
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupKind
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupPart
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupPoint
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupStore
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkBackupWorldToken
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackFiles
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackOutcome
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackPlan
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackRequest
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackResult
import calebxzau.rdi.mc.syncchunk.backup.SyncChunkRollbackState
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.storage.RegionFileStorage
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.storage.LevelResource
import net.minecraftforge.event.server.ServerAboutToStartEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

/**
 * Applies a rollback (回档) the master requested, before the world is loaded, so no chunk is in
 * memory while its region files change.
 *
 * 1. Every object of the target point is read and checked first; if any fails, the world is not
 *    touched and the server starts normally with a failed result.
 * 2. A before-rollback point records the current state, and the request moves to `Applying`.
 * 3. Chunks and files are written. Writing is idempotent, so a start after a crash writes them again.
 *    A write error stops the server from starting rather than letting players into a half-restored
 *    world; the owner can then give up the rollback, which restores the before-rollback point.
 */
@Mod.EventBusSubscriber(modid = "rdi")
object SyncChunkRollbackService {
    private val logger = LoggerFactory.getLogger(SyncChunkRollbackService::class.java)

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    @JvmStatic
    fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        val server = event.server as? DedicatedServer ?: return
        val hostDir = server.serverDirectory.toPath().toAbsolutePath().normalize()
        val worldDir = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()
        val root = SyncChunkBackupStore.rootOf(hostDir)
        val request = SyncChunkRollbackFiles.readRequest(root).getOrElse { exception ->
            logger.error("Discarding an unreadable rollback request", exception)
            finish(root, null, SyncChunkRollbackOutcome.Failed, "回档请求无法读取")
            return
        } ?: return
        apply(root, worldDir, server.worldData.overworldData().gameTime, request)
    }

    /**
     * Throws when the world may be partly restored; the server must not start then. [folderOf] maps a
     * dimension id to its storage folder; tests replace it to keep Minecraft's level classes unloaded.
     */
    internal fun apply(
        root: Path,
        worldDir: Path,
        gameTime: Long,
        initial: SyncChunkRollbackRequest,
        folderOf: (String) -> Path = { dimensionFolder(worldDir, it) },
    ) {
        val started = System.nanoTime()
        var request = initial
        logger.info("Applying rollback {} to point {} ({})", request.id, request.pointId, request.state)
        val (store, target) = runCatching {
            val token = SyncChunkBackupWorldToken.readOrCreate(worldDir).getOrThrow()
            val store = openStore(root, token)
            val target = SyncChunkRollbackPlan.target(store, request).getOrThrow()
            verify(store, target)
            store to target
        }.getOrElse { exception ->
            if (request.state == SyncChunkRollbackState.Applying) {
                throw IllegalStateException("Rollback ${request.id} was partly written and cannot continue; the server will not start", exception)
            }
            logger.error("Rollback {} cannot be applied; the world is unchanged", request.id, exception)
            finish(root, request, SyncChunkRollbackOutcome.Failed, reason(exception))
            return
        }
        RegionStorages(folderOf).use { storages ->
            if (request.state == SyncChunkRollbackState.Requested) {
                val beforeId = runCatching { captureBeforeRollback(store, storages, worldDir, target, request, gameTime) }.getOrElse { exception ->
                    logger.error("Failed to record the state before rollback {}; the world is unchanged", request.id, exception)
                    finish(root, request, SyncChunkRollbackOutcome.Failed, "无法备份回档前的状态：${reason(exception)}")
                    return
                }
                val applying = request.copy(state = SyncChunkRollbackState.Applying, beforePointId = beforeId)
                SyncChunkRollbackFiles.writeRequest(root, applying).getOrElse { exception ->
                    logger.error("Failed to record that rollback {} started; the world is unchanged", request.id, exception)
                    finish(root, request, SyncChunkRollbackOutcome.Failed, "无法记录回档进度：${reason(exception)}")
                    return
                }
                request = applying
            }
            try {
                restore(store, storages, worldDir, target)
            } catch (exception: Exception) {
                throw IllegalStateException("Rollback ${request.id} failed while writing; the server will not start and retries on the next start", exception)
            }
        }
        finish(root, request, SyncChunkRollbackOutcome.Done, null)
        logger.info(
            "Rollback {} restored point {}: chunks={} files={} beforePoint={} {}ms",
            request.id, target.id, target.chunks.size, target.files.size, request.beforePointId,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        )
    }

    private fun openStore(root: Path, token: String): SyncChunkBackupStore {
        val (store, report) = SyncChunkBackupStore.open(root, token).getOrThrow()
        if (report.discardedForOtherWorld) logger.warn("Discarded sync chunk backup points of a previous world")
        report.invalidPoints.forEach { logger.warn("Removed invalid sync chunk backup point {}: {}", it.name, it.reason) }
        if (report.orphanObjects > 0) logger.info("Removed {} unreferenced sync chunk backup objects", report.orphanObjects)
        return store
    }

    /** Reads every object once and checks it, so damage is found before anything is written. */
    private fun verify(store: SyncChunkBackupStore, target: SyncChunkBackupPoint) {
        target.chunks.forEach { chunk ->
            chunk.terrain?.let { readTag(store, it, CanonicalNbtHash.VOLATILE_TERRAIN_KEYS) }
            chunk.entities?.let { readTag(store, it, emptySet()) }
            chunk.poi?.let { readTag(store, it, emptySet()) }
        }
        target.files.forEach { store.readFile(it).getOrThrow() }
    }

    private fun readTag(store: SyncChunkBackupStore, hash: String, ignoredRootKeys: Set<String>): CompoundTag {
        val bytes = store.readObject(hash).getOrThrow()
        val tag = DataInputStream(ByteArrayInputStream(bytes)).use { NbtIo.read(it, NbtAccounter.UNLIMITED) }
        if (CanonicalNbtHash.sha1(tag, ignoredRootKeys) != hash) throw IOException("备份区块内容与记录不一致：${hash}")
        return tag
    }

    private fun captureBeforeRollback(
        store: SyncChunkBackupStore,
        storages: RegionStorages,
        worldDir: Path,
        target: SyncChunkBackupPoint,
        request: SyncChunkRollbackRequest,
        gameTime: Long,
    ): String {
        val createdAt = System.currentTimeMillis()
        val id = SyncChunkBackupIds.newUuidV7(createdAt).toString()
        val session = store.begin(id, SyncChunkBackupKind.BeforeRollback, createdAt, gameTime, rollbackId = request.id)
        try {
            SyncChunkRollbackPlan.beforeRollbackKeys(store, target).forEach { key ->
                val storage = storages.of(key.dimensionId)
                val pos = ChunkPos(key.chunkX, key.chunkZ)
                session.putChunk(
                    key,
                    part(storage.terrain.read(pos), CanonicalNbtHash.VOLATILE_TERRAIN_KEYS),
                    part(storage.entities.read(pos), emptySet()),
                    part(storage.poi.read(pos), emptySet()),
                ).getOrThrow()
            }
            SyncChunkBackupFileRules.collect(worldDir).getOrThrow().forEach { path ->
                session.putFile(path, SyncChunkBackupFileRules.readStable(worldDir.resolve(path)).getOrThrow()).getOrThrow()
            }
            // The target must survive this point's retention pass even without the request file.
            checkNotNull(session.commit(System.currentTimeMillis(), pinned = setOf(target.id)).getOrThrow()) { "回档前的回档点未保存" }
        } catch (exception: Throwable) {
            session.abort().onFailure(exception::addSuppressed)
            throw exception
        }
        return id
    }

    private fun part(tag: CompoundTag?, ignoredRootKeys: Set<String>): SyncChunkBackupPart {
        tag ?: return SyncChunkBackupPart.Absent
        val raw = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { NbtIo.write(tag, it) } }.toByteArray()
        return SyncChunkBackupPart.Present(CanonicalNbtHash.sha1(tag, ignoredRootKeys), raw)
    }

    /** A part the point does not have is cleared, e.g. entities that appeared after the point. */
    private fun restore(store: SyncChunkBackupStore, storages: RegionStorages, worldDir: Path, target: SyncChunkBackupPoint) {
        target.chunks.forEach { chunk ->
            val storage = storages.of(chunk.key.dimensionId)
            val pos = ChunkPos(chunk.key.chunkX, chunk.key.chunkZ)
            storage.terrain.write(pos, chunk.terrain?.let { readTag(store, it, CanonicalNbtHash.VOLATILE_TERRAIN_KEYS) })
            storage.entities.write(pos, chunk.entities?.let { readTag(store, it, emptySet()) })
            storage.poi.write(pos, chunk.poi?.let { readTag(store, it, emptySet()) })
        }
        storages.flush()
        target.files.forEach { file -> replaceFile(worldDir.resolve(file.path), store.readFile(file).getOrThrow()) }
    }

    private fun replaceFile(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".${target.name}-", ".tmp")
        try {
            Files.write(tmp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /** Writes the result first: if removing the request fails, the next start only writes everything again. */
    private fun finish(root: Path, request: SyncChunkRollbackRequest?, outcome: SyncChunkRollbackOutcome, message: String?) {
        val result = SyncChunkRollbackResult(
            requestId = request?.id,
            pointId = request?.pointId,
            beforePointId = request?.beforePointId,
            outcome = outcome,
            message = message,
            finishedAt = System.currentTimeMillis(),
        )
        SyncChunkRollbackFiles.writeResult(root, result).onFailure { logger.error("Failed to write the rollback result", it) }
        SyncChunkRollbackFiles.deleteRequest(root).onFailure { logger.error("Failed to remove the finished rollback request", it) }
    }

    private fun reason(exception: Throwable): String = exception.message ?: exception.javaClass.simpleName

    private fun dimensionFolder(worldDir: Path, dimensionId: String): Path {
        val location = ResourceLocation.tryParse(dimensionId) ?: throw IOException("区块维度ID无效：${dimensionId}")
        return DimensionType.getStorageFolder(ResourceKey.create(Registries.DIMENSION, location), worldDir)
    }

    /** The three region folders of each dimension, opened on first use. */
    internal class RegionStorages(private val folderOf: (String) -> Path) : AutoCloseable {
        class Dimension(val terrain: RegionFileStorage, val entities: RegionFileStorage, val poi: RegionFileStorage) {
            val all get() = listOf(terrain, entities, poi)
        }

        private val opened = LinkedHashMap<String, Dimension>()

        fun of(dimensionId: String): Dimension = opened.getOrPut(dimensionId) {
            val folder = folderOf(dimensionId)
            Dimension(
                terrain = RegionFileStorage(folder.resolve("region"), true),
                entities = RegionFileStorage(folder.resolve("entities"), true),
                poi = RegionFileStorage(folder.resolve("poi"), true),
            )
        }

        fun flush() = opened.values.forEach { dimension -> dimension.all.forEach { it.flush() } }

        override fun close() {
            var failure: Throwable? = null
            opened.values.flatMap { it.all }.forEach { storage ->
                runCatching { storage.close() }.onFailure { error -> failure?.addSuppressed(error) ?: run { failure = error } }
            }
            failure?.let { throw it }
        }
    }
}
