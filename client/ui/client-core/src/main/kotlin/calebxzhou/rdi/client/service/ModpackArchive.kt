package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportsRuntime
import calebxzau.rdi.common.model.LoaderDeclaration
import calebxzau.rdi.common.model.LoaderRecognition
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzau.rdi.common.model.Modpack2ContentDto
import calebxzau.rdi.common.model.Modpack2ContentSourceDto
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.util.openChineseZip
import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.client.modcatalog.CatalogDigestAlgorithm
import calebxzau.rdi.client.modcatalog.CatalogFile
import calebxzau.rdi.client.modcatalog.CatalogFileHashes
import calebxzau.rdi.client.modcatalog.CatalogFileRef
import calebxzau.rdi.client.modcatalog.CatalogContentType
import calebxzau.rdi.client.modcatalog.CatalogProjectRef
import calebxzau.rdi.client.modcatalog.EnvironmentCompatibility
import calebxzau.rdi.client.modcatalog.EnvironmentRequirement
import calebxzau.rdi.client.modcatalog.effectiveEnvironment
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.ModPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class ModpackArchiveFormat { CURSEFORGE, MODRINTH }

private fun EnvironmentCompatibility?.toArchiveContentSide(): ContentSide = when {
    this == null -> ContentSide.Both
    client == EnvironmentRequirement.UNSUPPORTED && server != EnvironmentRequirement.UNSUPPORTED -> ContentSide.Server
    server == EnvironmentRequirement.UNSUPPORTED && client != EnvironmentRequirement.UNSUPPORTED -> ContentSide.Client
    else -> ContentSide.Both
}

enum class ModpackArchiveReadStage(val displayName: String) {
    SCANNING_ENTRIES("扫描压缩包条目"),
    PARSING_CONTENT("解析整合包内容"),
    READING_OVERRIDES("读取覆盖文件"),
    LOADING_MOD_METADATA("加载Mod资料"),
}

data class ModpackArchiveReadProgress(
    val stage: ModpackArchiveReadStage,
    val completed: Int? = null,
    val total: Int? = null,
) {
    val fraction: Float?
        get() = if (completed != null && total != null && total > 0) {
            (completed.toFloat() / total).coerceIn(0f, 1f)
        } else {
            null
        }
}

enum class ModpackArchiveContentType(val displayName: String) {
    MOD("模组"),
    RESOURCE_PACK("资源包"),
    SHADER_PACK("光影包"),
    DATA_PACK("数据包"),
    OTHER("其他文件"),
}

data class ModpackArchivePreview(
    val archive: Path,
    val archiveSize: Long,
    val archiveModifiedAt: Long,
    val format: ModpackArchiveFormat,
    val name: String,
    val summary: String?,
    val mcVersion: McVersion,
    val modLoader: ModLoader,
    val files: List<ModpackArchiveFile>,
    val embeddedMods: List<ModpackArchiveEmbeddedMod>,
    val overrides: List<ModpackArchiveOverride>,
    /** Original root manifest bytes, retained verbatim for server validation. */
    val manifestJson: String = "",
) {
    val requiredFiles get() = files.filter(ModpackArchiveFile::required)
    val optionalFiles get() = files.filterNot(ModpackArchiveFile::required)
}

data class ModpackArchiveMetadata(
    val archive: Path,
    val format: ModpackArchiveFormat,
    val name: String,
    val summary: String?,
    val mcVersion: McVersion,
    val modLoader: ModLoader,
    val declaredLoaderVersion: String? = null,
)

data class ModpackArchiveFile(
    val key: String,
    val targetPath: String,
    val displayName: String,
    val summary: String?,
    val iconUrls: List<String>,
    val size: Long,
    val sha1: String,
    val urls: List<String>,
    val headers: Map<String, String> = emptyMap(),
    val required: Boolean,
    val contentType: ModpackArchiveContentType,
    /** Authoritative catalog metadata for manifest entries; null only for legacy test fixtures. */
    val content: Modpack2ContentDto? = null,
    val manifestSource: Modpack2ContentSourceDto? = null,
)

data class ModpackArchiveEmbeddedMod(
    val key: String,
    val entryName: String,
    val targetPath: String,
    val displayName: String,
    val size: Long,
)

data class ModpackArchiveOverride(
    val entryName: String,
    val targetPath: String,
    val size: Long,
    val layer: Int,
)

data class CurseForgeArchiveRef(val projectId: Int, val fileId: Int)

data class ArchiveCatalogFile(
    val projectId: Int,
    val displayName: String,
    val fileName: String,
    val size: Long,
    val sha1: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val summary: String? = null,
    val iconUrls: List<String> = emptyList(),
    val contentType: CatalogContentType,
    val platform: ModPlatform = ModPlatform.CURSEFORGE,
    val fileId: String = "",
    val slug: String = "",
    val murmur2: Long? = null,
    val projectKey: String = projectId.toString(),
    val side: ContentSide = ContentSide.Both,
    val minecraftVersions: Set<String> = emptySet(),
    val loaders: Set<ModLoader> = emptySet(),
)

interface ModpackArchiveCatalog {
    suspend fun resolveCurseForgeFiles(
        refs: List<CurseForgeArchiveRef>,
    ): Result<Map<Int, ArchiveCatalogFile>>

    /** Metadata-only Modrinth version_files seam. Older callers may omit it. */
    suspend fun resolveModrinthFiles(
        refs: List<ModrinthArchiveRef>,
    ): Result<Map<String, ArchiveCatalogFile>> =
        Result.failure(UnsupportedOperationException("Modrinth metadata resolver unavailable"))
}

data class ModrinthArchiveRef(val path: String, val sha1: String)

class CatalogModpackArchiveCatalog(
    private val catalog: ModCatalog,
) : ModpackArchiveCatalog {
    override suspend fun resolveCurseForgeFiles(
        refs: List<CurseForgeArchiveRef>,
    ): Result<Map<Int, ArchiveCatalogFile>> = try {
        val fileRefs = refs.mapTo(linkedSetOf()) {
            CatalogFileRef(ModPlatform.CURSEFORGE, it.fileId.toString())
        }
        val files = catalog.getFiles(fileRefs).getOrThrow().value
        val projectRefs = files.values.mapTo(linkedSetOf()) { it.project }
        val projects = catalog.getMods(projectRefs).getOrThrow().value
        val result = linkedMapOf<Int, ArchiveCatalogFile>()
        refs.forEach { ref ->
            val fileRef = CatalogFileRef(ModPlatform.CURSEFORGE, ref.fileId.toString())
            val file = files[fileRef] ?: return@forEach
            require(file.project == CatalogProjectRef(ModPlatform.CURSEFORGE, ref.projectId.toString())) {
                "CurseForge项目与文件不匹配：${ref.projectId}/${ref.fileId}"
            }
            val sha1 = file.digests.firstOrNull { it.algorithm == CatalogDigestAlgorithm.SHA1 }?.value
                ?: error("整合包文件校验信息不完整：${ref.fileId}")
            val download = catalog.resolveDownload(file).getOrThrow()
            val project = projects[file.project]
            val projectSource = project?.sources?.firstOrNull { it.ref == file.project }
            result[ref.fileId] = ArchiveCatalogFile(
                projectId = ref.projectId,
                displayName = project?.nameCn ?: project?.name ?: file.displayName,
                fileName = download.fileName,
                size = file.fileSize,
                sha1 = sha1,
                url = download.url,
                headers = download.headers,
                summary = project?.summary,
                iconUrls = project?.iconUrls.orEmpty(),
                contentType = projectSource?.contentType ?: CatalogContentType.OTHER,
                platform = ModPlatform.CURSEFORGE,
                fileId = ref.fileId.toString(),
                slug = projectSource?.slug.orEmpty(),
                murmur2 = file.digests.firstOrNull {
                    it.algorithm == CatalogDigestAlgorithm.CURSEFORGE_MURMUR2
                }?.value?.toLongOrNull(),
                side = file.effectiveEnvironment(projectSource).toArchiveContentSide(),
                minecraftVersions = file.minecraftVersions,
                loaders = file.loaders,
            )
        }
        Result.success(result)
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

    override suspend fun resolveModrinthFiles(
        refs: List<ModrinthArchiveRef>,
    ): Result<Map<String, ArchiveCatalogFile>> = if (refs.isEmpty()) {
        Result.success(emptyMap())
    } else try {
        val hashes = refs.map { ref ->
            CatalogFileHashes(ref.path, ref.sha1, null)
        }
        val matched = catalog.matchFiles(hashes).getOrThrow()
        require(matched.keys.containsAll(refs.map(ModrinthArchiveRef::path))) {
            "无法解析Modrinth清单文件：${refs.filterNot { it.path in matched }.joinToString { it.path }}"
        }
        val files = matched.values.toSet()
        val projects = catalog.getMods(files.mapTo(linkedSetOf(), CatalogFile::project)).getOrThrow().value
        Result.success(refs.associate { ref ->
            val file = matched.getValue(ref.path)
            val sha1 = file.digests.firstOrNull { it.algorithm == CatalogDigestAlgorithm.SHA1 }
                ?.value?.trim()?.lowercase()
            require(sha1 == ref.sha1.trim().lowercase()) { "Modrinth文件SHA-1不匹配：${ref.path}" }
            require(file.fileSize > 0) { "Modrinth文件大小无效：${ref.path}" }
            val basename = ref.path.substringAfterLast('/')
            require(file.fileName == basename) { "Modrinth文件名不匹配：${ref.path}" }
            val project = projects[file.project]
            val source = project?.sources?.firstOrNull { it.ref == file.project }
            require(source != null) { "找不到Modrinth项目资料：${file.project.projectId}" }
            val download = catalog.resolveDownload(file).getOrThrow()
            ref.path to ArchiveCatalogFile(
                projectId = 0,
                displayName = source.name,
                fileName = file.fileName,
                size = file.fileSize,
                sha1 = sha1,
                url = download.url,
                headers = download.headers,
                summary = source.summary,
                iconUrls = listOfNotNull(source.iconUrl),
                contentType = source.contentType,
                platform = ModPlatform.MODRINTH,
                fileId = file.ref.fileId,
                slug = source.slug,
                projectKey = file.project.projectId,
                side = file.effectiveEnvironment(source).toArchiveContentSide(),
                minecraftVersions = file.minecraftVersions,
                loaders = file.loaders,
            )
        })
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        Result.failure(cause)
    }
}

private val lgr by Loggers

open class ModpackArchiveReader(
    private val catalog: ModpackArchiveCatalog,
) {
    open suspend fun readMetadata(archive: Path): Result<ModpackArchiveMetadata> = try {
        require(Files.isRegularFile(archive)) { "找不到整合包文件：$archive" }
        Result.success(
            withContext(Dispatchers.IO) {
                archive.toFile().openChineseZip().use { zip ->
                    val manifests = findManifests(readEntries(zip))
                    manifests.curse?.let { entry ->
                        val manifest = serdesJson.decodeFromString<CurseManifestMetadata>(readText(zip, entry))
                        val mcVersion = supportedVersion(manifest.minecraft.version)
                        val identity = LoaderRecognition.curseForge(
                            manifest.minecraft.modLoaders.map { LoaderDeclaration(it.id, it.primary) },
                        ).getOrThrow()
                        require(mcVersion.supportsRuntime(identity.loader)) {
                            "暂不支持Mod加载器${identity.loader.name}"
                        }
                        ModpackArchiveMetadata(
                            archive = archive,
                            format = ModpackArchiveFormat.CURSEFORGE,
                            name = normalizedName(manifest.name, archive),
                            summary = null,
                            mcVersion = mcVersion,
                            modLoader = identity.loader,
                            declaredLoaderVersion = identity.declaredVersion,
                        )
                    } ?: run {
                        val entry = requireNotNull(manifests.modrinth)
                        val index = serdesJson.decodeFromString<ModrinthMetadata>(readText(zip, entry))
                        require(index.formatVersion > 0) { "不支持的Modrinth整合包格式版本：${index.formatVersion}" }
                        require(index.game.equals("minecraft", ignoreCase = true)) {
                            "不支持的游戏类型：${index.game}"
                        }
                        val mcVersion = supportedVersion(index.dependencies["minecraft"].orEmpty())
                        val identity = LoaderRecognition.modrinth(index.dependencies).getOrThrow()
                        require(mcVersion.supportsRuntime(identity.loader)) {
                            "暂不支持Mod加载器${identity.loader.name}"
                        }
                        ModpackArchiveMetadata(
                            archive = archive,
                            format = ModpackArchiveFormat.MODRINTH,
                            name = normalizedName(index.name, archive),
                            summary = index.summary?.trim()?.ifEmpty { null },
                            mcVersion = mcVersion,
                            modLoader = identity.loader,
                            declaredLoaderVersion = identity.declaredVersion,
                        )
                    }
                }
            },
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

    open suspend fun inspect(archive: Path): Result<ModpackArchivePreview> = try {
        inspectWithProgress(archive) {}
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

    open suspend fun inspectWithProgress(
        archive: Path,
        onProgress: (ModpackArchiveReadProgress) -> Unit = {},
    ): Result<ModpackArchivePreview> = try {
        require(Files.isRegularFile(archive)) { "找不到整合包文件：$archive" }
        Result.success(
            withContext(Dispatchers.IO) {
                archive.toFile().openChineseZip().use { zip ->
                    val entries = readEntries(zip, onProgress)
                    val manifests = findManifests(entries)
                    manifests.curse?.let {
                        inspectCurseForge(zip, it, entries, archive, onProgress)
                    } ?: inspectModrinth(zip, requireNotNull(manifests.modrinth), entries, archive, onProgress)
                }
            }
        )
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        Result.failure(cause)
    }

    private suspend fun inspectCurseForge(
        zip: ZipFile,
        manifestEntry: ZipEntry,
        entries: List<ZipEntry>,
        archive: Path,
        onProgress: (ModpackArchiveReadProgress) -> Unit,
    ): ModpackArchivePreview {
        val manifestJson = readText(zip, manifestEntry)
        val manifest = serdesJson.decodeFromString<CurseManifest>(manifestJson)
        require(manifest.overrides == null || manifest.overrides == OVERRIDES) {
            "CurseForge整合包只支持根目录overrides"
        }
        val mcVersion = supportedVersion(manifest.minecraft.version)
        val identity = LoaderRecognition.curseForge(
            manifest.minecraft.modLoaders.map { LoaderDeclaration(it.id, it.primary) },
        ).getOrThrow()
        val modLoader = supportedRuntimeLoader(identity.loader, mcVersion)
        val refs = manifest.files.map { CurseForgeArchiveRef(it.projectId, it.fileId) }
        onProgress(ModpackArchiveReadProgress(ModpackArchiveReadStage.PARSING_CONTENT))
        val resolved = catalog.resolveCurseForgeFiles(refs).getOrThrow()
        onProgress(
            ModpackArchiveReadProgress(
                ModpackArchiveReadStage.PARSING_CONTENT,
                completed = 0,
                total = manifest.files.size,
            )
        )
        require(refs.all { it.fileId in resolved }) {
            "无法解析CurseForge文件：${refs.filterNot { it.fileId in resolved }.joinToString { it.fileId.toString() }}"
        }
        val files = manifest.files.mapIndexed { index, item ->
            val file = resolved.getValue(item.fileId)
            require(file.projectId == item.projectId) { "CurseForge项目与文件不匹配：${item.projectId}/${item.fileId}" }
            require(file.fileId.isBlank() || file.fileId == item.fileId.toString()) {
                "CurseForge文件身份不匹配：${item.projectId}/${item.fileId}"
            }
            val contentType = file.contentType.toArchiveContentType()
                ?: throw IllegalArgumentException("不支持的CurseForge项目类型：${item.projectId}")
            validateCatalogTarget(file, contentType, mcVersion, modLoader, "${item.projectId}/${item.fileId}")
            require(file.size > 0) { "整合包文件大小无效：${item.fileId}" }
            require(safeFileName(file.fileName) == file.fileName) { "整合包文件名无效：${file.fileName}" }
            if (file.fileId.isNotBlank()) {
                require(file.murmur2 != null) { "整合包文件缺少Murmur2：${file.fileId}" }
            }
            val url = validUrl(file.url).getOrThrow()
            ModpackArchiveFile(
                key = "cf:${item.projectId}:${item.fileId}",
                targetPath = safeTarget("${contentType.targetDirectory}/${safeFileName(file.fileName)}"),
                displayName = file.displayName,
                summary = file.summary,
                iconUrls = file.iconUrls,
                size = file.size,
                sha1 = validSha1(file.sha1),
                urls = listOf(url),
                headers = file.headers,
                required = item.required,
                contentType = contentType,
                content = file.toContentDto(
                    targetPath = safeTarget("${contentType.targetDirectory}/${safeFileName(file.fileName)}"),
                    archiveType = contentType,
                    required = item.required,
                ),
                manifestSource = Modpack2ContentSourceDto.CurseForgeManifestEntry(
                    projectId = item.projectId.toString(), fileId = item.fileId.toString(),
                ),
            )
                .also {
                    onProgress(
                        ModpackArchiveReadProgress(
                            ModpackArchiveReadStage.PARSING_CONTENT,
                            completed = index + 1,
                            total = manifest.files.size,
                        )
                    )
                }
        }
        validateTargetConflicts(files)
        val overrides = readOverrides(entries, includeClientOverrides = false, onProgress)
        return preview(
            archive = archive,
            format = ModpackArchiveFormat.CURSEFORGE,
            name = manifest.name,
            summary = null,
            mcVersion = mcVersion,
            modLoader = modLoader,
            files = files,
            overrides = overrides,
            manifestJson = manifestJson,
        )
    }

    private suspend fun inspectModrinth(
        zip: ZipFile,
        manifestEntry: ZipEntry,
        entries: List<ZipEntry>,
        archive: Path,
        onProgress: (ModpackArchiveReadProgress) -> Unit,
    ): ModpackArchivePreview {
        val manifestJson = readText(zip, manifestEntry)
        val index = serdesJson.decodeFromString<ModrinthIndex>(manifestJson)
        require(index.formatVersion > 0) { "不支持的Modrinth整合包格式版本：${index.formatVersion}" }
        require(index.game.equals("minecraft", ignoreCase = true)) { "不支持的游戏类型：${index.game}" }
        val mcVersion = supportedVersion(index.dependencies["minecraft"].orEmpty())
        val identity = LoaderRecognition.modrinth(index.dependencies).getOrThrow()
        val modLoader = supportedRuntimeLoader(identity.loader, mcVersion)
        onProgress(
            ModpackArchiveReadProgress(
                ModpackArchiveReadStage.PARSING_CONTENT,
                completed = 0,
                total = index.files.size,
            )
        )
        val supportedEntries = index.files.mapIndexedNotNull { indexNumber, entry ->
            if (entry.env?.client == Environment.UNSUPPORTED) null else indexNumber to entry
        }
        val resolved = runCatching {
            catalog.resolveModrinthFiles(supportedEntries.map { (_, entry) ->
                ModrinthArchiveRef(safeTarget(entry.path), validSha1(entry.hashes["sha1"].orEmpty()))
            }).getOrThrow()
        }.getOrElse { cause ->
            if (cause is UnsupportedOperationException) emptyMap() else throw cause
        }
        val files = index.files.mapIndexedNotNull { indexNumber, entry ->
            val file = when (entry.env?.client ?: Environment.REQUIRED) {
                Environment.UNSUPPORTED -> null
                Environment.REQUIRED, Environment.OPTIONAL -> {
                    val targetPath = safeTarget(entry.path)
                    require(entry.fileSize >= 0) { "Modrinth文件大小无效：${entry.path}" }
                    require(entry.downloads.isNotEmpty()) { "Modrinth文件缺少下载地址：${entry.path}" }
                    // Some exporters write raw spaces into CurseForge mirror URLs; one bad mirror must not reject the file.
                    val downloads = entry.downloads.mapNotNull { raw ->
                        validUrl(raw).getOrElse { cause ->
                            lgr.warn(cause) { "跳过无效下载地址：${entry.path} -> $raw" }
                            null
                        }
                    }
                    require(downloads.isNotEmpty()) { "Modrinth文件缺少有效下载地址：${entry.path}" }
                    val sha1 = validSha1(entry.hashes["sha1"].orEmpty())
                    val resolvedFile = resolved[targetPath]
                    val archiveType = contentTypeFromPath(targetPath)
                    if (resolved.isNotEmpty()) {
                        require(resolvedFile != null) { "无法解析Modrinth清单文件：$targetPath" }
                        require(resolvedFile.sha1.equals(sha1, true)) { "Modrinth文件SHA-1不匹配：$targetPath" }
                        require(resolvedFile.fileName == targetPath.substringAfterLast('/')) {
                            "Modrinth文件名不匹配：$targetPath"
                        }
                        require(resolvedFile.size == entry.fileSize) { "Modrinth文件大小不匹配：$targetPath" }
                        require(resolvedFile.projectKey.isNotBlank() && resolvedFile.fileId.isNotBlank()) {
                            "Modrinth文件缺少项目或版本身份：$targetPath"
                        }
                        validateCatalogTarget(resolvedFile, archiveType, mcVersion, modLoader, targetPath)
                    }
                    ModpackArchiveFile(
                        key = "mr:${targetPath.lowercase(Locale.ROOT)}:$sha1",
                        targetPath = targetPath,
                        displayName = resolvedFile?.displayName ?: entry.path.substringAfterLast('/'),
                        summary = resolvedFile?.summary,
                        iconUrls = resolvedFile?.iconUrls.orEmpty(),
                        size = resolvedFile?.size ?: entry.fileSize,
                        sha1 = sha1,
                        urls = (resolvedFile?.url?.let(::listOf) ?: downloads).distinct(),
                        headers = resolvedFile?.headers.orEmpty(),
                        required = entry.env?.client != Environment.OPTIONAL,
                        contentType = archiveType,
                        content = resolvedFile?.toContentDto(
                            targetPath = targetPath,
                            archiveType = archiveType,
                            required = entry.env?.client != Environment.OPTIONAL,
                        ),
                        manifestSource = Modpack2ContentSourceDto.ModrinthManifestEntry(targetPath, sha1),
                    )
                }
            }
            onProgress(
                ModpackArchiveReadProgress(
                    ModpackArchiveReadStage.PARSING_CONTENT,
                    completed = indexNumber + 1,
                    total = index.files.size,
                )
            )
            file
        }
        validateTargetConflicts(files)
        val overrides = readOverrides(entries, includeClientOverrides = true, onProgress)
        return preview(
            archive = archive,
            format = ModpackArchiveFormat.MODRINTH,
            name = index.name,
            summary = index.summary,
            mcVersion = mcVersion,
            modLoader = modLoader,
            files = files,
            overrides = overrides,
            manifestJson = manifestJson,
        )
    }

    private fun preview(
        archive: Path,
        format: ModpackArchiveFormat,
        name: String,
        summary: String?,
        mcVersion: McVersion,
        modLoader: ModLoader,
        files: List<ModpackArchiveFile>,
        overrides: List<ModpackArchiveOverride>,
        manifestJson: String,
    ): ModpackArchivePreview {
        val embeddedMods = overrides.filter {
            it.targetPath.startsWith("mods/", ignoreCase = true) && it.targetPath.endsWith(".jar", ignoreCase = true)
        }.map {
            ModpackArchiveEmbeddedMod(
                key = "zip:${it.entryName}",
                entryName = it.entryName,
                targetPath = it.targetPath,
                displayName = it.targetPath.substringAfterLast('/'),
                size = it.size,
            )
        }
        val fileTargets = files.mapTo(linkedSetOf()) { it.targetPath.lowercase(Locale.ROOT) }
        embeddedMods.forEach {
            require(it.targetPath.lowercase(Locale.ROOT) !in fileTargets) {
                "清单Mod与覆盖目录Mod路径冲突：${it.targetPath}"
            }
        }
        return ModpackArchivePreview(
            archive = archive,
            archiveSize = Files.size(archive),
            archiveModifiedAt = Files.getLastModifiedTime(archive).toMillis(),
            format = format,
            name = normalizedName(name, archive),
            summary = summary?.trim()?.ifEmpty { null },
            mcVersion = mcVersion,
            modLoader = modLoader,
            files = files,
            embeddedMods = embeddedMods,
            overrides = overrides,
            manifestJson = manifestJson,
        )
    }

    private fun readEntries(
        zip: ZipFile,
        onProgress: (ModpackArchiveReadProgress) -> Unit = {},
    ): List<ZipEntry> {
        val total = zip.size()
        onProgress(
            ModpackArchiveReadProgress(
                ModpackArchiveReadStage.SCANNING_ENTRIES,
                completed = 0,
                total = total,
            )
        )
        val entries = buildList {
            val iterator = zip.entries()
            var completed = 0
            while (iterator.hasMoreElements()) {
                add(iterator.nextElement())
                completed++
                onProgress(
                    ModpackArchiveReadProgress(
                        ModpackArchiveReadStage.SCANNING_ENTRIES,
                        completed = completed,
                        total = total,
                    )
                )
            }
        }
        require(entries.size <= MAX_ARCHIVE_ENTRIES) { "压缩包条目超过${MAX_ARCHIVE_ENTRIES}个" }
        val extractedSize = entries.asSequence().filterNot(ZipEntry::isDirectory).map(ZipEntry::getSize)
            .filter { it >= 0 }.sum()
        require(extractedSize <= MAX_EXTRACTED_BYTES) { "压缩包解压后超过20GB" }
        return entries
    }

    private fun findManifests(entries: List<ZipEntry>): ManifestEntries {
        val curse = entries.filter { !it.isDirectory && it.name == CURSE_MANIFEST }
        val modrinth = entries.filter { !it.isDirectory && it.name == MODRINTH_MANIFEST }
        require(curse.size + modrinth.size == 1) {
            if (curse.isEmpty() && modrinth.isEmpty()) {
                "压缩包根目录缺少整合包清单"
            } else {
                "压缩包根目录存在多个整合包清单"
            }
        }
        return ManifestEntries(curse.singleOrNull(), modrinth.singleOrNull())
    }

    private fun normalizedName(name: String, archive: Path): String =
        name.trim().ifEmpty { archive.fileName.toString().substringBeforeLast('.') }

    private fun readOverrides(
        entries: List<ZipEntry>,
        includeClientOverrides: Boolean,
        onProgress: (ModpackArchiveReadProgress) -> Unit = {},
    ): List<ModpackArchiveOverride> {
        val pathsByLayer = mutableMapOf<Int, MutableSet<String>>()
        val candidates = entries.mapNotNull { entry ->
            if (entry.isDirectory) return@mapNotNull null
            val normalized = entry.name.replace('\\', '/')
            val rootAndLayer = when {
                normalized.startsWith("$OVERRIDES/") -> OVERRIDES to 0
                includeClientOverrides && normalized.startsWith("$CLIENT_OVERRIDES/") -> CLIENT_OVERRIDES to 1
                else -> return@mapNotNull null
            }
            Triple(entry, rootAndLayer.first, rootAndLayer.second)
        }
        onProgress(
            ModpackArchiveReadProgress(
                ModpackArchiveReadStage.READING_OVERRIDES,
                completed = 0,
                total = candidates.size,
            )
        )
        return candidates.mapIndexed { index, (entry, root, layer) ->
            val normalized = entry.name.replace('\\', '/')
            val target = safeTarget(normalized.removePrefix("$root/"))
            require(pathsByLayer.getOrPut(layer, ::linkedSetOf).add(target.lowercase(Locale.ROOT))) {
                "覆盖目录存在重复路径：$target"
            }
            onProgress(
                ModpackArchiveReadProgress(
                    ModpackArchiveReadStage.READING_OVERRIDES,
                    completed = index + 1,
                    total = candidates.size,
                )
            )
            ModpackArchiveOverride(entry.name, target, entry.size, layer)
        }.sortedWith(compareBy(ModpackArchiveOverride::layer).thenBy(ModpackArchiveOverride::entryName))
    }

    private fun supportedVersion(value: String): McVersion = McVersion.from(value.trim())
        ?.takeIf(McVersion::enabled)
        ?: throw IllegalArgumentException("暂不支持MC版本${value.trim()}")

    private fun supportedRuntimeLoader(loader: ModLoader, mcVersion: McVersion): ModLoader {
        require(mcVersion.supportsRuntime(loader)) {
            "已识别为Minecraft${mcVersion.mcVer}／${loader.name}，当前版本暂不支持导入"
        }
        return loader
    }

    private fun validateTargetConflicts(files: List<ModpackArchiveFile>) {
        val paths = linkedSetOf<String>()
        files.forEach {
            require(paths.add(it.targetPath.lowercase(Locale.ROOT))) { "清单存在重复目标路径：${it.targetPath}" }
        }
    }

    private fun validateCatalogTarget(
        file: ArchiveCatalogFile,
        contentType: ModpackArchiveContentType,
        mcVersion: McVersion,
        modLoader: ModLoader,
        identity: String,
    ) {
        if (file.fileId.isBlank()) return
        if (contentType == ModpackArchiveContentType.MOD) {
            require(mcVersion.mcVer in file.minecraftVersions) {
                "${identity}不支持整合包Minecraft版本${mcVersion.mcVer}"
            }
            require(modLoader in file.loaders) {
                "${identity}不支持整合包Mod加载器${modLoader.name}"
            }
        } else if (file.minecraftVersions.isNotEmpty()) {
            require(mcVersion.mcVer in file.minecraftVersions) {
                "${identity}不支持整合包Minecraft版本${mcVersion.mcVer}"
            }
        }
        if (file.loaders.isNotEmpty()) {
            require(modLoader in file.loaders) {
                "${identity}不支持整合包Mod加载器${modLoader.name}"
            }
        }
    }

    private fun contentTypeFromPath(path: String): ModpackArchiveContentType {
        val parts = path.lowercase(Locale.ROOT).split('/')
        return when {
            parts.first() == "mods" -> ModpackArchiveContentType.MOD
            parts.first() == "resourcepacks" -> ModpackArchiveContentType.RESOURCE_PACK
            parts.first() == "shaderpacks" -> ModpackArchiveContentType.SHADER_PACK
            parts.first() == "datapacks" ||
                (parts.first() == "saves" && parts.size >= 4 && parts[2] == "datapacks") ->
                ModpackArchiveContentType.DATA_PACK
            else -> ModpackArchiveContentType.OTHER
        }
    }

    private fun CatalogContentType.toArchiveContentType(): ModpackArchiveContentType? = when (this) {
        CatalogContentType.MOD -> ModpackArchiveContentType.MOD
        CatalogContentType.RESOURCE_PACK -> ModpackArchiveContentType.RESOURCE_PACK
        CatalogContentType.SHADER_PACK -> ModpackArchiveContentType.SHADER_PACK
        CatalogContentType.OTHER -> null
    }

    private fun ArchiveCatalogFile.toContentDto(
        targetPath: String,
        archiveType: ModpackArchiveContentType,
        required: Boolean,
    ): Modpack2ContentDto? {
        if (fileId.isBlank()) return null
        val contentPlatform = when (platform) {
            ModPlatform.CURSEFORGE -> ContentPlatform.CurseForge
            ModPlatform.MODRINTH -> ContentPlatform.Modrinth
        }
        val contentType = when (archiveType) {
            ModpackArchiveContentType.MOD -> ContentType.Mod
            ModpackArchiveContentType.RESOURCE_PACK -> ContentType.ResPack
            ModpackArchiveContentType.SHADER_PACK -> ContentType.ShaderPack
            ModpackArchiveContentType.DATA_PACK -> ContentType.DataPack
            ModpackArchiveContentType.OTHER -> ContentType.Other
        }
        val contentHash = if (contentPlatform == ContentPlatform.CurseForge) {
            murmur2?.toString() ?: return null
        } else {
            sha1
        }
        return Modpack2ContentDto(
            platform = contentPlatform,
            type = contentType,
            projectId = projectKey,
            fileId = fileId,
            slug = slug,
            hash = contentHash,
            targetPath = targetPath,
            side = side,
            required = required,
            fileSize = size,
        )
    }

    private fun safeFileName(value: String): String {
        val normalized = value.replace('\\', '/')
        require(normalized.substringAfterLast('/') == normalized && normalized.isNotBlank()) {
            "整合包文件名无效：$value"
        }
        return safeTarget(normalized)
    }

    private val ModpackArchiveContentType.targetDirectory: String
        get() = when (this) {
            ModpackArchiveContentType.MOD -> "mods"
            ModpackArchiveContentType.RESOURCE_PACK -> "resourcepacks"
            ModpackArchiveContentType.SHADER_PACK -> "shaderpacks"
            ModpackArchiveContentType.DATA_PACK, ModpackArchiveContentType.OTHER ->
                error("该内容类型没有CurseForge安装目录")
        }

    private fun safeTarget(raw: String): String {
        val normalized = raw.replace('\\', '/').trimStart()
        require(normalized.isNotEmpty()) { "整合包文件路径为空" }
        require(!normalized.startsWith('/') && !DRIVE_PATH.matches(normalized)) { "整合包路径越界：$raw" }
        val parts = normalized.split('/')
        require(parts.isNotEmpty() && parts.none { segment ->
            segment.isEmpty() || segment == "." || segment == ".." ||
                segment.endsWith('.') || segment.endsWith(' ') || segment.contains(':') ||
                WINDOWS_RESERVED.matches(segment)
        }) { "整合包路径无效：$raw" }
        return parts.joinToString("/")
    }

    /** Returns [value] percent-encoded where needed, or fails when it is malformed or not on an allowed host. */
    private fun validUrl(value: String): Result<String> = runCatching {
        val normalized = encodeIllegalUrlChars(value.trim())
        val uri = runCatching { URI(normalized) }.getOrElse { cause ->
            throw IllegalArgumentException("无效下载地址：$value", cause)
        }
        require(uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase(Locale.ROOT) in ALLOWED_HOSTS) {
            "不允许的下载地址：$value"
        }
        normalized
    }

    /** Percent-encodes characters that [URI] rejects (spaces, non-ASCII, ...) while keeping existing escapes. */
    private fun encodeIllegalUrlChars(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val out = StringBuilder(bytes.size)
        bytes.forEachIndexed { index, byte ->
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            val isEscape = char == '%' && index + 2 < bytes.size &&
                HEX_DIGITS.indexOf(bytes[index + 1].toInt().toChar().uppercaseChar()) >= 0 &&
                HEX_DIGITS.indexOf(bytes[index + 2].toInt().toChar().uppercaseChar()) >= 0
            if (code < 0x80 && (char.isLetterOrDigit() || char in URL_SAFE_CHARS || isEscape)) {
                out.append(char)
            } else {
                out.append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0x0F])
            }
        }
        return out.toString()
    }

    private fun validSha1(value: String): String {
        val normalized = value.trim().lowercase(Locale.ROOT)
        require(SHA1.matches(normalized)) { "整合包文件校验信息无效" }
        return normalized
    }

    private fun readText(zip: ZipFile, entry: ZipEntry): String {
        require(entry.size <= MAX_MANIFEST_BYTES || entry.size < 0) { "整合包清单过大" }
        val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_MANIFEST_BYTES + 1) }
        require(bytes.size <= MAX_MANIFEST_BYTES) { "整合包清单过大" }
        return bytes.toString(StandardCharsets.UTF_8)
    }

    @Serializable
    private data class CurseManifestMetadata(
        val name: String,
        val minecraft: CurseMinecraft,
    )

    @Serializable
    private data class CurseManifest(
        val name: String,
        val minecraft: CurseMinecraft,
        val overrides: String? = null,
        val files: List<CurseFile> = emptyList(),
    )

    @Serializable
    private data class CurseMinecraft(
        val version: String,
        val modLoaders: List<CurseLoader> = emptyList(),
    )

    @Serializable
    private data class CurseLoader(val id: String, val primary: Boolean = false)

    @Serializable
    private data class CurseFile(
        @SerialName("projectID") val projectId: Int,
        @SerialName("fileID") val fileId: Int,
        val required: Boolean = true,
    )

    @Serializable
    private data class ModrinthMetadata(
        val formatVersion: Int,
        val game: String,
        val name: String,
        val summary: String? = null,
        val dependencies: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class ModrinthIndex(
        val formatVersion: Int,
        val game: String,
        val name: String,
        val summary: String? = null,
        val files: List<ModrinthFile> = emptyList(),
        val dependencies: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class ModrinthFile(
        val path: String,
        val hashes: Map<String, String>,
        val env: ModrinthEnvironment? = null,
        val downloads: List<String> = emptyList(),
        val fileSize: Long,
    )

    @Serializable
    private data class ModrinthEnvironment(val client: Environment = Environment.REQUIRED)

    private data class ManifestEntries(
        val curse: ZipEntry?,
        val modrinth: ZipEntry?,
    )

    @Serializable
    private enum class Environment {
        @SerialName("required") REQUIRED,
        @SerialName("optional") OPTIONAL,
        @SerialName("unsupported") UNSUPPORTED,
    }

    private companion object {
        const val CURSE_MANIFEST = "manifest.json"
        const val MODRINTH_MANIFEST = "modrinth.index.json"
        const val OVERRIDES = "overrides"
        const val CLIENT_OVERRIDES = "client-overrides"
        const val MAX_ARCHIVE_ENTRIES = 100_000
        const val MAX_EXTRACTED_BYTES = 20L * 1024 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        val DRIVE_PATH = Regex("^[A-Za-z]:[/\\\\].*")
        val WINDOWS_RESERVED = Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")
        val SHA1 = Regex("^[0-9a-f]{40}$")
        const val HEX_DIGITS = "0123456789ABCDEF"
        const val URL_SAFE_CHARS = "-._~:/?#@!$&'()*+,;="
        val ALLOWED_HOSTS = setOf(
            "cdn.modrinth.com",
            "github.com",
            "raw.githubusercontent.com",
            "gitlab.com",
            "mediafilez.forgecdn.net",
            "edge.forgecdn.net",
            "media.forgecdn.net",
            "mod.mcimirror.top",
        )
    }
}
