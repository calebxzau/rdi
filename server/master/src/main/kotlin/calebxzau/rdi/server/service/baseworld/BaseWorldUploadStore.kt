package calebxzau.rdi.server.service.baseworld

import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.model.BaseWorldUploadSessionVo
import calebxzau.rdi.common.model.BaseWorldUploadStatus
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.util.humanFileSize
import calebxzau.rdi.common.util.uuid7j
import calebxzau.rdi.server.service.upload.ChunkedUploadService
import calebxzau.rdi.server.service.worldarchive.WorldArchiveExtractor
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Disk-backed state for a BaseWorld tar.zst upload. Callers serialize mutations per world. */
class BaseWorldUploadStore(
    private val root: File = calebxzhou.rdi.master.BaseWorldDir,
    private val maxArchiveSize: Long = BaseWorld.MaxSize,
    private val maxExtractedSize: Long = BaseWorld.MaxSize,
    private val clock: Clock = Clock.systemUTC(),
    private val partSize: Int = PART_SIZE,
) {
    private val chunkedUploadService = ChunkedUploadService()
    private val archiveExtractor = WorldArchiveExtractor(maxExtractedSize)

    init {
        require(maxArchiveSize >= 0)
        require(maxExtractedSize >= 0)
        require(partSize > 0)
        root.mkdirs()
        cleanupPartTemporaries()
    }

    suspend fun create(ownerId: UUID, worldId: UUID, size: Long, sha1: String): BaseWorldUploadSessionVo =
        withContext(Dispatchers.IO) {
            checkSize(size)
            val normalizedSha1 = normalizeSha1(sha1)
            val worldSessions = sessionsRoot(worldId)
            worldSessions.mkdirs()
            val existing = findActive(worldSessions)
            if (existing != null) {
                if (existing.metadata.status == BaseWorldUploadStatus.Uploading &&
                    existing.metadata.ownerId == ownerId.toString() &&
                    existing.metadata.size == size && existing.metadata.sha1 == normalizedSha1
                ) {
                    existing.metadata = existing.metadata.copy(expiresAt = clock.millis() + SESSION_TTL_MILLIS)
                    persistMetadata(existing)
                    return@withContext existing.toVo()
                }
                throw RequestError("该地图模板已有上传任务，请先取消后重试")
            }
            val id = uuid7j()
            val dir = worldSessions.resolve(id.toString())
            check(dir.mkdirs()) { "无法创建上传会话目录" }
            val state = SessionState(
                id = id,
                dir = dir,
                metadata = SessionMetadata(
                    ownerId = ownerId.toString(),
                    worldId = worldId.toString(),
                    uploadId = id.toString(),
                    size = size,
                    sha1 = normalizedSha1,
                    partSize = partSize,
                    uploadedParts = emptyMap(),
                    expiresAt = clock.millis() + SESSION_TTL_MILLIS,
                ),
            )
            try {
                persistMetadata(state)
            } catch (error: Throwable) {
                runCatching { dir.deleteRecursively() }.onFailure { error.addSuppressed(it) }
                throw error
            }
            state.toVo()
        }

    suspend fun status(ownerId: UUID, worldId: UUID, id: UUID): BaseWorldUploadSessionVo =
        withContext(Dispatchers.IO) {
            val state = requireSession(ownerId, worldId, id)
            if (state.metadata.status == BaseWorldUploadStatus.Uploading) {
                state.metadata = state.metadata.copy(expiresAt = clock.millis() + SESSION_TTL_MILLIS)
                persistMetadata(state)
            }
            state.toVo()
        }

    suspend fun statusFast(ownerId: UUID, worldId: UUID, id: UUID): BaseWorldUploadSessionVo =
        withContext(Dispatchers.IO) { requireSession(ownerId, worldId, id, expire = false).toVo() }

    suspend fun uploadPart(
        ownerId: UUID,
        worldId: UUID,
        id: UUID,
        index: Int,
        declaredLength: Long?,
        expectedSha1: String,
        source: ByteReadChannel,
    ) = withContext(Dispatchers.IO) {
        val ticket = preparePart(ownerId, worldId, id, index, declaredLength, expectedSha1)
            ?: return@withContext
        val temporary = createPartTemporary()
        try {
            chunkedUploadService.receive(source, temporary, ticket.expectedLength.toLong(), ticket.sha1)
            commitPart(ticket, temporary)
        } finally {
            temporary.delete()
        }
    }

    /** Validates an upload part while the caller holds the owning world lock. */
    internal suspend fun preparePart(
        ownerId: UUID,
        worldId: UUID,
        id: UUID,
        index: Int,
        declaredLength: Long?,
        expectedSha1: String,
    ): PreparedPart? = withContext(Dispatchers.IO) {
        val state = requireSession(ownerId, worldId, id)
        requestCheck(state.metadata.status == BaseWorldUploadStatus.Uploading, "该上传任务正在校验，请稍后再试")
        val partSha1 = normalizeSha1(expectedSha1)
        val expectedLength = state.expectedPartLength(index)
        requestCheck(declaredLength == null || declaredLength == expectedLength.toLong(), "分片长度不正确")
        val previousHash = state.metadata.uploadedParts[index]
        if (previousHash != null) {
            requestCheck(previousHash == partSha1, "该分片已上传，校验值不一致")
            val existingPart = state.partFile(index)
            if (existingPart.isFile && existingPart.length() == expectedLength.toLong()) {
                state.metadata = state.metadata.copy(expiresAt = clock.millis() + SESSION_TTL_MILLIS)
                persistMetadata(state)
                return@withContext null
            }
            state.metadata = state.metadata.copy(uploadedParts = state.metadata.uploadedParts - index)
            persistMetadata(state)
        }
        PreparedPart(
            ownerId = ownerId,
            worldId = worldId,
            id = id,
            index = index,
            expectedLength = expectedLength,
            sha1 = partSha1,
            uploadSize = state.metadata.size,
            partSize = state.metadata.partSize,
            uploadSha1 = state.metadata.sha1,
        )
    }

    /** Commits a verified part while the caller holds the owning world lock. */
    internal suspend fun commitPart(ticket: PreparedPart, temporary: File) = withContext(Dispatchers.IO) {
        val state = requireSession(ticket.ownerId, ticket.worldId, ticket.id)
        requestCheck(state.metadata.status == BaseWorldUploadStatus.Uploading, "该上传任务正在校验，请稍后再试")
        val expectedLength = state.expectedPartLength(ticket.index)
        requestCheck(expectedLength == ticket.expectedLength, "上传会话已被修改，请重试")
        requestCheck(state.metadata.size == ticket.uploadSize, "上传会话已被修改，请重试")
        requestCheck(state.metadata.partSize == ticket.partSize, "上传会话已被修改，请重试")
        requestCheck(state.metadata.sha1 == ticket.uploadSha1, "上传会话已被修改，请重试")
        val previousHash = state.metadata.uploadedParts[ticket.index]
        if (previousHash != null) {
            requestCheck(previousHash == ticket.sha1, "该分片已上传，校验值不一致")
            val existingPart = state.partFile(ticket.index)
            if (existingPart.isFile && existingPart.length() == expectedLength.toLong()) {
                state.metadata = state.metadata.copy(expiresAt = clock.millis() + SESSION_TTL_MILLIS)
                persistMetadata(state)
                return@withContext
            }
        }
        Files.move(
            temporary.toPath(),
            state.partFile(ticket.index).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        state.metadata = state.metadata.copy(
            uploadedParts = state.metadata.uploadedParts + (ticket.index to ticket.sha1),
            expiresAt = clock.millis() + SESSION_TTL_MILLIS,
        )
        persistMetadata(state)
    }

    internal fun createPartTemporary(): File =
        Files.createTempFile(root.toPath(), ".base-world-part-", ".tmp").toFile()

    suspend fun acceptForCompletion(ownerId: UUID, worldId: UUID, id: UUID): BaseWorldUploadSessionVo =
        withContext(Dispatchers.IO) {
            val state = requireSession(ownerId, worldId, id)
            when (state.metadata.status) {
                BaseWorldUploadStatus.Queued,
                BaseWorldUploadStatus.Processing,
                BaseWorldUploadStatus.Ready,
                BaseWorldUploadStatus.Failed -> return@withContext state.toVo()
                BaseWorldUploadStatus.Uploading -> Unit
            }
            requestCheck(state.metadata.uploadedParts.keys == (0 until state.partCount).toSet(), "还有分片未上传")
            for (index in 0 until state.partCount) {
                requestCheck(state.partFile(index).isFile, "上传分片不存在")
                requestCheck(state.partFile(index).length() == state.expectedPartLength(index).toLong(), "上传分片大小不正确")
            }
            state.metadata = state.metadata.copy(status = BaseWorldUploadStatus.Queued)
            persistMetadata(state)
            state.toVo()
        }

    suspend fun markProcessing(ownerId: UUID, worldId: UUID, id: UUID): BaseWorldUploadSessionVo =
        withContext(Dispatchers.IO) {
            val state = requireSession(ownerId, worldId, id)
            when (state.metadata.status) {
                BaseWorldUploadStatus.Queued -> {
                    state.metadata = state.metadata.copy(status = BaseWorldUploadStatus.Processing)
                    persistMetadata(state)
                }
                BaseWorldUploadStatus.Processing,
                BaseWorldUploadStatus.Ready,
                BaseWorldUploadStatus.Failed -> Unit
                BaseWorldUploadStatus.Uploading -> throw RequestError("上传任务尚未提交")
            }
            state.toVo()
        }

    suspend fun restoreUploading(ownerId: UUID, worldId: UUID, id: UUID) = withContext(Dispatchers.IO) {
        val state = requireSession(ownerId, worldId, id)
        if (state.metadata.status == BaseWorldUploadStatus.Queued) {
            state.metadata = state.metadata.copy(status = BaseWorldUploadStatus.Uploading)
            persistMetadata(state)
        }
    }

    suspend fun restoreQueued(ownerId: UUID, worldId: UUID, id: UUID) = withContext(Dispatchers.IO) {
        val state = requireSession(ownerId, worldId, id)
        if (state.metadata.status == BaseWorldUploadStatus.Processing) {
            state.metadata = state.metadata.copy(status = BaseWorldUploadStatus.Queued)
            persistMetadata(state)
        }
    }

    suspend fun markTerminal(
        ownerId: UUID,
        worldId: UUID,
        id: UUID,
        status: BaseWorldUploadStatus,
        errorMessage: String? = null,
    ): BaseWorldUploadSessionVo = withContext(Dispatchers.IO) {
        require(status == BaseWorldUploadStatus.Ready || status == BaseWorldUploadStatus.Failed)
        val state = requireSession(ownerId, worldId, id)
        state.metadata = state.metadata.copy(status = status, errorMessage = errorMessage)
        persistMetadata(state)
        state.toVo()
    }

    suspend fun setNotified(ownerId: UUID, worldId: UUID, id: UUID) = withContext(Dispatchers.IO) {
        val state = requireSession(ownerId, worldId, id)
        if (!state.metadata.notified) {
            state.metadata = state.metadata.copy(notified = true)
            persistMetadata(state)
        }
    }

    suspend fun shouldNotify(ownerId: UUID, worldId: UUID, id: UUID): Boolean = withContext(Dispatchers.IO) {
        requireSession(ownerId, worldId, id).metadata.notified.not()
    }

    suspend fun openForCompletion(ownerId: UUID, worldId: UUID, id: UUID): PreparedUpload =
        withContext(Dispatchers.IO) {
            val state = requireSession(ownerId, worldId, id)
            requestCheck(
                state.metadata.status == BaseWorldUploadStatus.Processing,
                "上传任务当前不可处理",
            )
            val archive = Files.createTempFile(root.toPath(), ".base-world-upload-", ".tar.zst").toFile()
            try {
                chunkedUploadService.assemble(
                    parts = (0 until state.partCount).map(state::partFile),
                    expectedSize = state.metadata.size,
                    expectedSha1 = state.metadata.sha1,
                    destination = archive,
                )
                PreparedUpload(state, archive)
            } catch (error: Throwable) {
                archive.delete()
                throw error
            }
        }

    suspend fun extract(prepared: PreparedUpload, ensureTaskActive: () -> Unit = {}): ExtractedUpload = withContext(Dispatchers.IO) {
        val context = coroutineContext
        var stage: File? = null
        try {
            val extractedSize = archiveExtractor.scan(prepared.archive, ensureTaskActive) { _, entry, input, context ->
                WorldArchiveExtractor.consumeEntry(input, entry.size, context, ensureTaskActive)
            }
            val stageDir = Files.createTempDirectory(root.toPath(), ".base-world-stage-").toFile()
            stage = stageDir
            Files.move(prepared.archive.toPath(), stageDir.resolve("world.tar.zst").toPath())
            ExtractedUpload(prepared, stageDir, extractedSize)
        } catch (error: Throwable) {
            stage?.deleteRecursively()
            throw error
        }
    }

    /** Validates and securely extracts a complete world archive into a fresh directory. */
    suspend fun extractArchiveToDir(
        archive: File,
        targetDir: File,
        ensureTaskActive: () -> Unit = {},
    ): Long = archiveExtractor.extractToDir(archive, targetDir, ensureTaskActive)

    internal suspend fun sessions(): List<SessionSnapshot> = withContext(Dispatchers.IO) {
        if (!root.resolve(".uploads").isDirectory) return@withContext emptyList()
        buildList {
            root.resolve(".uploads").listFiles(File::isDirectory)?.forEach { worldDir ->
                worldDir.listFiles(File::isDirectory)?.forEach { sessionDir ->
                    val metadataFile = sessionDir.resolve(METADATA_FILE_NAME)
                    if (!metadataFile.isFile) return@forEach
                    runCatching {
                        val metadata = serdesJson.decodeFromString<SessionMetadata>(metadataFile.readText())
                        requestCheck(metadata.worldId == worldDir.name, "上传会话与目录不匹配")
                        requestCheck(metadata.uploadId == sessionDir.name, "上传会话与目录不匹配")
                        val expiredUploading = metadata.status == BaseWorldUploadStatus.Uploading && metadata.expiresAt <= clock.millis()
                        val expiredNotifiedTerminal = metadata.status.isTerminal && metadata.notified && metadata.expiresAt <= clock.millis()
                        if (expiredUploading || expiredNotifiedTerminal) {
                            check(sessionDir.deleteRecursively()) { "无法删除过期上传会话" }
                            return@runCatching null
                        }
                        SessionSnapshot(UUID.fromString(worldDir.name), SessionState(UUID.fromString(sessionDir.name), sessionDir, metadata))
                    }.onSuccess { it?.let(::add) }
                        .onFailure { logger.error(it) { "读取地图模板上传会话失败: ${sessionDir.name}" } }
                }
            }
        }
    }

    suspend fun cleanupPayloads(prepared: PreparedUpload) = withContext(Dispatchers.IO) {
        prepared.archive.delete()
        prepared.state.dir.listFiles()?.filterNot { it.name == METADATA_FILE_NAME }?.forEach { it.deleteRecursively() }
    }

    suspend fun cleanupSessionPayloads(ownerId: UUID, worldId: UUID, id: UUID) = withContext(Dispatchers.IO) {
        val state = requireSession(ownerId, worldId, id)
        state.dir.listFiles()?.filterNot { it.name == METADATA_FILE_NAME }?.forEach { it.deleteRecursively() }
    }

    suspend fun cancel(ownerId: UUID, worldId: UUID, id: UUID) = withContext(Dispatchers.IO) {
        val state = requireSession(ownerId, worldId, id)
        requestCheck(state.metadata.status == BaseWorldUploadStatus.Uploading, "该上传任务正在校验，请稍后再试")
        check(state.dir.deleteRecursively()) { "无法删除上传会话" }
    }

    suspend fun checkDeletionAllowed(worldId: UUID) = withContext(Dispatchers.IO) {
        val root = sessionsRoot(worldId)
        root.listFiles(File::isDirectory)?.forEach { dir ->
            val file = dir.resolve(METADATA_FILE_NAME)
            if (!file.isFile) return@forEach
            val metadata = serdesJson.decodeFromString<SessionMetadata>(file.readText())
            requestCheck(
                metadata.status != BaseWorldUploadStatus.Queued && metadata.status != BaseWorldUploadStatus.Processing,
                "地图模板正在校验，请稍后再试",
            )
        }
    }

    suspend fun cleanupSession(prepared: PreparedUpload) = withContext(Dispatchers.IO) {
        var failure: Throwable? = null
        if (prepared.archive.exists() && !prepared.archive.delete()) {
            failure = IllegalStateException("无法删除临时上传文件: ${prepared.archive.absolutePath}")
        }
        if (prepared.state.dir.exists() && !prepared.state.dir.deleteRecursively()) {
            val error = IllegalStateException("无法删除上传会话: ${prepared.state.dir.absolutePath}")
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }

    suspend fun cancelWorld(worldId: UUID) = withContext(Dispatchers.IO) {
        val root = sessionsRoot(worldId)
        if (root.exists() && !root.deleteRecursively()) {
            throw IllegalStateException("无法删除地图模板上传会话: ${root.absolutePath}")
        }
    }

    private fun requireSession(ownerId: UUID, worldId: UUID, id: UUID, expire: Boolean = true): SessionState {
        val dir = sessionsRoot(worldId).resolve(id.toString())
        val metadataFile = dir.resolve(METADATA_FILE_NAME)
        if (!metadataFile.isFile) throw RequestError("上传会话不存在或已过期")
        val metadata = serdesJson.decodeFromString<SessionMetadata>(metadataFile.readText())
        requestCheck(metadata.ownerId == ownerId.toString(), "无权访问该上传会话")
        requestCheck(metadata.worldId == worldId.toString() && metadata.uploadId == id.toString(), "上传会话与地图模板不匹配")
        val state = SessionState(id, dir, metadata)
        val expiredUploading = state.metadata.status == BaseWorldUploadStatus.Uploading && state.metadata.expiresAt <= clock.millis()
        val expiredNotifiedTerminal = state.metadata.status.isTerminal && state.metadata.notified && state.metadata.expiresAt <= clock.millis()
        if (expire && (expiredUploading || expiredNotifiedTerminal)) {
            check(state.dir.deleteRecursively()) { "无法删除过期上传会话" }
            throw RequestError("上传会话已经过期")
        }
        return state
    }

    private fun findActive(root: File): SessionState? {
        val directories = root.listFiles(File::isDirectory)
            ?: throw IllegalStateException("无法读取上传会话目录: ${root.absolutePath}")
        var active: SessionState? = null
        for (dir in directories) {
            val id = runCatching { UUID.fromString(dir.name) }
                .getOrElse { throw IllegalStateException("上传会话目录名称不正确: ${dir.name}", it) }
            val metadataFile = dir.resolve(METADATA_FILE_NAME)
            if (!metadataFile.isFile) throw IllegalStateException("上传会话缺少元数据: ${metadataFile.absolutePath}")
            val metadata = serdesJson.decodeFromString<SessionMetadata>(metadataFile.readText())
            val expiredUploading = metadata.status == BaseWorldUploadStatus.Uploading && metadata.expiresAt <= clock.millis()
            val expiredNotifiedTerminal = metadata.status.isTerminal && metadata.notified && metadata.expiresAt <= clock.millis()
            if (expiredUploading || expiredNotifiedTerminal) {
                check(dir.deleteRecursively()) { "无法删除过期上传会话: ${dir.absolutePath}" }
                continue
            }
            if (metadata.status != BaseWorldUploadStatus.Ready && metadata.status != BaseWorldUploadStatus.Failed) {
                check(active == null) { "地图模板存在多个活动上传会话" }
                active = SessionState(id, dir, metadata)
            }
        }
        return active
    }

    private fun persistMetadata(state: SessionState) {
        val temporary = state.dir.resolve("$METADATA_FILE_NAME.tmp")
        temporary.writeText(serdesJson.encodeToString(state.metadata))
        runCatching {
            Files.move(temporary.toPath(), state.metadataFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary.toPath(), state.metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sessionsRoot(worldId: UUID): File = root.resolve(".uploads").resolve(worldId.toString())

    private fun cleanupPartTemporaries() {
        val files = runCatching { Files.list(root.toPath()) }
            .onFailure { logger.error(it) { "读取地图模板上传临时文件失败" } }
            .getOrNull() ?: return
        files.use { paths ->
            paths.filter { path ->
                val name = path.fileName.toString()
                name.startsWith(".base-world-part-") &&
                    name.endsWith(".tmp") &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            }.forEach { path ->
                runCatching { Files.deleteIfExists(path) }
                    .onFailure { logger.error(it) { "清理地图模板上传临时文件失败: ${path.toAbsolutePath()}" } }
            }
        }
    }

    private fun checkSize(size: Long) {
        requestCheck(size in 1..maxArchiveSize, "上传文件大小必须在${maxArchiveSize.humanFileSize}以内")
    }

    private fun normalizeSha1(value: String): String = value.trim().lowercase().also {
        requestCheck(SHA1_PATTERN.matches(it), "SHA-1格式不正确")
    }

    data class PreparedUpload internal constructor(
        internal val state: SessionState,
        internal val archive: File,
    )

    data class ExtractedUpload internal constructor(
        val prepared: PreparedUpload,
        val stage: File,
        val size: Long,
    )

    internal data class SessionState(
        val id: UUID,
        val dir: File,
        var metadata: SessionMetadata,
    ) {
        val metadataFile get() = dir.resolve(METADATA_FILE_NAME)
        val partCount get() = ((metadata.size + metadata.partSize - 1) / metadata.partSize).toInt()
        fun partFile(index: Int) = dir.resolve("part-$index")
        fun expectedPartLength(index: Int): Int {
            requestCheck(index in 0 until partCount, "分片序号超出范围")
            return minOf(metadata.partSize.toLong(), metadata.size - index.toLong() * metadata.partSize).toInt()
        }
        fun toVo() = BaseWorldUploadSessionVo(
            id,
            metadata.size,
            metadata.partSize,
            partCount,
            metadata.uploadedParts.keys.sorted(),
            metadata.expiresAt,
            metadata.status,
            metadata.errorMessage,
        )
    }

    internal data class PreparedPart(
        val ownerId: UUID,
        val worldId: UUID,
        val id: UUID,
        val index: Int,
        val expectedLength: Int,
        val sha1: String,
        val uploadSize: Long,
        val partSize: Int,
        val uploadSha1: String,
    )

    @Serializable
    internal data class SessionMetadata(
        val ownerId: String,
        val worldId: String,
        val uploadId: String,
        val size: Long,
        val sha1: String,
        val partSize: Int,
        val uploadedParts: Map<Int, String>,
        val expiresAt: Long,
        val status: BaseWorldUploadStatus = BaseWorldUploadStatus.Uploading,
        val errorMessage: String? = null,
        val notified: Boolean = false,
    )

    internal data class SessionSnapshot(val worldId: UUID, val state: SessionState)

    private companion object {
        const val PART_SIZE = BASE_WORLD_PART_SIZE
        const val SESSION_TTL_MILLIS = 24 * 60 * 60 * 1000L
        const val METADATA_FILE_NAME = "session.json"
        val SHA1_PATTERN = Regex("^[0-9a-f]{40}$")
        val logger = io.github.oshai.kotlinlogging.KotlinLogging.logger {}
    }
}

private fun requestCheck(condition: Boolean, message: String) {
    if (!condition) throw RequestError(message)
}

private val BaseWorldUploadStatus.isTerminal: Boolean
    get() = this == BaseWorldUploadStatus.Ready || this == BaseWorldUploadStatus.Failed

const val BASE_WORLD_PART_SIZE: Int = 4 * 1024 * 1024
