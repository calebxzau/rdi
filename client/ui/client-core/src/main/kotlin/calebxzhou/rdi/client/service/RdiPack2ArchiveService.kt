package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.service.content.ClientContentMigrator
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.ContentDigest
import calebxzhou.rdi.client.service.content.ContentDigestAlgorithm
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzhou.rdi.client.service.content.ContentSource
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.archive.forEachTarZstEntryStreaming
import calebxzhou.rdi.common.archive.readFirstTarZstEntry
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.model.Task2Progress
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.service.murmur2
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import org.bson.types.ObjectId

data class RdiPack2Identity(
    val modpackId: ObjectId,
    val versionName: String,
    val rootName: String,
)

private data class RdiPack2SourceEntry(
    val path: Path,
    val relative: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

data class PreparedRdiPack2Task(val task: Task2, val dedupeKey: String)

private const val MAX_RDI_PACK2_PAYLOAD_ENTRIES = 100_000
private const val MAX_RDI_PACK2_PAYLOAD_BYTES = 20L * 1024L * 1024L * 1024L

fun rdiPack2ExportTask(packdir: ModpackLocalDir, output: File): PreparedRdiPack2Task {
    val id = requireNotNull(packdir.vo).id
    val key = "rdipack2-export:${id.toHexString()}:${packdir.verName}:${output.absoluteFile.normalize()}"
    return PreparedRdiPack2Task(
        Task2.Leaf("导出整合包 ${packdir.verName}") { context ->
            exportRdiPack2(packdir, output, context)
        },
        key,
    )
}

suspend fun exportRdiPack2(packdir: ModpackLocalDir, output: File, context: Task2Context) =
    withContext(Dispatchers.IO) {
        val id = requireNotNull(packdir.vo).id
        require(packdir.dir.isDirectory) { "整合包目录不存在" }
        require(packdir.versionId == "${id}_${packdir.verName}") { "整合包目录身份无效" }

        val sourceRoot = packdir.dir.toPath().toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(sourceRoot)) { "整合包目录不能是符号链接" }
        val normalizedOutput = output.absoluteFile.toPath().normalize()
        require(!normalizedOutput.startsWith(sourceRoot)) { "导出文件不能位于整合包目录内" }
        context.emit(Task2Progress("正在扫描整合包文件…"))
        val sourceRootLastModified = Files.getLastModifiedTime(sourceRoot, LinkOption.NOFOLLOW_LINKS).toMillis()
        val entries = Files.walk(sourceRoot).use { stream ->
            stream.map { path ->
                context.ensureActive()
                val relative = sourceRoot.relativize(path)
                val relativeText = relative.toString().replace(File.separatorChar, '/')
                if (relativeText.isEmpty()) return@map null
                val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val isDirectory = attributes.isDirectory
                require(!attributes.isSymbolicLink) { "整合包包含不支持的符号链接：$relativeText" }
                require(attributes.isDirectory || attributes.isRegularFile) {
                    "整合包包含不支持的文件：$relativeText"
                }
                if (excludedRdiPack2Path(relativeText, isDirectory)) return@map null
                RdiPack2SourceEntry(
                    path = path,
                    relative = relativeText,
                    isDirectory = isDirectory,
                    size = if (isDirectory) 0L else attributes.size(),
                    lastModified = attributes.lastModifiedTime().toMillis(),
                )
            }.filter { it != null }.map { it!! }.toList().sortedBy { it.relative }
        }
        val regularFiles = entries.filterNot(RdiPack2SourceEntry::isDirectory)
        var totalBytes = 0L
        regularFiles.forEach { totalBytes = addRdiPack2PayloadBytes(totalBytes, it.size) }
        requireRdiPack2PayloadWithinLimits(entries.size, totalBytes)
        normalizedOutput.parent?.let(Files::createDirectories)
        val temporary = normalizedOutput.resolveSibling(".${normalizedOutput.fileName}.${UUID.randomUUID()}.tmp")
        try {
            context.emit(
                Task2Progress(
                    "正在生成整合包",
                    0f,
                    completedBytes = 0L,
                    totalBytes = totalBytes,
                    completedItems = 0,
                    totalItems = regularFiles.size,
                )
            )
            TarZstArchiveWriter(temporary.toFile()).use { archive ->
                archive.addDirectory(packdir.versionId, sourceRootLastModified)
                var writtenItems = 0
                var writtenBytes = 0L
                entries.forEach { entry ->
                    context.ensureActive()
                    val archivePath = "${packdir.versionId}/${entry.relative}"
                    if (entry.isDirectory) {
                        archive.addDirectory(archivePath, entry.lastModified)
                        return@forEach
                    }
                    context.emit(
                        Task2Progress(
                            "正在写入 ${entry.relative}",
                            rdiPack2ProgressFraction(writtenItems, regularFiles.size, 0f, 1f),
                            writtenBytes,
                            totalBytes,
                            completedItems = writtenItems,
                            totalItems = regularFiles.size,
                        )
                    )
                    openRdiPack2ExportChannel(entry.path).use { channel ->
                        Channels.newInputStream(channel).use { input ->
                            archive.addFileStreaming(
                                archivePath,
                                input,
                                entry.size,
                                entry.lastModified,
                                context::ensureActive,
                            )
                        }
                    }
                    writtenItems++
                    writtenBytes += entry.size
                    context.emit(
                        Task2Progress(
                            "正在写入 ${entry.relative}",
                            rdiPack2ProgressFraction(writtenItems, regularFiles.size, 0f, 1f),
                            writtenBytes,
                            totalBytes,
                            completedItems = writtenItems,
                            totalItems = regularFiles.size,
                        )
                    )
                }
            }
            context.ensureActive()
            try {
                Files.move(temporary, normalizedOutput, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, normalizedOutput, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (cause: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                cleanupRdiPack2Failure(cause, temporary)
            }
            throw cause
        }
        context.emit(Task2Progress("导出完成", 1f, totalBytes, totalBytes, completedItems = regularFiles.size, totalItems = regularFiles.size))
    }

fun prepareRdiPack2ImportTask(file: File): PreparedRdiPack2Task {
    val identity = readRdiPack2Identity(file)
    val key = "rdipack2-import:${identity.modpackId.toHexString()}:${identity.versionName}"
    return PreparedRdiPack2Task(
        Task2.Leaf("导入整合包 ${identity.versionName}") { context ->
            importRdiPack2(file, identity, context)
        },
        key,
    )
}

private suspend fun importRdiPack2(file: File, identity: RdiPack2Identity, context: Task2Context) = withContext(Dispatchers.IO) {
    require(file.isFile && file.name.endsWith(".rdipack2", true)) { "RDI整合包文件无效" }
    val id = identity.modpackId
    val versionName = identity.versionName
    val brief = server.makeRequest<Modpack.BriefVo>("modpack/$id/brief").data ?: error("未找到整合包信息")
    val version = server.makeRequest<Modpack.Version>("modpack/$id/version/$versionName").data ?: error("未找到整合包版本信息")
    require(version.modpackId == id && brief.id == id) { "整合包版本身份不匹配" }
    val target = ModpackService.getVersionDir(id, versionName).toPath().toAbsolutePath().normalize()
    require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "目标整合包已存在" }
    require(RunningGameGuard.aliveCount("${id}_${versionName}") == 0) { "整合包正在运行，不能导入" }

    val staging = Files.createTempDirectory(ClientDirs.versionsDir.toPath(), "rdipack2-stage-")
    val transaction = Files.createTempDirectory(ClientDirs.packProcDir.toPath(), "rdipack2-import-")
    var committed = false
    try {
        val collisions = RdiPack2CollisionTracker()
        val recognized = mutableListOf<Pair<Path, Long>>()
        var count = 0
        var payloadEntries = 0
        var payloadBytes = 0L
        var first = true
        forEachTarZstEntryStreaming(file) { entry, input ->
            context.ensureActive()
            val raw = entry.path.replace('\\', '/')
            require(!entry.isSymbolicLink && !entry.isHardLink && !entry.isSpecial && !entry.isSparse) { "归档包含不支持的特殊文件：$raw" }
            if (first) {
                first = false
                require(entry.isDirectory) { "整合包根目录必须是归档第一个条目" }
                val current = parseRdiPack2Root(raw)
                require(current == identity) { "整合包根目录在任务开始前发生变化" }
                Files.setLastModifiedTime(staging, java.nio.file.attribute.FileTime.fromMillis(entry.time))
                count++
                return@forEachTarZstEntryStreaming
            }
            if (isRdiPack2DuplicateRoot(raw, identity, entry.isDirectory)) {
                return@forEachTarZstEntryStreaming
            }
            require(raw.startsWith("${identity.rootName}/")) { "归档路径不在根目录内：$raw" }
            val relative = normalizeRdiPack2PayloadPath(raw.removePrefix("${identity.rootName}/"), entry.isDirectory)
            collisions.register(relative, entry.isDirectory)
            if (!entry.isDirectory) require(entry.size >= 0) { "归档文件大小无效：$relative" }
            requireRdiPack2PayloadAdditionWithinLimits(
                payloadEntries,
                payloadBytes,
                1,
                if (entry.isDirectory) 0L else entry.size,
            )
            val targetPath = staging.resolve(relative).normalize()
            require(targetPath.startsWith(staging)) { "归档路径越界：$relative" }
            if (entry.isDirectory) {
                Files.createDirectories(targetPath)
                Files.setLastModifiedTime(targetPath, java.nio.file.attribute.FileTime.fromMillis(entry.time))
            } else {
                require(Files.getFileStore(staging).usableSpace >= entry.size) {
                    "磁盘空间不足，无法导入文件：$relative"
                }
                targetPath.parent?.let(Files::createDirectories)
                Files.newOutputStream(targetPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    copyChecked(input, output, entry.size, context)
                }
                Files.setLastModifiedTime(targetPath, java.nio.file.attribute.FileTime.fromMillis(entry.time))
                val modRelative = relative.removePrefix("mods/")
                if (relative.startsWith("mods/") && !modRelative.contains('/') && modRelative.endsWith(".jar", true) && isRdiPack2DirectCacheCandidate(relative)) {
                    recognized += targetPath to entry.size
                }
            }
            payloadEntries++
            if (!entry.isDirectory) payloadBytes = addRdiPack2PayloadBytes(payloadBytes, entry.size)
            count++
            context.emit(Task2Progress("导入 ${relative.substringAfterLast('/')}", completedItems = count))
        }
        require(!first && count > 0) { "RDI整合包缺少根目录" }
        val modsDir = staging.resolve("mods").also(Files::createDirectories)
        if (recognized.isNotEmpty()) {
            val requests = buildList {
                recognized.forEach { (stagingSource, size) ->
                    val parsed = requireNotNull(ClientContentMigrator.parseFileName(stagingSource.fileName.toString()))
                    context.ensureActive()
                    val digest = try {
                        verifiedDigest(stagingSource, parsed.platform.name, parsed.embeddedHash).also {
                            context.ensureActive()
                        }
                    } catch (_: RdiPack2DigestMismatchException) {
                        context.ensureActive()
                        return@forEach
                    }
                    val source = transaction.resolve(stagingSource.fileName.toString())
                    Files.move(stagingSource, source, StandardCopyOption.REPLACE_EXISTING)
                    add(
                        ContentRequest(
                            id = "rdipack2:${source.fileName}", relativePath = source.fileName.toString(), size = size,
                            digests = digest.first, sources = listOf(ContentSource(
                                knownSize = size,
                                name = source.fileName.toString(),
                                localOnly = true,
                                downloader = { destination, _ ->
                                    runCatching { Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING) }
                                },
                            )), allowNetwork = false, displayName = source.fileName.toString(),
                        )
                    )
                }
            }
            if (requests.isNotEmpty()) {
                ClientContentStores.shared.materialize(requests, modsDir, context::emit).getOrThrow()
            }
        }
        require(Files.isDirectory(modsDir)) { "导入整合包缺少mods目录" }
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "目标整合包在导入期间已存在" }
        context.ensureActive()
        try { Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE) } catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging, target) }
        committed = true
        context.emit(Task2Progress("导入完成", 1f, completedItems = count, totalItems = count))
    } finally {
        if (!committed) staging.toFile().deleteRecursivelyNoSymlink()
        transaction.toFile().deleteRecursivelyNoSymlink()
    }
}

internal class RdiPack2DigestMismatchException(message: String) : IllegalArgumentException(message)

internal fun verifiedDigest(source: Path, platform: String, embedded: String?): Pair<List<ContentDigest>, String> {
    val value = embedded?.trim() ?: ""
    return when (platform) {
        "CURSEFORGE" -> {
            val expected = value.toULongOrNull()?.takeIf { it <= UInt.MAX_VALUE.toULong() }?.toString() ?: error("CurseForge文件名摘要无效")
            val actual = source.murmur2.toULong().toString()
            if (actual != expected) throw RdiPack2DigestMismatchException("CurseForge文件摘要不匹配")
            listOf(ContentDigest(ContentDigestAlgorithm.MURMUR2, expected)) to expected
        }
        "MODRINTH" -> {
            require(value.matches(Regex("[0-9a-fA-F]{40}"))) { "Modrinth文件名摘要无效" }
            val actual = sha1(source)
            if (!actual.equals(value, true)) throw RdiPack2DigestMismatchException("Modrinth文件摘要不匹配")
            listOf(ContentDigest(ContentDigestAlgorithm.SHA1, value.lowercase())) to value
        }
        "GITHUB" -> when (value.length) {
            40 -> {
                val actual = sha1(source)
                if (!actual.equals(value, true)) throw RdiPack2DigestMismatchException("GitHub文件SHA-1摘要不匹配")
                listOf(ContentDigest(ContentDigestAlgorithm.SHA1, value.lowercase())) to value
            }
            64 -> {
                val sha256 = digest(source, "SHA-256")
                if (!sha256.equals(value, true)) throw RdiPack2DigestMismatchException("GitHub文件SHA-256摘要不匹配")
                val sha1 = sha1(source)
                listOf(ContentDigest(ContentDigestAlgorithm.SHA256, value.lowercase()), ContentDigest(ContentDigestAlgorithm.SHA1, sha1)) to value
            }
            else -> error("GitHub文件名摘要无效")
        }
        "RDI_CORE" -> { val actual = sha1(source); listOf(ContentDigest(ContentDigestAlgorithm.SHA1, actual)) to actual }
        else -> error("未知内容平台")
    }
}

private fun copyChecked(input: InputStream, output: java.io.OutputStream, expected: Long, context: Task2Context) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (copied < expected) {
        context.ensureActive()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - copied).toInt())
        require(read >= 0) { "归档文件内容不完整" }
        output.write(buffer, 0, read)
        copied += read
    }
}

fun readRdiPack2Identity(file: File): RdiPack2Identity {
    var result: RdiPack2Identity? = null
    readFirstTarZstEntry(file) { entry, input ->
        require(entry.isDirectory && !entry.isSymbolicLink && !entry.isHardLink && !entry.isSpecial && !entry.isSparse) {
            "整合包根目录必须是普通目录"
        }
        result = parseRdiPack2Root(entry.path.replace('\\', '/'))
    }
    return requireNotNull(result) { "RDI整合包缺少根目录" }
}

internal fun parseRdiPack2Root(raw: String): RdiPack2Identity {
    require(raw.endsWith('/')) { "RDI整合包根目录路径无效：$raw" }
    val rootName = raw.removeSuffix("/")
    val match = Regex("^([0-9a-fA-F]{24})_(.+)$").matchEntire(rootName)
        ?: throw IllegalArgumentException("RDI整合包根目录路径无效：$raw")
    val versionName = match.groupValues[2]
    require(versionName.isNotBlank() && !versionName.any(Char::isISOControl)) { "整合包版本名无效" }
    require(!versionName.contains('/') && !versionName.contains('\\') && !versionName.contains(':') && versionName != "." && versionName != "..") {
        "整合包版本名无效"
    }
    require(!versionName.startsWith("/") && !versionName.startsWith("\\")) { "整合包版本名无效" }
    return RdiPack2Identity(ObjectId(match.groupValues[1]), versionName, rootName)
}

internal fun normalizeRdiPack2PayloadPath(raw: String, isDirectory: Boolean): String {
    val value = raw.replace('\\', '/').let { if (isDirectory) it.removeSuffix("/") else it }
    require(value.isNotBlank() && !value.startsWith("/") && !value.contains(":") && !value.any(Char::isISOControl)) {
        "归档路径无效：$raw"
    }
    val segments = value.split('/')
    require(segments.none { it.isBlank() || it == "." || it == ".." }) { "归档路径越界：$raw" }
    return value
}

internal fun isRdiPack2DuplicateRoot(raw: String, identity: RdiPack2Identity, isDirectory: Boolean): Boolean =
    isDirectory && raw.replace('\\', '/') == "${identity.rootName}/"

internal fun rdiPack2CollisionKey(path: String): String = path.lowercase(Locale.ROOT)

internal class RdiPack2CollisionTracker {
    private val spellings = HashMap<String, String>()
    private val entries = HashMap<String, Boolean>()

    fun register(path: String, isDirectory: Boolean): String {
        val components = path.split('/')
        var actualPrefix = ""
        var collisionPrefix = ""
        components.forEach { component ->
            actualPrefix = if (actualPrefix.isEmpty()) component else "$actualPrefix/$component"
            collisionPrefix = rdiPack2CollisionKey(actualPrefix)
            val previousSpelling = spellings[collisionPrefix]
            require(previousSpelling == null || previousSpelling == actualPrefix) {
                "归档路径大小写冲突：$path"
            }
            spellings[collisionPrefix] = actualPrefix
        }
        require(entries[collisionPrefix] == null) { "归档包含重复路径：$path" }
        require(entries.keys.none { collisionPrefix.startsWith("$it/") && entries[it] == false }) {
            "归档包含文件/目录冲突：$path"
        }
        if (!isDirectory) {
            require(entries.keys.none { it.startsWith("$collisionPrefix/") }) {
                "归档包含文件/目录冲突：$path"
            }
        }
        entries[collisionPrefix] = isDirectory
        return collisionPrefix
    }
}

private fun cleanupRdiPack2Failure(cause: Throwable, temporary: Path) {
    val temporaryFailure = runCatching {
        Files.deleteIfExists(temporary)
        check(!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) { "无法清理临时导出文件：$temporary" }
    }.exceptionOrNull()
    temporaryFailure?.let(cause::addSuppressed)
}

internal fun requireRdiPack2PayloadWithinLimits(entryCount: Int, payloadBytes: Long) {
    require(entryCount in 0..MAX_RDI_PACK2_PAYLOAD_ENTRIES) {
        "整合包条目数量超过限制：$MAX_RDI_PACK2_PAYLOAD_ENTRIES"
    }
    require(payloadBytes in 0..MAX_RDI_PACK2_PAYLOAD_BYTES) {
        "整合包文件总大小超过限制：$MAX_RDI_PACK2_PAYLOAD_BYTES"
    }
}

internal fun addRdiPack2PayloadBytes(currentBytes: Long, additionalBytes: Long): Long {
    require(currentBytes >= 0 && additionalBytes >= 0) { "整合包文件总大小统计无效" }
    require(currentBytes <= MAX_RDI_PACK2_PAYLOAD_BYTES - additionalBytes) {
        "整合包文件总大小超过限制：$MAX_RDI_PACK2_PAYLOAD_BYTES"
    }
    return currentBytes + additionalBytes
}

internal fun requireRdiPack2PayloadAdditionWithinLimits(
    currentEntries: Int,
    currentBytes: Long,
    additionalEntries: Int,
    additionalBytes: Long,
) {
    require(currentEntries >= 0 && currentBytes >= 0 && additionalEntries >= 0 && additionalBytes >= 0) {
        "整合包条目统计无效"
    }
    require(currentEntries <= MAX_RDI_PACK2_PAYLOAD_ENTRIES - additionalEntries) {
        "整合包条目数量超过限制：$MAX_RDI_PACK2_PAYLOAD_ENTRIES"
    }
    require(currentBytes <= MAX_RDI_PACK2_PAYLOAD_BYTES - additionalBytes) {
        "整合包文件总大小超过限制：$MAX_RDI_PACK2_PAYLOAD_BYTES"
    }
}

internal fun openRdiPack2ExportChannel(path: Path): SeekableByteChannel =
    Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))

internal fun rdiPack2ProgressFraction(completedItems: Int, totalItems: Int, start: Float, end: Float): Float {
    if (totalItems <= 0) return start
    return (start + (end - start) * (completedItems.toFloat() / totalItems)).coerceIn(start, end)
}

internal fun excludedRdiPack2Path(path: String, isDirectory: Boolean = false): Boolean {
    val top = path.substringBefore('/')
    if (top.equals("logs", true) || top.equals("crash-reports", true) || top.equals("saves", true) || top.equals("screenshots", true) || top.equals(".mixin.out", true)) {
        if (isDirectory || path.contains('/')) return true
    }
    if (isDirectory) return false
    return path.substringAfterLast('/').endsWith(".log", true) || path.substringAfterLast('/').endsWith(".log.gz", true)
}

internal fun isRdiPack2DirectCacheCandidate(path: String): Boolean {
    val normalized = path.replace('\\', '/')
    if (!normalized.startsWith("mods/") || normalized.removePrefix("mods/").contains('/')) return false
    val name = normalized.removePrefix("mods/")
    return name.endsWith(".jar", true) && ClientContentMigrator.parseFileName(name) != null
}

private fun sha1(path: Path): String = digest(path, "SHA-1")

private fun digest(path: Path, algorithm: String): String {
    val md = MessageDigest.getInstance(algorithm)
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) { val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
