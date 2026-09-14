package calebxzau.rdi.client.packproc

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.util.*
import calebxzau.rdi.client.modcatalog.CatalogSlugRef
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzau.rdi.client.modcatalog.getMetadataOrEmpty
import calebxzau.rdi.mediaproc.MediaProcUnavailableException
import calebxzau.rdi.mediaproc.OggAudioCodec
import calebxzau.rdi.mediaproc.OggCodecDetector
import calebxzau.rdi.mediaproc.OggTranscodeResult
import calebxzau.rdi.mediaproc.OggTranscoder
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.archive.extractArchiveToDir
import calebxzhou.rdi.common.archive.listArchiveEntries
import calebxzhou.rdi.common.deser
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.isExcludedConfigPath
import calebxzhou.rdi.common.model.*
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.service.CurseForgeService
import calebxzhou.rdi.common.service.CurseForgeService.loadInfoCurseForge
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.service.ModpackModProcessor
import calebxzhou.rdi.common.service.ModrinthService
import calebxzhou.rdi.common.service.ModrinthService.mapModrinthVersions
import calebxzhou.rdi.common.service.ModrinthService.toCardVo
import calebxzhou.rdi.common.service.murmur2
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val lgr by Loggers

private const val MODPACK_UPLOAD_VERSION_ERROR = "目前只支持上传MC1.20.1和MC1.21.1整合包"

internal fun mapCurseForgeExtraCandidate(
    candidate: File,
    gamePath: String,
    file: CurseForgeFile,
    project: CurseForgeModInfo,
): Content? {
    val type = when (project.classId) {
        12L -> ContentType.ResPack
        6552L -> ContentType.ShaderPack
        else -> return null
    }
    if (file.modId != project.id || file.fileFingerprint != candidate.murmur2) return null
    val expectedSha1 = file.hashes.firstOrNull { it.algo == 1 }
        ?.value?.trim()?.lowercase().orEmpty()
    val actualSha1 = candidate.sha1.lowercase()
    if (expectedSha1.isBlank() || expectedSha1 != actualSha1) return null
    return Content(
        platform = ContentPlatform.CurseForge,
        type = type,
        projectId = project.id.toString(),
        fileId = file.id.toString(),
        slug = project.slug.ifBlank { project.id.toString() },
        hash = file.fileFingerprint.toString(),
        path = gamePath,
        side = ContentSide.Client,
        required = true,
        downloadUrls = listOf(file.realDownloadUrl),
    )
}

private fun normalizeEffectiveClientExtraPath(path: String): String =
    path.replace('\\', '/').trimStart('/').removePrefix("overrides/").lowercase()

/**
 * Stages a matched local extra only after rechecking the bytes against the
 * platform identity captured during matching.  The source remains untouched.
 */
internal fun stageEmbeddedClientExtra(
    content: Content,
    sourceFile: File,
    sourceRelativePath: String,
    expectedSha1: String,
    stagedFile: File,
): EmbeddedClientExtraSource {
    require(sourceFile.isFile) { "客户端额外内容源文件不存在: $sourceRelativePath" }
    val normalizedSha1 = expectedSha1.trim().lowercase()
    require(normalizedSha1.isNotBlank()) { "客户端额外内容缺少SHA-1: $sourceRelativePath" }
    require(sourceFile.sha1.equals(normalizedSha1, ignoreCase = true)) {
        "客户端额外内容在暂存前发生变化: $sourceRelativePath"
    }
    sourceFile.copyTo(stagedFile, overwrite = true)
    require(stagedFile.isFile && stagedFile.sha1.equals(normalizedSha1, ignoreCase = true)) {
        "客户端额外内容暂存校验失败: $sourceRelativePath"
    }
    return EmbeddedClientExtraSource(
        content = content,
        stagedFile = stagedFile,
        sourceRelativePath = sourceRelativePath,
        verifiedSha1 = normalizedSha1,
    )
}

fun requireModpackUploadVersion(mcVersion: McVersion) {
    if (!mcVersion.supportsModpackUpload()) {
        throw ModpackError(MODPACK_UPLOAD_VERSION_ERROR)
    }
}

// ==================== Upload-only code ====================

class ModpackProcessor(
    private val paths: PackProcessingPaths,
    private val embeddedClientExtraMatcher: (suspend (File, List<Content>) -> List<EmbeddedClientExtraSource>)? = null,
) {
    fun processUploadMods(mods: List<Mod>): MutableList<Mod> =
        ModpackModProcessor.processMods(mods)

    private fun ModCardMatch.withSide(side: Mod.Side): ModCardMatch = copy(
        mod = mod.copy(side = side),
        card = card?.copy(side = side)
    )

    private fun ModCardMatch.toMod(): Mod = mod.copy(
        downloadUrls = mod.downloadUrls.toList()
    )

    private fun openBundledResource(name: String): InputStream =
        ModpackProcessor::class.java.classLoader.getResourceAsStream(name)
            ?: throw ModpackError("缺少资源文件: $name")

    suspend fun loadLocalModpack(
        modCatalog: ModCatalog,
        file: File,
        onProgress: LoadProgressConsumer
    ): Result<LoadedLocalModpack> = withContext(Dispatchers.IO) {
        paths.workDir.mkdirs()
        var payload: UploadPayload? = null
        runCatching {
            onProgress.phase("开始读取整合包")
            payload = parseUploadPayload(file, onProgress).getOrThrow()
            val parsedPayload = payload
            onProgress.phase(
                buildString {
                    append("整合包概要读取完成 ")
                    append(parsedPayload.sourceName.ifBlank { "未命名整合包" })
                    append(" ")
                    append(parsedPayload.sourceVersion.ifBlank { "1.0" })
                }
            )
            onProgress.phase(
                buildString {
                    append("开始整理Mod列表 MC")
                    append(parsedPayload.mcVersion.mcVer)
                    append(" ")
                    append(parsedPayload.modloader.name)
                }
            )
            val mods = loadUploadPayloadMods(
                modCatalog = modCatalog,
                payload = parsedPayload,
                onProgress = onProgress
            ).getOrThrow()
            onProgress.phase("Mod列表整理完成，共${mods.size}个，准备进入编辑")
            LoadedLocalModpack(
                sourceType = parsedPayload.sourceType,
                sourceDir = parsedPayload.sourceDir,
                packName = parsedPayload.sourceName,
                packVersion = parsedPayload.sourceVersion,
                mcVersion = parsedPayload.mcVersion,
                modloader = parsedPayload.modloader,
                mods = mods,
                clientExtras = parsedPayload.clientExtras,
                embeddedClientExtraSources = parsedPayload.embeddedClientExtraSources,
                embeddedModOriginalFileNames = parsedPayload.embeddedModOriginalFileNames,
                embeddedModSources = parsedPayload.embeddedModSources,
                serverExtraFiles = parsedPayload.serverExtraFiles,
                containsExcludedMcaFiles = containsExcludedMcaFiles(parsedPayload.sourceDir)
            )
        }.fold(
            onSuccess = ::ok,
            onFailure = { error ->
                payload?.sourceDir?.let { runCatching { it.deleteRecursivelyNoSymlink() } }
                Result.failure(error)
            }
        )
    }

    private fun parseUploadPayload(
        file: File,
        onProgress: (LoadProgress) -> Unit
    ): Result<UploadPayload> {
        var prepared: PreparedModpack? = null
        return runCatching {
            onProgress(LoadProgress.Phase("准备解析整合包概要"))
            onProgress(LoadProgress.Phase("快速检查整合包结构"))
            val preflight = preflightPackStructure(file)
            prepared = runCatching {
                prepareModpackSource(file, onProgress)
            }.getOrElse { e ->
                throw ModpackError("读取整合包文件失败", e)
            }
            val preparedPack = prepared
            onProgress(LoadProgress.Phase("整合包结构检查完成"))
            when (preflight.packType) {
                PackType.MODRINTH -> {
                    onProgress(LoadProgress.Phase("读取Modrinth概要文件"))
                    inspectModrinthUploadPayload(preparedPack.rootDir)
                }

                PackType.CURSEFORGE -> {
                    onProgress(LoadProgress.Phase("读取CurseForge概要文件"))
                    inspectCurseForgeUploadPayload(preparedPack.rootDir)
                }

                PackType.UNKNOWN -> throw ModpackError("找不到此包的概要文件(manifest.json/modrinth.index.json)")
            }
        }.fold(
            onSuccess = ::ok,
            onFailure = { e ->
                prepared?.rootDir?.let { runCatching { it.deleteRecursivelyNoSymlink() } }
                Result.failure(e)
            }
        )
    }

    private suspend fun loadUploadPayloadMods(
        modCatalog: ModCatalog,
        payload: UploadPayload,
        onProgress: (LoadProgress) -> Unit
    ): Result<MutableList<Mod>> {
        val sourceDir = payload.sourceDir
        onProgress(LoadProgress.Phase("扫描包内内置mods"))
        val embeddedMods = collectEmbeddedModFiles(sourceDir)
        if (embeddedMods.isEmpty()) {
            onProgress(LoadProgress.Phase("未发现包内内置mods"))
        } else {
            onProgress(LoadProgress.Phase("发现包内内置mods${embeddedMods.size}个"))
        }
        val embeddedMatches = matchLocalModFiles(embeddedMods, onProgress)
        if (embeddedMatches.mods.isNotEmpty()) {
            onProgress(LoadProgress.Phase("已识别内置mods${embeddedMatches.mods.size}个"))
        }
        payload.embeddedModOriginalFileNames = embeddedMatches.mods.mapNotNull { uiMod ->
            val originalName = uiMod.file?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            uiMod.mod.fileName to originalName
        }.toMap()
        val resolvedMods = when (payload.sourceType) {
            LocalModpackSourceType.MODRINTH -> {
                onProgress(LoadProgress.Phase("解析Modrinth整合包索引"))
                val loaded = ModrinthService.loadModpack(sourceDir) { curseForgeSlugs ->
                    val refs = curseForgeSlugs.map { CatalogSlugRef(ModPlatform.CURSEFORGE, it) }.toSet()
                    val metadata = modCatalog.getMetadataOrEmpty(refs)
                    curseForgeSlugs.mapNotNull { slug ->
                        val local = metadata[CatalogSlugRef(ModPlatform.CURSEFORGE, slug)] ?: return@mapNotNull null
                        local.project(ModPlatform.MODRINTH)?.slug?.let { slug to it }
                    }.toMap()
                }.getOrThrow()
                payload.clientExtras = loaded.clientExtras.toMutableList()
                (loaded.mods + embeddedMatches.mods.map { it.toMod() })
                    .distinctBy { "${it.platform}:${it.projectId}:${it.fileId}:${it.hash}" }
                    .toMutableList()
            }

            LocalModpackSourceType.CURSEFORGE -> {
                onProgress(LoadProgress.Phase("解析CurseForge整合包清单"))
                val modpackData = loadCurseForgeFromDir(sourceDir)
                val baseMods = CurseForgeService.mapManifestEntriesToMods(modpackData.manifest.files)
                payload.clientExtras = CurseForgeService.mapManifestEntriesToContents(modpackData.manifest.files)
                    .toMutableList()
                (baseMods + embeddedMatches.mods.map { it.toMod() })
                    .distinctBy { "${it.platform}:${it.projectId}:${it.fileId}:${it.hash}" }
                    .toMutableList()
            }
        }
        onProgress(LoadProgress.Phase("整理Mod单双端属性"))
        ModService.run { resolvedMods.postProcessModSides() }
        payload.embeddedModSources = stageMatchedEmbeddedMods(embeddedMatches.mods)
        payload.embeddedClientExtraSources = embeddedClientExtraMatcher?.invoke(sourceDir, payload.clientExtras)
            ?: matchEmbeddedClientExtras(sourceDir, payload.clientExtras)
        payload.clientExtras = mergeEmbeddedClientExtras(
            sourceDir = sourceDir,
            declared = payload.clientExtras,
            matched = payload.embeddedClientExtraSources.map { it.content },
        ).toMutableList()
        if (embeddedMatches.matchedFiles.isNotEmpty()) {
            embeddedMatches.matchedFiles.forEach { it.delete() }
        }
        payload.mods = resolvedMods
        return ok(resolvedMods)
    }

    internal fun mergeEmbeddedClientExtras(
        sourceDir: File,
        declared: List<Content>,
        matched: List<Content>,
    ): List<Content> {
        val merged = linkedMapOf<String, Content>()
        val localOverrides = collectEffectiveLocalExtraPaths(sourceDir)
        declared.forEach { content ->
            val path = effectiveExtraPathKey(content.targetRelativePath)
            if (localOverrides.any { (localPath, isDirectory) ->
                    path == localPath || (isDirectory && path.startsWith("$localPath/"))
                }
            ) return@forEach
            val old = merged[path]
            if (old != null && old != content) {
                throw ModpackError("客户端额外内容路径冲突: ${content.targetRelativePath}")
            }
            merged[path] = content
        }
        matched.forEach { content ->
            val path = effectiveExtraPathKey(content.targetRelativePath)
            // A verified local override is the effective source for this path.
            // Keep a same-identity declaration once, and replace a remote
            // declaration that points at the same path with different bytes.
            merged[path] = content
        }
        return merged.values.toList()
    }

    private fun collectEffectiveLocalExtraPaths(rootDir: File): List<Pair<String, Boolean>> =
        collectEffectiveLocalExtraCandidates(rootDir).map { it.pathKey to it.file.isDirectory }

    private fun collectEffectiveLocalExtraCandidates(rootDir: File): List<ExtraCandidate> {
        val candidates = collectAllLocalExtraCandidates(rootDir)
        return candidates.groupBy(ExtraCandidate::pathKey).mapNotNull { (_, samePath) ->
            val highestPriority = samePath.maxOf(ExtraCandidate::priority)
            val peers = samePath.filter { it.priority == highestPriority }
            val identities = peers.map(ExtraCandidate::byteIdentity).distinct()
            require(identities.size == 1) {
                "客户端额外内容路径冲突: ${peers.joinToString("、") { it.file.name }}"
            }
            peers.first()
        }
    }

    private fun collectAllLocalExtraCandidates(rootDir: File): List<ExtraCandidate> =
        clientExtraRoots(rootDir).flatMap { (directory, gameRoot, priority) ->
            if (!directory.isDirectory) return@flatMap emptyList()
            val prefix = if (directory.relativeTo(rootDir).invariantSeparatorsPath
                    .startsWith("overrides/", ignoreCase = true)
            ) "overrides/" else ""
            directory.listFiles().orEmpty().filter { child ->
                child.isDirectory || (child.isFile && child.extension.equals("zip", true))
            }.map { child ->
                ExtraCandidate(
                    file = child,
                    sourceRelativePath = "$prefix$gameRoot/${child.name}",
                    gamePath = "$gameRoot/${child.name}",
                    priority = priority,
                )
            }
        }

    private fun effectiveExtraPathKey(path: String): String {
        return normalizeEffectiveClientExtraPath(path)
    }

    private fun clientExtraRoots(rootDir: File) = listOf(
        Triple(rootDir.resolve("resourcepacks"), "resourcepacks", 0),
        Triple(rootDir.resolve("shaderpacks"), "shaderpacks", 0),
        Triple(rootDir.resolve("overrides/resourcepacks"), "resourcepacks", 1),
        Triple(rootDir.resolve("overrides/shaderpacks"), "shaderpacks", 1),
    )

    suspend fun loadServerPack(
        file: File,
        clientMods: List<Mod>,
        clientModSources: Map<Mod, File>,
        onProgress: LoadProgressConsumer
    ): Result<LoadedServerPackResult> = withContext(Dispatchers.IO) {
        paths.workDir.mkdirs()
        runCatching {
            onProgress.phase("开始读取服务端目录")
            if (!file.exists() || !file.isDirectory) {
                throw ModpackError("请选择服务端根目录")
            }
            val modsDir = file.resolve("mods")
            if (!modsDir.exists() || !modsDir.isDirectory) {
                throw ModpackError("请选择服务端根目录，目录下应有mods文件夹")
            }
            val modFileSelection = collectServerPackModFiles(file)
            if (modFileSelection.selected.isEmpty()) {
                throw ModpackError("请选择服务端根目录，目录下应有mods文件夹")
            }
            val clientModsByModId = buildClientModsByModId(clientMods, clientModSources)
            val matchedByModId = matchServerModsByClientModId(
                files = modFileSelection.selected,
                clientModsByModId = clientModsByModId,
                onProgress = onProgress
            )
            val matched = matchLocalModFiles(matchedByModId.unmatchedFiles, onProgress)
            val finalMatchedMods = (matched.mods + matchedByModId.mods)
                .distinctBy(::serverModMergeKey)
            val matchedFiles = matched.matchedFiles +
                matchedByModId.matchedFiles +
                modFileSelection.discarded
            val finalUnmatchedFiles = matched.unmatchedFiles
            if (finalUnmatchedFiles.isNotEmpty()) {
                val preview = finalUnmatchedFiles.take(5).joinToString("、") { it.nameWithoutExtension }
                val suffix = if (finalUnmatchedFiles.size > 5) "等${finalUnmatchedFiles.size}个" else ""
                onProgress.warn("服务端目录中有${finalUnmatchedFiles.size}个mod未识别，将作为额外服务端文件带上：$preview$suffix")
            }
            val resolvedMods = finalMatchedMods.map { it.toMod() }.toMutableList().also {
                ModService.run { it.postProcessModSides() }
            }
            val serverExtraSelection = collectServerPackExtraFiles(
                rootDir = file,
                matchedModFiles = matchedFiles
            )
            val embeddedModSources = stageMatchedEmbeddedMods(finalMatchedMods)
            LoadedServerPackResult(
                mods = resolvedMods,
                serverExtraFiles = serverExtraSelection.files,
                embeddedModSources = embeddedModSources,
                containsExcludedMcaFiles = serverExtraSelection.containsExcludedMcaFiles
            )
        }.fold(
            onSuccess = ::ok,
            onFailure = { error -> Result.failure(error) }
        )
    }

    private data class EmbeddedMatchResult(
        val mods: List<ModCardMatch>,
        val matchedFiles: Set<File>,
        val unmatchedFiles: List<File>
    )

    private data class EmbeddedMergedResult(
        val mods: List<ModCardMatch>,
        val matchedFiles: Set<File>,
        val unmatchedFiles: List<File>
    )

    private data class ServerExtraFileSelection(
        val files: List<ServerExtraFile>,
        val containsExcludedMcaFiles: Boolean
    )

    private suspend fun matchEmbeddedModsCF(
        files: List<File>
    ): EmbeddedMatchResult {
        if (files.isEmpty()) return EmbeddedMatchResult(emptyList(), emptySet(), emptyList())
        val result = files.loadInfoCurseForge()
        if (result.matched.isEmpty()) return EmbeddedMatchResult(emptyList(), emptySet(), files)
        val matchedFiles = result.matched.mapNotNull { it.file }.toSet()
        val matched = result.matched
        return EmbeddedMatchResult(matched, matchedFiles, result.unmatched)
    }

    private suspend fun matchEmbeddedModsMR(
        files: List<File>
    ): EmbeddedMatchResult {
        if (files.isEmpty()) return EmbeddedMatchResult(emptyList(), emptySet(), emptyList())
        val hashToVersion = files.mapModrinthVersions()
        if (hashToVersion.isEmpty()) return EmbeddedMatchResult(emptyList(), emptySet(), files)
        val projectIds = hashToVersion.values.map { it.projectId }.distinct()
        val projectMap = ModrinthService.getMultipleProjects(projectIds).associateBy { it.id }
        val matched = mutableListOf<ModCardMatch>()
        val matchedFiles = mutableSetOf<File>()
        files.forEach { file ->
            val sha1 = file.sha1
            val version = hashToVersion[sha1] ?: return@forEach
            val project = projectMap[version.projectId]
            val slug = project?.slug?.takeIf { it.isNotBlank() }
                ?: file.nameWithoutExtension.ifBlank { version.projectId }
            val side = project?.run {
                if (serverSide == "unsupported") {
                    return@run Mod.Side.CLIENT
                }
                if (clientSide == "unsupported") {
                    return@run Mod.Side.SERVER
                }
                Mod.Side.BOTH
            } ?: Mod.Side.BOTH
            val fileInfo = version.files.firstOrNull { it.hashes["sha1"] == sha1 }
            val downloadUrls = fileInfo?.url?.let { listOf(it) } ?: emptyList()
            val rawMod = Mod(
                platform = "mr",
                projectId = version.projectId,
                slug = slug,
                fileId = version.id,
                hash = sha1,
                side = side,
                downloadUrls = downloadUrls
            )
            val mod = ModCardMatch(
                mod = rawMod,
                card = project?.toCardVo(file)?.copy(side = side),
                file = file
            )
            matched += mod
            matchedFiles += file
        }
        val unmatchedFiles = files.filterNot { it in matchedFiles }
        return EmbeddedMatchResult(matched, matchedFiles, unmatchedFiles)
    }

    private fun stageMatchedEmbeddedMods(mods: List<ModCardMatch>): List<EmbeddedModSource> {
        if (mods.isEmpty()) return emptyList()
        val stagingDir = Files.createTempDirectory(paths.workDir.toPath(), "embedded-mods-").toFile()
        return try {
            mods.mapNotNull { uiMod ->
                val sourceFile = uiMod.file ?: return@mapNotNull null
                if (!sourceFile.exists() || !sourceFile.isFile) {
                    throw ModpackError("识别的内嵌mod文件不存在: ${sourceFile.absolutePath}")
                }
                val targetFile = stagingDir.resolve(uiMod.mod.fileName)
                sourceFile.copyTo(targetFile, overwrite = true)
                EmbeddedModSource(
                    mod = uiMod.mod,
                    stagedFile = targetFile,
                    originalFileName = sourceFile.name
                )
            }
        } catch (error: Throwable) {
            runCatching { stagingDir.deleteRecursivelyNoSymlink() }
            throw ModpackError("暂存内嵌mod失败", error)
        }
    }

    private suspend fun matchEmbeddedClientExtras(
        rootDir: File,
        extras: List<Content>,
    ): List<EmbeddedClientExtraSource> {
        val candidates = collectEffectiveLocalExtraCandidates(rootDir)
            .filter { it.file.isFile && it.file.extension.equals("zip", true) && !it.file.name.endsWith(".zip.txt", true) }
        if (candidates.isEmpty()) return emptyList()

        // Direct and override roots can contain the same effective placement.
        // Preserve both path and source identity, while giving overrides the
        // local priority for that one placement only.
        val selectedCandidates = candidates
        val matched = mutableListOf<MatchedEmbeddedClientExtra>()

        suspend fun <T> platformLookup(label: String, block: suspend () -> T): T = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw ModpackError("${label}失败", error)
        }

        val mrVersions = platformLookup("Modrinth客户端额外内容匹配") {
            selectedCandidates.map { it.file }.mapModrinthVersions()
        }
        val mrProjects = if (mrVersions.isEmpty()) {
            emptyMap()
        } else {
            platformLookup("Modrinth客户端额外内容项目解析") {
                ModrinthService.getMultipleProjects(mrVersions.values.map { it.projectId }.distinct())
            }.associateBy { it.id }
        }
        selectedCandidates.forEach { candidate ->
            val sha1 = candidate.file.sha1.lowercase()
            val version = mrVersions[sha1] ?: return@forEach
            val type = candidate.contentType
            val project = mrProjects[version.projectId]
            val fileInfo = version.files.firstOrNull { it.hashes["sha1"]?.equals(sha1, true) == true }
            if (fileInfo == null) return@forEach
            val expectedSha1 = fileInfo.hashes["sha1"]?.trim()?.lowercase().orEmpty()
            if (expectedSha1.isBlank()) return@forEach
            val defaultContent = Content(
                platform = ContentPlatform.Modrinth,
                type = type,
                projectId = version.projectId,
                fileId = version.id,
                slug = project?.slug?.ifBlank { version.projectId } ?: version.projectId,
                hash = sha1,
                path = candidate.gamePath,
                side = ContentSide.Client,
                required = true,
                downloadUrls = listOf(fileInfo.url),
            )
            if (candidate.file.sha1.equals(expectedSha1, ignoreCase = true)) {
                matched += MatchedEmbeddedClientExtra(defaultContent, candidate, expectedSha1)
            }
        }

        val unresolved = selectedCandidates.filterNot { candidate ->
            matched.any { it.candidate == candidate }
        }
        val cfMatches = if (unresolved.isEmpty()) {
            CurseForgeFingerprintData()
        } else {
            platformLookup("CurseForge客户端额外内容指纹匹配") {
                CurseForgeService.matchFingerprintData(unresolved.map { it.file.murmur2 }.distinct())
            }
        }
        run {
            val matchesByFingerprint = cfMatches.exactMatches.groupBy { it.file.fileFingerprint }
            val projectIds = cfMatches.exactMatches.mapNotNull { it.id.takeIf { id -> id > 0 } }.distinct()
            val projects = if (projectIds.isEmpty()) {
                emptyMap()
            } else {
                platformLookup("CurseForge客户端额外内容项目解析") {
                    CurseForgeService.getModsInfoIncludingNonMods(projectIds)
                }.associateBy { it.id }
            }
            unresolved.forEach { candidate ->
                val match = matchesByFingerprint[candidate.file.murmur2].orEmpty().firstOrNull()
                    ?: return@forEach
                val project = projects[match.id] ?: return@forEach
                val content = mapCurseForgeExtraCandidate(
                    candidate = candidate.file,
                    gamePath = candidate.gamePath,
                    file = match.file,
                    project = project,
                ) ?: return@forEach
                val expectedSha1 = match.file.hashes.firstOrNull { it.algo == 1 }
                    ?.value?.trim()?.lowercase().orEmpty()
                matched += MatchedEmbeddedClientExtra(content, candidate, expectedSha1)
            }
        }

        val staged = mutableListOf<File>()
        return try {
            matched.map { match ->
                val target = Files.createTempFile(paths.workDir.toPath(), "embedded-extra-", ".${match.candidate.file.extension}").toFile()
                staged += target
                stageEmbeddedClientExtra(
                    content = match.content,
                    sourceFile = match.candidate.file,
                    sourceRelativePath = match.candidate.sourceRelativePath,
                    expectedSha1 = match.expectedSha1,
                    stagedFile = target,
                )
            }
        } catch (error: Throwable) {
            staged.forEach { file ->
                runCatching { Files.deleteIfExists(file.toPath()) }
                    .onFailure { cleanup -> lgr.warn(cleanup) { "无法清理失败的客户端额外内容暂存: $file" } }
            }
            throw ModpackError("暂存客户端额外内容失败", error)
        }
    }

    private data class MatchedEmbeddedClientExtra(
        val content: Content,
        val candidate: ExtraCandidate,
        val expectedSha1: String,
    )

    private data class ExtraCandidate(
        val file: File,
        val sourceRelativePath: String,
        val gamePath: String,
        val priority: Int,
    ) {
        val pathKey: String
            get() = normalizeEffectiveClientExtraPath(gamePath)

        fun byteIdentity(): String {
            if (file.isFile) return "file:${file.sha1}"
            val files = file.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(file).invariantSeparatorsPath }
                .map { "${it.relativeTo(file).invariantSeparatorsPath}:${it.sha1}" }
            return "directory:${files.joinToString("|")}"
        }

        val contentType: ContentType
            get() = if (gamePath.startsWith("resourcepacks/", ignoreCase = true)) {
                ContentType.ResPack
            } else {
                ContentType.ShaderPack
            }
    }

    private suspend fun matchLocalModFiles(
        files: List<File>,
        onProgress: (LoadProgress) -> Unit
    ): EmbeddedMergedResult {
        if (files.isEmpty()) return EmbeddedMergedResult(emptyList(), emptySet(), emptyList())
        onProgress.phase("发现服务端目录中的mod${files.size}个，上网搜索信息中")
        val mrResult = runCatching {
            matchEmbeddedModsMR(files)
        }.getOrElse { e ->
            throw ModpackError("Modrinth匹配内置mod失败", e)
        }
        val remaining = mrResult.unmatchedFiles
        onProgress.phase("Modrinth找到${mrResult.mods.size}个 开始搜索CurseForge")
        val cfResult = runCatching {
            matchEmbeddedModsCF(remaining)
        }.getOrElse { e ->
            throw ModpackError("CurseForge匹配内置mod失败", e)
        }
        onProgress.phase("匹配完成：MR${mrResult.mods.size}个，CF${cfResult.mods.size} 个，处理结果中，请等一分钟...")
        val mergedMods = (mrResult.mods + cfResult.mods)
            .distinctBy(::serverModMergeKey)
        val matchedFiles = mrResult.matchedFiles + cfResult.matchedFiles
        return EmbeddedMergedResult(mergedMods, matchedFiles, cfResult.unmatchedFiles)
    }

    private fun buildClientModsByModId(
        clientMods: List<Mod>,
        clientModSources: Map<Mod, File>
    ): Map<String, Mod> {
        if (clientMods.isEmpty()) return emptyMap()
        val clientModsByModId = linkedMapOf<String, Mod>()
        clientMods.forEach { clientMod ->
            val clientFile = clientModSources[clientMod]?.takeIf { it.exists() && it.isFile } ?: return@forEach
            val modId = readPrimaryModId(clientFile) ?: return@forEach
            val previous = clientModsByModId.putIfAbsent(modId, clientMod)
            if (previous != null && previous != clientMod) {
                lgr.warn { "多个客户端mod共用了同一个modId=$modId，将保留第一个${previous.slug}，忽略${clientMod.slug}" }
            }
        }
        return clientModsByModId
    }

    private suspend fun matchServerModsByClientModId(
        files: List<File>,
        clientModsByModId: Map<String, Mod>,
        onProgress: LoadProgressConsumer
    ): EmbeddedMatchResult {
        if (files.isEmpty() || clientModsByModId.isEmpty()) {
            return EmbeddedMatchResult(emptyList(), emptySet(), files)
        }
        onProgress.phase("平台未识别的服务端mod，按modId与客户端已下载mod对比")
        val matchedMods = mutableListOf<ModCardMatch>()
        val matchedFiles = mutableSetOf<File>()
        val ignoredFiles = mutableSetOf<File>()
        val usedServerModIds = mutableSetOf<String>()
        files.forEach { serverFile ->
            val serverModId = readPrimaryModId(serverFile) ?: return@forEach
            if (serverModId in usedServerModIds) {
                lgr.warn { "服务端目录里有多个jar共用了同一个modId=$serverModId，将保留第一个并忽略后续文件" }
                ignoredFiles += serverFile
                matchedFiles += serverFile
                return@forEach
            }
            val clientMod = clientModsByModId[serverModId] ?: return@forEach
            if (!fileMatchesModContent(serverFile, clientMod)) {
                return@forEach
            }
            usedServerModIds += serverModId
            matchedMods += ModCardMatch(
                mod = clientMod,
                file = serverFile
            )
                .withSide(Mod.Side.BOTH)
            matchedFiles += serverFile
        }
        return EmbeddedMatchResult(
            mods = matchedMods,
            matchedFiles = matchedFiles,
            unmatchedFiles = files.filterNot { it in matchedFiles || it in ignoredFiles }
        )
    }

    private fun readPrimaryModId(file: File): String? = readPrimaryModConfig(file)?.modId

    private fun serverModMergeKey(match: ModCardMatch): String {
        val fileModId = match.file?.let(::readPrimaryModId)
        if (!fileModId.isNullOrBlank()) return "modid:$fileModId"
        if (match.mod.slug.isNotBlank()) return "slug:${match.mod.slug.trim().lowercase()}"
        if (match.mod.projectId.isNotBlank()) return "project:${match.mod.projectId.trim()}"
        return "${match.mod.platform}:${match.mod.projectId}:${match.mod.fileId}:${match.mod.hash}"
    }

    private data class PreparedModpack(
        val rootDir: File,
        val name: String
    )

    private data class PackStructurePreflight(
        val packType: PackType
    )

    private data class AssetProcessInput(
        val relativePath: String,
        val relativeLower: String,
        val rawBytes: ByteArray
    )

    private fun formatPercent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"

    private fun displayProgressFileName(path: String): String =
        path.substringAfterLast('/').ifBlank { path }

    private fun preflightPackStructure(input: File): PackStructurePreflight {
        return runCatching {
            if (!input.exists()) {
                throw ModpackError("找不到整合包文件: ${input.path}")
            }
            val archiveEntryNames = if (input.isDirectory) {
                null
            } else {
                listArchiveEntries(input).map { it.path }
            }
            val packType = archiveEntryNames?.let(::detectPackType) ?: detectPackType(input)
            if (packType == PackType.UNKNOWN) {
                throw ModpackError("找不到此包的概要文件(manifest.json/modrinth.index.json)")
            }
            val hasOverrides = archiveEntryNames?.let { hasOverridesDir(it, packType) } ?: hasOverridesDir(input)
            if (!hasOverrides) {
                throw ModpackError("找不到包中overrides目录")
            }
            PackStructurePreflight(packType)
        }.getOrElse { error ->
            if (error is ModpackError) throw error
            throw ModpackError("读取整合包文件失败", error)
        }
    }

    private fun prepareModpackSource(input: File, onProgress: (LoadProgress) -> Unit): PreparedModpack {
        val tempDir = Files.createTempDirectory(paths.workDir.toPath(), "pack-").toFile()
        return try {
            if (input.isDirectory) {
                onProgress(LoadProgress.Phase("正在复制整合包目录"))
                input.copyRecursively(tempDir, overwrite = true)
                onProgress(LoadProgress.Percent("整合包目录复制完成", 1f))
                PreparedModpack(tempDir, input.name)
            } else {
                onProgress(LoadProgress.Phase("正在解压整合包"))
                extractArchiveToDir(input, tempDir) { done, total, _ ->
                    val fraction = done.toFloat() / total.coerceAtLeast(1)
                    onProgress(LoadProgress.Percent("正在解压整合包(${done}/$total)", fraction))
                }
                onProgress(LoadProgress.Percent("整合包解压完成", 1f))
                PreparedModpack(tempDir, input.nameWithoutExtension)
            }
        } catch (error: Throwable) {
            runCatching { tempDir.deleteRecursivelyNoSymlink() }
            throw error
        }
    }

    private fun detectPackType(rootDir: File): PackType {
        var hasMrIndex = false
        var hasCfManifest = false
        rootDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            when (file.name) {
                "modrinth.index.json" -> hasMrIndex = true
                "manifest.json" -> hasCfManifest = true
            }
            if (hasMrIndex || hasCfManifest) return@forEach
        }
        return when {
            hasMrIndex -> PackType.MODRINTH
            hasCfManifest -> PackType.CURSEFORGE
            else -> PackType.UNKNOWN
        }
    }

    private fun detectPackType(entryNames: List<String>): PackType {
        var hasMrIndex = false
        var hasCfManifest = false
        entryNames.forEach { entryName ->
            when (entryName.replace('\\', '/').trimStart('/').substringAfterLast('/')) {
                "modrinth.index.json" -> hasMrIndex = true
                "manifest.json" -> hasCfManifest = true
            }
            if (hasMrIndex || hasCfManifest) return@forEach
        }
        return when {
            hasMrIndex -> PackType.MODRINTH
            hasCfManifest -> PackType.CURSEFORGE
            else -> PackType.UNKNOWN
        }
    }

    private fun findFile(rootDir: File, fileName: String): File? {
        return rootDir.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
    }

    private fun loadCurseForgeFromDir(rootDir: File): CurseForgeModpackData {
        val manifestFile = findFile(rootDir, "manifest.json")
            ?: throw ModpackError("整合包缺少文件：manifest.json")
        val manifestJson = manifestFile.readText(Charsets.UTF_8)
        val manifest = runCatching {
            serdesJson.decodeFromString<CurseForgePackManifest>(manifestJson)
        }.getOrElse { e ->
            lgr.warn { "manifest.json解析失败: ${manifestFile.absolutePath + "\n" + e}" }
            throw ModpackError("manifest.json 解析失败: ${e.message}")
        }
        return CurseForgeModpackData(
            manifest = manifest,
            file = rootDir
        )
    }

    private fun inspectModrinthUploadPayload(rootDir: File): UploadPayload {
        val indexFile = findFile(rootDir, "modrinth.index.json")
            ?: throw ModpackError("整合包缺少文件：modrinth.index.json")
        val index = indexFile.readText(Charsets.UTF_8).deser<ModrinthModpackIndex>().getOrElse { err ->
            throw ModpackError("modrinth概要文件解析失败", err)
        }
        val mcVersion = index.dependencies["minecraft"]?.trim().orEmpty().let {
            resolveSupportedMcVersion(it)
        }
        val modloader = (index.dependencies.keys
            .firstOrNull { ModLoader.from(it) != null }
            ?.let { resolveSupportedModLoader(it) }
            ?: throw ModpackError("不支持的Mod加载器: 未知"))
        return UploadPayload(
            sourceType = LocalModpackSourceType.MODRINTH,
            sourceDir = rootDir,
            mods = mutableListOf(),
            mcVersion = mcVersion,
            modloader = modloader,
            sourceName = index.name,
            sourceVersion = index.versionId.ifBlank { "1.0" }
        )
    }

    private fun inspectCurseForgeUploadPayload(rootDir: File): UploadPayload {
        val modpackData = loadCurseForgeFromDir(rootDir)
        val mcVersion = resolveSupportedMcVersion(modpackData.manifest.minecraft.version)
        val modloader = resolveSupportedModLoader(modpackData.manifest.minecraft.modLoaders.firstOrNull()?.id.orEmpty())

        return UploadPayload(
            sourceType = LocalModpackSourceType.CURSEFORGE,
            sourceDir = rootDir,
            mods = mutableListOf(),
            mcVersion = mcVersion,
            modloader = modloader,
            sourceName = modpackData.manifest.name,
            sourceVersion = modpackData.manifest.version.ifBlank { "1.0" }
        )
    }

    private fun resolveSupportedMcVersion(
        mcVersionText: String,
    ): McVersion {
        val mcVersion = McVersion.from(mcVersionText)
        if (mcVersion == null || !mcVersion.enabled) {
            throw ModpackError("暂不支持MC版本${mcVersionText}")
        }
        requireModpackUploadVersion(mcVersion)
        return mcVersion
    }

    private fun resolveSupportedModLoader(loaderText: String): ModLoader {
        val normalized = loaderText.trim()
        return ModLoader.from(normalized).takeIf { it != null }
            ?: throw ModpackError("暂不支持Mod加载器${normalized.ifBlank { "未知" }}")
    }

    private data class ModFileSelection(
        val selected: List<File>,
        val discarded: Set<File>
    )

    private fun collectEmbeddedModFiles(rootDir: File): List<File> {
        val modFiles = rootDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .filter { it.invariantSeparatorsPath.contains("/mods/") }
            .toList()
        return selectPreferredModFiles(modFiles).selected
    }

    private fun collectServerPackModFiles(rootDir: File): ModFileSelection {
        val modsDir = rootDir.resolve("mods")
        if (!modsDir.exists() || !modsDir.isDirectory) return ModFileSelection(emptyList(), emptySet())
        val modFiles = modsDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .toList()
        return selectPreferredModFiles(modFiles)
    }

    private fun selectPreferredModFiles(files: List<File>): ModFileSelection {
        if (files.size <= 1) return ModFileSelection(files, emptySet())
        val selected = files
            .groupBy(::embeddedModIdentityKey)
            .values
            .map { grouped -> grouped.maxWithOrNull(::compareEmbeddedModFileVersion) ?: grouped.first() }
        val selectedFiles = selected.toSet()
        return ModFileSelection(
            selected = selected,
            discarded = files.filterNot { it in selectedFiles }.toSet()
        )
    }

    private data class EmbeddedModConfigInfo(
        val modId: String?,
        val version: String?
    )

    private fun embeddedModIdentityKey(file: File): String {
        val config = readPrimaryModConfig(file)
        val modId = config?.modId
        if (!modId.isNullOrBlank()) return "modid:$modId"
        val lowerName = file.nameWithoutExtension.lowercase()
        val slug = lowerName.substringBeforeVersionSuffix()
        if (slug.isNotBlank()) return "slug:$slug"
        return "file:$lowerName"
    }

    private fun compareEmbeddedModFileVersion(left: File, right: File): Int {
        val leftVersion = readEmbeddedModVersion(left)
        val rightVersion = readEmbeddedModVersion(right)
        val versionCompare = compareLooseVersionStrings(leftVersion, rightVersion)
        if (versionCompare != 0) return versionCompare
        return left.nameWithoutExtension.compareTo(right.nameWithoutExtension, ignoreCase = true)
    }

    private fun readEmbeddedModVersion(file: File): String {
        val configVersion = readPrimaryModConfig(file)?.version
        if (!configVersion.isNullOrBlank()) return configVersion.trim()
        return extractVersionFromFileName(file.nameWithoutExtension)
    }

    private fun readPrimaryModConfig(file: File): EmbeddedModConfigInfo? = runCatching {
        ModService.run {
            JarFile(file).use { jar ->
                jar.readModMeta()?.let { meta ->
                    EmbeddedModConfigInfo(
                        modId = meta.primaryModId?.trim()?.lowercase()?.ifBlank { null },
                        version = meta.version?.trim()?.ifBlank { null }
                    )
                }
            }
        }
    }.getOrNull()

    private fun collectServerPackExtraFiles(
        rootDir: File,
        matchedModFiles: Set<File>
    ): ServerExtraFileSelection {
        val canonicalMatchedFiles = matchedModFiles.mapTo(mutableSetOf()) { it.canonicalFile }
        var containsExcludedMcaFiles = false
        val files = rootDir.walkTopDown()
            .onEnter { dir -> !shouldSkipServerExtraDir(rootDir, dir) }
            .filter { it.isFile }
            .filterNot { it.canonicalFile in canonicalMatchedFiles }
            .mapNotNull { file ->
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                if (relativePath.isBlank()) return@mapNotNull null
                if (shouldSkipServerExtraFile(relativePath, file)) {
                    if (shouldExcludeMca(relativePath, isDirectory = false)) {
                        containsExcludedMcaFiles = true
                    }
                    return@mapNotNull null
                }
                if (containsExcludedMcaArchiveEntry(file)) {
                    containsExcludedMcaFiles = true
                }
                ServerExtraFile(
                    sourceFile = file,
                    relativePath = relativePath
                )
            }
            .toList()
        return ServerExtraFileSelection(files, containsExcludedMcaFiles)
    }

    private fun shouldSkipServerExtraDir(rootDir: File, dir: File): Boolean {
        if (dir == rootDir) return false
        val relativePath = dir.relativeTo(rootDir).invariantSeparatorsPath.lowercase()
        val name = dir.name.lowercase()
        if (name == "cache" || name == "logs" || name == "crash-reports") return true
        if (relativePath.startsWith("libraries/")) return true
        val childDirNames = dir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.map { it.name.lowercase() }
            ?.toSet()
            .orEmpty()
        return setOf("bin", "lib", "jmods").all { it in childDirNames }
    }

    private fun shouldSkipServerExtraFile(relativePath: String, file: File): Boolean {
        if (isDisabledFile(relativePath, isDirectory = false)) return true
        if (shouldExcludeMca(relativePath, isDirectory = false)) return true
        val relativeLower = relativePath.lowercase()
        val fileNameLower = file.name.lowercase()
        if (relativeLower.startsWith("libraries/")) return true
        if (relativeLower.startsWith("logs/") || relativeLower.startsWith("crash-reports/")) return true
        if (fileNameLower == ".ds_store" || fileNameLower == "desktop.ini") return true
        if (file.extension.equals("db", ignoreCase = true)) return true
        if (file.extension.lowercase() in serverExtraMediaExtensions) return true
        val isInsideMods = relativeLower.startsWith("mods/")
        if (!isInsideMods && file.extension.equals("jar", ignoreCase = true)) return true
        if (!isInsideMods && file.extension.equals("exe", ignoreCase = true)) return true
        if (!isInsideMods && file.extension.isBlank()) return true
        return false
    }

    private fun isDisabledFile(path: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        return path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
            .equals("disabled", ignoreCase = true)
    }

    private fun containsExcludedMcaFiles(rootDir: File): Boolean {
        return rootDir.walkTopDown()
            .filter { it.isFile }
            .any { file ->
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath
                if (shouldExcludeMca(relativePath, isDirectory = false)) return@any true
                val relativeLower = relativePath.lowercase()
                if (shouldSkipEntry(relativeLower, isDirectory = false)) return@any false
                containsExcludedMcaArchiveEntry(file)
            }
    }

    private fun containsExcludedMcaArchiveEntry(file: File): Boolean {
        if (!file.extension.equals("zip", ignoreCase = true) &&
            !file.extension.equals("jar", ignoreCase = true)
        ) return false
        return file.openChineseZip().use { zip ->
            zip.entries().asSequence().any { entry ->
                shouldExcludeMca(entry.name, entry.isDirectory)
            }
        }
    }

    private fun String.substringBeforeVersionSuffix(): String {
        val match = Regex("""^(.*?)(?:[-_.]?\d.*)$""").matchEntire(this)
        return match?.groupValues?.getOrNull(1)?.trim('-', '_', '.')?.ifBlank { this } ?: this
    }

    private fun extractVersionFromFileName(nameWithoutExtension: String): String {
        val match = Regex("""(?:^|[-_.])(\d[\w.\-+]*)$""").find(nameWithoutExtension)
        return match?.groupValues?.getOrNull(1)?.trim()?.ifBlank { "0" } ?: "0"
    }

    private fun compareLooseVersionStrings(left: String, right: String): Int {
        if (left == right) return 0
        val leftTokens = left.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
        val rightTokens = right.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
        val maxSize = maxOf(leftTokens.size, rightTokens.size)
        for (index in 0 until maxSize) {
            val leftToken = leftTokens.getOrNull(index)
            val rightToken = rightTokens.getOrNull(index)
            if (leftToken == rightToken) continue
            if (leftToken == null) return -1
            if (rightToken == null) return 1
            val leftNumber = leftToken.toLongOrNull()
            val rightNumber = rightToken.toLongOrNull()
            val cmp = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> 1
                rightNumber != null -> -1
                else -> leftToken.compareTo(rightToken)
            }
            if (cmp != 0) return cmp
        }
        return left.compareTo(right)
    }

    private val serverExtraMediaExtensions = setOf(
        "ogg",
        "wav",
        "mp3",
        "flac",
        "aac",
        "m4a",
        "opus",
        "wma",
        "mp4",
        "mov",
        "avi",
        "mkv",
        "webm",
        "m4v",
        "mpeg",
        "mpg",
        "flv",
        "wmv",
        "png",
        "jpg",
        "jpeg",
        "webp",
        "gif",
        "bmp",
        "tif",
        "tiff",
        "avif",
        "ico",
        "svg",
        "psd",
    )

    private fun hasOverridesDir(rootDir: File): Boolean {
        return rootDir.walkTopDown().any { it.isDirectory && it.name.equals("overrides", ignoreCase = true) }
    }

    private fun hasOverridesDir(entryNames: List<String>, packType: PackType): Boolean {
        if (packType == PackType.UNKNOWN) return false
        val metaFileName = when (packType) {
            PackType.MODRINTH -> "modrinth.index.json"
            PackType.CURSEFORGE -> "manifest.json"
            PackType.UNKNOWN -> return false
        }
        val metaPath = entryNames.firstOrNull {
            it.replace('\\', '/').trimStart('/').substringAfterLast('/') == metaFileName
        } ?: return false
        val rootPrefix = metaPath.replace('\\', '/')
            .trimStart('/')
            .substringBeforeLast('/', missingDelimiterValue = "")
            .let { if (it.isBlank()) "" else "$it/" }
        val overridesPath = rootPrefix + "overrides"
        val overridesPrefix = "$overridesPath/"
        return entryNames.any { rawName ->
            val normalized = rawName.replace('\\', '/').trimStart('/')
            normalized == overridesPath || normalized.startsWith(overridesPrefix)
        }
    }

    suspend fun buildUploadArchive(
        payload: UploadPayload,
        onProgress: LoadProgressConsumer = {}
    ): File = buildUploadArchive(
        rootDir = payload.sourceDir,
        baseName = payload.sourceName,
        serverExtraFiles = payload.serverExtraFiles,
        excludedClientExtras = payload.embeddedClientExtraSources,
        clientExtras = payload.clientExtras,
        onProgress = onProgress
    )

    suspend fun buildUploadArchive(
        rootDir: File,
        baseName: String,
        serverExtraFiles: List<ServerExtraFile> = emptyList(),
        excludedClientExtras: List<EmbeddedClientExtraSource> = emptyList(),
        onProgress: LoadProgressConsumer = {},
    ): File = buildUploadArchiveInternal(
        rootDir = rootDir,
        baseName = baseName,
        serverExtraFiles = serverExtraFiles,
        excludedClientExtras = excludedClientExtras,
        clientExtras = emptyList(),
        onProgress = onProgress,
    )

    suspend fun buildUploadArchive(
        rootDir: File,
        baseName: String,
        serverExtraFiles: List<ServerExtraFile> = emptyList(),
        excludedClientExtras: List<EmbeddedClientExtraSource> = emptyList(),
        clientExtras: List<Content>,
        onProgress: LoadProgressConsumer = {},
    ): File = buildUploadArchiveInternal(
        rootDir = rootDir,
        baseName = baseName,
        serverExtraFiles = serverExtraFiles,
        excludedClientExtras = excludedClientExtras,
        clientExtras = clientExtras,
        onProgress = onProgress,
    )

    private suspend fun buildUploadArchiveInternal(
        rootDir: File,
        baseName: String,
        serverExtraFiles: List<ServerExtraFile>,
        excludedClientExtras: List<EmbeddedClientExtraSource>,
        clientExtras: List<Content>,
        onProgress: LoadProgressConsumer,
    ): File {
        paths.workDir.mkdirs()
        val safeName = baseName.ifBlank { "modpack" }
        val target = paths.workDir.resolve("${safeName}_${System.currentTimeMillis()}.tar.zst")
        val oggWorkDir = Files.createTempDirectory(paths.workDir.toPath(), "ogg-batch-").toFile()
        try {
            val walkEntries = rootDir.walkTopDown().toList()
            val fileEntries = walkEntries.filter { it != rootDir }
            val oversizedUnpackedResourcepacks = findOversizedUnpackedResourcepacks(rootDir)
            val effectiveLocalExtras = collectEffectiveLocalExtraCandidates(rootDir)
            val effectiveSources = effectiveLocalExtras.mapTo(mutableSetOf()) { it.sourceRelativePath }
            val overrideShaderConfigs = fileEntries.asSequence().filter(File::isFile)
                .map { it.relativeTo(rootDir).invariantSeparatorsPath.lowercase() }
                .filter { it.startsWith("overrides/shaderpacks/") && it.endsWith(".zip.txt") }
                .map { it.removePrefix("overrides/") }
                .toSet()
            val shadowedLocalSources = collectAllLocalExtraCandidates(rootDir)
                .map { it.sourceRelativePath }
                .filterNot { it in effectiveSources }
                .toSet() + fileEntries.mapNotNull { file ->
                    val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                    relative.takeIf {
                        file.isFile && it.lowercase() in overrideShaderConfigs
                    }
                }
            val excludedSourcePaths = excludedClientExtras.map { it.sourceRelativePath }.toSet() + shadowedLocalSources
            verifyMatchedExtraSources(rootDir, excludedClientExtras, "预处理前")
            val retainedShaderConfigPaths = (
                clientExtras.filter { it.type == ContentType.ShaderPack }
                    .map { "${it.targetRelativePath}.txt" } +
                    excludedClientExtras.filter { it.content.type == ContentType.ShaderPack }
                        .map { "${it.sourceRelativePath}.txt" }
                ).toSet()
            onProgress.phase("正在预处理整合包资源文件")
            val processedAssets = preprocessAssetInputsInParallel(
                inputs = walkEntries.asSequence()
                    .filter { it.isFile }
                    .mapNotNull { file ->
                        val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                        if (relative.isBlank()) return@mapNotNull null
                        val relativeLower = relative.lowercase()
                        if (!shouldPreprocessAsset(relativeLower)) return@mapNotNull null
                        if (isExcludedBeforePreprocess(relative, excludedSourcePaths, oversizedUnpackedResourcepacks)) {
                            return@mapNotNull null
                        }
                        AssetProcessInput(relative, relativeLower, file.readBytes())
                    }
                    .toList(),
                oggWorkDir = oggWorkDir,
                onProgress = { done, total, currentPath ->
                    val fraction = if (total <= 0) 1f else done.toFloat() / total.toFloat()
                    onProgress(
                        LoadProgress.Percent(
                            "正在预处理资源文件${displayProgressFileName(currentPath)}($done/$total) ${formatPercent(fraction)}",
                            fraction * 0.5f
                        )
                    )
                }
            )
            val addedDirs = mutableSetOf<String>()
            TarZstArchiveWriter(target).use { out ->
                val totalWriteEntries = (
                    fileEntries.size +
                        serverExtraFiles.size
                    ).coerceAtLeast(1)
                var writtenEntries = 0
                for (file in fileEntries) {
                    if (file == rootDir) continue
                    val relative = file.relativeTo(rootDir).invariantSeparatorsPath
                    if (relative.isBlank()) continue
                    val matchedExtra = excludedClientExtras.firstOrNull {
                        matchesSourcePath(it.sourceRelativePath, relative)
                    }
                    if (matchedExtra != null) {
                        continue
                    }
                    if (shadowedLocalSources.any { relative == it || relative.startsWith("$it/") }) continue
                    if (oversizedUnpackedResourcepacks.any { relative == it || relative.startsWith("$it/") }) {
                        continue
                    }
                    if (isResourcepackZipPath(relative) && file.isFile && file.length() > RESOURCEPACK_MAX_SIZE_BYTES) {
                        continue
                    }
                    val relativeLower = relative.lowercase()
                    val topLevel = relative.substringBefore('/', relative)
                    writeProcessedEntry(
                        relative = relative,
                        relativeLower = relativeLower,
                        isDirectory = file.isDirectory,
                        lastModified = file.lastModified(),
                        topLevel = topLevel,
                        out = out,
                        addedDirs = addedDirs,
                        readAllBytes = { file.readBytes() },
                        resourcepackBytes = { readResourcepackFile(file, relativeLower, processedAssets[relative]) },
                        nestedZipBytes = if (relativeLower.endsWith(".zip") || relativeLower.endsWith(".jar")) {
                            {
                                processNestedZip(
                                    file,
                                    preserveResourcepackEntries = topLevel.equals("resourcepacks", ignoreCase = true)
                                )
                            }
                        } else null,
                        preprocessedBytes = processedAssets[relative],
                        retainedShaderConfigPaths = retainedShaderConfigPaths,
                    )
                    writtenEntries++
                    val fraction = writtenEntries.toFloat() / totalWriteEntries.toFloat()
                    onProgress(
                        LoadProgress.Percent(
                            "正在写入整合包文件${displayProgressFileName(relative)}($writtenEntries/$totalWriteEntries) ${formatPercent(fraction)}",
                            0.5f + fraction * 0.5f
                        )
                    )
                }
                for (extraFile in serverExtraFiles) {
                    val relative = "server/${extraFile.relativePath.trimStart('/')}"
                    val sourceFile = extraFile.sourceFile
                    if (!sourceFile.exists() || !sourceFile.isFile) {
                        throw ModpackError("服务端额外文件不存在: ${sourceFile.absolutePath}")
                    }
                    if (isDisabledFile(relative, isDirectory = false) ||
                        isDisabledFile(sourceFile.name, isDirectory = false)
                    ) continue
                    if (shouldExcludeMca(relative, isDirectory = false)) continue
                    ensureArchiveParents(relative, out, addedDirs)
                    val bytes = if (
                        sourceFile.extension.equals("zip", ignoreCase = true) ||
                        sourceFile.extension.equals("jar", ignoreCase = true)
                    ) {
                        processNestedZip(sourceFile, mcaOnly = true)
                    } else {
                        sourceFile.readBytes()
                    }
                    out.addFile(relative, bytes, sourceFile.lastModified())
                    writtenEntries++
                    val fraction = writtenEntries.toFloat() / totalWriteEntries.toFloat()
                    onProgress(
                        LoadProgress.Percent(
                            "正在写入整合包文件${displayProgressFileName(relative)}($writtenEntries/$totalWriteEntries) ${formatPercent(fraction)}",
                            0.5f + fraction * 0.5f
                        )
                    )
                }
            }
            verifyMatchedExtraSources(rootDir, excludedClientExtras, "打包完成前")
            onProgress(LoadProgress.Percent("整合包打包完成", 1f))
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(target.toPath()) }
                .onFailure { cleanup -> lgr.warn(cleanup) { "无法清理失败的整合包暂存: $target" } }
            throw error
        } finally {
            runCatching { oggWorkDir.deleteRecursivelyNoSymlink() }
        }
        return target
    }

    private fun verifyMatchedExtraSources(
        rootDir: File,
        sources: List<EmbeddedClientExtraSource>,
        phase: String,
    ) {
        sources.forEach { matchedExtra ->
            val sourcePath = resolveMatchedSourceFile(rootDir, matchedExtra.sourceRelativePath)
            require(sourcePath.isFile) {
                "已匹配客户端额外内容不存在（${phase}）: ${matchedExtra.sourceRelativePath}"
            }
            require(sourcePath.sha1.equals(matchedExtra.verifiedSha1, ignoreCase = true)) {
                "客户端资源文件在${phase}发生变化: ${matchedExtra.sourceRelativePath}"
            }
        }
    }

    private fun resolveMatchedSourceFile(rootDir: File, sourceRelativePath: String): File {
        val normalized = sourceRelativePath.replace('\\', '/')
        require(
            normalized.isNotBlank() &&
                !normalized.startsWith('/') &&
                !Regex("^[A-Za-z]:").containsMatchIn(normalized) &&
                normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
        ) {
            "客户端额外内容源路径无效: $sourceRelativePath"
        }
        val rootPath = rootDir.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            "客户端额外内容源根目录无效: ${rootDir.absolutePath}"
        }
        var current = rootPath
        normalized.split('/').forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) {
                "客户端额外内容源路径不能包含软链接: $sourceRelativePath"
            }
        }
        val exact = current.normalize().toFile()
        require(exact.toPath().startsWith(rootPath)) {
            "客户端额外内容源路径越界: $sourceRelativePath"
        }
        require(Files.isRegularFile(exact.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "客户端额外内容源文件不存在: $sourceRelativePath"
        }
        return exact
    }

    private fun matchesSourcePath(sourceRelativePath: String, archiveRelativePath: String): Boolean {
        val source = sourceRelativePath.replace('\\', '/')
        val archive = archiveRelativePath.replace('\\', '/')
        return source == archive
    }

    private fun isExcludedBeforePreprocess(
        relative: String,
        excludedSourcePaths: Set<String>,
        oversizedUnpackedResourcepacks: Set<String>,
    ): Boolean {
        if (excludedSourcePaths.any { relative == it || relative.startsWith("$it/") }) return true
        if (oversizedUnpackedResourcepacks.any { relative == it || relative.startsWith("$it/") }) return true
        val normalized = relative.replace('\\', '/').trimStart('/').removePrefix("overrides/")
        return normalized.startsWith("shaderpacks/", ignoreCase = true)
    }

    private suspend fun processNestedZip(
        zipFile: File,
        preserveResourcepackEntries: Boolean = false,
        mcaOnly: Boolean = false
    ): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { out ->
                val addedDirs = mutableSetOf<String>()
                zipFile.openChineseZip().use { zip ->
                    val oggWorkDir = Files.createTempDirectory(paths.workDir.toPath(), "ogg-nested-").toFile()
                    try {
                        val entries = zip.entries().asSequence().toList()
                        val processedAssets = if (preserveResourcepackEntries || mcaOnly) {
                            emptyMap()
                        } else {
                            preprocessAssetInputsInParallel(
                                inputs = entries.asSequence()
                                    .filter { !it.isDirectory }
                                    .mapNotNull { entry ->
                                        val relative = entry.name.replace('\\', '/').trimStart('/')
                                        if (relative.isBlank()) return@mapNotNull null
                                        val relativeLower = relative.lowercase()
                                        if (!shouldPreprocessAsset(relativeLower)) return@mapNotNull null
                                        AssetProcessInput(
                                            relative,
                                            relativeLower,
                                            zip.getInputStream(entry).use { it.readBytes() }
                                        )
                                    }
                                    .toList(),
                                oggWorkDir = oggWorkDir
                            )
                        }
                        for (entry in entries) {
                            val relative = entry.name.replace('\\', '/').trimStart('/')
                            if (relative.isBlank()) continue
                            val relativeLower = relative.lowercase()
                            val topLevel = relative.substringBefore('/', relative)
                            val isNestedJarEntry = zipFile.extension.equals("jar", ignoreCase = true)
                            writeProcessedNestedZipEntry(
                                relative = relative,
                                relativeLower = relativeLower,
                                isDirectory = entry.isDirectory,
                                lastModified = entry.time,
                                topLevel = topLevel,
                                out = out,
                                addedDirs = addedDirs,
                                readAllBytes = { zip.getInputStream(entry).use { it.readBytes() } },
                                resourcepackBytes = {
                                    readResourcepackEntry(zip, entry, relativeLower, processedAssets[relative])
                                },
                                nestedZipBytes = null,
                                skipCacheDirectory = !isNestedJarEntry,
                                preprocessedBytes = processedAssets[relative],
                                preserveResourcepackEntries = preserveResourcepackEntries,
                                mcaOnly = mcaOnly
                            )
                        }
                    } finally {
                        runCatching { oggWorkDir.deleteRecursivelyNoSymlink() }
                    }
                }
            }
            baos.toByteArray()
        }
    }

    private fun shouldSkipEntry(
        rawRelativeLower: String,
        isDirectory: Boolean,
        skipCacheDirectory: Boolean = true,
        retainedShaderConfigPaths: Set<String> = emptySet(),
    ): Boolean {
        val relativeLower = rawRelativeLower.replace("overrides/", "")
        val retainedConfig = retainedShaderConfigPaths.any {
            relativeLower == it.lowercase().replace('\\', '/').removePrefix("overrides/")
        }
        if (disallowedClientPathPrefixes.any { relativeLower.startsWith(it) } && !retainedConfig) return true
        if (skipCacheDirectory && containsCacheDirectory(relativeLower)) return true
        if (relativeLower.startsWith("config/") && relativeLower.removePrefix("config/").isExcludedConfigPath()) return true
        if (disallowedClientPathKeywords.any { relativeLower.contains(it) }) return true
        if (relativeLower.endsWith(".mp4") || relativeLower.endsWith(".mov")) return true
        if (shouldExcludeMca(rawRelativeLower, isDirectory)) return true
        if (isQuestLangEntryDisallowed(relativeLower, isDirectory)) return true
        return false
    }

    private fun isResourcepackZipPath(relative: String): Boolean {
        val normalized = relative.replace('\\', '/').trimStart('/').removePrefix("overrides/")
        if (!normalized.startsWith("resourcepacks/", ignoreCase = true)) return false
        val packName = normalized.removePrefix("resourcepacks/").substringBefore('/')
        return packName.endsWith(".zip", ignoreCase = true)
    }

    private fun findOversizedUnpackedResourcepacks(rootDir: File): Set<String> {
        val roots = listOf(
            rootDir.resolve("resourcepacks"),
            rootDir.resolve("overrides/resourcepacks"),
        )
        return roots.flatMap { resourceRoot ->
            if (!resourceRoot.isDirectory) return@flatMap emptyList()
            resourceRoot.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { packDir ->
                val total = packDir.walkTopDown()
                    .filter { it.isFile }
                    .sumOf { it.length() }
                if (total > RESOURCEPACK_MAX_SIZE_BYTES) {
                    packDir.relativeTo(rootDir).invariantSeparatorsPath
                } else null
            }
        }.toSet()
    }

    private fun shouldExcludeMca(path: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        val normalized = path.replace('\\', '/').trim('/')
        if (normalized.isBlank() ||
            !normalized.substringAfterLast('/').endsWith(".mca", ignoreCase = true)
        ) return false
        return normalized.split('/').none { it.equals("ftbteambases", ignoreCase = true) }
    }

    private fun containsCacheDirectory(relativeLower: String): Boolean {
        val normalized = relativeLower.replace('\\', '/').trim('/')
        if (normalized.isEmpty()) return false
        return normalized.split('/').any { it.equals("cache", ignoreCase = true) }
    }

    private suspend fun writeProcessedEntry(
        relative: String,
        relativeLower: String,
        isDirectory: Boolean,
        lastModified: Long,
        topLevel: String,
        out: TarZstArchiveWriter,
        addedDirs: MutableSet<String>,
        readAllBytes: () -> ByteArray,
        resourcepackBytes: (suspend () -> ByteArray?)?,
        nestedZipBytes: (suspend () -> ByteArray)?,
        skipCacheDirectory: Boolean = true,
        preprocessedBytes: ByteArray? = null,
        retainedShaderConfigPaths: Set<String> = emptySet(),
    ) {
        if (isDisabledFile(relative, isDirectory)) return
        if (shouldSkipEntry(
                relativeLower,
                isDirectory,
                skipCacheDirectory = skipCacheDirectory,
                retainedShaderConfigPaths = retainedShaderConfigPaths,
            )) return

        if (isDirectory) {
            addDirectoryEntry(relative, out, addedDirs)
            return
        }

        val effectiveRelativeLower = relativeLower.removePrefix("overrides/")
        if (effectiveRelativeLower == "resourcepacks" ||
            effectiveRelativeLower.startsWith("resourcepacks/", ignoreCase = true)
        ) {
            val bytes = nestedZipBytes?.invoke() ?: resourcepackBytes?.invoke() ?: return
            if (nestedZipBytes != null && bytes.size > RESOURCEPACK_MAX_SIZE_BYTES) return
            ensureArchiveParents(relative, out, addedDirs)
            out.addFile(relative, bytes, lastModified)
            return
        }

        ensureArchiveParents(relative, out, addedDirs)
        val bytes = when {
            nestedZipBytes != null -> nestedZipBytes()
            relativeLower.endsWith(".png") -> preprocessedBytes ?: processUploadPng(readAllBytes(), relative)
            relativeLower.endsWith(".ogg") -> preprocessedBytes ?: processOggBytes(readAllBytes(), relative)
            relativeLower.endsWith(".mp3") -> preprocessedBytes ?: emptyMp3Bytes
            else -> readAllBytes()
        }
        out.addFile(relative, bytes, lastModified)
    }

    private suspend fun writeProcessedNestedZipEntry(
        relative: String,
        relativeLower: String,
        isDirectory: Boolean,
        lastModified: Long,
        topLevel: String,
        out: ZipOutputStream,
        addedDirs: MutableSet<String>,
        readAllBytes: () -> ByteArray,
        resourcepackBytes: (suspend () -> ByteArray?)?,
        nestedZipBytes: (suspend () -> ByteArray)?,
        skipCacheDirectory: Boolean = true,
        preprocessedBytes: ByteArray? = null,
        preserveResourcepackEntries: Boolean = false,
        mcaOnly: Boolean = false
    ) {
        if (shouldExcludeMca(relative, isDirectory)) return
        if (mcaOnly) {
            if (isDirectory) {
                addZipDirectoryEntry(relative, out, addedDirs)
                return
            }
            ensureZipParents(relative, out, addedDirs)
            val zipEntry = ZipEntry(relative).apply { time = lastModified }
            out.putNextEntry(zipEntry)
            out.write(readAllBytes())
            out.closeEntry()
            return
        }
        if (!preserveResourcepackEntries &&
            shouldSkipEntry(relativeLower, isDirectory, skipCacheDirectory = skipCacheDirectory)
        ) return

        if (isDirectory) {
            addZipDirectoryEntry(relative, out, addedDirs)
            return
        }

        val bytes = if (preserveResourcepackEntries) {
            if (relativeLower.endsWith(".mp3")) emptyMp3Bytes else readAllBytes()
        } else if (topLevel == "resourcepacks") {
            resourcepackBytes?.invoke() ?: return
        } else {
            when {
                nestedZipBytes != null -> nestedZipBytes()
                relativeLower.endsWith(".png") -> preprocessedBytes ?: processUploadPng(readAllBytes(), relative)
                relativeLower.endsWith(".ogg") -> preprocessedBytes ?: processOggBytes(readAllBytes(), relative)
                relativeLower.endsWith(".mp3") -> preprocessedBytes ?: emptyMp3Bytes
                else -> readAllBytes()
            }
        }
        ensureZipParents(relative, out, addedDirs)
        val zipEntry = ZipEntry(relative).apply { time = lastModified }
        out.putNextEntry(zipEntry)
        out.write(bytes)
        out.closeEntry()
    }

    private suspend fun readResourcepackEntry(
        source: java.util.zip.ZipFile,
        entry: ZipEntry,
        relativeLower: String,
        preprocessedBytes: ByteArray? = null
    ): ByteArray? {
        val isOgg = relativeLower.endsWith(".ogg")
        if (relativeLower.endsWith(".mp3")) return emptyMp3Bytes
        if (!isOgg && entry.size != -1L && entry.size > RESOURCEPACK_MAX_SIZE_BYTES) {
            return null
        }
        val processed = if (preprocessedBytes != null) {
            preprocessedBytes
        } else {
            val rawBytes = source.getInputStream(entry).use { input ->
                when {
                    entry.size == -1L -> input.readBytes()
                    entry.size > Int.MAX_VALUE -> return null
                    !isOgg && entry.size > RESOURCEPACK_MAX_SIZE_BYTES -> return null
                    else -> input.readNBytes(entry.size.toInt())
                }
            }
            when {
                isOgg -> processOggBytes(rawBytes, entry.name)
                relativeLower.endsWith(".png") -> processUploadPng(rawBytes, entry.name)
                relativeLower.endsWith(".mp3") -> emptyMp3Bytes
                else -> rawBytes
            }
        }
        if (processed.size > RESOURCEPACK_MAX_SIZE_BYTES) return null
        return processed
    }

    private val disallowedClientPathPrefixes = setOf(
        "config/fancymenu/",
        //有材质 "packmenu",
        "shaderpacks/",
        "kubejs/probe/"
    )
    private val disallowedClientPathKeywords = setOf(
        "yes_steve_model",
        "史蒂夫模型",
        "touhou_little_maid"
    )
    private val allowedQuestLangFiles = setOf("en_us.snbt", "zh_cn.snbt")
    private  val QUEST_LANG_PREFIX = "config/ftbquests/quests/lang/"
    private val RESOURCEPACK_MAX_SIZE_BYTES = 5L * 1024L * 1024L
    private fun isQuestLangEntryDisallowed(relativeLower: String, isDirectory: Boolean): Boolean {
        if (!relativeLower.startsWith(QUEST_LANG_PREFIX)) return false
        val remainder = relativeLower.removePrefix(QUEST_LANG_PREFIX)
        if (remainder.isEmpty()) return false
        if (isDirectory) return true
        if (remainder.contains('/')) return true
        return remainder !in allowedQuestLangFiles
    }

    private suspend fun readResourcepackFile(file: File, relativeLower: String, preprocessedBytes: ByteArray? = null): ByteArray? {
        val isOgg = relativeLower.endsWith(".ogg")
        if (relativeLower.endsWith(".mp3")) return emptyMp3Bytes
        //未匹配资源包按原始大小限制为5MiB。
        if (!isOgg && file.length() > RESOURCEPACK_MAX_SIZE_BYTES) return null
        val processed = if (preprocessedBytes != null) {
            preprocessedBytes
        } else {
            val rawBytes = file.inputStream().use { input ->
                when {
                    file.length() > Int.MAX_VALUE -> return null
                    else -> input.readBytes()
                }
            }
            when {
                isOgg -> processOggBytes(rawBytes, file.name)
                relativeLower.endsWith(".png") -> processUploadPng(rawBytes, file.path)
                relativeLower.endsWith(".mp3") -> emptyMp3Bytes
                else -> rawBytes
            }
        }
        if (processed.size > RESOURCEPACK_MAX_SIZE_BYTES) return null
        return processed
    }

    private fun shouldPreprocessAsset(relativeLower: String): Boolean {
        return relativeLower.endsWith(".png") ||
            relativeLower.endsWith(".ogg")
    }

    private suspend fun preprocessAssetInputsInParallel(
        inputs: List<AssetProcessInput>,
        oggWorkDir: File,
        onProgress: (done: Int, total: Int, currentPath: String) -> Unit = { _, _, _ -> }
    ): Map<String, ByteArray> {
        if (inputs.isEmpty()) return emptyMap()
        val semaphore = Semaphore(8)
        val doneCount = AtomicInteger(0)
        val total = inputs.size
        return coroutineScope {
            inputs.map { input ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val processed = when {
                            input.relativeLower.endsWith(".png") -> processUploadPng(input.rawBytes, input.relativePath)
                            input.relativeLower.endsWith(".ogg") -> processOggBytes(input.rawBytes, input.relativePath, oggWorkDir)
                            input.relativeLower.endsWith(".mp3") -> emptyMp3Bytes
                            else -> input.rawBytes
                        }
                        onProgress(doneCount.incrementAndGet(), total, input.relativePath)
                        input.relativePath to processed
                    }
                }
            }.awaitAll().toMap()
        }
    }

    private val emptyOggBytes: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            openBundledResource("assets/empty.ogg").use { it.readBytes() }
        }.getOrElse { error ->
            lgr.error(error) { "无法读取RDI空音频资源assets/empty.ogg" }
            throw ModpackError("音频处理模块损坏，请更新客户端", error)
        }
    }

    private val emptyMp3Bytes: ByteArray by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            openBundledResource("assets/empty.mp3").use { it.readBytes() }
        }.getOrElse { error ->
            lgr.error(error) { "无法读取RDI空音频资源assets/empty.mp3" }
            throw ModpackError("音频处理模块损坏，请更新客户端", error)
        }
    }

    private suspend fun processOggBytes(
        rawBytes: ByteArray,
        entryName: String,
        workDir: File = paths.workDir
    ): ByteArray {
        val result = OggTranscoder.transcodeOgg(rawBytes, workDir.toPath())
        return result.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is OggTranscodeResult.Encoded -> outcome.bytes
                    is OggTranscodeResult.TooLong -> {
                        lgr.warn {
                            "OGG超过10秒，已替换为空音频: $entryName (${outcome.durationMicros}微秒)"
                        }
                        emptyOggBytes
                    }
                }
            },
            onFailure = { error ->
                if (error is MediaProcUnavailableException) {
                    throw ModpackError("音频处理模块损坏，请更新客户端", error)
                }
                lgr.error(error) { "OGG处理失败，已替换为空音频: $entryName" }
                emptyOggBytes
            }
        )
    }

    suspend fun ensureAudioReady() {
        paths.workDir.mkdirs()
        OggTranscoder.ensureReady().getOrElse { error ->
            throw ModpackError("音频处理模块损坏，请更新客户端", error)
        }
        Files.createDirectories(paths.workDir.toPath())
        val workDir = Files.createTempDirectory(paths.workDir.toPath(), "ogg-ready-").toFile()
        try {
            val input = openBundledResource("assets/empty.ogg").use { it.readBytes() }
            val result = OggTranscoder.transcodeOgg(input, workDir.toPath()).getOrElse { error ->
                if (error is MediaProcUnavailableException) {
                    throw ModpackError("音频处理模块损坏，请更新客户端", error)
                }
                throw ModpackError("音频处理模块初始化失败，请重试", error)
            }
            val encoded = when (result) {
                is OggTranscodeResult.Encoded -> result.bytes
                is OggTranscodeResult.TooLong -> throw ModpackError("音频处理模块初始化失败，请重试")
            }
            val codec = BufferedInputStream(encoded.inputStream()).use { stream ->
                OggCodecDetector.detect(stream).getOrElse { error ->
                    throw ModpackError("音频处理模块初始化失败，请重试", error)
                }
            }
            check(codec == OggAudioCodec.OPUS) { "Upload audio canary did not produce Opus" }
        } finally {
            runCatching { workDir.deleteRecursivelyNoSymlink() }
        }
    }

    private fun addDirectoryEntry(rawPath: String, output: TarZstArchiveWriter, addedDirs: MutableSet<String>) {
        val sanitized = rawPath.trim('/').ifEmpty { return }
        ensureArchiveParents(sanitized, output, addedDirs)
        val dirEntry = "$sanitized/"
        if (addedDirs.add(dirEntry)) output.addDirectory(sanitized)
    }

    private fun addZipDirectoryEntry(rawPath: String, output: ZipOutputStream, addedDirs: MutableSet<String>) {
        val sanitized = rawPath.trim('/').ifEmpty { return }
        ensureZipParents(sanitized, output, addedDirs)
        val dirEntry = "$sanitized/"
        if (!addedDirs.add(dirEntry)) return
        output.putNextEntry(ZipEntry(dirEntry))
        output.closeEntry()
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
            if (addedDirs.add(dirEntry)) output.addDirectory(current)
        }
    }

    private fun ensureZipParents(path: String, output: ZipOutputStream, addedDirs: MutableSet<String>) {
        val normalized = path.trim('/').ifEmpty { return }
        val parts = normalized.split('/')
        if (parts.size <= 1) return
        var current = ""
        for (i in 0 until parts.size - 1) {
            val part = parts[i]
            if (part.isEmpty()) continue
            current = if (current.isEmpty()) part else "$current/$part"
            val dirEntry = "$current/"
            if (!addedDirs.add(dirEntry)) continue
            output.putNextEntry(ZipEntry(dirEntry))
            output.closeEntry()
        }
    }

}

private enum class PackType { MODRINTH, CURSEFORGE, UNKNOWN }
