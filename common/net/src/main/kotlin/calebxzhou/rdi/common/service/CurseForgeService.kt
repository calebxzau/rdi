package calebxzhou.rdi.common.service

import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.common.model.Content
import calebxzau.rdi.common.model.ContentPlatform
import calebxzau.rdi.common.model.ContentSide
import calebxzau.rdi.common.model.ContentType as ClientContentType
import calebxzau.rdi.common.model.validateAndMergeClientExtras
import calebxzau.rdi.common.model.validateClientExtra
import calebxzhou.rdi.common.util.openChineseZip
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.net.json
import calebxzhou.rdi.common.net.ktorClient
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.service.ModService.buildIconUrls
import calebxzhou.rdi.common.service.ModService.modLogo
import calebxzhou.rdi.common.service.ModService.ofMirrorUrl
import calebxzhou.rdi.common.service.ModService.readModMeta
import calebxzhou.rdi.common.service.ModrinthService.getMultipleProjects
import calebxzhou.rdi.common.service.ModrinthService.getVersionsFromHashes
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.io.files.FileNotFoundException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.VisibleForTesting
import java.io.File
import java.util.jar.JarFile


object CurseForgeService {
    private val lgr by Loggers
    const val OFFICIAL_URL = "https://api.curseforge.com/v1"
    private const val API_KEY_HEADER = "x-api-key"
    private val apiKey = byteArrayOf(
        36, 50, 97, 36, 49, 48, 36, 55, 87, 87, 86, 49, 87, 69, 76, 99, 119, 88, 56, 88,
        112, 55, 100, 54, 56, 77, 72, 115, 46, 53, 103, 114, 84, 121, 90, 86, 97, 54,
        83, 121, 110, 121, 101, 83, 121, 77, 104, 49, 114, 115, 69, 56, 57, 110, 73,
        97, 48, 57, 122, 79
    ).let(::String)
    private val apiKeyHeaders = mapOf(API_KEY_HEADER to apiKey)
    //镜像源可能会缺mod  比如McJtyLib - 1.21-9.0.14

    fun downloadHeadersFor(url: String): Map<String, String> {
        if (!url.startsWith("https://", ignoreCase = true)) return emptyMap()
        val host = url.substring(8).substringBefore('/').substringBefore(':')
        return if (
            host.equals("mod.mcimirror.top", ignoreCase = true) ||
            host.endsWith(".forgecdn.net", ignoreCase = true)
        ) apiKeyHeaders else emptyMap()
    }


    suspend fun List<File>.loadInfoCurseForge(): CurseForgeLocalResult {
        val hashToFile = this.associateBy { it.murmur2 }
        val hashes = hashToFile.keys.toList()
        val fingerprintData = matchFingerprintData(hashes)
        val result = getInfosFromHash(hashToFile, fingerprintData)
        //cache loaded info
        return result
    }


    //从完整的cf mod信息取得card vo
    private fun CurseForgeModInfo.toCardVo(
        modFile: File? = null,
        side: Mod.Side = Mod.Side.BOTH,
    ): Mod.CardVo {
        val icons = buildIconUrls(logo?.thumbnailUrl, logo?.url)
        val resolvedName = (name ?: slug).ifBlank { slug }
        val localMeta = modFile?.readLocalModCardMeta()
        val introText = summary?.takeIf { it.isNotBlank() }?.trim()
            ?: localMeta?.description
            ?: "暂无介绍"

        return Mod.CardVo(
            name = resolvedName,
            nameCn = null,
            intro = introText,
            iconData = localMeta?.iconBytes,
            iconUrls = icons,
            side = side
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


    suspend fun getInfosFromHash(
        hashToFile: Map<Long, File>,
        fingerprintData: CurseForgeFingerprintData
    ): CurseForgeLocalResult {

        data class MatchRecord(
            val projectId: Int,
            val fileId: String,
            val fingerprint: String,
            val file: File,
            val side: Mod.Side,
        )

        val matchRecords = fingerprintData.exactMatches.mapNotNull { match ->
            val projectId = match.id.takeIf { it > 0 } ?: return@mapNotNull null
            val fingerprint = match.file.fileFingerprint
            val localFile = hashToFile[fingerprint] ?: return@mapNotNull null
            MatchRecord(
                projectId = projectId,
                fileId = match.file.id.toString(),
                fingerprint = fingerprint.toString(),
                file = localFile,
                side = match.file.gameVersions.toCurseForgeModSide() ?: Mod.Side.BOTH,
            )
        }.groupBy { it.projectId }

        val modIds = matchRecords.keys.toList()
        if (modIds.isEmpty()) {
            lgr.info { "mod id 是空的" }
            return CurseForgeLocalResult(
                matched = emptyList(),
                unmatched = hashToFile.values.toList()
            )
        }

        val cfMods = getModsInfo(modIds)

        data class CfModMeta(
            val projectId: Int,
            val canonicalSlug: String,
            val normalizedSlug: String,
            val files: List<MatchRecord>,
            val mod: CurseForgeModInfo
        )

        val cfModMeta = cfMods.mapNotNull { mod ->
            val projectId = mod.id
            val files = matchRecords[projectId].orEmpty()
            if (files.isEmpty()) return@mapNotNull null
            val rawSlug = mod.slug.trim()
            val canonicalSlug = when {
                rawSlug.isNotEmpty() -> rawSlug
                !mod.name.isNullOrBlank() -> mod.name.trim()
                else -> projectId.toString()
            }
            CfModMeta(
                projectId = projectId,
                canonicalSlug = canonicalSlug,
                normalizedSlug = canonicalSlug.lowercase(),
                files = files,
                mod = mod
            )
        }

        val matchedFiles = cfModMeta.flatMap { meta -> meta.files.map { it.file } }.toList()

        val matched = cfModMeta.flatMap { meta ->
            meta.files.map { record ->
                ModCardMatch(
                    mod = Mod(
                        platform = "cf",
                        projectId = meta.projectId.toString(),
                        slug = meta.canonicalSlug,
                        fileId = record.fileId,
                        hash = record.fingerprint,
                        side = record.side,
                    ),
                    card = meta.mod.toCardVo(record.file, record.side),
                    file = record.file
                )
            }
        }
        val unmatched = hashToFile.values.filterNot { it in matchedFiles }
        lgr.info { "curseforge没找到这些mod：${unmatched}" }
        return CurseForgeLocalResult(matched, unmatched)

    }

    suspend fun mapManifestEntriesToMods(files: List<CurseForgePackManifest.File>): List<Mod> {
        val modInfoMap = getModsInfo(files.map { it.projectId }).associateBy { it.id }
        val fileInfoMap = getModFilesInfo(files.map { it.fileId }).associateBy { it.id }
        val fileSha1Map = fileInfoMap.mapValues { (_, fileInfo) ->
            fileInfo.hashes.firstOrNull { it.algo == 1 }?.value?.trim()?.lowercase().orEmpty()
        }
        val allSha1 = fileSha1Map.values.filter { it.isNotBlank() }.distinct()
        val sha1ToMrVersion = if (allSha1.isEmpty()) {
            emptyMap()
        } else {
            getVersionsFromHashes(allSha1)
        }
        val mrProjectMap = sha1ToMrVersion.values
            .map { it.projectId }
            .distinct()
            .let { ids ->
                if (ids.isEmpty()) emptyMap() else getMultipleProjects(ids).associateBy { it.id }
            }
        return files.mapNotNull { curseFile ->
            val modInfo = modInfoMap[curseFile.projectId] ?: let {
                lgr.warn { "mod ${curseFile.projectId}/${curseFile.fileId} 在mod info map没有信息" }
                return@mapNotNull null
            }
            val cfSlug = modInfo.slug
            val fileInfo = fileInfoMap[curseFile.fileId] ?: let {
                lgr.error{"mod ${curseFile.projectId}/${curseFile.fileId} file info map没有信息"}
                return@mapNotNull null
            }
            val mrProject = fileSha1Map[curseFile.fileId]
                ?.takeIf { it.isNotBlank() }
                ?.let { sha1ToMrVersion[it] }
                ?.let { mrProjectMap[it.projectId] }
            val side = fileInfo.gameVersions.toCurseForgeModSide()
                ?: mrProject?.run {
                    if (serverSide == "unsupported") {
                        return@run Mod.Side.CLIENT
                    }
                    if (clientSide == "unsupported") {
                        return@run Mod.Side.SERVER
                    } else return@run Mod.Side.BOTH
                }
                ?: Mod.Side.UNKNOWN
            Mod(
                platform = "cf",
                projectId = modInfo.id.toString(),
                slug = cfSlug,
                fileId = fileInfo.id.toString(),
                hash = fileInfo.fileFingerprint.toString(),
                side = side
            )
        }.also { mod ->
            lgr.info { "server mod：${mod.filter { it.side == Mod.Side.SERVER }.map { it.slug }}" }
            lgr.info { "client mod：${mod.filter { it.side == Mod.Side.CLIENT }.map { it.slug }}" }
            lgr.info { "both  mod：${mod.filter { it.side == Mod.Side.BOTH }.map { it.slug }}" }
        }
    }

    /** Resolves resourcepack and shaderpack manifest entries without applying the Mod class filter. */
    suspend fun mapManifestEntriesToContents(files: List<CurseForgePackManifest.File>): List<Content> {
        if (files.isEmpty()) return emptyList()
        val modInfoMap = getModsInfoIncludingNonMods(files.map { it.projectId }).associateBy { it.id }
        val fileInfoMap = getModFilesInfo(files.map { it.fileId }).associateBy { it.id }
        return resolveManifestEntriesToContents(files, modInfoMap, fileInfoMap)
    }

    internal fun resolveManifestEntriesToContents(
        files: List<CurseForgePackManifest.File>,
        modInfoMap: Map<Int, CurseForgeModInfo>,
        fileInfoMap: Map<Int, CurseForgeFile>,
    ): List<Content> = files.mapNotNull { manifestFile ->
            val modInfo = modInfoMap[manifestFile.projectId]
                ?: throw ModpackError("无法解析客户端资源项目: ${manifestFile.projectId}/${manifestFile.fileId}")
            val type = when (modInfo.classId) {
                12L -> ClientContentType.ResPack
                6552L -> ClientContentType.ShaderPack
                else -> return@mapNotNull null
            }
            val fileInfo = fileInfoMap[manifestFile.fileId]
                ?: throw ModpackError("无法解析客户端资源文件: ${manifestFile.fileId}")
            if (fileInfo.modId != manifestFile.projectId) {
                throw ModpackError("客户端资源文件不属于声明的项目: ${manifestFile.projectId}/${manifestFile.fileId}")
            }
            val fileName = fileInfo.fileName?.trim().orEmpty()
            val fileNameParts = fileName.replace('\\', '/').split('/')
            if (fileName.isBlank() || fileNameParts.size != 1 || fileNameParts.any { it == "." || it == ".." || it.isBlank() }) {
                throw ModpackError("客户端资源文件缺少文件名: ${manifestFile.projectId}/${manifestFile.fileId}")
            }
            val pathRoot = if (type == ClientContentType.ResPack) "resourcepacks" else "shaderpacks"
            Content(
                platform = ContentPlatform.CurseForge,
                type = type,
                projectId = modInfo.id.toString(),
                fileId = fileInfo.id.toString(),
                slug = modInfo.slug.ifBlank { modInfo.id.toString() },
                // CF cache identity is the fingerprint; SHA-1 remains available for local verification.
                hash = fileInfo.fileFingerprint.toString(),
                path = "$pathRoot/$fileName",
                side = ContentSide.Client,
                required = manifestFile.required,
                downloadUrls = listOf(fileInfo.realDownloadUrl),
            ).validateClientExtra()
        }.validateAndMergeClientExtras()

    /**
     * Load and validate a CurseForge modpack from a ZIP file
     * @param zipPath Path to the modpack ZIP file
     * @return Parsed manifest and list of override entries with their root prefix
     * @throws ModpackError if validation fails
     */
    suspend fun loadModpack(zipPath: String): Result<CurseForgeModpackData> = runCatching {
        val zipFile = File(zipPath)
        if (!zipFile.exists() || !zipFile.isFile) {
            throw ModpackError("找不到整合包文件: ${zipFile.path}")
        }

        zipFile.openChineseZip().use { zip ->
            val entries = zip.entries().asSequence().toList()
            entries.find { it.name == ".minecraft" }
                ?.let { throw ModpackError("你应该选整合包 而不是客户端\n你可以用PCL的导出功能 将客户端转换为整合包") }
            //玩不了gto
            val hasGtoCore = entries.any { it.name.startsWith("overrides/mods/gtocore") }
            val hasGtoNativeLib = entries.any { it.name.startsWith("overrides/mods/gtonativelib") }
            if (hasGtoCore && hasGtoNativeLib) {
                throw ModpackError("无法识别具有单机反作弊与代码加密的整合包")
            }
            val manifestEntry = entries.firstOrNull {
                !it.isDirectory && it.name.substringAfterLast('/') == "manifest.json"
            } ?: throw ModpackError("整合包缺少文件：manifest.json")

            val rootPrefix = manifestEntry.name.substringBeforeLast('/', missingDelimiterValue = "")
                .let { if (it.isBlank()) "" else "$it/" }
            val overridesFolder = rootPrefix + "overrides/"
            val overrideEntries = entries.filter { !it.isDirectory && it.name.startsWith(overridesFolder) }

            if (overrideEntries.isEmpty()) {
                throw ModpackError("整合包缺少目录：overrides")
            }

            val manifestJson = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val manifest = runCatching {
                serdesJson.decodeFromString<CurseForgePackManifest>(manifestJson)
            }.getOrElse {
                throw ModpackError("manifest.json 解析失败: ${it.message}")
            }

            val supportedVersion = McVersion.from(manifest.minecraft.version)
            if (supportedVersion == null || !supportedVersion.enabled) {
                val supportedList = McVersion.entries.joinToString(", ") { it.mcVer }
                throw ModpackError("不支持的 MC 版本: ${manifest.minecraft.version}，当前只支持: $supportedList")
            }

            val loaderId = manifest.minecraft.modLoaders.firstOrNull { it.primary }?.id
                ?: manifest.minecraft.modLoaders.firstOrNull()?.id
            val loaderSupported = loaderId?.startsWith("neoforge", ignoreCase = true) == true ||
                loaderId?.startsWith("forge", ignoreCase = true) == true
            if (!loaderSupported) {
                throw ModpackError("不支持的 Mod 加载器: ${loaderId ?: "未知"}，当前只支持 Forge/NeoForge")
            }

            val modpackName = manifest.name.trim()
            if (modpackName.isEmpty()) {
                throw ModpackError("manifest.json 中缺少整合包名称")
            }

            var versionName = manifest.version.trim()
            if (versionName.isEmpty()) {
                lgr.warn { "manifest.json 中缺少版本号" }
                versionName = "1.0"
            }

            CurseForgeModpackData(
                manifest = manifest,
                file = zipFile,
            )
        }
    }.recoverCatching { error ->
        if (error is ModpackError) throw error
        throw ModpackError("处理整合包时出错: ${error.message}")
    }


    @VisibleForTesting
    private suspend fun makeRequest(
        path: String,
        method: HttpMethod = HttpMethod.Get,
        body: Any? = null,
        ignoreMirror: Boolean = false,
        params: Map<String, Any> = emptyMap()
    ): HttpResponse {
        suspend fun doRequest(base: String) = ktorClient.request {
            url("${base}/${path}")
            json()
            header(API_KEY_HEADER, apiKey)
            body?.let { setBody(it) }
            params.forEach { parameter(it.key, it.value) }
            this.method = method
        }
        if (ignoreMirror || !ModService.preferMirror) {
            return doRequest(OFFICIAL_URL)
        }

        val mirrorResult = runCatching { doRequest(OFFICIAL_URL.ofMirrorUrl) }
        mirrorResult.getOrNull()?.let { response ->
            val returnedHtml = response.contentType()?.match(ContentType.Text.Html) == true
            if (response.status.isSuccess() && !returnedHtml) return response

            val body = response.bodyAsText()
            lgr.warn {
                "CurseForge mirror returned ${response.status} ${response.contentType()}, " +
                    "falling back to official API: $body"
            }
        }

        mirrorResult.exceptionOrNull()?.let { ex ->
            lgr.warn { "CurseForge mirror request with exception, falling back to official API: ${ex.message + "\n" + ex }" }
        }

        return doRequest(OFFICIAL_URL)
    }

    /**
     * matches a list of murmur2 fingerprints against CurseForge's database
     * @param hashes List of Long fingerprints in murmur2 format
     * @return CurseForgeFingerprintData containing exact matches, partial matches, and unmatched fingerprints
     */
    suspend fun matchFingerprintData(hashes: List<Long>): CurseForgeFingerprintData {
        @Serializable
        data class CurseForgeFingerprintRequest(val fingerprints: List<Long>)

        val response = makeRequest(
            //432 for minecraft
            "fingerprints/432",
            HttpMethod.Post,
            CurseForgeFingerprintRequest(fingerprints = hashes)
        ).body<CurseForgeFingerprintResponse>()
        val data = response.data ?: CurseForgeFingerprintData()
        lgr.debug {
            "CurseForge: ${data.exactMatches.size} exact matches, ${data.partialMatches.size} partial matches, ${data.unmatchedFingerprints.size} unmatched"
        }

        return data
    }

    suspend fun searchMods(
        query: String? = null,
        mcVersion: String? = null,
        loader: String? = null,
        sortField: Int? = null,
        sortOrder: String = "desc",
        offset: Int = 0,
        limit: Int = 20
    ): CurseForgeModSearchResponse {
        require(offset >= 0) { "offset不能小于0" }
        require(limit in 1..50) { "limit必须在1..50之间" }

        val params = buildMap<String, Any> {
            put("gameId", 432)
            put("classId", 6)
            query?.trim()?.takeIf(String::isNotBlank)?.let { put("searchFilter", it) }
            mcVersion?.trim()?.takeIf(String::isNotBlank)?.let { version ->
                put("gameVersion", version)
                loader?.trim()?.takeIf(String::isNotBlank)?.toCurseForgeModLoaderType()?.let {
                    put("modLoaderType", it)
                }
            }
            sortField?.let { put("sortField", it) }
            put("sortOrder", sortOrder)
            put("index", offset)
            put("pageSize", limit)
        }

        return makeRequest("mods/search", params = params).body()
    }

    suspend fun getModFiles(
        modId: Int,
        mcVersion: String? = null,
        loader: String? = null,
        offset: Int = 0,
        limit: Int = 50
    ): CurseForgeFileListResponse {
        require(offset >= 0) { "offset不能小于0" }
        require(limit in 1..50) { "limit必须在1..50之间" }
        val params = buildMap<String, Any> {
            mcVersion?.trim()?.takeIf(String::isNotBlank)?.let { version ->
                put("gameVersion", version)
                loader?.trim()?.takeIf(String::isNotBlank)?.toCurseForgeModLoaderType()?.let {
                    put("modLoaderType", it)
                }
            }
            put("index", offset)
            put("pageSize", limit)
        }
        return makeRequest("mods/${modId}/files", params = params).body()
    }

    suspend fun getModDescription(modId: Int): String =
        makeRequest(
            path = "mods/${modId}/description",
            params = mapOf("stripped" to true)
        ).body<CurseForgeStringResponse>().data.orEmpty()

    suspend fun getModFileChangelog(modId: Int, fileId: Int): String =
        makeRequest(
            path = "mods/${modId}/files/${fileId}/changelog",
            params = mapOf("stripped" to true)
        ).body<CurseForgeStringResponse>().data.orEmpty()

    suspend fun getModFileDownloadUrl(modId: Int, fileId: Int): String? =
        makeRequest("mods/${modId}/files/${fileId}/download-url")
            .body<CurseForgeStringResponse>()
            .data
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private suspend fun requestModFiles(fileIds: List<Int>, official: Boolean = false): List<CurseForgeFile> {
        @Serializable
        data class CFFileIdsRequest(val fileIds: List<Int>)

        return makeRequest(
            "mods/files",
            HttpMethod.Post,
            CFFileIdsRequest(fileIds),
            ignoreMirror = official,
        ).body<CurseForgeFileListResponse>().data
    }

    private suspend fun requestMods(
        modIds: List<Int>,
        official: Boolean = false,
        filterModsOnly: Boolean = true,
    ): List<CurseForgeModInfo> {
        @Serializable
        data class CFModsRequest(val modIds: List<Int>, val filterPcOnly: Boolean = true)

        @Serializable
        data class CFModsResponse(val data: List<CurseForgeModInfo> = emptyList())
        return makeRequest(
            "mods",
            HttpMethod.Post,
            CFModsRequest(modIds, filterPcOnly = filterModsOnly),
            official
        ).body<CFModsResponse>().data.let { mods ->
            if (filterModsOnly) mods.filter { it.isMod } else mods
        }

    }

    //从mod project id列表获取cf mod信息
    suspend fun getModsInfo(modIds: List<Int>): List<CurseForgeModInfo> {

        if (modIds.isEmpty()) return emptyList()

        val mods = requestMods(modIds).toMutableList()
        val foundIds = mods.mapTo(mutableSetOf()) { it.id }
        val missingIds = modIds.filterNot { foundIds.contains(it) }
        if (missingIds.isNotEmpty()) {
            lgr.warn { "not found ids：${missingIds}, retry official api" }
            mods += requestMods(missingIds, true)
        }

        return mods
    }

    suspend fun getModsInfoIncludingNonMods(modIds: List<Int>): List<CurseForgeModInfo> {
        if (modIds.isEmpty()) return emptyList()
        val distinctIds = modIds.distinct()
        val mods = requestMods(distinctIds, filterModsOnly = false).toMutableList()
        val foundIds = mods.mapTo(mutableSetOf()) { it.id }
        val missingIds = distinctIds.filterNot { it in foundIds }
        if (missingIds.isNotEmpty()) {
            lgr.warn { "not found ids：$missingIds, retry official api" }
            mods += requestMods(missingIds, official = true, filterModsOnly = false)
        }
        return mods
    }

    suspend fun getModFileInfo(modId: Int, fileId: Int): CurseForgeFile? {
        return makeRequest("mods/${modId}/files/${fileId}").body<CurseForgeFileResponse>().data
    }

    suspend fun getModFilesInfo(fileIds: List<Int>): List<CurseForgeFile> {
        if (fileIds.isEmpty()) return emptyList()

        val files = requestModFiles(fileIds).toMutableList()
        val foundIds = files.mapTo(mutableSetOf()) { it.id }
        val missingIds = fileIds.filterNot { foundIds.contains(it) }
        if (missingIds.isNotEmpty()) {
            lgr.warn { "not found ids：${missingIds}, retry official api" }
            files += requestModFiles(missingIds, true)
        }
        return files
    }

    fun parseModpack(zipFile: File): Result<CurseForgePackManifest> {
        zipFile.openChineseZip().use { zip ->
            val manifestEntry = zip.getEntry("manifest.json")
                ?: throw FileNotFoundException("Invalid modpack zip: manifest.json not found")
            val manifestJson = zip.getInputStream(manifestEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val manifest = runCatching {
                Json.decodeFromString<CurseForgePackManifest>(manifestJson)
            }.getOrElse {
                throw SerializationException("manifest.json parse failed: ${it.message}", it)
            }
            return Result.success(manifest)
        }
    }
}

private fun String.toCurseForgeModLoaderType(): Int? =
    when (lowercase()) {
        "forge" -> 1
        "fabric" -> 4
        "quilt" -> 5
        "neoforge" -> 6
        else -> null
    }



