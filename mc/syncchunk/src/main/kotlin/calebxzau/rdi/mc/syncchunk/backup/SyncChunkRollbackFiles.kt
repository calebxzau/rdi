package calebxzau.rdi.mc.syncchunk.backup

import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.name

/**
 * The rollback hand-over between the master and the host, next to the backup store (see
 * [SyncChunkBackupStore]):
 *
 * ```text
 * rollback-request.json   written by the master, removed by the host once the rollback is finished
 * rollback-result.json    written by the host for the last request it finished
 * ```
 *
 * Both are replaced atomically through a temporary file in the same directory, never in `tmp/`,
 * which the host clears when it opens the store.
 */
object SyncChunkRollbackFiles {
    const val REQUEST = "rollback-request.json"
    const val RESULT = "rollback-result.json"

    /** Null when no rollback is pending. */
    fun readRequest(root: Path): Result<SyncChunkRollbackRequest?> =
        read(root.resolve(REQUEST)) { SyncChunkBackupJson.decodeRollbackRequest(it) }

    fun writeRequest(root: Path, request: SyncChunkRollbackRequest): Result<Unit> =
        write(root.resolve(REQUEST), SyncChunkBackupJson.encodeRollbackRequest(request))

    fun deleteRequest(root: Path): Result<Unit> = runCatching {
        Files.deleteIfExists(root.resolve(REQUEST))
    }

    /** Null before the host finished any rollback. */
    fun readResult(root: Path): Result<SyncChunkRollbackResult?> =
        read(root.resolve(RESULT)) { SyncChunkBackupJson.decodeRollbackResult(it) }

    fun writeResult(root: Path, result: SyncChunkRollbackResult): Result<Unit> =
        write(root.resolve(RESULT), SyncChunkBackupJson.encodeRollbackResult(result))

    private fun <T> read(file: Path, decode: (String) -> Result<T>): Result<T?> = runCatching {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return@runCatching null
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) { "${file.name}不是普通文件" }
        decode(Files.readString(file)).getOrThrow()
    }

    private fun write(file: Path, text: String): Result<Unit> = runCatching {
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, ".${file.name}-", ".tmp")
        try {
            Files.write(tmp, text.toByteArray(Charsets.UTF_8), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
