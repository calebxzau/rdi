package calebxzau.rdi.client.service

import calebxzhou.rdi.client.net.lgr
import calebxzau.rdi.common.model.BaseWorld
import calebxzau.rdi.common.model.BaseWorldUploadSessionCreateDto
import calebxzau.rdi.common.model.BaseWorldUploadStatus
import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.digestHex
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.sha1dig
import calebxzhou.rdi.common.util.validateModpackName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.StandardCharsets

private const val MAX_ENTRIES = 100_000
private val BASE_WORLD_LEVEL_TYPE_PATTERN = Regex("^[a-z0-9_.-]+:[a-z0-9/._-]+$")

fun validateBaseWorldLevelType(value: String): Result<Unit> = runCatching {
    require(BASE_WORLD_LEVEL_TYPE_PATTERN.matches(value.trim())) {
        "地形类型必须是foo:bar格式"
    }
}

fun validateBaseWorldName(value: String): Result<Unit> =
    value.validateModpackName().fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { error ->
            Result.failure(
                RequestError(
                    error.message?.replace("整合包", "地图模板") ?: "地图模板名称无效",
                    cause = error,
                ),
            )
        },
    )

data class BaseWorldUploadTaskConfig(
    val maxPartRetries: Int = DEFAULT_CHUNKED_UPLOAD_PART_RETRIES,
    val retryDelayMillis: Long = DEFAULT_CHUNKED_UPLOAD_RETRY_DELAY_MILLIS,
    val parallelism: Int = DEFAULT_CHUNKED_UPLOAD_PARALLELISM,
)

class BaseWorldUploadService(
    private val api: BaseWorldApi,
    private val tempRoot: File = ClientDirs.packProcDir.resolve("baseworld-uploads"),
    private val maxSize: Long = BaseWorld.MaxSize,
    private val config: BaseWorldUploadTaskConfig = BaseWorldUploadTaskConfig(),
) {
    init {
        require(maxSize > 0) { "地图模板大小限制必须为正数" }
        require(config.maxPartRetries >= 0) { "分片重试次数不能为负数" }
        require(config.retryDelayMillis >= 0) { "分片重试间隔不能为负数" }
        require(config.parallelism in 1..DEFAULT_CHUNKED_UPLOAD_PARALLELISM) { "分片并发数必须在1到8之间" }
    }

    fun uploadTask(request: BaseWorldUploadRequest): Task2 {
        validateBaseWorldName(request.name).getOrThrow()
        val snapshot = request.copy(
            directory = request.directory?.absoluteFile?.toPath()?.normalize()?.toFile(),
            name = request.name,
            levelType = request.levelType.trim(),
            generatorSettings = request.generatorSettings?.trim()?.takeIf(String::isNotEmpty),
        )
        return Task2.Leaf("上传地图模板 ${snapshot.name}") { context ->
            upload(snapshot, context)
        }
    }

    private suspend fun upload(request: BaseWorldUploadRequest, context: Task2Context) =
        withContext(Dispatchers.IO) {
            require(request.name.isNotBlank()) { "地图模板名称不能为空" }
            validateBaseWorldLevelType(request.levelType).getOrThrow()
            val directory = request.directory
            if (directory == null) {
                context.emit(Task2Progress("正在创建生成地图模板", 0f))
                api.create(
                    BaseWorld.CreateDto(
                        name = request.name,
                        levelType = request.levelType,
                        generatorSettings = request.generatorSettings,
                        size = 0,
                        generated = true,
                    ),
                )
                context.emit(Task2Progress("地图模板已创建", 1f))
                return@withContext
            }
            val sourceRoot = directory.toPath().toAbsolutePath().normalize()
            val temporaryRoot = tempRoot.absoluteFile.toPath().toAbsolutePath().normalize()
            require(!sourceRoot.startsWith(temporaryRoot) && !temporaryRoot.startsWith(sourceRoot)) {
                "地图模板临时目录不能与世界目录重叠"
            }
            require(!Files.isSymbolicLink(sourceRoot) && Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
                "世界目录无效"
            }

            var worldId: java.util.UUID? = null
            var completionStarted = false
            var temporary: Path? = null
            try {
                context.emit(Task2Progress("正在扫描地图文件", 0f))
                val initial = scanSource(sourceRoot, context)
                require(initial.totalBytes <= maxSize) { "地图文件总大小超过${maxSize.humanFileSize}限制" }
                Files.createDirectories(temporaryRoot)
                temporary = Files.createTempDirectory(temporaryRoot, "baseworld-upload-")
                val archive = temporary.resolve("world.tar.zst")
                writeArchive(sourceRoot, initial, archive, context)
                context.ensureActive()
                currentCoroutineContext().ensureActive()
                val finalSnapshot = scanSource(sourceRoot, context)
                require(initial.entries == finalSnapshot.entries && initial.totalBytes == finalSnapshot.totalBytes) {
                    "世界目录在打包期间发生变化，请重试"
                }
                require(Files.size(archive) in 1..maxSize) { "世界压缩包大小超过${maxSize.humanFileSize}限制" }
                val archiveSha1 = sha1(archive, context)
                val archiveSize = Files.size(archive)

                val world = api.create(
                    BaseWorld.CreateDto(
                        name = request.name,
                        levelType = request.levelType,
                        generatorSettings = request.generatorSettings,
                        size = initial.totalBytes,
                        generated = false,
                    ),
                )
                worldId = world.id
                val session = api.createUpload(
                    world.id,
                    BaseWorldUploadSessionCreateDto(archiveSize, archiveSha1),
                )
                uploadParts(world.id, session, archive, context)
                context.ensureActive()
                currentCoroutineContext().ensureActive()
                completionStarted = true
                context.emit(Task2Progress("正在提交地图模板校验", 0.99f, archiveSize, archiveSize))
                val completion = api.completeUpload(world.id, session.id)
                when (completion.status) {
                    BaseWorldUploadStatus.Queued,
                    BaseWorldUploadStatus.Processing -> context.emit(
                        Task2Progress(
                            "上传已提交，校验结果将通过邮件通知",
                            1f,
                            archiveSize,
                            archiveSize,
                        ),
                    )

                    BaseWorldUploadStatus.Ready -> context.emit(
                        Task2Progress(
                            "地图模板已准备好，请查看邮件",
                            1f,
                            archiveSize,
                            archiveSize,
                        ),
                    )

                    BaseWorldUploadStatus.Failed -> throw RequestError(
                        completion.errorMessage ?: "地图模板校验失败",
                    )

                    BaseWorldUploadStatus.Uploading -> throw RequestError(
                        "服务器未接受地图模板上传完成请求",
                    )
                }
            } catch (cause: Throwable) {
                if (worldId != null && !completionStarted) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { withTimeout(10_000L) { api.delete(worldId!!) } }
                            .onFailure { cleanupError ->
                                lgr.error(cleanupError) { "清理失败的地图模板元数据失败" }
                            }
                    }
                }
                throw cause
            } finally {
                temporary?.toFile()?.let { directory ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        if (directory.exists()) {
                            directory.deleteRecursivelyNoSymlink()
                        }
                        if (directory.exists()) {
                            lgr.warn { "清理地图模板上传临时目录失败: ${directory.absolutePath}" }
                        }
                    }
                }
            }
        }

    private suspend fun uploadParts(
        worldId: java.util.UUID,
        session: calebxzau.rdi.common.model.BaseWorldUploadSessionVo,
        archive: Path,
        context: Task2Context,
    ) {
        val archiveSize = Files.size(archive)
        ChunkedUploader(
            file = archive,
            descriptor = ChunkedUploadDescriptor(
                size = session.size,
                partSize = session.partSize,
                partCount = session.partCount,
                uploadedParts = session.uploadedParts,
            ),
            maxPartRetries = config.maxPartRetries,
            retryDelayMillis = config.retryDelayMillis,
            parallelism = config.parallelism,
            uploadPart = { index, bytes, partSha1, onBytesSent ->
                api.uploadPart(worldId, session.id, index, bytes, partSha1, onBytesSent)
            },
            ensureActive = context::ensureActive,
            onProgress = { completedBytes, completedParts ->
                context.emit(
                    Task2Progress(
                        "正在上传分片${completedParts}/${session.partCount}",
                        completedBytes.toFloat() / archiveSize,
                        completedBytes,
                        archiveSize,
                        completedItems = completedParts,
                        totalItems = session.partCount,
                    ),
                )
            },
        ).upload()
    }

    private suspend fun writeArchive(
        sourceRoot: Path,
        snapshot: SourceSnapshot,
        archive: Path,
        context: Task2Context,
    ) {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        var completedBytes = 0L
        var completedItems = 0
        TarZstArchiveWriter(archive.toFile()).use { writer ->
            snapshot.entries.values.sortedBy { it.relative }.forEach { entry ->
                context.ensureActive()
                job?.ensureActive()
                if (entry.directory) {
                    writer.addDirectory(entry.relative, entry.lastModified)
                    return@forEach
                }
                context.emit(
                    Task2Progress(
                        "正在打包 ${entry.relative}",
                        if (snapshot.totalBytes == 0L) 0f else completedBytes.toFloat() / snapshot.totalBytes,
                        completedBytes,
                        snapshot.totalBytes,
                        completedItems = completedItems,
                        totalItems = snapshot.fileCount,
                    ),
                )
                Files.newByteChannel(
                    sourceRoot.resolve(entry.relative),
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { channel ->
                    Channels.newInputStream(channel).use { input ->
                        writer.addFileStreaming(
                            entry.relative,
                            input,
                            entry.size,
                            entry.lastModified,
                        ) {
                            context.ensureActive()
                            job?.ensureActive()
                        }
                    }
                }
                val after = readSourceEntry(sourceRoot.resolve(entry.relative), entry.relative)
                require(!after.directory && after.size == entry.size && after.lastModified == entry.lastModified) {
                    "地图文件在打包期间发生变化：${entry.relative}"
                }
                completedBytes += entry.size
                completedItems++
                context.emit(
                    Task2Progress(
                        "正在打包 ${entry.relative}",
                        if (snapshot.totalBytes == 0L) 1f else completedBytes.toFloat() / snapshot.totalBytes,
                        completedBytes,
                        snapshot.totalBytes,
                        completedItems = completedItems,
                        totalItems = snapshot.fileCount,
                    ),
                )
            }
        }
    }

    private suspend fun scanSource(root: Path, context: Task2Context): SourceSnapshot {
        val entries = linkedMapOf<String, SourceEntry>()
        var totalBytes = 0L
        var count = 0
        Files.walk(root).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                context.ensureActive()
                currentCoroutineContext().ensureActive()
                val path = iterator.next()
                if (path == root) continue
                count++
                require(count <= MAX_ENTRIES) { "地图文件数量超过限制" }
                val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
                require(relative.isNotBlank() && relative.split('/').none { it.isEmpty() || ':' in it || '\\' in it }) {
                    "世界目录包含服务器不支持的路径：$relative"
                }
                val entry = readSourceEntry(path, relative)
                entries[relative] = entry
                if (!entry.directory) {
                    totalBytes = Math.addExact(totalBytes, entry.size)
                    require(totalBytes <= maxSize) { "地图文件总大小超过${maxSize.humanFileSize}限制" }
                }
            }
        }
        val level = entries["level.dat"]
        require(level != null && !level.directory && level.size > 0) { "世界目录缺少非空level.dat" }
        return SourceSnapshot(entries, totalBytes, entries.values.count { !it.directory })
    }

    private fun readSourceEntry(path: Path, relative: String): SourceEntry {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        require(!attributes.isSymbolicLink) { "世界目录包含不支持的符号链接：$relative" }
        require(attributes.isDirectory || attributes.isRegularFile) { "世界目录包含不支持的特殊文件：$relative" }
        return SourceEntry(
            relative = relative,
            directory = attributes.isDirectory,
            size = if (attributes.isDirectory) 0L else attributes.size(),
            lastModified = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun sha1(path: Path, context: Task2Context): String {
        val digest = sha1dig()
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                context.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digestHex()
    }
}

private data class SourceSnapshot(
    val entries: Map<String, SourceEntry>,
    val totalBytes: Long,
    val fileCount: Int,
)

private data class SourceEntry(
    val relative: String,
    val directory: Boolean,
    val size: Long,
    val lastModified: Long,
)

fun baseWorldUploadTaskKey(ownerId: String, directory: File): String {
    val normalized = directory.absoluteFile.toPath().normalize().toString()
    val path = if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
        normalized.lowercase()
    } else {
        normalized
    }
    return "baseworld-upload:${ownerId.trim()}:$path"
}

fun baseWorldGeneratedTaskKey(ownerId: String, name: String, levelType: String, generatorSettings: String?): String {
    val normalized = listOf(name.trim(), levelType.trim(), generatorSettings?.trim().orEmpty())
        .joinToString("\u0000")
        .toByteArray(StandardCharsets.UTF_8)
    val digest = sha1dig().apply { update(normalized) }.digestHex()
    return "baseworld-generated:${ownerId.trim()}:$digest"
}
