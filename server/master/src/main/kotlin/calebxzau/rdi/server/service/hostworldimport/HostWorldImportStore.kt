package calebxzau.rdi.server.service.hostworldimport

import calebxzau.rdi.common.model.HostWorldImportMemberWarning
import calebxzau.rdi.common.model.HostWorldImportSessionVo
import calebxzau.rdi.common.model.HostWorldImportStatus
import calebxzau.rdi.common.util.uuid7j
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.master.Host2Dir
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID

/** A member warning as stored on disk, without contextual serializers. */
@Serializable
data class StoredMemberWarning(val playerId: String, val reason: String) {
    fun toVo() = HostWorldImportMemberWarning(ObjectId(playerId), reason)
}

/** Everything persisted for one import, including the swap journal, in one atomically written file. */
@Serializable
data class ImportSessionMetadata(
    val importId: String,
    val hostId: String,
    val ownerId: String,
    val size: Long,
    val sha1: String,
    val partSize: Int,
    val uploadedParts: Map<Int, String> = emptyMap(),
    val expiresAt: Long,
    val memberPlayerIds: List<String> = emptyList(),
    val memberWarnings: List<StoredMemberWarning> = emptyList(),
    /** Players that could not be added after the import (P3), listed in the result mail. */
    val memberFailures: List<StoredMemberWarning> = emptyList(),
    val journal: HostWorldImportJournal = HostWorldImportJournal(ImportJournalStatus.Uploading),
)

/** One import session on disk. */
class ImportSession internal constructor(val dir: File, val metadata: ImportSessionMetadata) {
    val id: UUID get() = UUID.fromString(metadata.importId)
    val hostId: ObjectId get() = ObjectId(metadata.hostId)
    val ownerId: ObjectId get() = ObjectId(metadata.ownerId)
    val journal: HostWorldImportJournal get() = metadata.journal
    val partCount: Int get() = ((metadata.size + metadata.partSize - 1) / metadata.partSize).toInt()
    val archive: File get() = dir.resolve(ARCHIVE_NAME)

    fun partFile(index: Int): File = dir.resolve("part-${index}")

    fun expectedPartLength(index: Int): Int {
        requestCheck(index in 0 until partCount, "分片序号超出范围")
        return minOf(metadata.partSize.toLong(), metadata.size - index.toLong() * metadata.partSize).toInt()
    }

    fun toVo() = HostWorldImportSessionVo(
        id = id,
        size = metadata.size,
        partSize = metadata.partSize,
        partCount = partCount,
        uploadedParts = metadata.uploadedParts.keys.sorted(),
        expiresAt = metadata.expiresAt,
        status = journal.status.toDto(),
        errorMessage = journal.failureMessage,
        memberWarnings = metadata.memberWarnings.map { it.toVo() },
    )

    internal companion object {
        const val METADATA_FILE_NAME = "session.json"
        const val ARCHIVE_NAME = "upload.tar.zst"
    }
}

fun ImportJournalStatus.toDto(): HostWorldImportStatus = when (this) {
    ImportJournalStatus.Uploading -> HostWorldImportStatus.Uploading
    ImportJournalStatus.Queued -> HostWorldImportStatus.Queued
    ImportJournalStatus.Processing -> HostWorldImportStatus.Processing
    ImportJournalStatus.Ready -> HostWorldImportStatus.Ready
    ImportJournalStatus.Failed -> HostWorldImportStatus.Failed
}

/**
 * Disk-backed import sessions under `<root>/<hostId>/<importId>/`. The root lives next to the host
 * directories but outside them, because deleting a host moves its directory away.
 *
 * Every metadata write uses [DurableFiles.writeAtomically] (atomic move only), since the swap journal
 * is part of the metadata. Callers serialize mutations of one host's sessions.
 */
class HostWorldImportStore(
    private val root: File = Host2Dir.resolve(".world-imports"),
    private val maxArchiveSize: Long = MAX_ARCHIVE_SIZE,
    private val clock: Clock = Clock.systemUTC(),
    private val partSize: Int = PART_SIZE,
) {
    init {
        require(partSize > 0)
        root.mkdirs()
    }

    /**
     * Creates a session, or resumes the owner's identical session that is still uploading. Fails if
     * the host has any other unfinished import or one that needs manual recovery.
     */
    fun create(
        hostId: ObjectId,
        ownerId: ObjectId,
        size: Long,
        sha1: String,
        memberPlayerIds: List<ObjectId>,
        memberWarnings: List<HostWorldImportMemberWarning>,
    ): ImportSession {
        requestCheck(size in 1..maxArchiveSize, "存档大小必须在${maxArchiveSize.humanFileSize}以内")
        val normalizedSha1 = normalizeSha1(sha1)
        val warnings = memberWarnings.map { StoredMemberWarning(it.playerId.toHexString(), it.reason) }
        val ids = memberPlayerIds.map { it.toHexString() }
        sessions(hostId).firstOrNull { !it.journal.status.isTerminal || it.journal.recoveryRequired }?.let { existing ->
            val meta = existing.metadata
            if (existing.journal.status == ImportJournalStatus.Uploading && meta.ownerId == ownerId.toHexString() &&
                meta.size == size && meta.sha1 == normalizedSha1
            ) {
                return write(existing.dir, meta.copy(expiresAt = uploadExpiry(), memberPlayerIds = ids, memberWarnings = warnings))
            }
            checkNotBlocked(existing)
            throw RequestError("该房间已有存档导入任务，请先取消后重试")
        }
        val id = uuid7j()
        val dir = hostRoot(hostId).resolve(id.toString())
        check(dir.mkdirs()) { "无法创建存档导入目录" }
        return try {
            write(
                dir,
                ImportSessionMetadata(
                    importId = id.toString(),
                    hostId = hostId.toHexString(),
                    ownerId = ownerId.toHexString(),
                    size = size,
                    sha1 = normalizedSha1,
                    partSize = partSize,
                    expiresAt = uploadExpiry(),
                    memberPlayerIds = ids,
                    memberWarnings = warnings,
                ),
            )
        } catch (error: Throwable) {
            runCatching { dir.deleteRecursively() }.onFailure { error.addSuppressed(it) }
            throw error
        }
    }

    /** The session, or a [RequestError] if it does not exist or its upload expired. */
    fun get(hostId: ObjectId, importId: UUID): ImportSession {
        val session = find(hostId, importId) ?: throw RequestError("存档导入任务不存在")
        if (session.journal.status == ImportJournalStatus.Uploading && session.metadata.expiresAt < clock.millis()) {
            session.dir.deleteRecursively()
            throw RequestError("存档上传已过期，请重新导入")
        }
        return session
    }

    fun find(hostId: ObjectId, importId: UUID): ImportSession? {
        val dir = hostRoot(hostId).resolve(importId.toString())
        return if (dir.resolve(ImportSession.METADATA_FILE_NAME).isFile) read(dir) else null
    }

    /** All sessions of [hostId], oldest first. */
    fun sessions(hostId: ObjectId): List<ImportSession> =
        hostRoot(hostId).listFiles { file -> file.isDirectory }.orEmpty()
            .filter { it.resolve(ImportSession.METADATA_FILE_NAME).isFile }
            .sortedBy { it.name }
            .mapNotNull { dir -> readOrLog(dir) }

    /** All sessions of all hosts. */
    fun allSessions(): List<ImportSession> =
        root.listFiles { file -> file.isDirectory && ObjectId.isValid(file.name) }.orEmpty()
            .flatMap { sessions(ObjectId(it.name)) }

    /** The session that blocks lifecycle operations on [hostId], if any (plan §10.6). */
    fun blockingSession(hostId: ObjectId): ImportSession? = sessions(hostId).firstOrNull { it.journal.blocksHost }

    /** Throws the guard's [RequestError] if [session] blocks its host. */
    fun checkNotBlocked(session: ImportSession) {
        if (session.journal.recoveryRequired) throw RequestError(RECOVERY_REQUIRED_BLOCK)
        if (session.journal.blocksHost) throw RequestError(IMPORT_IN_PROGRESS_BLOCK)
    }

    /** Applies [change] to the metadata and writes it atomically. */
    fun update(session: ImportSession, change: (ImportSessionMetadata) -> ImportSessionMetadata): ImportSession =
        write(session.dir, change(read(session.dir).metadata))

    /** The journal of [session], for [HostWorldSwap]. Writes keep the rest of the metadata. */
    fun journalStore(session: ImportSession): HostWorldImportJournalStore = object : HostWorldImportJournalStore {
        override fun read(): HostWorldImportJournal = read(session.dir).journal

        override fun write(journal: HostWorldImportJournal) {
            update(session) { it.copy(journal = journal) }
        }
    }

    /** Validates a part before it is received. Returns null if the same part is already stored. */
    fun preparePart(session: ImportSession, index: Int, declaredLength: Long?, sha1: String): PartTicket? {
        requestCheck(session.journal.status == ImportJournalStatus.Uploading, "该存档已提交，正在处理")
        val partSha1 = normalizeSha1(sha1)
        val expectedLength = session.expectedPartLength(index)
        requestCheck(declaredLength == null || declaredLength == expectedLength.toLong(), "分片长度不正确")
        val previous = session.metadata.uploadedParts[index]
        if (previous != null) {
            requestCheck(previous == partSha1, "该分片已上传，校验值不一致")
            if (session.partFile(index).length() == expectedLength.toLong()) return null
        }
        return PartTicket(session.id, index, expectedLength, partSha1)
    }

    fun createPartTemporary(): File = Files.createTempFile(root.toPath(), ".part-", ".tmp").toFile()

    /** Moves a received and verified part into place. */
    fun commitPart(hostId: ObjectId, ticket: PartTicket, temporary: File): ImportSession {
        val session = get(hostId, ticket.importId)
        requestCheck(session.journal.status == ImportJournalStatus.Uploading, "该存档已提交，正在处理")
        Files.move(temporary.toPath(), session.partFile(ticket.index).toPath(), StandardCopyOption.REPLACE_EXISTING)
        return update(session) {
            it.copy(uploadedParts = it.uploadedParts + (ticket.index to ticket.sha1), expiresAt = uploadExpiry())
        }
    }

    /** `Uploading` → `Queued` once every part is present. */
    fun markQueued(session: ImportSession): ImportSession {
        requestCheck(session.journal.status == ImportJournalStatus.Uploading, "该存档已提交")
        requestCheck(session.metadata.uploadedParts.keys == (0 until session.partCount).toSet(), "还有分片未上传")
        for (index in 0 until session.partCount) {
            requestCheck(session.partFile(index).length() == session.expectedPartLength(index).toLong(), "上传分片大小不正确")
        }
        return update(session) { it.copy(journal = it.journal.copy(status = ImportJournalStatus.Queued)) }
    }

    /** `Queued` → `Uploading`, when the processing task could not be started. */
    fun restoreUploading(session: ImportSession) {
        update(session) {
            if (it.journal.status == ImportJournalStatus.Queued) it.copy(journal = it.journal.copy(status = ImportJournalStatus.Uploading)) else it
        }
    }

    fun parts(session: ImportSession): List<File> = (0 until session.partCount).map { session.partFile(it) }

    /** Deletes the parts and the assembled archive; the metadata stays. */
    fun cleanupPayloads(session: ImportSession) {
        for (index in 0 until session.partCount) session.partFile(index).delete()
        session.archive.delete()
    }

    /** Deletes a session that is still uploading. */
    fun cancel(session: ImportSession) {
        requestCheck(session.journal.status == ImportJournalStatus.Uploading, "存档已提交，无法取消")
        session.dir.deleteRecursively()
    }

    /** Deletes every session of a deleted host. Only allowed when none of them blocks the host. */
    fun deleteHost(hostId: ObjectId) {
        sessions(hostId).forEach { checkNotBlocked(it) }
        hostRoot(hostId).deleteRecursively()
    }

    /**
     * Deletes uploads past their expiry, and finished sessions past their retention, unless they are
     * claimed, need manual recovery, or still have a mail to send.
     */
    fun expire(isClaimed: (UUID) -> Boolean) {
        val now = clock.millis()
        allSessions().forEach { session ->
            val journal = session.journal
            val expired = when {
                isClaimed(session.id) || journal.recoveryRequired -> false
                journal.status == ImportJournalStatus.Uploading -> session.metadata.expiresAt < now
                journal.status.isTerminal -> journal.notified && session.metadata.expiresAt < now
                else -> false
            }
            if (expired) {
                logger.info { "删除过期的存档导入任务: ${session.dir}" }
                session.dir.deleteRecursively()
            }
        }
    }

    /** Sets the retention of a session that just finished. */
    fun retainFinished(session: ImportSession): ImportSession =
        update(session) { it.copy(expiresAt = clock.millis() + FINISHED_RETENTION_MILLIS) }

    private fun hostRoot(hostId: ObjectId): File = root.resolve(hostId.toHexString())

    private fun uploadExpiry(): Long = clock.millis() + UPLOAD_TTL_MILLIS

    private fun read(dir: File): ImportSession =
        ImportSession(dir, JSON.decodeFromString(dir.resolve(ImportSession.METADATA_FILE_NAME).readText()))

    private fun readOrLog(dir: File): ImportSession? =
        runCatching { read(dir) }.getOrElse { error ->
            logger.error(error) { "读取存档导入任务失败: ${dir}" }
            null
        }

    private fun write(dir: File, metadata: ImportSessionMetadata): ImportSession {
        DurableFiles.writeAtomically(dir.resolve(ImportSession.METADATA_FILE_NAME).toPath(), JSON.encodeToString(metadata).toByteArray())
        return ImportSession(dir, metadata)
    }

    private fun normalizeSha1(value: String): String = value.trim().lowercase().also {
        requestCheck(SHA1_PATTERN.matches(it), "SHA-1格式不正确")
    }

    data class PartTicket(val importId: UUID, val index: Int, val expectedLength: Int, val sha1: String)

    companion object {
        const val MAX_ARCHIVE_SIZE = 2L * 1024 * 1024 * 1024
        const val PART_SIZE = 4 * 1024 * 1024
        const val UPLOAD_TTL_MILLIS = 24 * 60 * 60 * 1000L
        const val FINISHED_RETENTION_MILLIS = 7 * 24 * 60 * 60 * 1000L
        const val IMPORT_IN_PROGRESS_BLOCK = "房间正在导入存档，请稍后再试"
        const val RECOVERY_REQUIRED_BLOCK = "房间存档导入中断，请联系管理员"

        private val SHA1_PATTERN = Regex("^[0-9a-f]{40}$")
        private val logger = KotlinLogging.logger {}

        /** Unknown enum values must fail rather than be coerced: the journal drives file moves. */
        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

internal fun requestCheck(condition: Boolean, message: String) {
    if (!condition) throw RequestError(message)
}
