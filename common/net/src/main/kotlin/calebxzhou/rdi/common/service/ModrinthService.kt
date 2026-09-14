package calebxzhou.rdi.common.service

import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType
import calebxzau.rdi.common.model.validateAndMergeClientExtras
import calebxzau.rdi.common.model.validateClientExtra
import calebxzhou.rdi.common.util.openChineseZip
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.net.json
import calebxzhou.rdi.common.net.ktorClient
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.service.ModService.buildIconUrls
import calebxzhou.rdi.common.service.ModService.modLogo
import calebxzhou.rdi.common.service.ModService.ofMirrorUrl
import calebxzhou.rdi.common.service.ModService.readModMeta
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import java.io.File
import java.util.jar.JarFile

object ModrinthService {
    private val lgr by Loggers
    const val OFFICIAL_URL = "https://api.modrinth.com/v2"
    const val V3_OFFICIAL_URL = "https://api.modrinth.com/v3"
    data class LoadedModpack(
        val index: ModrinthModpackIndex,
        val file: File,
        val mods: List<Mod>,
        val clientExtras: List<Content> = emptyList(),
        val mcVersion: McVersion,
        val modloader: ModLoader
    )

    internal data class ManifestResolution(
        val mods: List<Mod>,
        val clientExtras: List<Content>,
    )

    suspend fun loadModpack(
        modpackFile: File,
        resolveModrinthSlugs: suspend (Set<String>) -> Map<String, String> = { emptyMap() }
    ): Result<LoadedModpack> = runCatching {
        if (!modpackFile.exists()) {
            throw ModpackError("找不到整合包文件: ${modpackFile.path}")
        }
        val index = if (modpackFile.isDirectory) {
            if (!hasOverridesDir(modpackFile)) {
                throw ModpackError("整合包缺少目录：overrides")
            }
            val indexFile = modpackFile.walkTopDown()
                .firstOrNull { it.isFile && it.name == "modrinth.index.json" }
                ?: throw ModpackError("整合包缺少文件：modrinth.index.json")
            val indexJson = indexFile.readText(Charsets.UTF_8)
            runCatching {
                serdesJson.decodeFromString<ModrinthModpackIndex>(indexJson)
            }.getOrElse { err ->
                throw ModpackError("modrinth.index.json 解析失败: ${err.message}")
            }
        } else {
            modpackFile.openChineseZip().use { zip ->
                val indexEntry = zip.entries().asSequence().firstOrNull {
                    !it.isDirectory && it.name.substringAfterLast('/') == "modrinth.index.json"
                } ?: throw ModpackError("整合包缺少文件：modrinth.index.json")
                val rootPrefix = indexEntry.name.substringBeforeLast('/', missingDelimiterValue = "")
                    .let { if (it.isBlank()) "" else "$it/" }
                if (!hasOverridesDir(zip.entries().asSequence().toList().map { it.name }, rootPrefix)) {
                    throw ModpackError("整合包缺少目录：overrides")
                }
                val indexJson = zip.getInputStream(indexEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                runCatching {
                    serdesJson.decodeFromString<ModrinthModpackIndex>(indexJson)
                }.getOrElse { err ->
                    throw ModpackError("modrinth.index.json 解析失败: ${err.message}")
                }
            }
        }

        if (!index.game.equals("minecraft", ignoreCase = true)) {
            throw ModpackError("不支持的游戏类型: ${index.game}")
        }
        if (index.formatVersion <= 0) {
            throw ModpackError("不支持的整合包格式版本: ${index.formatVersion}")
        }
        val mcVersion = index.dependencies["minecraft"]?.trim().orEmpty()
        if (mcVersion.isBlank()) {
            throw ModpackError("整合包缺少MC版本")
        }
        val parsedMcVersion = McVersion.from(mcVersion)
        if (parsedMcVersion == null || !parsedMcVersion.enabled) {
            throw ModpackError("不支持的MC版本: $mcVersion")
        }
        val loaderKey = index.dependencies.keys.firstOrNull { ModLoader.from(it) != null } ?: throw ModpackError("不支持的Mod加载器: 未知")
        val parsedModloader = ModLoader.from(loaderKey) ?: throw ModpackError("不支持的Mod加载器: $loaderKey")
        val supportedEntries = index.files.map { entry ->
            entry to normalizeManifestPath(entry.path)
        }.filter { (_, path) -> manifestContentType(path) != null }
            .filter { (entry, path) ->
                manifestContentType(path) == ContentType.Mod ||
                    entry.env?.client != ModrinthModpackIndex.EnvSide.unsupported
            }
        val hashes = supportedEntries.map { it.first.hashes.sha1.trim().lowercase() }.distinct()
        val hashVersions = if (hashes.isEmpty()) emptyMap() else getVersionsFromHashes(hashes)
        val projectIds = hashVersions.values.map { it.projectId }.distinct()
        val projects = if (projectIds.isEmpty()) emptyMap() else getMultipleProjects(projectIds).associateBy { it.id }

        val unresolved = supportedEntries.filter { (entry, _) ->
            !versionContainsSha1(hashVersions[entry.hashes.sha1.trim().lowercase()], entry.hashes.sha1)
        }
        val cfFileIds = unresolved.mapNotNull { (entry, _) ->
            entry.downloads.firstNotNullOfOrNull(::parseCurseForgeFileId)
        }.distinct()
        val cfFiles = CurseForgeService.getModFilesInfo(cfFileIds).associateBy { it.id }
        val cfProjects = CurseForgeService.getModsInfoIncludingNonMods(cfFiles.values.map { it.modId }.distinct())
            .associateBy { it.id }
        val cfFallback = supportedEntries.associate { (entry, path) ->
            val sha1 = entry.hashes.sha1.trim().lowercase()
            val value = if (versionContainsSha1(hashVersions[sha1], sha1)) null else {
                val fileId = entry.downloads.firstNotNullOfOrNull(::parseCurseForgeFileId)
                    ?: throw unresolvedManifestEntry(entry.path)
                val file = cfFiles[fileId] ?: throw unresolvedManifestEntry(entry.path)
                val actualSha1 = file.hashes.firstOrNull { it.algo == 1 }?.value?.trim()?.lowercase()
                if (actualSha1 != sha1) throw ModpackError("客户端文件SHA-1不匹配: ${entry.path} (file ${file.id})")
                val project = cfProjects[file.modId] ?: throw unresolvedManifestEntry(entry.path)
                file to project
            }
            (entry to path) to value
        }
        val cfSlugs = cfFallback.values.filterNotNull().map { it.second.slug }.toSet()
        val cfSlugToMrSlug = resolveModrinthSlugs(cfSlugs)
        val mrCandidates = cfFallback.values.filterNotNull().flatMap { (file, project) ->
            listOfNotNull(cfSlugToMrSlug[project.slug], project.slug).filter { it.isNotBlank() }
        }.distinct()
        val mrProjectBySlug = if (mrCandidates.isEmpty()) emptyMap() else
            getMultipleProjects(mrCandidates).associateBy { it.slug.trim().lowercase() }
        val resolution = resolveManifestEntries(index, hashVersions, projects, cfFallback, cfSlugToMrSlug, mrProjectBySlug)

        LoadedModpack(
            index = index,
            file = modpackFile,
            mods = resolution.mods,
            clientExtras = resolution.clientExtras,
            mcVersion = parsedMcVersion,
            modloader = parsedModloader
        )
    }

    internal fun resolveManifestEntries(
        index: ModrinthModpackIndex,
        hashVersions: Map<String, ModrinthVersionInfo>,
        projects: Map<String, ModrinthProject>,
        cfFallback: Map<Pair<ModrinthModpackIndex.FileEntry, String>, Pair<CurseForgeFile, CurseForgeModInfo>?> = emptyMap(),
        cfSlugToMrSlug: Map<String, String> = emptyMap(),
        mrProjectsBySlug: Map<String, ModrinthProject> = emptyMap(),
    ): ManifestResolution {
        val mods = mutableListOf<Mod>()
        val extrasByPath = linkedMapOf<String, Content>()
        index.files.forEach { entry ->
            val path = normalizeManifestPath(entry.path)
            val type = manifestContentType(path) ?: return@forEach
            if (type != ContentType.Mod && entry.env?.client == ModrinthModpackIndex.EnvSide.unsupported) return@forEach
            val sha1 = entry.hashes.sha1.trim().lowercase()
            val version = hashVersions[sha1]
            val mrVersion = version?.takeIf { versionContainsSha1(it, sha1) }
            if (mrVersion != null) {
                val project = projects[mrVersion.projectId] ?: throw unresolvedManifestEntry(entry.path)
                val slug = project.slug.takeIf { it.isNotBlank() } ?: throw unresolvedManifestEntry(entry.path)
                if (type == ContentType.Mod) {
                    mods += Mod("mr", mrVersion.projectId, slug, mrVersion.id, sha1, project.toModSide(), entry.downloads)
                } else {
                    appendExtra(extrasByPath, Content(ContentPlatform.Modrinth, type, mrVersion.projectId, mrVersion.id,
                        slug, sha1, path, ContentSide.Client,
                        entry.env?.client != ModrinthModpackIndex.EnvSide.optional, entry.downloads).validateClientExtra())
                }
                return@forEach
            }
            val fallback = cfFallback[entry to path] ?: throw unresolvedManifestEntry(entry.path)
            val (file, project) = fallback ?: throw unresolvedManifestEntry(entry.path)
            val fileSha1 = file.hashes.firstOrNull { it.algo == 1 }?.value?.trim()?.lowercase()
            if (fileSha1 != sha1) throw ModpackError("客户端文件SHA-1不匹配: ${entry.path} (file ${file.id})")
            if (type == ContentType.Mod && project.classId != 6L) throw unresolvedManifestEntry(entry.path)
            val slug = project.slug.takeIf { it.isNotBlank() } ?: throw unresolvedManifestEntry(entry.path)
            val side = file.gameVersions.toCurseForgeModSide()
                ?: cfSlugToMrSlug[slug]?.let { mrProjectsBySlug[it.trim().lowercase()]?.toModSide() }
                ?: mrProjectsBySlug[slug.trim().lowercase()]?.toModSide()
                ?: Mod.Side.UNKNOWN
            if (type == ContentType.Mod) {
                mods += Mod("cf", project.id.toString(), slug, file.id.toString(), file.fileFingerprint.toString(), side, entry.downloads)
            } else {
                appendExtra(extrasByPath, Content(ContentPlatform.CurseForge, type, project.id.toString(), file.id.toString(), slug,
                    file.fileFingerprint.toString(), path, ContentSide.Client,
                    entry.env?.client != ModrinthModpackIndex.EnvSide.optional, entry.downloads).validateClientExtra())
            }
        }
        return ManifestResolution(mods, extrasByPath.values.toList().validateAndMergeClientExtras())
    }

    private fun appendExtra(target: MutableMap<String, Content>, content: Content) {
        val path = content.path ?: content.targetRelativePath
        val key = path.lowercase()
        val previous = target[key]
        if (previous == null) {
            target[key] = content
            return
        }
        val sameIdentity = previous.platform == content.platform &&
            previous.type == content.type && previous.projectId == content.projectId &&
            previous.fileId == content.fileId && previous.hash.equals(content.hash, ignoreCase = true)
        if (!sameIdentity) throw ModpackError("客户端资源安装路径冲突: $path")
        target[key] = previous.copy(
            required = previous.required || content.required,
            downloadUrls = (previous.downloadUrls + content.downloadUrls).distinct(),
        )
    }

    private fun unresolvedManifestEntry(path: String): ModpackError =
        ModpackError("无法解析整合包文件: $path")

    private fun versionContainsSha1(version: ModrinthVersionInfo?, sha1: String): Boolean =
        version?.files?.any { file ->
            file.hashes.any { (algorithm, value) ->
                algorithm.equals("sha1", ignoreCase = true) && value.equals(sha1, ignoreCase = true)
            }
        } == true

    internal fun normalizeManifestPath(rawPath: String): String {
        val original = rawPath.replace('\\', '/')
        if (original.isBlank() || original.startsWith('/') ||
            Regex("^[A-Za-z]:").containsMatchIn(original) || ':' in original || '\u0000' in original) {
            throw ModpackError("整合包路径必须是相对路径: $rawPath")
        }
        val segments = original.split('/')
        if (segments.any { it == ".." }) throw ModpackError("整合包路径包含非法上级目录: $rawPath")
        return segments.filter { it.isNotBlank() && it != "." }.joinToString("/")
    }

    internal fun manifestContentType(path: String): ContentType? = when {
        path.equals("resourcepacks", ignoreCase = true) || path.startsWith("resourcepacks/", ignoreCase = true) -> ContentType.ResPack
        path.equals("shaderpacks", ignoreCase = true) || path.startsWith("shaderpacks/", ignoreCase = true) -> ContentType.ShaderPack
        path.equals("mods", ignoreCase = true) || path.startsWith("mods/", ignoreCase = true) -> ContentType.Mod
        else -> null
    }

    private fun hasOverridesDir(rootDir: File): Boolean {
        return rootDir.walkTopDown().any { it.isDirectory && it.name.equals("overrides", ignoreCase = true) }
    }

    private fun hasOverridesDir(entryNames: List<String>, rootPrefix: String): Boolean {
        val overridesPath = rootPrefix + "overrides"
        val overridesPrefix = "$overridesPath/"
        return entryNames.any { rawName ->
            val normalized = rawName.replace('\\', '/').trimStart('/')
            normalized == overridesPath || normalized.startsWith(overridesPrefix)
        }
    }

    private fun ModrinthProject.toModSide(): Mod.Side {
        if (serverSide == "unsupported") return Mod.Side.CLIENT
        if (clientSide == "unsupported") return Mod.Side.SERVER
        return Mod.Side.BOTH
    }

    private fun parseCurseForgeFileId(url: String): Int? {
        val match = Regex("/files/(\\d+)/(\\d+)/").find(url) ?: return null
        val idPart1 = match.groupValues.getOrNull(1) ?: return null
        val idPart2 = match.groupValues.getOrNull(2) ?: return null
        val paddedPart2 = idPart2.padStart(3, '0')
        return (idPart1 + paddedPart2).toIntOrNull()
    }

    fun ModrinthProject.toCardVo(modFile: File? = null): Mod.CardVo {
        val icons = buildIconUrls(iconUrl)
        val resolvedName = (title ?: slug).ifBlank { slug }
        val localMeta = modFile?.readLocalModCardMeta()
        val introText = description?.takeIf { it.isNotBlank() }?.trim()
            ?: localMeta?.description
            ?: "暂无介绍"

        return Mod.CardVo(
            name = resolvedName,
            nameCn = null,
            intro = introText,
            iconData = localMeta?.iconBytes,
            iconUrls = icons,
            side = Mod.Side.BOTH
        )
    }

    private data class LocalModCardMeta(
        val iconBytes: ByteArray? = null,
        val description: String? = null
    )

    private fun File.readLocalModCardMeta(): LocalModCardMeta = runCatching {
        JarFile(this).use { jar ->
            LocalModCardMeta(
                iconBytes = jar.modLogo,
                description = jar.readModMeta()?.description
            )
        }
    }.getOrDefault(LocalModCardMeta())
    suspend fun mrreq(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        params: Map<String, Any>? = null,
        body: Any? = null
    ): HttpResponse {
        suspend fun doRequest(base: String) = ktorClient.request {
            url("${base}/${path}")
            json()
            body?.let { setBody(it) }
            params?.forEach { parameter(it.key, it.value) }
            this.method = method
        }

        if (!ModService.preferMirror) {
            return doRequest(OFFICIAL_URL)
        }

        val mirrorResult = runCatching<HttpResponse> { doRequest(OFFICIAL_URL.ofMirrorUrl) }
        val mirrorResponse = mirrorResult.getOrNull()
        if (mirrorResponse != null && mirrorResponse.status.isSuccess()) {
            return mirrorResponse
        } else {
            val bodyAsText = mirrorResponse?.bodyAsText()
            lgr.warn { "Modrinth mirror fail，${mirrorResponse?.status},$bodyAsText" }
        }

        mirrorResult.exceptionOrNull()?.let {
            lgr.warn { "Modrinth mirror request failed, falling back to official API: ${it.message}" }
        }
        val officialResponse = doRequest(OFFICIAL_URL)
        return officialResponse
    }

    suspend fun mrreqV3(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        params: Map<String, Any>? = null,
        body: Any? = null
    ): HttpResponse {
        suspend fun doRequest(base: String) = ktorClient.request {
            url("${base}/${path}")
            json()
            body?.let { setBody(it) }
            params?.forEach { parameter(it.key, it.value) }
            this.method = method
        }

        if (!ModService.preferMirror) {
            return doRequest(V3_OFFICIAL_URL)
        }

        val mirrorResult = runCatching<HttpResponse> { doRequest(V3_OFFICIAL_URL.ofMirrorUrl) }
        val mirrorResponse = mirrorResult.getOrNull()
        if (mirrorResponse != null && mirrorResponse.status.isSuccess()) {
            return mirrorResponse
        } else {
            val bodyAsText = mirrorResponse?.bodyAsText()
            lgr.warn { "Modrinth v3 mirror fail，${mirrorResponse?.status},$bodyAsText" }
        }

        mirrorResult.exceptionOrNull()?.let {
            lgr.warn { "Modrinth v3 mirror request failed, falling back to official API: ${it.message}" }
        }
        return doRequest(V3_OFFICIAL_URL)
    }

    suspend fun getProjectDetailV3(projectIdOrSlug: String): ModrinthV3Project {
        val normalizedId = projectIdOrSlug.trim()
        require(normalizedId.isNotBlank()) { "projectId不能为空" }
        return mrreqV3("project/$normalizedId").body()
    }

    suspend fun getVersionsV3(ids: List<String>): List<ModrinthV3Version> {
        val normalizedIds = ids.map { it.trim() }.filter(String::isNotBlank).distinct()
        if (normalizedIds.isEmpty()) return emptyList()
        return mrreqV3(
            path = "versions",
            params = mapOf("ids" to Json.encodeToString(normalizedIds))
        ).body()
    }

    suspend fun getProjectVersionsV3(
        projectIdOrSlug: String,
        gameVersions: List<String> = emptyList(),
        loaders: List<String> = emptyList(),
        includeChangelog: Boolean = true
    ): List<ModrinthV3Version> {
        val normalizedId = projectIdOrSlug.trim()
        require(normalizedId.isNotBlank()) { "projectId不能为空" }
        val params = buildMap<String, Any> {
            val normalizedGameVersions = gameVersions.map { it.trim() }.filter(String::isNotBlank).distinct()
            val normalizedLoaders = loaders.map { it.trim() }.filter(String::isNotBlank).distinct()
            if (normalizedGameVersions.isNotEmpty()) {
                put("game_versions", Json.encodeToString(normalizedGameVersions))
            }
            if (normalizedLoaders.isNotEmpty()) {
                put("loaders", Json.encodeToString(normalizedLoaders))
            }
            put("include_changelog", includeChangelog)
        }
        return mrreqV3(
            path = "project/$normalizedId/version",
            params = params
        ).body()
    }

    suspend fun getMultipleProjects(idSlugs: List<String>): List<ModrinthProject> {
        return fetchMultipleProjects(idSlugs) { normalizedIds ->
            mrreq("projects", params = mapOf("ids" to Json.encodeToString(normalizedIds)))
                .body()
        }
    }

    internal suspend fun fetchMultipleProjects(
        idSlugs: List<String>,
        fetcher: suspend (List<String>) -> List<ModrinthProject>,
    ): List<ModrinthProject> {
        val normalizedIds = idSlugs.asSequence()
            .distinct()
            .toList()

        if (normalizedIds.isEmpty()) return emptyList()

        val projects = fetcher(normalizedIds)
        if (projects.size != normalizedIds.size) {
            val missing = normalizedIds.toSet() - projects.map { it.id }.toSet() - projects.map { it.slug }.toSet()
            if (missing.isNotEmpty()) {
                lgr.debug { "Modrinth: ${missing.size} ids unmatched: ${missing.joinToString()}" }
            }
        }

        lgr.info { "Modrinth: fetched ${projects.size} projects for ${normalizedIds.size} requested ids" }

        return projects
    }

    suspend fun searchProjects(
        query: String? = null,
        facets: List<List<String>> = emptyList(),
        index: ModrinthSearchIndex = ModrinthSearchIndex.RELEVANCE,
        offset: Int = 0,
        limit: Int = 10
    ): ModrinthSearchResponse {
        require(offset >= 0) { "offset不能小于0" }
        require(limit in 1..100) { "limit必须在1..100之间" }

        val params = buildMap<String, Any> {
            query?.trim()?.ifBlank { null }?.let { put("query", it) }
            if (facets.isNotEmpty()) {
                put("facets", Json.encodeToString(facets))
            }
            put("index", index.apiValue)
            put("offset", offset)
            put("limit", limit)
        }

        return mrreq(
            path = "search",
            params = params
        ).body()
    }

    suspend fun getProjectVersions(
        projectIdOrSlug: String,
        gameVersions: List<String> = emptyList(),
        loaders: List<String> = emptyList()
    ): List<ModrinthVersionInfo> {
        val params = buildMap<String, Any> {
            if (gameVersions.isNotEmpty()) {
                put("game_versions", Json.encodeToString(gameVersions))
            }
            if (loaders.isNotEmpty()) {
                put("loaders", Json.encodeToString(loaders))
            }
        }
        return mrreq(
            path = "project/${projectIdOrSlug.trim()}/version",
            params = params.ifEmpty { null }
        ).body()
    }

    suspend fun List<File>.mapModrinthVersions(): Map<String, ModrinthVersionInfo> {
        val hashes = map { it.sha1 }
        val response = mrreq(
            "version_files",
            method = HttpMethod.Post,
            body = ModrinthVersionLookupRequest(hashes = hashes, algorithm = "sha1")
        )
            .body<Map<String, ModrinthVersionInfo>>()

        val missing = hashes.filter { it !in response }
        if (missing.isNotEmpty()) {
            lgr.info { "Modrinth: ${response.size} matches, ${missing.size} hashes unmatched" }
        } else {
            lgr.info { "Modrinth: matched all ${response.size} hashes" }
        }

        return response
    }

    suspend fun getVersionsFromHashes(
        hashes: List<String>,
        algorithm: String = "sha1"
    ): Map<String, ModrinthVersionInfo> {
        val response = mrreq(
            "version_files",
            method = HttpMethod.Post,
            body = ModrinthVersionLookupRequest(hashes = hashes, algorithm = algorithm)
        )
            .body<Map<String, ModrinthVersionInfo>>()

        val missing = hashes.filter { it !in response }
        if (missing.isNotEmpty()) {
            lgr.info { "Modrinth: ${response.size} matches, ${missing.size} hashes unmatched" }
        } else {
            lgr.info { "Modrinth: matched all ${response.size} hashes" }
        }

        return response
    }
}

