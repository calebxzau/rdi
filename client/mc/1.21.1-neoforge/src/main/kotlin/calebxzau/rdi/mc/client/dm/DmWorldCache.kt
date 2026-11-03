package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.syncchunk.SyncChunkRequestError
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException

/** The local copy of a DM room world under `rdi/dm/<hostId>/world`. */
class DmWorldCache(private val gameDirectory: Path, val hostId: UUID) {
    val directory: Path = gameDirectory.resolve("rdi").resolve("dm").resolve(hostId.toString())
    val world: Path = directory.resolve(WORLD_NAME)
    private val marker: Path = directory.resolve("complete")

    val isPrepared: Boolean get() = Files.isRegularFile(marker) && Files.isDirectory(world)

    /**
     * Replaces the cached world with the latest checkpoint, falling back to the room's initial
     * world only when the master says no checkpoint exists yet (then the result is null). Any other
     * failure must not fall back: hosting the initial world would throw away the room's progress.
     */
    fun downloadAndReplace(client: DmHttpClient, cancelled: () -> Boolean): Result<DmCheckpointDownload?> = runCatching {
        Files.createDirectories(directory)
        val parentDirectory = directory.parent ?: error("DM缓存目录没有父目录")
        val zip = Files.createTempFile(parentDirectory, "world-init-", ".zip")
        val staging = Files.createTempDirectory(parentDirectory, "world-staging-")
        var published = false
        try {
            val download = client.downloadWorldCheckpoint(hostId, zip).getOrElse { error ->
                if (!isNoCheckpoint(error)) throw IOException("房间的世界暂时无法下载，请稍后再试", error)
                client.downloadWorldInit(hostId, zip).getOrThrow()
                null
            }
            DmWorldInitArchive.extract(zip, staging).getOrThrow()
            if (cancelled()) throw CancellationException("下载已取消")
            if (Files.exists(world)) moveToArchive(world)
            Files.deleteIfExists(marker)
            try {
                moveRetrying(staging, world, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                moveRetrying(staging, world)
            }
            published = true
            val markerTemp = Files.createTempFile(directory, "complete-", ".tmp")
            try {
                Files.writeString(markerTemp, hostId.toString())
                try {
                    Files.move(markerTemp, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(markerTemp, marker, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(markerTemp)
            }
            download
        } finally {
            Files.deleteIfExists(zip)
            if (!published) DmWorldInitArchive.deleteRecursively(staging)
        }
    }

    private fun moveToArchive(source: Path) {
        val archiveDirectory = gameDirectory.resolve("DEL").resolve("rdi").resolve("dm")
        Files.createDirectories(archiveDirectory)
        var archive = archiveDirectory.resolve("${hostId}-${System.currentTimeMillis()}-world")
        var suffix = 0
        while (Files.exists(archive)) {
            suffix++
            archive = archiveDirectory.resolve("${hostId}-${System.currentTimeMillis()}-${suffix}-world")
        }
        moveRetrying(source, archive)
    }

    /**
     * Windows refuses to rename a directory while another process (antivirus, indexer) still has
     * one of its freshly written files open, so transient access errors are retried.
     */
    private fun moveRetrying(source: Path, target: Path, vararg options: CopyOption) {
        var attempt = 1
        while (true) {
            try {
                Files.move(source, target, *options)
                return
            } catch (error: FileSystemException) {
                val transient = error is AccessDeniedException ||
                    error.reason?.contains("used by another process", ignoreCase = true) == true
                if (!transient || attempt >= MOVE_ATTEMPTS) throw error
                Thread.sleep(MOVE_RETRY_MILLIS * attempt)
                attempt++
            }
        }
    }

    companion object {
        const val WORLD_NAME = "world"
        private const val MOVE_ATTEMPTS = 8
        private const val MOVE_RETRY_MILLIS = 250L
        private val LEVEL_FILES = listOf("level.dat", "level.dat_old")

        internal fun isNoCheckpoint(error: Throwable): Boolean =
            error is SyncChunkRequestError && error.reason == "NoCheckpoint"

        /**
         * Removes `Data.Player` from level.dat so whoever opens the world as singleplayer loads
         * their own `playerdata/<uuid>.dat` instead of the previous host's character.
         */
        fun removeLevelPlayer(worldRoot: Path): Result<Unit> = runCatching {
            LEVEL_FILES.map(worldRoot::resolve).filter(Files::isRegularFile).forEach { file ->
                val tag = NbtIo.readCompressed(file, NbtAccounter.create(DmWorldDataCapture.MAX_LEVEL_DATA_BYTES))
                val data = tag.get("Data")
                require(data is CompoundTag) { "${file.fileName}的Data不是NBT复合标签" }
                if (data.contains("Player")) {
                    data.remove("Player")
                    NbtIo.writeCompressed(tag, file)
                }
            }
        }
    }
}
