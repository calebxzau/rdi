package calebxzau.rdi.anvilrw.remap

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

data class RemapProgress(val bytesDone: Long, val bytesTotal: Long)

/** A file copied unchanged although it may hold player data that was not migrated. */
data class UnhandledFile(val relativePath: String, val reason: String)

data class SyncFilterReport(
    /** Chunks in the sync list. */
    val markedChunkCount: Int,
    /** Marked chunks that exist in the `region/` stores of the save. */
    val presentChunkCount: Int,
    /** Region, entity and POI files skipped or emptied because they hold no marked chunk. */
    val droppedFileCount: Int,
)

data class RemapReport(
    val filesScanned: Int,
    val filesRewritten: Int,
    val pathsRenamed: Int,
    val replacementsBySource: Map<UUID, Int>,
    val possiblyUnmigrated: List<UnhandledFile>,
    /** The save was hardcore; the flag is now off in `level.dat` and `level.dat_old`. */
    val hardcoreSwitchedOff: Boolean,
    /** The singleplayer owner's playerdata was written from `Data.Player`. */
    val hostDataFromLevelDat: Boolean,
    /** Null when no sync chunk list was given. */
    val syncFilter: SyncFilterReport?,
    val bytesBefore: Long,
    val bytesAfter: Long,
)

/**
 * Copies a save into an empty directory while replacing mapped player UUIDs (plan §8).
 *
 * The source is only read, through [SaveSnapshot], and is verified again at the end. Every
 * replacement preserves length except in text int arrays, so all other bytes stay as they were.
 *
 * - Region files are rewritten strictly by [RawRegionFile], in parallel within the memory budget.
 * - `level.dat`, `level.dat_old` and playerdata are strict gzip NBT: any problem fails the import.
 * - Other NBT and strictly valid UTF-8 text are patched when they can be read; otherwise they are
 *   copied unchanged, and listed in [RemapReport.possiblyUnmigrated] when a mapped UUID was seen or
 *   the file could not be checked.
 * - Path segments that are a mapped UUID are renamed. Two outputs with the same path fail the import.
 * - The singleplayer owner's playerdata is written from `Data.Player` (§8.7).
 * - Hardcore is switched off (§8.10).
 * - With [syncChunks], only marked chunks of each dimension's `region/`, `entities/` and `poi/`
 *   stores are kept; other chunks are never read and will regenerate (§8.11).
 *
 * On failure, everything written below the target is removed.
 */
class SaveUuidRemapper(
    private val mapping: Map<UUID, UUID>,
    private val syncChunks: List<SaveSyncChunk>? = null,
    private val chunkLimit: Int = RemapLimits.CHUNK_DECOMPRESSED_BYTES,
    private val parallelism: Int = RemapLimits.regionParallelism(),
) {
    private val matcher = UuidMatcher(mapping)
    private val patcher = NbtBytePatcher(mapping)
    private val textPatcher = TextUuidPatcher(mapping)
    private val renamer = SavePathRenamer(mapping)
    private val keptByDimension: Map<String, Set<Long>>? =
        syncChunks?.groupBy { it.dimensionId }?.mapValues { (_, chunks) -> chunks.mapTo(HashSet()) { packChunk(it.chunkX, it.chunkZ) } }

    fun remapTree(
        source: Path,
        target: Path,
        snapshot: SaveSnapshot,
        progress: (RemapProgress) -> Unit = {},
        ensureActive: () -> Unit = {},
    ): Result<RemapReport> {
        val result = try {
            remapResult { Run(source, target, snapshot, progress, ensureActive).execute() }
        } catch (cancelled: CancellationException) {
            cleanTarget(target)
            throw cancelled
        }
        if (result.isFailure) cleanTarget(target)
        return result
    }

    /** State of one [remapTree] call. */
    private inner class Run(
        val source: Path,
        val target: Path,
        val snapshot: SaveSnapshot,
        val progress: (RemapProgress) -> Unit,
        val ensureActive: () -> Unit,
    ) {
        val replacements = ConcurrentHashMap<UUID, Int>()
        val unhandled = ArrayList<UnhandledFile>()
        val bytesDone = AtomicLong()
        var filesRewritten = 0
        var hardcoreSwitchedOff = false
        var presentChunks = 0
        var droppedFiles = 0

        fun execute(): RemapReport {
            require(snapshot.root == source) { "The snapshot was taken of ${snapshot.root}, not ${source}" }
            Files.createDirectories(target)
            require(Files.list(target).use { it.findFirst().isEmpty }) { "${target} is not empty" }

            val outputs = planOutputs()
            for (directory in snapshot.directories.sorted()) Files.createDirectories(target.resolve(renamer.rename(directory)))

            val regionFiles = snapshot.files.keys.filter { it.endsWith(".mca") }.sorted()
            val ownedExternals = rewriteRegions(regionFiles, outputs)
            for (path in snapshot.files.keys.sorted()) {
                ensureActive()
                if (path.endsWith(".mca")) continue
                if (path in ownedExternals || isChunkStoreExternal(path)) {
                    advance(snapshot.files.getValue(path).size)
                    continue
                }
                processFile(path, target.resolve(outputs.getValue(path)))
                advance(snapshot.files.getValue(path).size)
            }
            val hostData = writeHostPlayerData()
            snapshot.verify().getOrThrow()

            return RemapReport(
                filesScanned = snapshot.files.size,
                filesRewritten = filesRewritten,
                pathsRenamed = outputs.count { (from, to) -> from != to },
                replacementsBySource = replacements.toMap(),
                possiblyUnmigrated = unhandled.sortedBy { it.relativePath },
                hardcoreSwitchedOff = hardcoreSwitchedOff,
                hostDataFromLevelDat = hostData,
                syncFilter = syncChunks?.let { SyncFilterReport(it.size, presentChunks, droppedFiles) },
                bytesBefore = snapshot.totalBytes,
                bytesAfter = Files.walk(target).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() },
            )
        }

        /** Output path of every source file; two sources may not share one (case-insensitively). */
        private fun planOutputs(): Map<String, String> {
            val outputs = snapshot.files.keys.associateWith { renamer.rename(it) }
            val seen = HashMap<String, String>()
            for ((from, to) in outputs.entries.sortedBy { it.key }) {
                val other = seen.putIfAbsent(to.lowercase(Locale.ROOT), from)
                if (other != null) throw PathConflictException("${other} and ${from} would both be written to ${to}")
            }
            return outputs
        }

        /** Rewrites every region file; returns the `.mcc` files the region handler took care of. */
        private fun rewriteRegions(regionFiles: List<String>, outputs: Map<String, String>): Set<String> {
            val owned = ConcurrentHashMap.newKeySet<String>()
            val executor = Executors.newFixedThreadPool(parallelism.coerceAtLeast(1))
            try {
                val tasks = ArrayList<Pair<String, Future<RegionRewriteResult>>>()
                for (path in regionFiles) {
                    val dimension = chunkStoreDimension(path)
                    val kept = dimension?.let { keptByDimension?.get(it) ?: if (keptByDimension != null) emptySet() else null }
                    if (kept != null && kept.isEmpty()) {
                        droppedFiles++
                        advance(snapshot.files.getValue(path).size)
                        continue
                    }
                    tasks += path to executor.submit<RegionRewriteResult> {
                        ensureActive()
                        snapshot.checkUnchanged(path).getOrThrow()
                        val filter = kept?.let { set -> ChunkFilter { x, z -> packChunk(x, z) in set } }
                        RawRegionFile.rewrite(
                            source.resolve(path), target.resolve(outputs.getValue(path)), chunkTransform(), chunkLimit,
                            keep = filter, ensureActive = ensureActive,
                        ).getOrThrow().also { advance(snapshot.files.getValue(path).size) }
                    }
                }
                for ((path, task) in tasks) {
                    val result = try {
                        task.get()
                    } catch (failure: ExecutionException) {
                        throw failure.cause ?: failure
                    }
                    val directory = path.substringBeforeLast('/', "")
                    result.externalChunkFiles.forEach { owned += if (directory.isEmpty()) it else "${directory}/${it}" }
                    if (!result.copiedUnchanged && result.written) filesRewritten++
                    if (!result.written) droppedFiles++
                    if (chunkStoreDimension(path) != null && path.substringBeforeLast('/').substringAfterLast('/') == "region") {
                        presentChunks += result.chunkCount
                    }
                }
            } finally {
                // Running rewrites do not stop on interrupt; wait so nothing writes after a cleanup.
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.MINUTES)
            }
            return owned
        }

        private fun chunkTransform() = ChunkTransform { _, _, nbt ->
            val result = patcher.patch(nbt).getOrThrow()
            count(result.replacements)
            if (result.changed) result.bytes else null
        }

        /** `.mcc` files in chunk stores are written by the region handler for kept chunks only. */
        private fun isChunkStoreExternal(path: String): Boolean =
            keptByDimension != null && MCC_NAME.matches(path.substringAfterLast('/')) && chunkStoreDimension(path) != null

        private fun processFile(path: String, output: Path) {
            val size = snapshot.files.getValue(path).size
            when {
                STRICT_NBT.matches(path) -> processStrictNbt(path, output, size)
                size > RemapLimits.OTHER_NBT_FILE_BYTES -> copyUnchecked(path, output, "文件过大，未检查")
                else -> processOther(path, output)
            }
        }

        private fun processStrictNbt(path: String, output: Path, size: Long) {
            if (size > RemapLimits.STRICT_NBT_FILE_BYTES) throw RemapLimitExceededException("${path} exceeds ${RemapLimits.STRICT_NBT_FILE_BYTES} bytes")
            val original = snapshot.readFile(path).getOrThrow()
            val nbt = BoundedIo.gunzip(original, RemapLimits.STRICT_NBT_FILE_BYTES, path)
            val patched = patcher.patch(nbt).getOrThrow()
            count(patched.replacements)
            var bytes = patched.bytes
            var changed = patched.changed
            if (path == "level.dat" || path == "level.dat_old") {
                val metadata = NbtMetadataReader.open(nbt).getOrThrow().levelMetadata().getOrThrow()
                val offset = metadata.hardcoreByteOffset
                if (metadata.hardcore && offset != null) {
                    if (!changed) bytes = bytes.copyOf()
                    bytes[offset] = 0
                    changed = true
                    hardcoreSwitchedOff = true
                }
            }
            write(output, if (changed) BoundedIo.gzip(bytes) else original, changed)
        }

        private fun processOther(path: String, output: Path) {
            val original = snapshot.readFile(path).getOrThrow()
            if (original.size >= 2 && original[0] == GZIP_0 && original[1] == GZIP_1) {
                val content = try {
                    BoundedIo.gunzip(original, RemapLimits.OTHER_NBT_FILE_BYTES, path)
                } catch (_: RemapLimitExceededException) {
                    return copyWithReason(path, output, original, "文件过大，未检查")
                } catch (_: java.io.IOException) {
                    return copyScanned(path, output, original, original, "未识别的文件格式")
                }
                val rewritten = rewriteContent(path, content) ?: return copyScanned(path, output, original, content, "未识别的文件格式")
                return write(output, if (rewritten !== content) BoundedIo.gzip(rewritten) else original, rewritten !== content)
            }
            val rewritten = rewriteContent(path, original)
            if (rewritten != null) return write(output, rewritten, rewritten !== original)
            val reason = if (path.substringAfterLast('.', "").lowercase(Locale.ROOT) in TEXT_EXTENSIONS) "非UTF-8文本，未自动迁移" else "未识别的文件格式"
            copyScanned(path, output, original, original, reason)
        }

        /**
         * Patches structurally valid NBT or strictly valid UTF-8 text. Returns the same array when
         * nothing changed, or null when the content is neither.
         */
        private fun rewriteContent(path: String, content: ByteArray): ByteArray? {
            if (content.isNotEmpty() && content[0] == NBT_COMPOUND) {
                // A failure only means the content is not NBT; the other formats are tried next.
                patcher.patch(content).onSuccess { patched ->
                    count(patched.replacements)
                    return patched.bytes
                }
            }
            if (!TextUuidPatcher.isStrictUtf8(content)) return null
            if (content.size > RemapLimits.TEXT_FILE_BYTES) {
                unhandled += UnhandledFile(path, "文件过大，未检查")
                return content
            }
            val patched = textPatcher.patch(content).getOrThrow()
            count(patched.replacements)
            if (patched.mostLeastHits > 0) unhandled += UnhandledFile(path, "含有无法自动迁移的玩家ID写法")
            return patched.bytes
        }

        private fun copyScanned(path: String, output: Path, original: ByteArray, content: ByteArray, reason: String) {
            if (RawUuidScanner(matcher).scan(content)) unhandled += UnhandledFile(path, reason)
            Files.write(output, original)
        }

        private fun copyWithReason(path: String, output: Path, original: ByteArray, reason: String) {
            unhandled += UnhandledFile(path, reason)
            Files.write(output, original)
        }

        private fun copyUnchecked(path: String, output: Path, reason: String) {
            unhandled += UnhandledFile(path, reason)
            snapshot.copyFile(path, output).getOrThrow()
        }

        private fun write(output: Path, bytes: ByteArray, changed: Boolean) {
            Files.write(output, bytes)
            if (changed) filesRewritten++
        }

        /**
         * Minecraft loads the singleplayer owner from `Data.Player`, so that copy overwrites the owner's
         * playerdata file, under the mapped UUID if the owner is mapped.
         */
        private fun writeHostPlayerData(): Boolean {
            if ("level.dat" !in snapshot.files) return false
            val level = BoundedIo.gunzip(snapshot.readFile("level.dat").getOrThrow(), RemapLimits.STRICT_NBT_FILE_BYTES, "level.dat")
            val reader = NbtMetadataReader.open(level).getOrThrow()
            val host = reader.levelMetadata().getOrThrow().singleplayerUuid ?: return false
            val payload = reader.compoundPayload("Data", "Player") ?: return false
            val patched = patcher.patch(NbtMetadataReader.rootDocument(payload)).getOrThrow()
            val output = target.resolve("playerdata").resolve("${mapping[host] ?: host}.dat")
            Files.createDirectories(output.parent)
            Files.write(output, BoundedIo.gzip(patched.bytes))
            return true
        }

        private fun count(found: Map<UUID, Int>) {
            found.forEach { (uuid, n) -> replacements.merge(uuid, n, Int::plus) }
        }

        private fun advance(bytes: Long) {
            val done = bytesDone.addAndGet(bytes)
            synchronized(this) { progress(RemapProgress(done, snapshot.totalBytes)) }
        }
    }

    private fun cleanTarget(target: Path) {
        if (!Files.isDirectory(target)) return
        runCatching {
            Files.walk(target).use { stream -> stream.sorted(Comparator.reverseOrder()).filter { it != target }.forEach(Files::delete) }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            System.getLogger(SaveUuidRemapper::class.java.name).log(System.Logger.Level.ERROR, "Failed to clean ${target}", error)
        }
    }

    companion object {
        private const val GZIP_0 = 0x1F.toByte()
        private const val GZIP_1 = 0x8B.toByte()
        private const val NBT_COMPOUND = 0x0A.toByte()
        private val STRICT_NBT = Regex("""level\.dat(_old)?|playerdata/[^/]+\.dat(_old)?""")
        private val MCC_NAME = Regex("""c\.-?\d+\.-?\d+\.mcc""")
        private val CHUNK_STORES = setOf("region", "entities", "poi")
        private val TEXT_EXTENSIONS = setOf("snbt", "json", "json5", "toml", "cfg", "txt", "properties", "save", "yml", "yaml")

        private fun packChunk(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

        /**
         * The canonical dimension of a file directly inside a chunk store (§3.6), or null: `region/…`
         * is the overworld, `DIM-1/…` the nether, `DIM1/…` the end, `dimensions/<ns>/<path>/…` others.
         */
        fun chunkStoreDimension(relativePath: String): String? {
            val segments = relativePath.split('/')
            if (segments.size < 2 || segments[segments.size - 2] !in CHUNK_STORES) return null
            val folder = segments.subList(0, segments.size - 2)
            return when {
                folder.isEmpty() -> "minecraft:overworld"
                folder == listOf("DIM-1") -> "minecraft:the_nether"
                folder == listOf("DIM1") -> "minecraft:the_end"
                folder.size >= 3 && folder[0] == "dimensions" -> "${folder[1]}:${folder.drop(2).joinToString("/")}"
                else -> null
            }
        }
    }
}
