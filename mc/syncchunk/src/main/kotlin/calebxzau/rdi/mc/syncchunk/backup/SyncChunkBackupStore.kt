package calebxzau.rdi.mc.syncchunk.backup

import calebxzau.rdi.mc.syncchunk.SyncChunkKey
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdInputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.TreeMap
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Content-addressed backup store under `<host>/rdi/backup` (outside the world directory, so world
 * downloads, imports and backup mods never carry it):
 *
 * ```text
 * world-token              token of the world these points belong to
 * objects/ab/<sha1>.zst    zstd frame (with checksum) of one chunk part's NBT or one world file
 * points/<UUIDv7>.json     one backup point (回档点)
 * index.json               summaries for the master and launcher; derived from points/
 * tmp/                     staging for atomic writes
 * rollback-*.json          rollback hand-over with the master, see SyncChunkRollbackFiles
 * ```
 *
 * Objects are shared by every point that has the same content, so a point only adds what changed.
 * Not thread-safe: one backup thread owns the store.
 */
class SyncChunkBackupStore private constructor(
    private val root: Path,
    val worldToken: String,
    val maxBytes: Long,
) {
    /** A point removed on open because it could never be restored; [name] is its id or file name. */
    data class InvalidPoint(val name: String, val reason: String)

    data class OpenReport(val discardedForOtherWorld: Boolean, val invalidPoints: List<InvalidPoint>, val orphanObjects: Int)

    data class CommitResult(val point: SyncChunkBackupPointSummary, val droppedPoints: Int, val usedBytes: Long)

    private val points = TreeMap<String, SyncChunkBackupPoint>()
    private val manifestSizes = HashMap<String, Long>()
    private val objectSizes = HashMap<String, Long>()
    private val references = HashMap<String, Int>()

    private val objectsDir get() = root.resolve(OBJECTS)
    private val pointsDir get() = root.resolve(POINTS)
    private val tmpDir get() = root.resolve(TMP)

    val usedBytes: Long get() = objectSizes.values.sum() + manifestSizes.values.sum()

    fun points(): List<SyncChunkBackupPointSummary> = points.values.map { it.summary() }.sortedWith(SyncChunkBackupRetention.POINT_ORDER)

    fun point(id: String): SyncChunkBackupPoint? = points[id]

    fun latest(): SyncChunkBackupPoint? = points.values.maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

    fun begin(id: String, kind: SyncChunkBackupKind, createdAt: Long, gameTime: Long, rollbackId: String? = null): Session =
        Session(id, kind, createdAt, gameTime, rollbackId, latest())

    /** The raw bytes of an object; zstd verifies the frame checksum, so damage fails instead of returning garbage. */
    fun readObject(hash: String): Result<ByteArray> = runCatching {
        require(hash in objectSizes) { "备份对象不存在：${hash}" }
        ZstdInputStream(Files.newInputStream(objectPath(hash))).use { input ->
            val bytes = input.readNBytes(MAX_OBJECT_BYTES + 1)
            if (bytes.size > MAX_OBJECT_BYTES) throw IOException("备份对象超过大小上限：${hash}")
            bytes
        }
    }

    /** A world file of a point, checked against its recorded size and SHA-1. */
    fun readFile(file: SyncChunkBackupFile): Result<ByteArray> = runCatching {
        val bytes = readObject(file.hash).getOrThrow()
        if (bytes.size.toLong() != file.size || SyncChunkBackupIds.sha1Hex(bytes) != file.hash) {
            throw IOException("备份文件内容与记录不一致：${file.path}")
        }
        bytes
    }

    /** Builds one point. Objects are written as parts arrive, so whole chunks never pile up in memory. */
    inner class Session internal constructor(
        private val id: String,
        private val kind: SyncChunkBackupKind,
        private val createdAt: Long,
        private val gameTime: Long,
        private val rollbackId: String?,
        private val previous: SyncChunkBackupPoint?,
    ) {
        private val previousChunks = previous?.chunks?.associateBy { it.key }.orEmpty()
        private val previousFiles = previous?.files?.associateBy { it.path }.orEmpty()
        private val chunks = LinkedHashMap<SyncChunkKey, SyncChunkBackupChunk>()
        private val files = TreeMap<String, SyncChunkBackupFile>()
        private val written = HashSet<String>()
        private var addedBytes = 0L
        private var closed = false

        /**
         * Returns false when a part could not be read and no earlier point has this chunk; the chunk is
         * then left out instead of being recorded as empty.
         */
        fun putChunk(key: SyncChunkKey, terrain: SyncChunkBackupPart, entities: SyncChunkBackupPart, poi: SyncChunkBackupPart): Result<Boolean> =
            runCatching {
                check(!closed)
                val before = previousChunks[key]
                val parts = listOf(terrain, entities, poi)
                if (before == null && parts.any { it is SyncChunkBackupPart.Unavailable }) return@runCatching false
                chunks[key] = SyncChunkBackupChunk(
                    key = key,
                    terrain = resolve(terrain, before?.terrain),
                    entities = resolve(entities, before?.entities),
                    poi = resolve(poi, before?.poi),
                )
                true
            }

        /** Keeps the previous point's chunk, e.g. while its dimension is not loaded. */
        fun reuseChunk(key: SyncChunkKey): Boolean {
            check(!closed)
            val before = previousChunks[key] ?: return false
            chunks[key] = before
            return true
        }

        fun putFile(path: String, bytes: ByteArray): Result<Unit> = runCatching {
            check(!closed)
            require(SyncChunkBackupFileRules.isSafeRelativePath(path)) { "备份文件路径无效：${path}" }
            val hash = SyncChunkBackupIds.sha1Hex(bytes)
            ensureObject(hash, bytes)
            files[path] = SyncChunkBackupFile(path, hash, bytes.size.toLong())
        }

        /** Keeps the previous point's copy of a file that could not be read this time. */
        fun reuseFile(path: String): Boolean {
            check(!closed)
            val before = previousFiles[path] ?: return false
            files[path] = before
            return true
        }

        /**
         * Stores the point and applies retention, which never drops [pinned]; null when an automatic point
         * would equal the previous one. A before-rollback point is always stored, since a rollback records
         * it as its undo point.
         */
        fun commit(now: Long, pinned: Set<String> = emptySet()): Result<CommitResult?> = runCatching {
            check(!closed)
            closed = true
            val newChunks = chunks.values.sortedWith(CHUNK_ORDER)
            val newFiles = files.values.toList()
            if (kind == SyncChunkBackupKind.Auto && previous != null && newChunks == previous.chunks && newFiles == previous.files) {
                discardUnreferenced(written)
                return@runCatching null
            }
            val point = SyncChunkBackupPoint(
                id = id,
                kind = kind,
                createdAt = createdAt,
                gameTime = gameTime,
                chunks = newChunks,
                files = newFiles,
                changed = changes(newChunks, newFiles),
                addedBytes = addedBytes,
                rollbackId = rollbackId,
            )
            val dropped = try {
                writePoint(point)
                prune(now, pinned)
            } catch (error: Throwable) {
                if (point.id !in points) {
                    runCatching { discardUnreferenced(written) }.onFailure(error::addSuppressed)
                }
                throw error
            }
            writeIndex(now)
            CommitResult(point.summary(), dropped, usedBytes)
        }

        /** Gives up the point; objects written for it alone are removed. */
        fun abort(): Result<Unit> = runCatching {
            if (closed) return@runCatching
            closed = true
            discardUnreferenced(written)
        }

        private fun resolve(part: SyncChunkBackupPart, before: String?): String? = when (part) {
            is SyncChunkBackupPart.Present -> part.hash.also { ensureObject(it, part.raw) }
            SyncChunkBackupPart.Absent -> null
            SyncChunkBackupPart.Unavailable -> before
        }

        private fun ensureObject(hash: String, raw: ByteArray) {
            if (hash in objectSizes) return
            val size = writeObject(hash, raw)
            written += hash
            addedBytes += size
        }

        private fun changes(newChunks: List<SyncChunkBackupChunk>, newFiles: List<SyncChunkBackupFile>): SyncChunkBackupChanges {
            fun changedChunks(part: (SyncChunkBackupChunk) -> String?) = newChunks.count { chunk ->
                val before = previousChunks[chunk.key]
                before == null || part(before) != part(chunk)
            }
            val currentPaths = newFiles.mapTo(HashSet()) { it.path }
            return SyncChunkBackupChanges(
                terrainChunks = changedChunks { it.terrain },
                entityChunks = changedChunks { it.entities },
                poiChunks = changedChunks { it.poi },
                players = newFiles.count { file ->
                    SyncChunkBackupFileRules.playerDataUuid(file.path) != null && previousFiles[file.path]?.hash != file.hash
                },
                files = newFiles.count { previousFiles[it.path]?.hash != it.hash } + previousFiles.keys.count { it !in currentPaths },
            )
        }
    }

    private fun objectPath(hash: String): Path = objectsDir.resolve(hash.substring(0, 2)).resolve("${hash}${OBJECT_SUFFIX}")

    private fun writeObject(hash: String, raw: ByteArray): Long {
        val target = objectPath(hash)
        Files.createDirectories(target.parent)
        val tmp = newTmpFile()
        try {
            Files.write(tmp, compress(raw), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            forceToDisk(tmp)
            try {
                moveAtomically(tmp, target, replace = false)
            } catch (_: FileAlreadyExistsException) {
                // Left by an earlier run that stopped before referencing it; the content is the same.
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
        val size = Files.size(target)
        objectSizes[hash] = size
        return size
    }

    private fun writePoint(point: SyncChunkBackupPoint) {
        val bytes = SyncChunkBackupJson.encodePoint(point).toByteArray(Charsets.UTF_8)
        writeAtomically(pointsDir.resolve("${point.id}.json"), bytes)
        points[point.id] = point
        manifestSizes[point.id] = bytes.size.toLong()
        point.objectHashes().forEach { hash -> references.merge(hash, 1, Int::plus) }
    }

    /**
     * Applies [SyncChunkBackupRetention], then drops the oldest points while over [maxBytes]. [extraPinned]
     * and the points a pending rollback needs are never dropped; while its request cannot be read,
     * nothing is dropped.
     */
    private fun prune(now: Long, extraPinned: Set<String>): Int {
        val requested = SyncChunkRollbackFiles.readRequest(root).getOrElse { return 0 }?.pinnedPointIds().orEmpty()
        val pinned = requested + extraPinned
        val keep = SyncChunkBackupRetention.keep(points.values.map { it.summary() }, now, pinned)
        val expired = points.keys.filter { it !in keep }
        expired.forEach(::dropPoint)
        var dropped = expired.size
        val newest = latest()?.id
        while (usedBytes > maxBytes) {
            val oldest = points.values
                .filter { it.id != newest && it.id !in pinned }
                .minWithOrNull(compareBy({ it.createdAt }, { it.id })) ?: break
            dropPoint(oldest.id)
            dropped++
        }
        return dropped
    }

    private fun dropPoint(id: String) {
        val point = points[id] ?: return
        Files.deleteIfExists(pointsDir.resolve("${id}.json"))
        points.remove(id)
        manifestSizes.remove(id)
        point.objectHashes().forEach { hash ->
            val remaining = (references[hash] ?: 0) - 1
            if (remaining > 0) {
                references[hash] = remaining
            } else {
                references.remove(hash)
                deleteObject(hash)
            }
        }
    }

    /** Objects left behind by a failure here are removed as orphans when the store is next opened. */
    private fun discardUnreferenced(hashes: Collection<String>) {
        hashes.filter { it !in references }.forEach(::deleteObject)
    }

    private fun deleteObject(hash: String) {
        Files.deleteIfExists(objectPath(hash))
        objectSizes.remove(hash)
    }

    private fun writeIndex(now: Long) {
        val index = SyncChunkBackupIndex(
            worldToken = worldToken,
            updatedAt = now,
            usedBytes = usedBytes,
            maxBytes = maxBytes,
            points = points().reversed(),
        )
        writeAtomically(root.resolve(INDEX), SyncChunkBackupJson.encodeIndex(index).toByteArray(Charsets.UTF_8))
    }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val tmp = newTmpFile()
        try {
            Files.write(tmp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            forceToDisk(tmp)
            moveAtomically(tmp, target, replace = true)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun newTmpFile(): Path {
        Files.createDirectories(tmpDir)
        return Files.createTempFile(tmpDir, "w-", ".tmp")
    }

    private fun load(): OpenReport {
        val invalid = ArrayList<InvalidPoint>()
        if (Files.isDirectory(pointsDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.list(pointsDir).use { stream ->
                stream.asSequence().filter { it.name.endsWith(".json") }.sorted().toList()
            }.forEach { file ->
                val point = runCatching { Files.readString(file) }
                    .mapCatching { SyncChunkBackupJson.decodePoint(it).getOrThrow() }
                    .mapCatching { it.also { point -> require("${point.id}.json" == file.name) { "回档点ID和文件名不一致" } } }
                    .getOrElse { error ->
                        Files.deleteIfExists(file)
                        invalid.add(InvalidPoint(file.name, "无法读取：${error.message ?: error.javaClass.simpleName}"))
                        return@forEach
                    }
                points[point.id] = point
                manifestSizes[point.id] = Files.size(file)
                point.objectHashes().forEach { hash -> references.merge(hash, 1, Int::plus) }
            }
        }
        var orphans = 0
        if (Files.isDirectory(objectsDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(objectsDir).use { stream ->
                stream.asSequence().filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
            }.forEach { file ->
                val hash = file.name.removeSuffix(OBJECT_SUFFIX)
                if (hash in references && file.name.endsWith(OBJECT_SUFFIX)) {
                    objectSizes[hash] = Files.size(file)
                } else {
                    Files.deleteIfExists(file)
                    orphans++
                }
            }
        }
        // A point with a missing object could only be restored partly, so it is dropped; objects other
        // points still need are kept by the reference counts.
        points.values.toList().forEach { point ->
            val missing = point.objectHashes().count { it !in objectSizes }
            if (missing > 0) {
                dropPoint(point.id)
                invalid.add(InvalidPoint(point.id, "缺少${missing}个备份对象"))
            }
        }
        return OpenReport(discardedForOtherWorld = false, invalidPoints = invalid, orphanObjects = orphans)
    }

    companion object {
        const val ROOT = "rdi/backup"
        const val MAX_BYTES = 1024L * 1024 * 1024
        const val WORLD_TOKEN = "world-token"
        const val INDEX = "index.json"
        private const val OBJECTS = "objects"
        private const val POINTS = "points"
        private const val TMP = "tmp"
        const val OBJECT_SUFFIX = ".zst"

        /** Larger than any chunk part; world files are capped lower by [SyncChunkBackupFileRules.MAX_FILE_BYTES]. */
        private const val MAX_OBJECT_BYTES = 256 * 1024 * 1024

        /** Same level as RDI's zstd region chunks; the checksum lets a restore reject damaged objects. */
        const val ZSTD_LEVEL = 7

        private val CHUNK_ORDER = compareBy<SyncChunkBackupChunk>({ it.key.dimensionId }, { it.key.chunkX }, { it.key.chunkZ })

        /** The store of the host whose server directory is [hostDir]. */
        fun rootOf(hostDir: Path): Path = hostDir.resolve(ROOT)

        /**
         * Opens [root] for the world identified by [worldToken]. Points of another world (the world was
         * replaced, e.g. by a save import) are discarded, since they could never be restored into this one.
         */
        fun open(root: Path, worldToken: String, maxBytes: Long = MAX_BYTES, now: Long = System.currentTimeMillis()): Result<Pair<SyncChunkBackupStore, OpenReport>> =
            runCatching {
                require(maxBytes > 0)
                Files.createDirectories(root)
                val tokenFile = root.resolve(WORLD_TOKEN)
                val storedToken = if (Files.isRegularFile(tokenFile, LinkOption.NOFOLLOW_LINKS)) Files.readString(tokenFile).trim() else null
                val otherWorld = storedToken != worldToken &&
                    (storedToken != null || Files.exists(root.resolve(POINTS)) || Files.exists(root.resolve(OBJECTS)))
                if (otherWorld) {
                    listOf(POINTS, OBJECTS, INDEX).forEach { deleteRecursively(root.resolve(it)) }
                }
                deleteRecursively(root.resolve(TMP))
                val store = SyncChunkBackupStore(root, worldToken, maxBytes)
                if (storedToken != worldToken) store.writeAtomically(tokenFile, "${worldToken}\n".toByteArray(Charsets.UTF_8))
                val report = store.load().copy(discardedForOtherWorld = otherWorld)
                store.writeIndex(now)
                store to report
            }

        private fun compress(raw: ByteArray): ByteArray = ZstdCompressCtx().use { ctx ->
            ctx.setLevel(ZSTD_LEVEL)
            ctx.setChecksum(true)
            ctx.compress(raw)
        }

        private fun deleteRecursively(path: Path) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.walk(path).use { stream -> stream.sorted(Comparator.reverseOrder()).toList() }
                    .forEach { Files.deleteIfExists(it) }
            } else {
                Files.delete(path)
            }
        }

        private fun moveAtomically(source: Path, target: Path, replace: Boolean) {
            val options = if (replace) arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) else arrayOf(StandardCopyOption.ATOMIC_MOVE)
            try {
                if (!replace && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
                Files.move(source, target, *options)
            } catch (_: AtomicMoveNotSupportedException) {
                if (replace) Files.move(source, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(source, target)
            }
        }

        private fun forceToDisk(file: Path) {
            try {
                FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
            } catch (error: IOException) {
                throw IOException("无法写入备份文件${file}", error)
            }
        }
    }
}

/** `world/data/rdi_backup_token.txt` ties a backup store to one world; `rdi_` keeps it out of backups. */
object SyncChunkBackupWorldToken {
    const val RELATIVE_PATH = "data/rdi_backup_token.txt"

    fun readOrCreate(worldDir: Path, newToken: () -> String = { SyncChunkBackupIds.newUuidV7().toString() }): Result<String> = runCatching {
        val file = worldDir.resolve(RELATIVE_PATH)
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            val token = Files.readString(file).trim()
            require(TOKEN.matches(token)) { "备份世界标记无效：${file}" }
            return@runCatching token
        }
        check(!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { "备份世界标记不是普通文件：${file}" }
        val token = newToken()
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling("${file.name}.tmp")
        Files.writeString(tmp, "${token}\n")
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE)
        token
    }

    private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
