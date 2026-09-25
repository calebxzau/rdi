package calebxzau.rdi.client.modcatalog

import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.toCurseForgeModSide
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

internal class CurseForgeAdapter(
    httpClient: HttpClient,
    private val config: CurseForgeConfig,
    networkPolicy: CatalogNetworkPolicy,
    private val json: Json,
    onWarning: (Throwable) -> Unit = {}
) : PlatformAdapter {
    override val platform = ModPlatform.CURSEFORGE
    private val allowedDownloadHosts = setOf(
        "edge.forgecdn.net",
        "media.forgecdn.net",
        "mediafilez.forgecdn.net",
        URI(networkPolicy.mirrorBaseUrl).host.lowercase()
    )
    private val transport = CatalogHttpTransport(
        httpClient = httpClient,
        platform = platform,
        officialBaseUrl = config.baseUrl,
        mirrorBaseUrl = networkPolicy.mirrorBase(platform),
        defaultHeaders = mapOf(API_KEY_HEADER to config.apiKey),
        preferMirror = networkPolicy.preferMirrorProvider,
        json = json,
        onWarning = onWarning
    )

    override suspend fun search(
        query: String,
        target: CatalogTarget,
        sort: CatalogSort,
        offset: Int,
        limit: Int
    ): SourcePage {
        val response = get<CfSearchResponse>(
            "mods/search",
            buildMap {
                put("gameId", MINECRAFT_GAME_ID.toString())
                put("classId", MOD_CLASS_ID.toString())
                query.trim().takeIf(String::isNotBlank)?.let { put("searchFilter", it) }
                put("gameVersion", target.minecraftVersion.mcVer)
                put("modLoaderType", target.loader.curseForgeType().toString())
                sort.curseForgeField()?.let { put("sortField", it.toString()) }
                put("sortOrder", "desc")
                put("index", offset.toString())
                put("pageSize", limit.toString())
            }
        )
        val items = response.data.filter { it.classId == MOD_CLASS_ID }.map { it.toSource() }
        val total = response.pagination?.totalCount?.toLong()
        val next = (offset + response.data.size).takeIf {
            response.data.isNotEmpty() && (total == null || it < total)
        }
        return SourcePage(items, next, total)
    }

    override suspend fun resolveSlugs(slugs: List<String>, target: CatalogTarget): SlugResolution {
        val found = linkedMapOf<String, CatalogProjectSource>()
        val missing = linkedSetOf<String>()
        slugs.distinctBy(::normalizeProjectSlug).forEach { slug ->
            val normalized = normalizeProjectSlug(slug)
            val project = get<CfSearchResponse>(
                "mods/search",
                mapOf(
                    "gameId" to MINECRAFT_GAME_ID.toString(),
                    "classId" to MOD_CLASS_ID.toString(),
                    "slug" to slug,
                    "gameVersion" to target.minecraftVersion.mcVer,
                    "modLoaderType" to target.loader.curseForgeType().toString(),
                    "pageSize" to "1"
                )
            ).data.firstOrNull {
                it.classId == MOD_CLASS_ID && normalizeProjectSlug(it.slug) == normalized
            }?.toSource()
            if (project == null) missing += normalized else found[normalized] = project
        }
        return SlugResolution(found, missing)
    }

    override suspend fun getProjects(ids: Set<String>): AdapterResult<CatalogProjectSource> {
        if (ids.isEmpty()) return AdapterResult(emptyMap(), emptySet())
        val numericIds = ids.mapNotNull(String::toIntOrNull)
        val projects = post<CfDataResponse<List<CfProjectDto>>>(
            "mods",
            json.encodeToString(CfProjectRequest(numericIds))
        ).data.filter { it.gameId == null || it.gameId == MINECRAFT_GAME_ID }
            .associate { it.id.toString() to it.toSource() }
        return AdapterResult(projects, ids - projects.keys)
    }

    override suspend fun getDetails(ref: CatalogProjectRef): CatalogModDetailsSource {
        require(ref.platform == platform)
        val project = get<CfDataResponse<CfProjectDto>>("mods/${ref.projectId}").data
        val description = get<CfDataResponse<String>>("mods/${ref.projectId}/description").data
        return CatalogModDetailsSource(
            project = project.toSource(),
            description = description,
            authors = project.authors.mapNotNull(CfAuthorDto::name),
            categories = project.categories.mapNotNull(CfCategoryDto::name),
            sourceUrl = project.links?.websiteUrl ?: "https://www.curseforge.com/minecraft/mc-mods/${project.slug}",
            issuesUrl = project.links?.issuesUrl,
            wikiUrl = project.links?.wikiUrl
        )
    }

    override suspend fun listFiles(
        project: CatalogProjectRef,
        target: CatalogTarget,
        offset: Int,
        limit: Int
    ): AdapterFileList {
        require(project.platform == platform)
        val response = get<CfFileListResponse>(
            "mods/${project.projectId}/files",
            mapOf(
                "gameVersion" to target.minecraftVersion.mcVer,
                "modLoaderType" to target.loader.curseForgeType().toString(),
                "index" to offset.toString(),
                "pageSize" to limit.toString()
            )
        )
        val items = response.data.map { it.toFile() }
            .sortedByDescending(CatalogFile::publishedAt)
        val pagination = response.pagination
        val next = pagination?.let {
            (it.index + it.resultCount).takeIf { nextOffset -> it.resultCount > 0 && nextOffset < it.totalCount }
        }
        return AdapterFileList.Page(items, next)
    }

    override suspend fun getFiles(ids: Set<String>): AdapterResult<CatalogFile> {
        if (ids.isEmpty()) return AdapterResult(emptyMap(), emptySet())
        val numericIds = ids.mapNotNull(String::toIntOrNull)
        if (numericIds.isEmpty()) return AdapterResult(emptyMap(), ids)
        val files = post<CfDataResponse<List<CfFileDto>>>(
            "mods/files",
            json.encodeToString(CfFileRequest(numericIds))
        ).data.associate { it.id.toString() to it.toFile() }
        return AdapterResult(files, ids - files.keys)
    }

    override suspend fun matchFiles(files: List<CatalogFileHashes>): Map<String, CatalogFile> {
        if (files.isEmpty()) return emptyMap()
        val byFingerprint = files.filter { it.curseForgeFingerprint != null }
            .groupBy { it.curseForgeFingerprint!! }
        if (byFingerprint.isEmpty()) return emptyMap()
        val matches = linkedMapOf<String, CatalogFile>()
        val response = post<CfDataResponse<CfFingerprintDataDto>>(
            "fingerprints/$MINECRAFT_GAME_ID",
            json.encodeToString(CfFingerprintRequest(byFingerprint.keys.toList()))
        )
        response.data.exactMatches.forEach { match ->
            val fingerprint = match.file.fileFingerprint ?: return@forEach
            val mapped = match.file.toFile()
            byFingerprint[fingerprint].orEmpty().forEach { matches[it.key] = mapped }
        }
        return matches
    }

    override suspend fun getChangelog(file: CatalogFileRef): String {
        require(file.platform == platform)
        val project = getFiles(setOf(file.fileId)).found[file.fileId]?.project
            ?: throw CatalogException.InvalidResponse(platform)
        return get<CfDataResponse<String>>("mods/${project.projectId}/files/${file.fileId}/changelog").data
    }

    override suspend fun resolveDownload(file: CatalogFile): ResolvedDownload {
        require(file.ref.platform == platform)
        val url = file.downloadUrl
            ?.validatedDownloadUrl(platform)
            ?: throw CatalogException.DownloadUnavailable(file.ref)
        return ResolvedDownload(url, downloadHeadersFor(url), file.fileName, file.digests)
    }

    private fun CfProjectDto.toSource() = CatalogProjectSource(
        ref = CatalogProjectRef(platform, id.toString()),
        slug = slug,
        name = name,
        summary = summary.orEmpty().trim(),
        iconUrl = logo?.url ?: logo?.thumbnailUrl,
        downloadCount = downloadCount,
        updatedAt = dateModified.toInstant(),
        environment = EnvironmentCompatibility(),
        contentType = classId.toContentType()
    )

    private fun Int?.toContentType(): CatalogContentType = when (this) {
        MOD_CLASS_ID -> CatalogContentType.MOD
        RESOURCE_PACK_CLASS_ID -> CatalogContentType.RESOURCE_PACK
        SHADER_PACK_CLASS_ID -> CatalogContentType.SHADER_PACK
        else -> CatalogContentType.OTHER
    }

    private fun CfFileDto.toFile(): CatalogFile {
        val normalizedName = fileName?.trim()?.takeIf(String::isNotBlank) ?: throw invalidResponse()
        if (id <= 0 || modId <= 0) throw invalidResponse()
        val ref = CatalogFileRef(platform, id.toString())
        val project = CatalogProjectRef(platform, modId.toString())
        val digests = buildSet {
            hashes.firstOrNull { it.algo == SHA1_ALGORITHM }
                ?.value
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { add(CatalogDigest(CatalogDigestAlgorithm.SHA1, it.lowercase())) }
            fileFingerprint?.let {
                add(CatalogDigest(CatalogDigestAlgorithm.CURSEFORGE_MURMUR2, it.toString()))
            }
        }
        if (digests.isEmpty()) throw invalidResponse()
        return CatalogFile(
            ref = ref,
            project = project,
            displayName = displayName?.trim()?.takeIf(String::isNotBlank) ?: normalizedName,
            versionNumber = null,
            fileName = normalizedName,
            channel = releaseType.toReleaseChannel(),
            publishedAt = fileDate.toInstant(),
            fileSize = fileLength ?: fileSizeOnDisk ?: 0,
            minecraftVersions = gameVersions.filter { it.firstOrNull()?.isDigit() == true }.toSet(),
            loaders = gameVersions.mapNotNull(String::toModLoader).toSet(),
            environment = gameVersions.toCurseForgeEnvironment(),
            digests = digests,
            dependencies = dependencies.mapNotNull { it.toDomain() },
            downloadUrl = realDownloadUrl
        )
    }

    private fun CfDependencyDto.toDomain(): CatalogDependency? {
        val projectId = modId?.takeIf { it > 0 } ?: return null
        val requirement = when (relationType) {
            1 -> DependencyRequirement.EMBEDDED
            2 -> DependencyRequirement.OPTIONAL
            3 -> DependencyRequirement.REQUIRED
            4 -> DependencyRequirement.TOOL
            5 -> DependencyRequirement.INCOMPATIBLE
            6 -> DependencyRequirement.INCLUDED
            else -> return null
        }
        return CatalogDependency(
            DependencyTarget.Project(CatalogProjectRef(platform, projectId.toString())),
            requirement
        )
    }

    private fun downloadHeadersFor(url: String): Map<String, String> {
        val host = try {
            URI(url).host?.lowercase()
        } catch (cause: Exception) {
            throw CatalogException.InvalidResponse(platform, cause)
        } ?: throw CatalogException.InvalidResponse(platform)
        val allowed = host in allowedDownloadHosts || host.endsWith(".forgecdn.net")
        return if (allowed) mapOf(API_KEY_HEADER to config.apiKey) else emptyMap()
    }

    private suspend inline fun <reified T> get(
        path: String,
        parameters: Map<String, String> = emptyMap()
    ): T = transport.get(path, parameters).decode(platform, json)

    private suspend inline fun <reified T> post(path: String, body: String): T =
        transport.post(path, body).decode(platform, json)

    private fun invalidResponse() = CatalogException.InvalidResponse(platform)

    private fun String?.toInstant(): Instant = try {
        this?.let(Instant::parse) ?: Instant.EPOCH
    } catch (cause: Exception) {
        throw CatalogException.InvalidResponse(platform, cause)
    }

    private fun Int?.toReleaseChannel(): ReleaseChannel = when (this) {
        1 -> ReleaseChannel.RELEASE
        2 -> ReleaseChannel.BETA
        3 -> ReleaseChannel.ALPHA
        else -> throw invalidResponse()
    }

    companion object {
        private const val API_KEY_HEADER = "x-api-key"
        private const val MINECRAFT_GAME_ID = 432
        private const val MOD_CLASS_ID = 6
        private const val RESOURCE_PACK_CLASS_ID = 12
        private const val SHADER_PACK_CLASS_ID = 6552
        private const val SHA1_ALGORITHM = 1
    }
}

private fun ModLoader.curseForgeType(): Int = when (this) {
    ModLoader.forge -> 1
    ModLoader.neoforge -> 6
    ModLoader.Fabric -> 4
}

private fun CatalogSort.curseForgeField(): Int? = when (this) {
    CatalogSort.RELEVANCE -> null
    CatalogSort.DOWNLOADS -> 6
    CatalogSort.UPDATED -> 3
}

private fun String.toModLoader(): ModLoader? = when (lowercase()) {
    "forge" -> ModLoader.forge
    "neoforge" -> ModLoader.neoforge
    "fabric" -> ModLoader.Fabric
    else -> null
}

private fun List<String>.toCurseForgeEnvironment(): EnvironmentCompatibility = when (toCurseForgeModSide()) {
    calebxzhou.rdi.common.model.Mod.Side.BOTH -> EnvironmentCompatibility(
        client = EnvironmentRequirement.REQUIRED,
        server = EnvironmentRequirement.REQUIRED,
    )
    calebxzhou.rdi.common.model.Mod.Side.CLIENT -> EnvironmentCompatibility(
        client = EnvironmentRequirement.REQUIRED,
        server = EnvironmentRequirement.UNSUPPORTED,
    )
    calebxzhou.rdi.common.model.Mod.Side.SERVER -> EnvironmentCompatibility(
        client = EnvironmentRequirement.UNSUPPORTED,
        server = EnvironmentRequirement.REQUIRED,
    )
    null -> EnvironmentCompatibility()
    calebxzhou.rdi.common.model.Mod.Side.UNKNOWN -> EnvironmentCompatibility()
}

@Serializable
private data class CfDataResponse<T>(val data: T)

@Serializable
private data class CfProjectRequest(val modIds: List<Int>, val filterPcOnly: Boolean = true)

@Serializable
private data class CfFileRequest(val fileIds: List<Int>)

@Serializable
private data class CfFingerprintRequest(val fingerprints: List<Long>)

@Serializable
private data class CfProjectDto(
    val id: Int,
    val gameId: Int? = null,
    val name: String,
    val slug: String,
    val summary: String? = null,
    val downloadCount: Long = 0,
    val classId: Int? = null,
    val logo: CfLogoDto? = null,
    val dateModified: String? = null,
    val authors: List<CfAuthorDto> = emptyList(),
    val categories: List<CfCategoryDto> = emptyList(),
    val links: CfLinksDto? = null
)

@Serializable
private data class CfLogoDto(val thumbnailUrl: String? = null, val url: String? = null)

@Serializable
private data class CfAuthorDto(val name: String? = null)

@Serializable
private data class CfCategoryDto(val name: String? = null)

@Serializable
private data class CfLinksDto(
    val websiteUrl: String? = null,
    val wikiUrl: String? = null,
    val issuesUrl: String? = null
)

@Serializable
private data class CfSearchResponse(
    val data: List<CfProjectDto> = emptyList(),
    val pagination: CfPaginationDto? = null
)

@Serializable
private data class CfFileListResponse(
    val data: List<CfFileDto> = emptyList(),
    val pagination: CfPaginationDto? = null
)

@Serializable
private data class CfPaginationDto(
    val index: Int = 0,
    val resultCount: Int = 0,
    val totalCount: Int = 0
)

@Serializable
private data class CfFileDto(
    val id: Int,
    val modId: Int,
    val displayName: String? = null,
    val fileName: String? = null,
    val releaseType: Int? = null,
    val hashes: List<CfFileHashDto> = emptyList(),
    val fileDate: String? = null,
    val fileLength: Long? = null,
    val fileSizeOnDisk: Long? = null,
    val downloadUrl: String? = null,
    val gameVersions: List<String> = emptyList(),
    val dependencies: List<CfDependencyDto> = emptyList(),
    val fileFingerprint: Long? = null
) {
    val realDownloadUrl: String?
        get() {
            downloadUrl?.trim()?.takeIf(String::isNotBlank)?.let { return it }
            val normalizedName = fileName?.trim()?.takeIf(String::isNotBlank) ?: return null
            val idText = id.toString()
            if (idText.length <= 4) return null
            val encodedName = URLEncoder.encode(normalizedName, StandardCharsets.UTF_8).replace("+", "%20")
            return "https://mediafilez.forgecdn.net/files/${idText.take(4).toInt()}/${idText.drop(4).toInt()}/$encodedName"
        }
}

@Serializable
private data class CfFileHashDto(val value: String? = null, val algo: Int? = null)

@Serializable
private data class CfDependencyDto(val modId: Long? = null, val relationType: Int? = null)

@Serializable
private data class CfFingerprintDataDto(
    val exactMatches: List<CfFingerprintMatchDto> = emptyList()
)

@Serializable
private data class CfFingerprintMatchDto(val file: CfFileDto)
