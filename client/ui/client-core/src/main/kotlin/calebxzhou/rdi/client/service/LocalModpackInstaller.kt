package calebxzhou.rdi.client.service

import calebxzau.rdi.client.service.ClientContentStores
import calebxzau.rdi.common.util.uuid7
import calebxzau.rdi.mcinstall.writeMinecraftOptions
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzhou.rdi.client.service.content.ContentDigest
import calebxzhou.rdi.client.service.content.ContentDigestAlgorithm
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzhou.rdi.client.service.content.ContentSource
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.common.model.supportsModpackUpload
import calebxzhou.rdi.common.model.supportsRuntime
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.openChineseZip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.io.path.name

sealed interface LocalModpackInstallSource {
    data object Empty : LocalModpackInstallSource

    /** A game directory of another launcher; saves are copied only when the player asks for them. */
    data class CopyFrom(val directory: Path, val includeSaves: Boolean = false) : LocalModpackInstallSource

    data class Archive(
        val preview: ModpackArchivePreview,
        val selectedOptionalFiles: Set<String>,
    ) : LocalModpackInstallSource
}

data class LocalModpackInstallRequest(
    val source: LocalModpackInstallSource,
    val mcVersion: McVersion,
    val modLoader: ModLoader,
    val name: String,
    val comment: String?,
    val iconUrl: String?,
)

interface LocalModpackTaskSubmitter {
    fun submit(task: Task2, dedupeKey: String): String
}

private object ClientLocalModpackTaskSubmitter : LocalModpackTaskSubmitter {
    override fun submit(task: Task2, dedupeKey: String): String = ClientTaskManager.submit(task, dedupeKey)
}

/** Installs packs into `versions/local_<uuid>` without uploading them to RDI. */
class LocalModpackInstaller(
    private val versionsDirectory: Path,
    private val newId: () -> String = { uuid7().toString() },
    private val taskSubmitter: LocalModpackTaskSubmitter = ClientLocalModpackTaskSubmitter,
    private val contentStore: () -> ClientContentStore = { ClientContentStores.shared },
) {
    /** Validates the request and submits the install task; returns its run id. */
    fun install(request: LocalModpackInstallRequest): Result<String> = runCatching {
        val normalized = request.copy(
            name = request.name.trim(),
            comment = request.comment?.trim()?.ifEmpty { null },
            iconUrl = request.iconUrl?.trim()?.ifEmpty { null },
        )
        validate(normalized)
        val id = newId()
        taskSubmitter.submit(createTask(id, normalized), "local-modpack-install:$id")
    }

    private fun validate(request: LocalModpackInstallRequest) {
        require(request.name.isNotEmpty()) { "整合包名称不能为空" }
        require(request.mcVersion.supportsModpackUpload() && request.mcVersion.supportsRuntime(request.modLoader)) {
            "暂不支持${request.mcVersion.mcVer}/${request.modLoader}"
        }
        request.iconUrl?.let(::validateWebImageUrl)
        when (val source = request.source) {
            LocalModpackInstallSource.Empty -> Unit
            is LocalModpackInstallSource.CopyFrom -> require(Files.isDirectory(source.directory)) {
                "找不到要导入的整合包目录"
            }
            is LocalModpackInstallSource.Archive -> {
                require(source.preview.mcVersion == request.mcVersion && source.preview.modLoader == request.modLoader) {
                    "整合包版本信息不一致"
                }
                val optionalKeys = source.preview.optionalFiles.mapTo(hashSetOf(), ModpackArchiveFile::key)
                require(source.selectedOptionalFiles.all { it in optionalKeys }) { "选择了未知的可选文件" }
            }
        }
    }

    private fun createTask(id: String, request: LocalModpackInstallRequest): Task2 = Task2.Leaf(
        title = "${if (request.source == LocalModpackInstallSource.Empty) "创建" else "导入"}整合包${request.name}"
    ) { context ->
        installFiles(id, request, context)
    }

    private suspend fun installFiles(id: String, request: LocalModpackInstallRequest, context: Task2Context) {
        val stagingDirectory = versionsDirectory.resolve(LocalModpackStore.stagingDirName(id))
        val targetDirectory = versionsDirectory.resolve(LocalModpackStore.versionId(id))
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(versionsDirectory)
                stagingDirectory.toFile().deleteRecursivelyNoSymlink()
            }
            context.emit(Task2Progress("正在准备", 0f))
            val source = request.source
            when (source) {
                LocalModpackInstallSource.Empty -> withContext(Dispatchers.IO) {
                    Files.createDirectories(stagingDirectory.resolve("mods"))
                }
                is LocalModpackInstallSource.CopyFrom -> withContext(Dispatchers.IO) {
                    copyGameDirectory(source, stagingDirectory, context)
                }
                is LocalModpackInstallSource.Archive -> installArchive(source, stagingDirectory, context)
            }
            withContext(Dispatchers.IO) {
                // Keep the player's own options when a game directory brought them along.
                val keepOptions = source is LocalModpackInstallSource.CopyFrom &&
                    Files.isRegularFile(stagingDirectory.resolve("options.txt"))
                if (!keepOptions) writeMinecraftOptions(stagingDirectory.toFile(), request.mcVersion).getOrThrow()
            }
            context.ensureActive()
            context.emit(Task2Progress("正在保存", null))
            withContext(NonCancellable + Dispatchers.IO) {
                commitLock.withLock {
                    val existingNames = LocalModpackStore.scan(versionsDirectory.toFile())
                        .mapNotNull { (_, info) -> info.getOrNull()?.name }
                    val info = LocalModpackInfo(
                        id = id,
                        name = LocalModpackStore.uniqueName(request.name, existingNames),
                        comment = request.comment,
                        iconUrl = request.iconUrl,
                        mcVersion = request.mcVersion,
                        modLoader = request.modLoader,
                        createdAt = System.currentTimeMillis(),
                        source = when (source) {
                            LocalModpackInstallSource.Empty -> LocalModpackSourceKind.Empty
                            is LocalModpackInstallSource.CopyFrom -> LocalModpackSourceKind.Directory
                            is LocalModpackInstallSource.Archive -> LocalModpackSourceKind.Archive
                        },
                    )
                    LocalModpackStore.write(stagingDirectory.toFile(), info).getOrThrow()
                    moveDirectory(stagingDirectory, targetDirectory)
                    committed = true
                }
            }
            context.emit(Task2Progress("安装完成", 1f))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                stagingDirectory.toFile().deleteRecursivelyNoSymlink()
                if (!committed) targetDirectory.toFile().deleteRecursivelyNoSymlink()
            }
        }
    }

    private fun copyGameDirectory(
        source: LocalModpackInstallSource.CopyFrom,
        target: Path,
        context: Task2Context,
    ) {
        val root = source.directory.toAbsolutePath().normalize()
        val files = Files.walk(root).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                    shouldCopyGameFile(root.relativize(path).toString().replace('\\', '/'), root.name, source.includeSaves)
            }.toList()
        }
        Files.createDirectories(target)
        files.forEachIndexed { index, file ->
            context.ensureActive()
            val relative = root.relativize(file).toString().replace('\\', '/')
            val output = checkedTarget(target, relative)
            output.parent?.let(Files::createDirectories)
            Files.copy(file, output, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING)
            context.emit(
                Task2Progress(
                    message = "正在复制${file.name}",
                    fraction = (index + 1).toFloat() / files.size.coerceAtLeast(1),
                )
            )
        }
        Files.createDirectories(target.resolve("mods"))
    }

    private suspend fun installArchive(
        source: LocalModpackInstallSource.Archive,
        target: Path,
        context: Task2Context,
    ) {
        val preview = source.preview
        withContext(Dispatchers.IO) {
            require(
                Files.size(preview.archive) == preview.archiveSize &&
                    Files.getLastModifiedTime(preview.archive).toMillis() == preview.archiveModifiedAt
            ) { "整合包安装包在确认后发生了变化，请重新选择" }
            Files.createDirectories(target)
        }
        val files = preview.requiredFiles + preview.optionalFiles.filter { it.key in source.selectedOptionalFiles }
        val requests = files.map { file ->
            ContentRequest(
                id = file.key,
                relativePath = file.targetPath,
                size = file.size,
                digests = listOf(ContentDigest(ContentDigestAlgorithm.SHA1, file.sha1)),
                sources = file.urls.map { url ->
                    ContentSource(url = url, headers = file.headers, knownSize = file.size, name = file.displayName)
                },
                displayName = file.displayName,
            )
        }
        if (requests.isNotEmpty()) {
            contentStore().materialize(requests = requests, targetRoot = target, onProgress = context::emit).getOrThrow()
        }
        withContext(Dispatchers.IO) {
            applyOverrides(preview, target, context)
            Files.createDirectories(target.resolve("mods"))
        }
    }

    private fun applyOverrides(preview: ModpackArchivePreview, target: Path, context: Task2Context) {
        var extractedBytes = 0L
        preview.archive.toFile().openChineseZip().use { zip ->
            preview.overrides.forEachIndexed { index, override ->
                context.ensureActive()
                val entry = requireNotNull(zip.getEntry(override.entryName)) { "覆盖文件已不存在：${override.entryName}" }
                val output = checkedTarget(target, override.targetPath)
                output.parent?.let(Files::createDirectories)
                val temporary = output.resolveSibling(".${output.fileName}.tmp")
                Files.deleteIfExists(temporary)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { stream ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            extractedBytes += read
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) { "归档解压后超过20GB" }
                            stream.write(buffer, 0, read)
                        }
                    }
                }
                moveReplacing(temporary, output)
                context.emit(
                    Task2Progress(
                        "正在应用覆盖文件${index + 1}/${preview.overrides.size}",
                        (index + 1).toFloat() / preview.overrides.size.coerceAtLeast(1),
                    )
                )
            }
        }
    }

    private fun checkedTarget(root: Path, relative: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = normalizedRoot.resolve(relative).normalize()
        require(target.startsWith(normalizedRoot) && target != normalizedRoot) { "整合包路径越界：$relative" }
        return target
    }

    private fun moveDirectory(source: Path, target: Path) {
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "整合包目录已存在" }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun moveReplacing(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val MAX_EXTRACTED_BYTES = 20L * 1024 * 1024 * 1024

        /** Serializes the unique-name check and the final move across concurrent installs. */
        private val commitLock = Mutex()

        /** Launcher-owned or regenerated folders; they are never part of a pack. */
        private val EXCLUDED_ROOT_DIRECTORIES = setOf(
            "logs", "crash-reports", "natives", "versions", "libraries", "assets", "runtime",
            ".cache", "cache", "downloads", "debug", ".mixin.out", "pcl", ".fabric",
        )
        private val EXCLUDED_ROOT_FILES = setOf(
            "launcher_profiles.json", "launcher_accounts.json", "launcher_settings.json", "launcher_log.txt",
            "hmclversion.cfg", "usercache.json", "usernamecache.json", LocalModpackStore.INFO_FILE_NAME,
        )

        /** Whether [relativePath] of a game directory named [directoryName] belongs to the pack. */
        internal fun shouldCopyGameFile(relativePath: String, directoryName: String, includeSaves: Boolean): Boolean {
            val segments = relativePath.split('/').filter(String::isNotEmpty)
            val first = segments.firstOrNull()?.lowercase(Locale.ROOT) ?: return false
            if (segments.size > 1) {
                if (first in EXCLUDED_ROOT_DIRECTORIES) return false
                if (first == "saves") return includeSaves
                return true
            }
            if (first in EXCLUDED_ROOT_FILES) return false
            if (first.endsWith(".log") || first.endsWith(".jar")) return false
            if (first == "${directoryName.lowercase(Locale.ROOT)}.json") return false
            return true
        }
    }
}

private fun validateWebImageUrl(value: String) {
    val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("图标链接无效") }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) { "图标链接必须是HTTPS网络地址" }
}
