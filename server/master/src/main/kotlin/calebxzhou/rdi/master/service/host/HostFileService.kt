package calebxzhou.rdi.master.service.host

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.HOST_ALLOW_FILE_EXT
import calebxzhou.rdi.common.model.HOST_OPR_DIR
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.common.service.TaczGunpackValidator
import calebxzhou.rdi.master.service.host.HostService.HOST_WORKDIR_LIMIT_BYTES
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

object HostFileService {
    private val lgr by Loggers
    private const val HOST_FILE_MAX_BYTES: Long = 64L * 1024 * 1024
    private const val zipMaxSize: Long = 100L * 1024 * 1024
    private const val HOST_FILE_SEARCH_MAX_RESULTS = 200
    private val allowFileOprDir = HOST_OPR_DIR.keys


    private data class ResolvedHostFile(
        val path: String,
        val file: File
    )

    private val Path.invariantSeparatorsPath: String
        get() = toString().replace('\\', '/')

    private fun Host.resolveHostFile(relativePath: String, allowAllowedRoot: Boolean = false): ResolvedHostFile {
        val normalizedPath = relativePath.trim().replace('\\', '/')
        if (normalizedPath.isBlank()) throw RequestError("文件路径不能为空")
        if (normalizedPath.startsWith('/')) throw RequestError("非法文件路径")

        val hostRoot = dir.toPath().toAbsolutePath().normalize()
        val target = hostRoot.resolve(normalizedPath).normalize()
        if (!target.startsWith(hostRoot)) throw RequestError("非法文件路径")

        val resolvedPath = hostRoot.relativize(target).invariantSeparatorsPath
        val rootDir = resolvedPath.substringBefore('/')
        if (rootDir !in allowFileOprDir) throw RequestError("只能操作${allowFileOprDir.joinToString()}目录")
        if (!allowAllowedRoot && resolvedPath == rootDir) throw RequestError("不能直接操作目录根")

        checkNoHostFileSymlink(hostRoot, target)
        return ResolvedHostFile(resolvedPath, target.toFile())
    }

    private fun checkNoHostFileSymlink(hostRoot: Path, target: Path) {
        var current = hostRoot
        for (segment in hostRoot.relativize(target)) {
            current = current.resolve(segment)
            if (Files.isSymbolicLink(current)) throw RequestError("不允许操作软链接文件")
        }
    }

    private fun File.toHostFileEntry(root: File): Host.FileEntry {
        val absoluteRoot = root.absoluteFile
        val absoluteFile = this.absoluteFile
        return Host.FileEntry(
            path = absoluteFile.relativeTo(absoluteRoot).toPath().invariantSeparatorsPath,
            name = name,
            directory = isDirectory,
            size = if (isDirectory) 0 else length(),
            updateTime = lastModified()
        )
    }

    suspend fun HostContext.listHostFiles(path: String): List<Host.FileEntry> {
        if (path.isBlank()) {
            return allowFileOprDir.sorted().mapNotNull { dirName ->
                val file = host.dir.resolve(dirName)
                if (!file.isDirectory) return@mapNotNull null
                Host.FileEntry(
                    path = dirName,
                    name = dirName,
                    directory = true,
                    size = 0,
                    updateTime = file.lastModified()
                )
            }
        }

        val resolved = host.resolveHostFile(path, allowAllowedRoot = true)
        if (resolved.path.startsWith("tacz/")) throw RequestError("TaCZ枪包不允许子目录操作")
        val dir = resolved.file
        if (!dir.exists()) return emptyList()
        if (!dir.isDirectory) throw RequestError("目标不是目录")

        val hostRoot = host.dir.absoluteFile
        return dir.listFiles()
            ?.asSequence()
            ?.filterNot { Files.isSymbolicLink(it.toPath()) }
            ?.filterNot { it.name.startsWith(".rdi-upload-") }
            ?.filter {
                val entryPath = it.relativeTo(hostRoot).toPath().invariantSeparatorsPath
                it.isDirectory && !entryPath.isUnderTaczPath() || it.isFile && entryPath.isAllowedHostFilePath(directory = false)
            }
            ?.map { it.toHostFileEntry(hostRoot) }
            ?.sortedWith(compareBy<Host.FileEntry> { !it.directory }.thenBy { it.name.lowercase() })
            ?.toList()
            ?: emptyList()
    }

    fun HostContext.searchHostFiles(query: String): List<Host.FileEntry> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()

        val hostRoot = host.dir.absoluteFile
        val results = mutableListOf<Host.FileEntry>()
        val pendingDirs = ArrayDeque<File>()

        allowFileOprDir.sorted().forEach { dirName ->
            val rootDir = hostRoot.resolve(dirName)
            if (rootDir.isDirectory && !Files.isSymbolicLink(rootDir.toPath())) {
                pendingDirs.add(rootDir)
            }
        }

        while (pendingDirs.isNotEmpty() && results.size < HOST_FILE_SEARCH_MAX_RESULTS) {
            val dir = pendingDirs.removeFirst()
            val dirPath = dir.relativeTo(hostRoot).toPath().invariantSeparatorsPath
            if (dirPath.contains(normalizedQuery, ignoreCase = true) || dir.name.contains(normalizedQuery, ignoreCase = true)) {
                results += dir.toHostFileEntry(hostRoot)
            }

            dir.listFiles()
                ?.asSequence()
                ?.filterNot { Files.isSymbolicLink(it.toPath()) }
                ?.filterNot { it.name.startsWith(".rdi-upload-") }
                ?.forEach { child ->
                    if (results.size >= HOST_FILE_SEARCH_MAX_RESULTS) return@forEach
                    val childPath = child.relativeTo(hostRoot).toPath().invariantSeparatorsPath
                    when {
                        child.isDirectory && !childPath.isUnderTaczPath() -> {
                            pendingDirs.add(child)
                            if (childPath.contains(normalizedQuery, ignoreCase = true) || child.name.contains(normalizedQuery, ignoreCase = true)) {
                                results += child.toHostFileEntry(hostRoot)
                            }
                        }

                        child.isFile && childPath.isAllowedHostFilePath(directory = false) -> {
                            if (childPath.contains(normalizedQuery, ignoreCase = true) || child.name.contains(normalizedQuery, ignoreCase = true)) {
                                results += child.toHostFileEntry(hostRoot)
                            }
                        }
                    }
                }
        }

        return results
            .distinctBy { it.path }
            .sortedWith(compareBy<Host.FileEntry> { it.path.count { ch -> ch == '/' } }.thenBy { it.path.lowercase() })
    }

    suspend fun HostContext.uploadHostFile(call: ApplicationCall): Host.FileUploadVo {
        val multipart = call.receiveMultipart(formFieldLimit = zipMaxSize)
        var targetPath = call.request.queryParameters["path"]
        var uploadTemp: File? = null
        var uploadedSize = 0L

        fun uploadLimit(): Long =
            if (targetPath?.trim()?.replace('\\', '/')?.startsWith("tacz/") == true) {
                zipMaxSize
            } else {
                HOST_FILE_MAX_BYTES
            }

        try {
            while (true) {
                val part = multipart.readPart() ?: break
                try {
                    when (part) {
                        is PartData.FormItem -> if (part.name == "path") {
                            targetPath = part.value
                        }

                        is PartData.FileItem -> if (part.name == "file") {
                            uploadTemp?.delete()
                            uploadTemp = host.createHostUploadTempFile()
                            uploadedSize = receiveHostUploadFile(part.provider(), uploadTemp!!, uploadLimit())
                        }

                        is PartData.BinaryItem -> if (part.name == "file") {
                            uploadTemp?.delete()
                            uploadTemp = host.createHostUploadTempFile()
                            uploadedSize = receiveHostUploadFile(part.provider(), uploadTemp!!, uploadLimit())
                        }

                        else -> {}
                    }
                } finally {
                    part.dispose()
                }
            }

            val normalizedTargetPath = targetPath
                ?.takeIf { it.isNotBlank() }
                ?.normalizeHostUploadTargetPath()
                ?: throw ParamError("缺少路径")
            val tempFile = uploadTemp ?: throw ParamError("缺少文件")
            val resolved = host.resolveHostFile(normalizedTargetPath)
            val target = resolved.file
            if (target.exists() && target.isDirectory) throw RequestError("目标是目录")
            validateHostUploadTarget(resolved.path, tempFile)
            target.parentFile?.let { parent ->
                if (parent.exists() && !parent.isDirectory) throw RequestError("目标目录异常")
                parent.mkdirs()
            }

            host.resolveHostFile(normalizedTargetPath)
            host.checkHostFileQuota(tempFile, target, uploadedSize)
            moveHostUploadFile(tempFile, target)
            uploadTemp = null

            lgr.info { "Host ${host._id} 用户${player._id}上传房间文件 ${resolved.path} ${uploadedSize.humanFileSize}" }
            return Host.FileUploadVo(
                path = resolved.path,
                size = target.length(),
                updateTime = target.lastModified()
            )
        } catch (error: Throwable) {
            uploadTemp?.delete()
            throw error
        }
    }

    private fun String.normalizeHostUploadTargetPath(): String {
        val normalizedPath = trim().replace('\\', '/').trim('/')
        if (normalizedPath.isBlank()) throw RequestError("文件路径不能为空")
        val parent = normalizedPath.substringBeforeLast('/', "")
        val rawName = normalizedPath.substringAfterLast('/').trim()
        val dotIndex = rawName.lastIndexOf('.')
        val rawBaseName = if (dotIndex > 0) rawName.substring(0, dotIndex) else rawName
        val rawExtension = if (dotIndex > 0 && dotIndex < rawName.lastIndex) rawName.substring(dotIndex + 1) else ""
        val cleanBaseName = rawBaseName.filter { it.isAllowedHostUploadFileNameChar() }.ifBlank { "file" }
        val cleanExtension = rawExtension.filter { it.isLetterOrDigit() }
        val cleanName = if (cleanExtension.isBlank()) cleanBaseName else "$cleanBaseName.$cleanExtension"
        return if (parent.isBlank()) cleanName else "$parent/$cleanName"
    }

    private fun Char.isAllowedHostUploadFileNameChar(): Boolean =
        this == '-' || this == '_' || isDigit() || this in 'a'..'z' || this in 'A'..'Z' || this in '\u4e00'..'\u9fff'

    private fun String.isUnderTaczPath(): Boolean =
        this == "tacz" || startsWith("tacz/")

    private fun String.isDirectTaczFilePath(): Boolean {
        if (!startsWith("tacz/")) return false
        val childPath = removePrefix("tacz/")
        return childPath.isNotBlank() && '/' !in childPath
    }

    private fun String.fileExt(): String =
        substringAfterLast('/', this)
            .substringAfterLast('.', "")
            .lowercase()

    private fun String.isAllowedOrdinaryFilePath(): Boolean {
        val ext = fileExt()
        return ext.isNotBlank() && ext in HOST_ALLOW_FILE_EXT
    }

    private fun String.isAllowedHostFilePath(directory: Boolean): Boolean {
        if (isUnderTaczPath()) {
            return !directory && isDirectTaczFilePath() && fileExt() == "zip"
        }
        return directory || isAllowedOrdinaryFilePath()
    }

    private fun validateHostFilePath(path: String, directory: Boolean) {
        if (path.isAllowedHostFilePath(directory)) return
        if (path.isUnderTaczPath()) {
            throw RequestError("tacz仅支持zip文件")
        }
        if (!directory && path.fileExt() == "zip") {
            throw RequestError("zip文件只能放在tacz目录")
        }
        throw RequestError("暂不支持此类型的文件")
    }

    private fun validateTaczTextOperation(path: String) {
        if (path.isUnderTaczPath()) throw RequestError("TaCZ枪包请使用上传功能")
    }

    private fun Host.createHostUploadTempFile(): File {
        dir.mkdirs()
        return Files.createTempFile(dir.toPath(), ".rdi-upload-", ".tmp").toFile()
    }

    private suspend fun receiveHostUploadFile(channel: ByteReadChannel, target: File, maxBytes: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        Files.newOutputStream(
            target.toPath(),
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        ).use { output ->
            while (!channel.isClosedForRead) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) throw RequestError("文件过大，最大允许${maxBytes.toHostUploadLimitText()}")
                output.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun receiveHostUploadFile(source: Source, target: File, maxBytes: Long): Long {
        val bytes = source.buffered().readByteArray()
        if (bytes.size > maxBytes) throw RequestError("文件过大，最大允许${maxBytes.toHostUploadLimitText()}")
        target.writeBytes(bytes)
        return bytes.size.toLong()
    }

    private fun Long.toHostUploadLimitText(): String =
        if (this % (1024L * 1024L) == 0L) "${this / 1024L / 1024L}MB" else humanFileSize

    private fun validateHostUploadTarget(path: String, uploadTemp: File) {
        validateHostFilePath(path, directory = false)
        if (!path.isUnderTaczPath()) return
        if (!path.isDirectTaczFilePath()) {
            throw RequestError("TaCZ枪包只能上传到tacz根目录")
        }
        TaczGunpackValidator.validate(uploadTemp)
            .getOrElse { throw RequestError(it.message ?: "TaCZ枪包格式无效") }
    }

    private fun Host.checkHostFileQuota(uploadTemp: File, target: File, newSize: Long) {
        val ignoredPaths = setOf(uploadTemp, target)
            .map { it.toPath().toAbsolutePath().normalize() }
            .toSet()
        val totalSize = dir.walkTopDown()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .filterNot { it.toPath().toAbsolutePath().normalize() in ignoredPaths }
            .sumOf { it.length() }
        if (totalSize + newSize > HOST_WORKDIR_LIMIT_BYTES) {
            throw RequestError("房间目录超过限制，请删除不必要文件后再上传")
        }
    }

    private fun moveHostUploadFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun HostContext.readHostFile(path: String): Host.FileContentVo {
        val resolved = host.resolveHostFile(path)
        validateHostFilePath(resolved.path, directory = false)
        validateTaczTextOperation(resolved.path)
        val target = resolved.file
        if (!target.exists()) throw RequestError("文件不存在")
        if (target.isDirectory) throw RequestError("目标是目录")
        if (target.length() > HOST_FILE_MAX_BYTES) throw RequestError("文件过大，最大允许${HOST_FILE_MAX_BYTES.toHostUploadLimitText()}")
        return Host.FileContentVo(
            path = resolved.path,
            content = target.readText(Charsets.UTF_8),
            size = target.length(),
            updateTime = target.lastModified()
        )
    }

    fun HostContext.createHostFile(payload: Host.FileCreateDto): Host.FileUploadVo {
        val resolved = host.resolveHostFile(payload.path)
        val target = resolved.file
        validateHostFilePath(resolved.path, directory = payload.directory)
        if (resolved.path.isUnderTaczPath()) validateTaczTextOperation(resolved.path)
        if (target.exists()) throw RequestError("文件已存在")
        target.parentFile?.let { parent ->
            if (parent.exists() && !parent.isDirectory) throw RequestError("目标目录异常")
            parent.mkdirs()
        }
        if (payload.directory) {
            target.mkdirs()
        } else {
            writeHostFileContent(resolved.path, target, payload.content, requireExisting = false)
        }
        lgr.info { "Host ${host._id} 用户${player._id}创建房间文件 ${resolved.path}" }
        return Host.FileUploadVo(
            path = resolved.path,
            size = if (target.isDirectory) 0 else target.length(),
            updateTime = target.lastModified()
        )
    }

    fun HostContext.updateHostFile(payload: Host.FileWriteDto): Host.FileUploadVo {
        val resolved = host.resolveHostFile(payload.path)
        val target = resolved.file
        validateHostFilePath(resolved.path, directory = false)
        validateTaczTextOperation(resolved.path)
        if (!target.exists()) throw RequestError("文件不存在")
        if (target.isDirectory) throw RequestError("目标是目录")
        writeHostFileContent(resolved.path, target, payload.content, requireExisting = true)
        lgr.info { "Host ${host._id} 用户${player._id}更新房间文件 ${resolved.path}" }
        return Host.FileUploadVo(
            path = resolved.path,
            size = target.length(),
            updateTime = target.lastModified()
        )
    }

    private fun HostContext.writeHostFileContent(
        path: String,
        target: File,
        content: String,
        requireExisting: Boolean
    ) {
        if (requireExisting && !target.exists()) throw RequestError("文件不存在")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > HOST_FILE_MAX_BYTES) throw RequestError("文件过大，最大允许${HOST_FILE_MAX_BYTES.toHostUploadLimitText()}")
        val tempFile = host.createHostUploadTempFile()
        try {
            Files.write(
                tempFile.toPath(),
                bytes,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            host.checkHostFileQuota(tempFile, target, bytes.size.toLong())
            moveHostUploadFile(tempFile, target)
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    fun HostContext.renameHostFile(payload: Host.FileRenameDto): Host.FileUploadVo {
        val from = host.resolveHostFile(payload.from)
        val to = host.resolveHostFile(payload.to)
        val source = from.file
        val target = to.file
        if (!source.exists()) throw RequestError("文件不存在")
        if (target.exists()) throw RequestError("目标文件已存在")
        val isDirectory = source.isDirectory
        validateHostFilePath(from.path, directory = isDirectory)
        validateHostFilePath(to.path, directory = isDirectory)
        if (isDirectory) {
            if (to.path.startsWith("${from.path}/")) throw RequestError("不能把目录移动到自身内部")
            if (to.path.isAllowedOrdinaryFilePath()) throw RequestError("目录不能重命名为文件")
        }
        if (from.path.isUnderTaczPath() || to.path.isUnderTaczPath()) {
            if (isDirectory) throw RequestError("TaCZ枪包不允许目录操作")
            if (!from.path.isDirectTaczFilePath() || !to.path.isDirectTaczFilePath()) {
                throw RequestError("TaCZ枪包只能放在tacz根目录")
            }
        }
        target.parentFile?.let { parent ->
            if (parent.exists() && !parent.isDirectory) throw RequestError("目标目录异常")
            parent.mkdirs()
        }
        try {
            moveHostUploadFile(source, target)
        } catch (error: IOException) {
            lgr.warn { "Host ${host._id} 用户${player._id}重命名房间文件失败 ${from.path} -> ${to.path}: ${error.message}\n$error" }
            throw RequestError("重命名失败，请检查目标路径")
        } catch (error: SecurityException) {
            lgr.warn { "Host ${host._id} 用户${player._id}重命名房间文件失败 ${from.path} -> ${to.path}: ${error.message}\n$error" }
            throw RequestError("重命名失败，请检查目标路径")
        }
        lgr.info { "Host ${host._id} 用户${player._id}重命名房间文件 ${from.path} -> ${to.path}" }
        return Host.FileUploadVo(
            path = to.path,
            size = if (target.isDirectory) 0 else target.length(),
            updateTime = target.lastModified()
        )
    }

    fun HostContext.deleteHostFile(payload: Host.FileDeleteDto) {
        val resolved = host.resolveHostFile(payload.path)
        val target = resolved.file
        if (!target.exists()) throw RequestError("文件不存在")
        validateHostFilePath(resolved.path, directory = target.isDirectory)
        if (resolved.path.isUnderTaczPath() && target.isDirectory) throw RequestError("TaCZ枪包不允许目录操作")
        if (target.isDirectory && (target.list()?.isNotEmpty() == true)) throw RequestError("目录不为空")

        Files.deleteIfExists(target.toPath())
        lgr.info { "Host ${host._id} 用户${player._id}删除房间文件 ${resolved.path}" }
    }
}
