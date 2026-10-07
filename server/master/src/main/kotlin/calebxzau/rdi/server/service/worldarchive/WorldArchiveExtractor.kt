package calebxzau.rdi.server.service.worldarchive

import calebxzhou.rdi.common.archive.StreamingTarEntry
import calebxzhou.rdi.common.archive.forEachTarZstEntryStreaming
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.util.humanFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Validates and extracts uploaded world archives (`tar.zst`), shared by map templates and save
 * imports. Paths are normalized and must stay inside the target; links, special files, duplicate or
 * conflicting paths, too many entries and an extracted size over [maxExtractedSize] are rejected, and
 * a non-empty `level.dat` is required. Failures are [RequestError]s with player-facing messages.
 */
class WorldArchiveExtractor(private val maxExtractedSize: Long) {
    init {
        require(maxExtractedSize >= 0)
    }

    /** Validates and securely extracts a complete world archive into a fresh directory. */
    suspend fun extractToDir(
        archive: File,
        targetDir: File,
        ensureTaskActive: () -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        if (targetDir.exists()) {
            requestCheck(Files.isDirectory(targetDir.toPath(), LinkOption.NOFOLLOW_LINKS), "世界目录已存在")
            requestCheck(targetDir.listFiles()?.isEmpty() == true, "世界目录已存在")
        } else {
            check(targetDir.mkdirs()) { "无法创建世界目录" }
        }
        try {
            scan(archive, ensureTaskActive) { path, entry, input, context ->
                val target = targetDir.toPath().resolve(path).normalize()
                requestCheck(target.startsWith(targetDir.toPath()), "非法文件路径: $path")
                ensureSafeExtractionPath(targetDir, target)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(
                        target,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    ).use { output -> copyEntry(input, output, entry.size, context, ensureTaskActive) }
                }
            }
        } catch (error: Throwable) {
            targetDir.deleteRecursively()
            throw error
        }
    }

    /** Validates every entry of [archive] and passes each one to [onFile]. Returns the extracted size. */
    suspend fun scan(
        archive: File,
        ensureTaskActive: () -> Unit,
        onFile: (path: String, entry: StreamingTarEntry, input: InputStream, context: CoroutineContext) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val context = coroutineContext
        val files = HashSet<String>()
        val directories = HashSet<String>()
        val explicitDirectories = HashSet<String>()
        var extractedSize = 0L
        var levelDatFound = false
        var entryCount = 0
        forEachTarZstEntryStreaming(archive) { entry, input ->
            ensureTaskActive()
            entryCount++
            requestCheck(entryCount <= MAX_ENTRIES, "世界文件数量过多")
            val path = normalizeEntryPath(entry.path)
            if (path.isEmpty()) {
                requestCheck(entry.isDirectory && entry.size == 0L, "压缩包根目录不正确")
                return@forEachTarZstEntryStreaming
            }
            requestCheck(!entry.isSymbolicLink && !entry.isHardLink && !entry.isSpecial && !entry.isSparse, "世界压缩包包含不支持的文件类型")
            if (entry.isDirectory) {
                requestCheck(entry.size == 0L, "目录条目大小不正确")
                val key = path.lowercase()
                requestCheck(key !in files && explicitDirectories.add(key), "世界压缩包包含重复路径")
                ensureParentsAreDirectories(path, files)
                directories.add(key)
                addParentDirectories(path, directories)
                onFile(path, entry, input, context)
                return@forEachTarZstEntryStreaming
            }
            requestCheck(entry.size >= 0L && entry.size <= maxExtractedSize, "解压后的世界超过${maxExtractedSize.humanFileSize}限制")
            val key = path.lowercase()
            requestCheck(files.add(key) && key !in directories, "世界压缩包包含重复路径")
            ensureParentsAreDirectories(path, files)
            addParentDirectories(path, directories)
            extractedSize = Math.addExact(extractedSize, entry.size)
            requestCheck(extractedSize <= maxExtractedSize, "解压后的世界超过${maxExtractedSize.humanFileSize}限制")
            onFile(path, entry, input, context)
            if (path == "level.dat") levelDatFound = entry.size > 0
        }
        requestCheck(levelDatFound, "世界压缩包缺少level.dat")
        extractedSize
    }

    private fun ensureSafeExtractionPath(root: File, target: Path) {
        var current = root.toPath()
        val relative = root.toPath().relativize(target)
        for (part in relative) {
            current = current.resolve(part)
            requestCheck(!Files.isSymbolicLink(current), "世界压缩包目标路径包含符号链接")
        }
    }

    private fun normalizeEntryPath(raw: String): String {
        requestCheck('\\' !in raw, "压缩包包含非法文件路径")
        val path = raw.replace('\\', '/').trimEnd('/')
        if (path.isEmpty() || path == ".") return ""
        requestCheck(!path.startsWith('/') && !path.startsWith("//"), "压缩包包含非法文件路径")
        requestCheck(!Regex("^[A-Za-z]:").containsMatchIn(path) && ':' !in path, "压缩包包含非法文件路径")
        val segments = path.split('/')
        requestCheck(segments.none { it.isEmpty() || it == "." || it == ".." }, "压缩包包含非法文件路径")
        return segments.joinToString("/")
    }

    private fun ensureParentsAreDirectories(path: String, files: Set<String>) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            requestCheck(parent.lowercase() !in files, "世界压缩包包含文件目录冲突")
            parent = parent.substringBeforeLast('/', "")
        }
    }

    private fun addParentDirectories(path: String, directories: MutableSet<String>) {
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            directories += parent.lowercase()
            parent = parent.substringBeforeLast('/', "")
        }
    }

    private fun copyEntry(
        input: InputStream,
        output: OutputStream,
        expected: Long,
        context: CoroutineContext,
        ensureTaskActive: () -> Unit = {},
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (copied < expected) {
            ensureTaskActive()
            context.ensureActive()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - copied).toInt())
            requestCheck(read >= 0, "压缩包文件内容不完整")
            if (read == 0) continue
            copied += read
            output.write(buffer, 0, read)
        }
        requestCheck(input.read() == -1, "压缩包文件大小不正确")
    }

    companion object {
        private const val BUFFER_SIZE = 128 * 1024
        private const val MAX_ENTRIES = 100_000

        /** Reads and discards one entry, checking that it has exactly [expected] bytes. */
        fun consumeEntry(
            input: InputStream,
            expected: Long,
            context: CoroutineContext,
            ensureTaskActive: () -> Unit = {},
        ) {
            requestCheck(expected >= 0L, "压缩包文件大小不正确")
            val buffer = ByteArray(BUFFER_SIZE)
            var copied = 0L
            while (copied < expected) {
                ensureTaskActive()
                context.ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - copied).toInt())
                requestCheck(read >= 0, "压缩包文件内容不完整")
                if (read > 0) copied += read
            }
            requestCheck(input.read() == -1, "压缩包文件大小不正确")
        }

        private fun requestCheck(condition: Boolean, message: String) {
            if (!condition) throw RequestError(message)
        }
    }
}
