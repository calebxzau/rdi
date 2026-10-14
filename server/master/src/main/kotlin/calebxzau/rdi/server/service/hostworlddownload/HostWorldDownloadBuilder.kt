package calebxzau.rdi.server.service.hostworlddownload

import calebxzau.rdi.anvilrw.remap.ChunkTransform
import calebxzau.rdi.anvilrw.remap.RawRegionFile
import calebxzau.rdi.anvilrw.remap.SaveSyncChunk
import calebxzau.rdi.anvilrw.remap.SaveSyncChunks
import calebxzau.rdi.anvilrw.remap.dimensionStorageFolder
import calebxzau.rdi.common.model.HostWorldDownloadStage
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.exception.RequestError
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.asSequence

data class HostWorldDownloadBuildResult(val bytes: Long, val sha1: String, val chunkCount: Int)

/**
 * Packs a stopped host's world into a tar.zst save: the sync chunks of every chunk store (region
 * chunks keep RDI's zstd compression) and the other world files [HostWorldDownloadContent] includes.
 * Entries are relative to the world folder, so the archive extracts into a save folder directly.
 */
object HostWorldDownloadBuilder {
    private val CHUNK_STORES = listOf("region", "entities", "poi")
    private val KEEP_AS_IS = ChunkTransform { _, _, _ -> null }

    /**
     * Writes the archive to [target]; [workDir] holds the rewritten region files meanwhile and is
     * removed afterwards. [onProgress] gets (stage, done, total).
     */
    fun build(
        worldDir: Path,
        target: Path,
        workDir: Path,
        onProgress: (HostWorldDownloadStage, Int, Int) -> Unit = { _, _, _ -> },
    ): Result<HostWorldDownloadBuildResult> = runCatching {
        Files.createDirectories(workDir)
        try {
            val syncChunks = readSyncChunks(worldDir)
            var chunkCount = 0
            TarZstArchiveWriter(target.toFile()).use { archive ->
                chunkCount = addSyncChunks(worldDir, workDir, syncChunks, archive, onProgress)
                addFiles(worldDir, archive, onProgress)
            }
            onProgress(HostWorldDownloadStage.Compress, 0, 1)
            val result = HostWorldDownloadBuildResult(Files.size(target), sha1(target), chunkCount)
            onProgress(HostWorldDownloadStage.Compress, 1, 1)
            result
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    /** A host that never had sync chunks has no list; its save then holds no chunks. */
    private fun readSyncChunks(worldDir: Path): List<SaveSyncChunk> {
        val file = worldDir.resolve(SaveSyncChunks.RELATIVE_PATH)
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return SaveSyncChunks.parse(Files.readAllBytes(file)).getOrElse { throw RequestError("房间的同步区块列表无法读取", it) }
    }

    /** Returns the sync chunks whose terrain exists. */
    private fun addSyncChunks(
        worldDir: Path,
        workDir: Path,
        syncChunks: List<SaveSyncChunk>,
        archive: TarZstArchiveWriter,
        onProgress: (HostWorldDownloadStage, Int, Int) -> Unit,
    ): Int {
        val total = syncChunks.size
        var done = 0
        var terrainChunks = 0
        onProgress(HostWorldDownloadStage.SyncChunks, 0, total)
        val byRegion = syncChunks.groupBy { Triple(it.dimensionId, it.chunkX shr 5, it.chunkZ shr 5) }
        for ((region, chunks) in byRegion.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }, { it.key.third }))) {
            val (dimensionId, regionX, regionZ) = region
            val folder = dimensionStorageFolder(worldDir, dimensionId).getOrThrow()
            val kept = chunks.mapTo(HashSet()) { it.chunkX to it.chunkZ }
            for (store in CHUNK_STORES) {
                val source = folder.resolve(store).resolve("r.${regionX}.${regionZ}.mca")
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue
                val relative = worldDir.relativize(source).invariantSeparatorsPathString
                val rewritten = workDir.resolve(relative)
                Files.createDirectories(rewritten.parent)
                val result = RawRegionFile.rewrite(source, rewritten, KEEP_AS_IS, keep = { x, z -> (x to z) in kept }).getOrThrow()
                if (!result.written) continue
                if (store == "region") terrainChunks += result.chunkCount
                archive.addFile(relative, rewritten.toFile())
                for (mcc in result.externalChunkFiles) {
                    archive.addFile(relative.substringBeforeLast('/') + "/" + mcc, rewritten.resolveSibling(mcc).toFile())
                }
            }
            done += chunks.size
            onProgress(HostWorldDownloadStage.SyncChunks, done, total)
        }
        return terrainChunks
    }

    private fun addFiles(worldDir: Path, archive: TarZstArchiveWriter, onProgress: (HostWorldDownloadStage, Int, Int) -> Unit) {
        val files = Files.walk(worldDir).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { worldDir.relativize(it).invariantSeparatorsPathString }
                .filter(HostWorldDownloadContent::includesFile)
                .sorted()
                .toList()
        }
        var totalBytes = 0L
        onProgress(HostWorldDownloadStage.Files, 0, files.size)
        files.forEachIndexed { index, relative ->
            val file = worldDir.resolve(relative)
            val size = Files.size(file)
            if (size > HostWorldDownloadContent.MAX_FILE_BYTES) {
                throw RequestError("存档文件${relative}太大（${size / 1024 / 1024}MB），无法下载")
            }
            totalBytes += size
            if (totalBytes > HostWorldDownloadContent.MAX_TOTAL_BYTES) {
                throw RequestError("存档的其他数据超过${HostWorldDownloadContent.MAX_TOTAL_BYTES / 1024 / 1024}MB，无法下载")
            }
            Files.newInputStream(file).use { input ->
                archive.addFileStreaming(relative, input, size, Files.getLastModifiedTime(file).toMillis())
            }
            onProgress(HostWorldDownloadStage.Files, index + 1, files.size)
        }
    }

    private fun sha1(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
