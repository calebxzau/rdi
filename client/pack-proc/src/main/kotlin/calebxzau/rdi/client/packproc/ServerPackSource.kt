package calebxzau.rdi.client.packproc

import calebxzhou.rdi.common.archive.ArchiveReadLimits
import calebxzhou.rdi.common.archive.MODPACK_ARCHIVE_READ_LIMITS
import calebxzhou.rdi.common.archive.forEachArchiveEntryStreaming
import calebxzhou.rdi.common.archive.normalizeSafeArchivePath
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.LoadProgress
import calebxzhou.rdi.common.model.LoadProgressConsumer
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Locale

/** Extracted files belong to the upload work directory and must survive until upload finishes. */
internal suspend fun prepareServerPackDirectory(
    source: File,
    workDir: File,
    onProgress: LoadProgressConsumer,
    limits: ArchiveReadLimits = MODPACK_ARCHIVE_READ_LIMITS,
): File {
    // Retain directory support for existing processor callers.
    if (source.isDirectory) return source
    if (!source.isFile || !source.extension.equals("zip", ignoreCase = true)) {
        throw ModpackError("请选择服务端ZIP文件")
    }
    val context = currentCoroutineContext()
    context.ensureActive()
    Files.createDirectories(workDir.toPath())
    val staging = Files.createTempDirectory(workDir.toPath(), "server-pack-")
    try {
        onProgress(LoadProgress.Phase("正在解压服务端ZIP"))
        val files = hashSetOf<String>()
        val directories = hashSetOf<String>()
        forEachArchiveEntryStreaming(source, limits, beforeChunk = { context.ensureActive() }) { entry, input ->
            val path = normalizeSafeArchivePath(entry.path)
            require(path.isNotEmpty() || entry.isDirectory) { "服务端ZIP包含空文件路径" }
            if (path.isNotEmpty()) {
                val key = path.lowercase(Locale.ROOT)
                require(key !in files) { "服务端ZIP包含重复文件路径: ${entry.path}" }
                if (entry.isDirectory) {
                    directories += key
                } else {
                    require(key !in directories) { "服务端ZIP文件与目录路径冲突: ${entry.path}" }
                    files += key
                }
                var parent = key.substringBeforeLast('/', "")
                while (parent.isNotEmpty()) {
                    require(parent !in files) { "服务端ZIP文件与目录路径冲突: ${entry.path}" }
                    directories += parent
                    parent = parent.substringBeforeLast('/', "")
                }
                val target = staging.resolve(path).normalize()
                require(target.startsWith(staging)) { "非法服务端ZIP文件路径: ${entry.path}" }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                        input.copyTo(it)
                    }
                }
            }
        }
        val root = staging.toFile()
        val children = root.listFiles() ?: throw ModpackError("无法读取解压后的服务端目录")
        val candidates = (listOf(root) + children.filter(File::isDirectory))
            .filter { it.resolve("mods").isDirectory }
        if (candidates.isEmpty()) {
            throw ModpackError("服务端ZIP根目录或其下一层文件夹中应有mods文件夹")
        }
        if (candidates.size != 1) {
            throw ModpackError("服务端ZIP中找到多个服务端目录，请只保留一个")
        }
        return candidates.single()
    } catch (error: Throwable) {
        try {
            staging.toFile().deleteRecursivelyNoSymlink()
        } catch (cleanupError: Throwable) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }
}
