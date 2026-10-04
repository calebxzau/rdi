package calebxzhou.rdi.master.service.modpack

import calebxzhou.rdi.common.archive.*
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.DL_MOD_DIR
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.common.service.ModService.modId
import calebxzhou.rdi.common.service.ModService.readNeoForgeConfig
import calebxzhou.rdi.master.service.*
import calebxzhou.rdi.master.service.clientPackFile
import calebxzhou.rdi.master.service.clientZip
import calebxzhou.rdi.master.service.clientZstdPack
import calebxzhou.rdi.master.service.fullPackFile
import calebxzhou.rdi.master.service.storageDir
import calebxzhou.rdi.master.service.zip
import calebxzhou.rdi.master.service.zstdPack
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.jar.JarFile

/** Archive format/path safety, extraction, client-pack generation and migration. */
object ModpackArchiveService {
    private val lgr by Loggers
    private val disallowedClientPaths = setOf("shaderpacks")
    private val hostSkippedAssetExtensions = setOf("ogg", "jpg", "png")

    private fun createOrReplaceSymlink(link: Path, target: Path) {
        runCatching {
            Files.deleteIfExists(link)
        }
        link.parent?.let { Files.createDirectories(it) }
        try {
            Files.createSymbolicLink(link, target)
        } catch (err: Exception) {
            throw RequestError("创建软链接失败: ${link.toAbsolutePath()}")
        }
    }

    internal fun unzipOverrides(
        archiveFile: File,
        targetDir: File,
        includeClientOnlyMarkedMods: Boolean = true,
        skipHostAssetFiles: Boolean = false,
        skipRootWorld: Boolean = false
    ) {
        val archiveRoot = resolveServerInstallArchiveRoot(archiveFile)
        val versionDirPath = targetDir.toPath()
        forEachArchiveEntryStreaming(archiveFile, MODPACK_ARCHIVE_READ_LIMITS) { entry, input ->
            val safePath = normalizeSafeArchivePath(entry.path)
            val relativePath = extractServerInstallRelativePath(safePath, archiveRoot) ?: return@forEachArchiveEntryStreaming
            if (skipRootWorld && shouldSkipRootWorld(relativePath)) {
                return@forEachArchiveEntryStreaming
            }
            if (!includeClientOnlyMarkedMods && isClientOnlyMarkedModPath(relativePath)) {
                return@forEachArchiveEntryStreaming
            }
            if (shouldSkipHostClientOnlyJar(relativePath)) {
                return@forEachArchiveEntryStreaming
            }
            if (skipHostAssetFiles && shouldSkipHostAssetFile(relativePath)) {
                return@forEachArchiveEntryStreaming
            }
            val resolvedPath = versionDirPath.resolve(relativePath).normalize()
            if (!resolvedPath.startsWith(versionDirPath)) {
                throw RequestError("非法文件路径: ${entry.path}")
            }

            if (entry.isDirectory) {
                Files.createDirectories(resolvedPath)
            } else {
                resolvedPath.parent?.let { Files.createDirectories(it) }
                Files.newOutputStream(
                    resolvedPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                ).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun shouldSkipHostAssetFile(relativePath: String): Boolean {
        val extension = relativePath.substringAfterLast('.', "").lowercase()
        return extension in hostSkippedAssetExtensions
    }

    internal fun shouldSkipRootWorld(relativePath: String): Boolean =
        relativePath == "world" || relativePath.startsWith("world/")

    internal fun cleanupDisabledInstalledMods(host: Host, version: Modpack.Version, modsDir: File) {
        if (!modsDir.exists() || !modsDir.isDirectory || host.disabledMods.isEmpty()) return
        val disabledBaseMods = version.mods
            .filter {
                it.side != Mod.Side.CLIENT &&
                    it.side != Mod.Side.UNKNOWN &&
                    !it.fileName.startsWith(CLIENT_ONLY_MARK_PREFIX)
            }
            .filter { versionMod -> host.disabledMods.any { sameMod(it, versionMod) } }
        if (disabledBaseMods.isEmpty()) return

        val disabledFileNames = disabledBaseMods.map { it.fileName.lowercase() }.toSet()
        val disabledSlugs = disabledBaseMods.map { it.slug.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val disabledHashes = disabledBaseMods.map { it.hash.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
        val disabledModIds = disabledBaseMods.mapNotNull { disabledMod ->
            DL_MOD_DIR.resolve(disabledMod.fileName)
                .takeIf(File::exists)
                ?.let(::readPrimaryJarModId)
        }.toSet()

        modsDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("jar", true) }
            ?.forEach { file ->
                val lowerName = file.name.lowercase()
                val directNameMatch = lowerName in disabledFileNames ||
                    disabledSlugs.any(lowerName::contains) ||
                    disabledHashes.any(lowerName::contains)
                val modIdMatch = readPrimaryJarModId(file)?.let { it in disabledModIds } ?: false
                if (!directNameMatch && !modIdMatch) return@forEach
                runCatching { file.delete() }
                    .onSuccess { deleted ->
                        if (deleted) {
                            lgr.info { "Host ${host._id} 删除已禁用整合包Mod文件: ${file.name}" }
                        }
                    }
                    .onFailure { err ->
                        lgr.warn { "Host ${host._id} 删除已禁用整合包Mod文件失败 ${file.name}: ${err.message}" }
                    }
            }
    }

    private fun readPrimaryJarModId(file: File): String? {
        return runCatching {
            JarFile(file).use { jar ->
                jar.readNeoForgeConfig()
                    ?.modId
                    ?.trim()
                    ?.lowercase()
                    ?.ifBlank { null }
            }
        }.getOrNull()
    }

    internal fun buildClientPack(version: Modpack.Version) {
        val sourceArchive = version.fullPackFile
        if (!sourceArchive.exists()) return
        val clientArchive = version.clientZstdPack
        clientArchive.parentFile?.mkdirs()
        var entriesCopied = 0
        val retainedShaderConfigs = version.clientExtras
            .filter { it.type == ContentType.ShaderPack }
            .map { "${it.targetRelativePath}.txt".lowercase() }
            .toSet()
        data class SelectedEntry(val archivePath: String, val priority: Int, val isDirectory: Boolean)
        val selectedEntries = mutableMapOf<String, SelectedEntry>()
        forEachArchiveEntryStreaming(sourceArchive, MODPACK_ARCHIVE_READ_LIMITS) { entry, _ ->
            val path = resolveClientPackEntryPath(entry.path) ?: return@forEachArchiveEntryStreaming
            val relativeLower = path.relativePath.lowercase(Locale.ROOT)
            val previous = selectedEntries[relativeLower]
            if (previous == null || path.priority > previous.priority) {
                selectedEntries[relativeLower] = SelectedEntry(entry.path, path.priority, entry.isDirectory)
            } else if (path.priority == previous.priority) {
                if (!entry.isDirectory || !previous.isDirectory) {
                    throw RequestError("客户端整合包包含重复文件路径: ${path.relativePath}")
                }
            }
        }
        selectedEntries.forEach { (path, entry) ->
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                val parentEntry = selectedEntries[parent]
                if (parentEntry != null && !parentEntry.isDirectory) {
                    throw RequestError("客户端整合包文件与目录路径冲突: $path")
                }
                parent = parent.substringBeforeLast('/', "")
            }
        }

        val tempClientArchive = File.createTempFile(".${clientArchive.name}.", ".tmp", clientArchive.parentFile)
        try {
            TarZstArchiveWriter(tempClientArchive).use { output ->
                val addedDirs = mutableSetOf<String>()
                forEachArchiveEntryStreaming(sourceArchive, MODPACK_ARCHIVE_READ_LIMITS) { entry, input ->
                    val clientPath = resolveClientPackEntryPath(entry.path) ?: return@forEachArchiveEntryStreaming
                    val relative = clientPath.relativePath
                    val relativeLower = relative.lowercase(Locale.ROOT)
                    val selected = selectedEntries[relativeLower]
                    if (selected == null || selected.archivePath != entry.path || selected.priority != clientPath.priority) {
                        return@forEachArchiveEntryStreaming
                    }
                    if (disallowedClientPaths.any { relativeLower.startsWith(it) }) {
                        // The upload processor has already removed every shader
                        // archive and directory.  A surviving .zip.txt is the
                        // companion of a shader that was matched and retained;
                        // preserving it keeps shader options usable without
                        // reintroducing an unmatched shader binary.
                        if (!relativeLower.endsWith(".zip.txt") || relativeLower !in retainedShaderConfigs) {
                            return@forEachArchiveEntryStreaming
                        }
                    }
                    if (relativeLower.endsWith(".mca")) {
                        return@forEachArchiveEntryStreaming
                    }
                    if (entry.isDirectory) {
                        if (addDirectoryEntry(relative, output, addedDirs)) {
                            entriesCopied++
                        }
                        return@forEachArchiveEntryStreaming
                    }
                    ensureArchiveParents(relative, output, addedDirs)
                    output.addFileStreaming(relative, input, entry.size, entry.time)
                    entriesCopied++
                }
            }

            if (entriesCopied == 0) {
                Files.deleteIfExists(clientArchive.toPath())
            } else {
                try {
                    Files.move(tempClientArchive.toPath(), clientArchive.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(tempClientArchive.toPath(), clientArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            Files.deleteIfExists(version.clientZip.toPath())
        } finally {
            Files.deleteIfExists(tempClientArchive.toPath())
        }
    }

    internal fun unzipOverridesForTest(archiveFile: File, targetDir: File) =
        unzipOverrides(archiveFile, targetDir)

    internal fun buildClientPackForTest(version: Modpack.Version) = buildClientPack(version)

    internal fun upgradeFullPackArchive(version: Modpack.Version) {
        val sourceZip = version.zip
        if (!sourceZip.exists() || version.zstdPack.exists()) return
        version.zstdPack.parentFile?.mkdirs()
        val tempArchive = Files.createTempFile(version.storageDir.toPath(), ".${version.name}.migration-", ".tar.zst").toFile()
        try {
            TarZstArchiveWriter(tempArchive).use { output ->
                val addedDirs = mutableSetOf<String>()
                forEachArchiveEntryStreaming(sourceZip, MODPACK_ARCHIVE_READ_LIMITS) { entry, input ->
                    val safePath = normalizeSafeArchivePath(entry.path)
                    if (entry.isDirectory) {
                        addDirectoryEntry(safePath, output, addedDirs)
                    } else {
                        ensureArchiveParents(safePath, output, addedDirs)
                        output.addFileStreaming(safePath, input, entry.size, entry.time)
                    }
                }
            }
            if (tempArchive.length() <= 0L) {
                throw RequestError("迁移整合包归档失败")
            }
            try {
                Files.move(tempArchive.toPath(), version.zstdPack.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tempArchive.toPath(), version.zstdPack.toPath())
            }
            sourceZip.delete()
        } finally {
            Files.deleteIfExists(tempArchive.toPath())
        }
    }

    internal fun upgradeFullPackArchiveForTest(version: Modpack.Version) = upgradeFullPackArchive(version)

    private fun addDirectoryEntry(
        rawPath: String,
        output: TarZstArchiveWriter,
        addedDirs: MutableSet<String>
    ): Boolean {
        val sanitized = rawPath.trim('/').ifEmpty { return false }
        ensureArchiveParents(sanitized, output, addedDirs)
        val dirEntry = "$sanitized/"
        if (addedDirs.add(dirEntry)) {
            output.addDirectory(sanitized)
            return true
        }
        return false
    }

    private fun ensureArchiveParents(path: String, output: TarZstArchiveWriter, addedDirs: MutableSet<String>) {
        val normalized = path.trim('/').ifEmpty { return }
        val parts = normalized.split('/')
        if (parts.size <= 1) return
        var current = ""
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            if (part.isEmpty()) continue
            current = if (current.isEmpty()) part else "$current/$part"
            val dirEntry = "$current/"
            if (addedDirs.add(dirEntry)) {
                output.addDirectory(current)
            }
        }
    }


    private fun resolveServerInstallArchiveRoot(archiveFile: File): BuildArchiveRoot {
        val entryPaths = mutableListOf<String>()
        forEachArchiveEntryStreaming(archiveFile, MODPACK_ARCHIVE_READ_LIMITS) { entry, _ ->
            entryPaths += normalizeSafeArchivePath(entry.path)
        }
        return resolveServerInstallArchiveRoot(entryPaths)
    }

    internal fun resolveServerInstallArchiveRootForBuild(entryPaths: List<String>): BuildArchiveRoot {
        return resolveServerInstallArchiveRoot(entryPaths)
    }

    internal fun extractServerInstallRelativePathForBuild(entryName: String, root: BuildArchiveRoot): String? =
        extractServerInstallRelativePath(entryName, root)

    private fun resolveServerInstallArchiveRoot(entryPaths: List<String>): BuildArchiveRoot {
        val hasServerDir = entryPaths.any { entryPath ->
            hasArchiveRootDir(entryPath, BuildArchiveRoot.SERVER.dirName)
        }
        return if (hasServerDir) {
            BuildArchiveRoot.SERVER
        } else {
            BuildArchiveRoot.OVERRIDES
        }
    }

    private fun extractServerInstallRelativePath(
        entryName: String,
        archiveRoot: BuildArchiveRoot
    ): String? = extractArchiveRootRelativePath(entryName, archiveRoot.dirName)

    internal fun extractServerInstallRelativePathForTest(entryName: String, rootDirName: String): String? =
        extractArchiveRootRelativePath(entryName, rootDirName)

    private fun hasArchiveRootDir(entryName: String, rootDirName: String): Boolean {
        val normalized = entryName.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return false
        val prefix = "$rootDirName/"
        return normalized.startsWith(prefix, ignoreCase = true)
    }

    private fun extractArchiveRootRelativePath(entryName: String, rootDirName: String): String? {
        if (entryName.isBlank()) return null
        val normalized = entryName.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return null
        val prefix = "$rootDirName/"
        if (!normalized.startsWith(prefix, ignoreCase = true)) return null
        return normalized.substring(prefix.length).takeIf { it.isNotBlank() }
    }

    fun extractOverridesRelativePath(entryName: String): String? {
        if (entryName.isBlank()) return null
        val normalized = entryName.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return null
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        val overridesIndex = segments.indexOf("overrides")
        if (overridesIndex == -1) return null
        val relativeSegments = segments.drop(overridesIndex + 1)
        if (relativeSegments.isEmpty()) return null
        return relativeSegments.joinToString("/")
    }

    private fun extractClientPackRelativePath(entryName: String): String? {
        return resolveClientPackEntryPath(entryName)?.relativePath
    }

    internal fun extractClientPackRelativePathForTest(entryName: String): String? =
        extractClientPackRelativePath(entryName)

    private fun isClientOnlyMarkedModPath(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        if (!normalized.startsWith("mods/")) return false
        val fileName = normalized.substringAfterLast('/')
        return fileName.startsWith(CLIENT_ONLY_MARK_PREFIX) && fileName.endsWith(".jar", ignoreCase = true)
    }

    internal fun isClientOnlyMarkedModPathForTest(relativePath: String): Boolean =
        isClientOnlyMarkedModPath(relativePath)

    private fun shouldSkipHostClientOnlyJar(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/').trimStart('/')
        if (!normalized.startsWith("mods/")) return false
        val fileName = normalized.substringAfterLast('/').lowercase()
        return fileName.endsWith(".jar") && fileName.contains("rgp-client")
    }

    internal fun shouldSkipHostClientOnlyJarForTest(relativePath: String): Boolean =
        shouldSkipHostClientOnlyJar(relativePath)

    internal enum class BuildArchiveRoot(val dirName: String) {
        SERVER("server"),
        OVERRIDES("overrides")
    }


}
