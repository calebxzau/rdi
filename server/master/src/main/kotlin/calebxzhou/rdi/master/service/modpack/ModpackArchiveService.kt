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
import java.nio.file.StandardOpenOption
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
        forEachArchiveEntry(archiveFile) { entry ->
            val relativePath = extractServerInstallRelativePath(entry.path, archiveRoot) ?: return@forEachArchiveEntry
            if (skipRootWorld && shouldSkipRootWorld(relativePath)) {
                return@forEachArchiveEntry
            }
            if (!includeClientOnlyMarkedMods && isClientOnlyMarkedModPath(relativePath)) {
                return@forEachArchiveEntry
            }
            if (shouldSkipHostClientOnlyJar(relativePath)) {
                return@forEachArchiveEntry
            }
            if (skipHostAssetFiles && shouldSkipHostAssetFile(relativePath)) {
                return@forEachArchiveEntry
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
                    output.write(entry.bytes ?: byteArrayOf())
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
        if (clientArchive.exists()) clientArchive.delete()
        if (version.clientZip.exists()) version.clientZip.delete()

        var entriesCopied = 0
        val retainedShaderConfigs = version.clientExtras
            .filter { it.type == ContentType.ShaderPack }
            .map { "${it.targetRelativePath}.txt".lowercase() }
            .toSet()
        val overrideClientExtraPaths = mutableSetOf<String>()
        forEachArchiveEntry(sourceArchive) { entry ->
            val overridePath = extractOverridesRelativePath(entry.path)?.lowercase() ?: return@forEachArchiveEntry
            if (overridePath.startsWith("resourcepacks/") || overridePath.startsWith("shaderpacks/")) {
                overrideClientExtraPaths += overridePath
            }
        }

        TarZstArchiveWriter(clientArchive).use { output ->
            val addedDirs = mutableSetOf<String>()
            forEachArchiveEntry(sourceArchive) { entry ->
                val relative = extractClientPackRelativePath(entry.path) ?: return@forEachArchiveEntry
                val relativeLower = relative.lowercase()
                if ((relativeLower.startsWith("resourcepacks/") || relativeLower.startsWith("shaderpacks/")) &&
                    !entry.path.replace('\\', '/').trim('/').lowercase().startsWith("overrides/") &&
                    relativeLower in overrideClientExtraPaths
                ) return@forEachArchiveEntry
                if (disallowedClientPaths.any { relativeLower.startsWith(it) }) {
                    // The upload processor has already removed every shader
                    // archive and directory.  A surviving .zip.txt is the
                    // companion of a shader that was matched and retained;
                    // preserving it keeps shader options usable without
                    // reintroducing an unmatched shader binary.
                    if (!relativeLower.endsWith(".zip.txt") || relativeLower !in retainedShaderConfigs) {
                        return@forEachArchiveEntry
                    }
                }
                if (relativeLower.endsWith(".mca")) {
                    return@forEachArchiveEntry
                }
                if (entry.isDirectory) {
                    if (addDirectoryEntry(relative, output, addedDirs)) {
                        entriesCopied++
                    }
                    return@forEachArchiveEntry
                }
                ensureArchiveParents(relative, output, addedDirs)
                output.addFile(relative, entry.bytes ?: byteArrayOf(), entry.time)
                entriesCopied++
            }
        }

        if (entriesCopied == 0) {
            clientArchive.delete()
        }
    }

    internal fun unzipOverridesForTest(archiveFile: File, targetDir: File) =
        unzipOverrides(archiveFile, targetDir)

    internal fun buildClientPackForTest(version: Modpack.Version) = buildClientPack(version)

    internal fun upgradeFullPackArchive(version: Modpack.Version) {
        val sourceZip = version.zip
        if (!sourceZip.exists() || version.zstdPack.exists()) return
        version.zstdPack.parentFile?.mkdirs()
        val tempArchive = version.storageDir.resolve("${version.name}.tar.zst.tmp")
        if (tempArchive.exists()) tempArchive.delete()
        TarZstArchiveWriter(tempArchive).use { output ->
            val addedDirs = mutableSetOf<String>()
            forEachArchiveEntry(sourceZip) { entry ->
                if (entry.isDirectory) {
                    addDirectoryEntry(entry.path, output, addedDirs)
                } else {
                    ensureArchiveParents(entry.path, output, addedDirs)
                    output.addFile(entry.path, entry.bytes ?: byteArrayOf(), entry.time)
                }
            }
        }
        if (!tempArchive.exists() || tempArchive.length() <= 0L) {
            tempArchive.delete()
            throw RequestError("迁移整合包归档失败")
        }
        if (version.zstdPack.exists()) version.zstdPack.delete()
        tempArchive.renameTo(version.zstdPack)
        sourceZip.delete()
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
        return resolveServerInstallArchiveRoot(listArchiveEntries(archiveFile).map { it.path })
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
        extractOverridesRelativePath(entryName)?.let { return it }
        if (entryName.isBlank()) return null
        val normalized = entryName.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return null
        return normalized.takeIf {
            it.equals("gtnh", ignoreCase = true) || it.startsWith("gtnh/", ignoreCase = true) ||
                it.equals("resourcepacks", ignoreCase = true) || it.startsWith("resourcepacks/", ignoreCase = true) ||
                it.equals("shaderpacks", ignoreCase = true) || it.startsWith("shaderpacks/", ignoreCase = true)
        }
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
