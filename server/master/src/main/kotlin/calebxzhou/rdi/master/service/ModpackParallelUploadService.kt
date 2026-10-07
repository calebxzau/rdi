package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.exception.ChunkedUploadErrorCodes
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.ModpackUploadSessionCreateDto
import calebxzhou.rdi.common.model.ModpackUploadSessionVo
import calebxzhou.rdi.common.serdesJson
import calebxzau.rdi.server.service.upload.ChunkedUploadService
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import org.bson.types.ObjectId
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.ranges.until

internal class ModpackParallelUploadService(
    private val sessionsDir: File,
    private val maxFileSize: Long,
    private val clock: Clock = Clock.systemUTC(),
    private val partSize: Int = DEFAULT_PART_SIZE,
    private val chunkedUploadService: ChunkedUploadService = ChunkedUploadService()
) {
    private val sessions = ConcurrentHashMap<UUID, SessionState>()
    private val sessionsMutex = Mutex()

    init {
        require(partSize > 0) { "分片大小必须大于0" }
        loadSessions()
    }

    suspend fun create(
        ownerId: ObjectId,
        dto: ModpackUploadSessionCreateDto
    ): Result<ModpackUploadSessionVo> = resultOf {
        val fileName = dto.fileName.trim().let { File(it).name }.takeIf(String::isNotBlank)
            ?: throw RequestError("上传文件名不能为空")
        requestCheck(dto.size in 1..maxFileSize, "整合包文件过大，最大允许2GiB")
        val sha1 = dto.sha1.normalizedSha1()
        sessionsMutex.withLock {
            cleanupExpiredSessions()
            sessions.values.firstOrNull { state ->
                state.metadata.ownerId == ownerId.toHexString() &&
                    state.metadata.fileName == fileName &&
                    state.metadata.size == dto.size &&
                    state.metadata.sha1 == sha1
            }?.let { return@withLock it.toVo() }

            val id = generateUuidV7(clock.millis())
            val dir = sessionsDir.resolve(id.toString())
            check(dir.mkdirs()) { "无法创建上传会话目录" }
            val dataFile = dir.resolve(DATA_FILE_NAME)
            RandomAccessFile(dataFile, "rw").use { it.setLength(dto.size) }
            val metadata = SessionMetadata(
                ownerId = ownerId.toHexString(),
                fileName = fileName,
                size = dto.size,
                sha1 = sha1,
                partSize = partSize,
                uploadedParts = emptyMap(),
                ready = false,
                expiresAt = clock.millis() + SESSION_TTL_MILLIS
            )
            persistMetadata(dir, metadata)
            SessionState(id, dir, dataFile, metadata).also {
                sessions[id] = it
            }.toVo()
        }
    }

    suspend fun status(ownerId: ObjectId, id: UUID): Result<ModpackUploadSessionVo> = resultOf {
        val state = requireSession(ownerId, id)
        state.mutex.withLock { state.toVo() }
    }

    suspend fun uploadPart(
        ownerId: ObjectId,
        id: UUID,
        index: Int,
        declaredLength: Long?,
        expectedSha1: String,
        source: ByteReadChannel
    ): Result<Unit> = resultOf {
        val state = requireSession(ownerId, id)
        val partSha1 = expectedSha1.normalizedSha1()
        val expectedLength = state.expectedPartLength(index)
        requestCheck(declaredLength == null || declaredLength == expectedLength.toLong(), "分片长度不正确")
        val reservation = state.mutex.withLock {
            requestCheck(!state.metadata.ready, "上传会话已经完成")
            val committedSha1 = state.metadata.uploadedParts[index]
            if (committedSha1 != null) {
                requestCheck(committedSha1 == partSha1, "该分片已上传，校验值不一致")
                null
            } else {
                // A client retries a part only after abandoning the previous request, whose
                // connection may never report closing, so the newest request takes the part over.
                if (index !in state.activeParts) {
                    requestCheck(
                        state.activeParts.size < MAX_CONNECTIONS,
                        "同一上传会话最多允许8个并发连接",
                        ChunkedUploadErrorCodes.PART_RETRYABLE
                    )
                }
                (++state.lastReservation).also { state.activeParts[index] = it }
            }
        } ?: return@resultOf
        try {
            withContext(Dispatchers.IO) {
                val temporary = Files.createTempFile(state.dir.toPath(), ".part-upload-", ".tmp").toFile()
                var failure: Throwable? = null
                try {
                    chunkedUploadService.receive(source, temporary, expectedLength.toLong(), partSha1)
                    commitPart(state, index, reservation, partSha1, temporary)
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    if (temporary.exists() && !temporary.delete()) {
                        val cleanupError = IllegalStateException("无法删除临时上传文件: ${temporary.absolutePath}")
                        failure?.addSuppressed(cleanupError) ?: throw cleanupError
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                state.mutex.withLock { state.activeParts.remove(index, reservation) }
            }
        }
    }

    suspend fun complete(ownerId: ObjectId, id: UUID): Result<ModpackUploadSessionVo> = resultOf {
        val state = requireSession(ownerId, id)
        state.mutex.withLock {
            if (state.metadata.ready) return@withLock state.toVo()
            requestCheck(state.activeParts.isEmpty(), "仍有分片正在上传")
            requestCheck(
                state.metadata.uploadedParts.keys == (0 until state.partCount).toSet(),
                "还有分片未上传"
            )
            val actualSha1 = withContext(Dispatchers.IO) { state.dataFile.sha1.lowercase() }
            requestCheck(actualSha1 == state.metadata.sha1, "完整文件SHA-1校验失败")
            state.metadata = state.metadata.copy(
                ready = true,
                expiresAt = clock.millis() + SESSION_TTL_MILLIS
            )
            persistMetadata(state.dir, state.metadata)
            state.toVo()
        }
    }

    suspend fun cancel(ownerId: ObjectId, id: UUID): Result<Unit> = resultOf {
        val state = requireSession(ownerId, id)
        state.mutex.withLock {
            requestCheck(!state.finalizing, "上传文件正在使用")
            requestCheck(state.activeParts.isEmpty(), "仍有分片正在上传")
            sessions.remove(id)
            check(state.dir.deleteRecursively()) { "无法删除上传会话" }
        }
    }

    suspend fun <T> withReadyUpload(
        ownerId: ObjectId,
        id: UUID,
        block: suspend (File) -> T
    ): Result<T> = resultOf {
        val state = requireSession(ownerId, id)
        state.mutex.withLock {
            requestCheck(state.metadata.ready, "上传尚未完成")
            requestCheck(!state.finalizing, "上传文件正在使用")
            state.finalizing = true
        }
        try {
            block(state.dataFile).also {
                sessions.remove(id)
                if (!state.dir.deleteRecursively()) {
                    logger.warn(IllegalStateException("无法删除已消费的整合包上传会话: ${state.dir.absolutePath}")) {
                        "清理已消费的整合包上传会话失败"
                    }
                }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                state.mutex.withLock {
                    if (state.dataFile.exists()) {
                        state.finalizing = false
                    } else {
                        sessions.remove(id)
                        state.dir.deleteRecursively()
                    }
                }
            }
            throw error
        }
    }

    fun cleanupStaleSessions(): Result<Int> = runCatching {
        var cleaned = 0
        sessions.values.forEach { state ->
            if (!state.mutex.tryLock()) return@forEach
            try {
                val expired = state.metadata.expiresAt <= clock.millis() &&
                    state.activeParts.isEmpty() && !state.finalizing
                if (expired && sessions.remove(state.id, state)) {
                    state.dir.deleteRecursively()
                    cleaned++
                }
            } finally {
                state.mutex.unlock()
            }
        }
        cleaned
    }

    private suspend fun commitPart(
        state: SessionState,
        index: Int,
        reservation: Long,
        expectedSha1: String,
        temporary: File,
    ) = state.mutex.withLock {
        requestCheck(!state.metadata.ready, "上传会话已经完成")
        requestCheck(!state.finalizing, "上传文件正在使用")
        requestCheck(state.activeParts[index] == reservation, "该分片已由新的请求接管")
        val committedSha1 = state.metadata.uploadedParts[index]
        if (committedSha1 != null) {
            requestCheck(committedSha1 == expectedSha1, "该分片已上传，校验值不一致")
            return@withLock
        }
        copyPartToDataFile(temporary, state.dataFile, index.toLong() * state.metadata.partSize)
        state.metadata = state.metadata.copy(
            uploadedParts = state.metadata.uploadedParts + (index to expectedSha1),
            expiresAt = clock.millis() + SESSION_TTL_MILLIS
        )
        persistMetadata(state.dir, state.metadata)
    }

    private fun copyPartToDataFile(temporary: File, dataFile: File, position: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var offset = position
        FileChannel.open(temporary.toPath(), StandardOpenOption.READ).use { input ->
            FileChannel.open(dataFile.toPath(), StandardOpenOption.WRITE).use { output ->
                while (true) {
                    val read = input.read(ByteBuffer.wrap(buffer))
                    if (read == -1) break
                    if (read == 0) continue
                    val bytes = ByteBuffer.wrap(buffer, 0, read)
                    while (bytes.hasRemaining()) {
                        offset += output.write(bytes, offset)
                    }
                }
            }
        }
    }

    private fun requireSession(ownerId: ObjectId, id: UUID): SessionState {
        val state = sessions[id] ?: throw RequestError("上传会话不存在或已过期")
        requestCheck(state.metadata.ownerId == ownerId.toHexString(), "无权访问该上传会话")
        requestCheck(state.metadata.expiresAt > clock.millis(), "上传会话已经过期")
        return state
    }

    private fun loadSessions() {
        sessionsDir.mkdirs()
        sessionsDir.listFiles(File::isDirectory).orEmpty().forEach { dir ->
            runCatching {
                val id = UUID.fromString(dir.name)
                val metadata = serdesJson.decodeFromString<SessionMetadata>(dir.resolve(METADATA_FILE_NAME).readText())
                val dataFile = dir.resolve(DATA_FILE_NAME)
                require(dataFile.isFile && dataFile.length() == metadata.size)
                if (metadata.expiresAt <= clock.millis()) {
                    dir.deleteRecursively()
                } else {
                    cleanupPartTemporaries(dir)
                    sessions[id] = SessionState(id, dir, dataFile, metadata)
                }
            }.onFailure { dir.deleteRecursively() }
        }
    }

    private fun cleanupPartTemporaries(dir: File) {
        dir.listFiles()?.filter { file ->
            file.name.startsWith(".part-upload-") &&
                file.name.endsWith(".tmp") &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }?.forEach { file ->
            runCatching { Files.deleteIfExists(file.toPath()) }
                .onFailure { logger.error(it) { "清理整合包上传临时文件失败: ${file.absolutePath}" } }
        }
    }

    private suspend fun cleanupExpiredSessions() {
        sessions.values.filter { it.metadata.expiresAt <= clock.millis() }.forEach { state ->
            state.mutex.withLock {
                if (state.activeParts.isEmpty() && !state.finalizing && sessions.remove(state.id, state)) {
                    state.dir.deleteRecursively()
                }
            }
        }
    }

    private fun persistMetadata(dir: File, metadata: SessionMetadata) {
        val temporary = dir.resolve("$METADATA_FILE_NAME.tmp")
        temporary.writeText(serdesJson.encodeToString(metadata))
        runCatching {
            Files.move(
                temporary.toPath(),
                dir.resolve(METADATA_FILE_NAME).toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(
                temporary.toPath(),
                dir.resolve(METADATA_FILE_NAME).toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun String.normalizedSha1(): String {
        val normalized = trim().lowercase()
        requestCheck(SHA1_PATTERN.matches(normalized), "SHA-1格式不正确")
        return normalized
    }

    private fun requestCheck(condition: Boolean, message: String, errorCode: String? = null) {
        if (!condition) throw RequestError(message, errorCode = errorCode)
    }

    private data class SessionState(
        val id: UUID,
        val dir: File,
        val dataFile: File,
        var metadata: SessionMetadata,
        val mutex: Mutex = Mutex(),
        /** Part index to the reservation of the request currently allowed to commit it. */
        val activeParts: MutableMap<Int, Long> = mutableMapOf(),
        var lastReservation: Long = 0L,
        var finalizing: Boolean = false
    ) {
        val partCount: Int
            get() = ((metadata.size + metadata.partSize - 1) / metadata.partSize).toInt()

        fun expectedPartLength(index: Int): Int {
            if (index !in 0 until partCount) throw RequestError("分片序号超出范围")
            val start = index.toLong() * metadata.partSize
            return minOf(metadata.partSize.toLong(), metadata.size - start).toInt()
        }

        fun toVo() = ModpackUploadSessionVo(
            id = id,
            fileName = metadata.fileName,
            size = metadata.size,
            sha1 = metadata.sha1,
            partSize = metadata.partSize,
            partCount = partCount,
            uploadedParts = metadata.uploadedParts.keys.sorted(),
            ready = metadata.ready,
            expiresAt = metadata.expiresAt,
            maxParallelParts = MAX_CONNECTIONS
        )
    }

    @Serializable
    private data class SessionMetadata(
        val ownerId: String,
        val fileName: String,
        val size: Long,
        val sha1: String,
        val partSize: Int,
        val uploadedParts: Map<Int, String>,
        val ready: Boolean,
        val expiresAt: Long
    )

    private companion object {
        const val DEFAULT_PART_SIZE = 4 * 1024 * 1024
        const val BUFFER_SIZE = 128 * 1024
        const val MAX_CONNECTIONS = 8
        const val SESSION_TTL_MILLIS = 24 * 60 * 60 * 1000L
        const val DATA_FILE_NAME = "upload.data"
        const val METADATA_FILE_NAME = "session.json"
        val SHA1_PATTERN = Regex("^[0-9a-f]{40}$")
        val SECURE_RANDOM = SecureRandom()
        val logger = KotlinLogging.logger {}

        fun generateUuidV7(timestampMillis: Long): UUID {
            val random = SECURE_RANDOM.nextLong()
            val mostSignificantBits = ((timestampMillis and 0xFFFFFFFFFFFFL) shl 16) or
                0x7000L or (random and 0xFFFL)
            val leastSignificantBits = (SECURE_RANDOM.nextLong() and 0x3FFFFFFFFFFFFFFFL) or
                Long.MIN_VALUE
            return UUID(mostSignificantBits, leastSignificantBits)
        }
    }
}
