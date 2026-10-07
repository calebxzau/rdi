package calebxzau.rdi.server.service.hostworldimport

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Persistence of one import's journal. Both operations throw on failure. */
interface HostWorldImportJournalStore {
    fun read(): HostWorldImportJournal

    /** Must be atomic and durable: after it returns, a crash cannot lose or tear [journal]. */
    fun write(journal: HostWorldImportJournal)
}

/** A journal kept in its own JSON file, written with [DurableFiles.writeAtomically]. */
class AtomicJournalFile(private val file: Path) : HostWorldImportJournalStore {
    override fun read(): HostWorldImportJournal = JOURNAL_JSON.decodeFromString(Files.readString(file))

    override fun write(journal: HostWorldImportJournal) {
        DurableFiles.writeAtomically(file, JOURNAL_JSON.encodeToString(journal).toByteArray(Charsets.UTF_8))
    }

    private companion object {
        /** Strict decoding: an unknown enum value must fail instead of being coerced to a default. */
        val JOURNAL_JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

object DurableFiles {
    private val logger = KotlinLogging.logger {}

    /**
     * Replaces [file] with [bytes] so that a crash leaves either the old or the new content:
     * the bytes go to a temp file that is forced to disk, which then replaces [file] with an atomic
     * move. There is deliberately no fallback to a non-atomic move; if the filesystem cannot move
     * atomically, the write fails. The directory is then synced on a best-effort basis.
     */
    fun writeAtomically(file: Path, bytes: ByteArray) {
        writeAtomically(file, bytes) { source, target ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** [atomicMove] is the real atomic move in production; tests substitute failures. */
    internal fun writeAtomically(file: Path, bytes: ByteArray, atomicMove: (Path, Path) -> Unit) {
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        FileChannel.open(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        try {
            atomicMove(temporary, file)
        } catch (error: Exception) {
            Files.deleteIfExists(temporary)
            throw error
        }
        syncDirectory(file.parent)
    }

    private fun syncDirectory(directory: Path) {
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
            .onFailure { error -> logger.debug(error) { "目录同步不可用，已跳过: ${directory}" } }
    }
}
