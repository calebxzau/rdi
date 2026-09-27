package calebxzhou.rdi.common.service

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.util.humanSpeed
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.util.sha256
import calebxzhou.rdi.common.DL_MOD_DIR
import calebxzhou.rdi.common.deser
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.net.DownloadProgress
import calebxzhou.rdi.common.net.LocalArtifactHashAlgorithm
import calebxzhou.rdi.common.net.LocalArtifactRequest
import calebxzhou.rdi.common.net.LocalArtifactReuse
import calebxzhou.rdi.common.net.downloadFileFrom
import kotlinx.serialization.decodeFromString
import net.peanuuutz.tomlkt.Toml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.jar.JarFile
import java.util.jar.JarInputStream
import kotlin.io.path.exists


object ModService {
    var preferMirror = true
    var rdiModDownloadUrlProvider: (Mod) -> String? = { null }
    var rdiModDownloadHeadersProvider: (String) -> Map<String, String> = { emptyMap() }
    const val NEOFORGE_CONFIG_PATH = "META-INF/neoforge.mods.toml"
    const val FABRIC_CONFIG_PATH = "fabric.mod.json"
    const val FORGE_CONFIG_PATH = "META-INF/mods.toml"
    const val LEGACY_FORGE_CONFIG_PATH = "mcmod.info"
    private val lgr by Loggers
    private val modsToml = Toml { ignoreUnknownKeys = true }
    private val supportedModsTomlPaths = listOf(NEOFORGE_CONFIG_PATH, FORGE_CONFIG_PATH)
    private const val MAX_NESTED_JAR_DEPTH = 4
    private const val MAX_NESTED_JAR_BYTES = 32 * 1024 * 1024
    private const val MAX_NESTED_JAR_TOTAL_BYTES = 128 * 1024 * 1024
    private const val MAX_NESTED_METADATA_BYTES = 1024 * 1024

    /**
     * GitHub host records before the SHA-256 migration contain a SHA-1 digest.
     * Keep invalid hashes on the existing SHA-256 path so the old failure
     * behavior is unchanged.
     */
    fun githubUsesSha1(hash: String): Boolean =
        hash.trim().matches(Regex("[0-9a-fA-F]{40}"))

    val downloadedMods = DL_MOD_DIR.listFiles { it.extension == "jar" }?.toMutableList() ?: mutableListOf()
    var installedMods = DL_MOD_DIR.listFiles { it.extension == "jar" }?.toMutableList() ?: mutableListOf()

    fun JarFile.readNeoForgeConfig(): ModsTomlConfig? {
        return supportedModsTomlPaths.firstNotNullOfOrNull(::getJarEntry)?.let { modsTomlEntry ->
            getInputStream(modsTomlEntry).bufferedReader().use { reader ->
                parseModsToml(reader.readText())
            }
        }
    }

    fun JarFile.readModMeta(): JarModMeta? {
        readNeoForgeConfig()?.let { config ->
            val modEntries = config.mods
            val modIds = modEntries.mapNotNull { entry ->
                entry.modId.trim().lowercase().ifBlank { null }
            }.distinct()
            if (modIds.isNotEmpty()) {
                if (modIds.size > 1) {
                    lgr.warn { "Jar ${name}声明了多个modId: ${modIds.joinToString()}，将使用第一个${modIds.first()}" }
                }
                val primary = modEntries.firstOrNull { it.modId.isNotBlank() }
                return JarModMeta(
                    modIds = modIds,
                    version = primary?.version?.trim()?.ifBlank { null },
                    description = primary?.description?.trim()?.ifBlank { null },
                    logoFile = primary?.logoFile?.toJarIconPath()
                )
            }
        }
        getJarEntry(FABRIC_CONFIG_PATH)?.let { entry ->
            val metadata = getInputStream(entry).use {
                it.readBoundedBytes(MAX_NESTED_METADATA_BYTES).toString(Charsets.UTF_8)
            }
            parseFabricModMeta(metadata)?.let { return it }
        }
        return readLegacyForgeModMeta()
    }

    val ModsTomlConfig.modId
        get() = mods.firstOrNull()?.modId.orEmpty()
    val ModsTomlConfig.modDescription
        get() = mods.firstOrNull()?.description.orEmpty()
    val JarFile.modLogo
        get(): ByteArray? {
            val meta = readModMeta()
            val candidates = buildList {
                meta?.logoFile?.let { add(it) }
                meta?.primaryModId?.let { modId ->
                    add("assets/$modId/icon.png")
                    add("assets/$modId/logo.png")
                }
                add("logo.png")
                add("icon.png")
            }
            return candidates.distinct().firstNotNullOfOrNull { path ->
                getJarEntry(path)?.takeIf { !it.isDirectory }?.let { logoEntry ->
                    getInputStream(logoEntry).use { it.readBytes() }
                }
            }
        }

    fun buildIconUrls(vararg urls: String?): List<String> = buildIconUrls(urls.asIterable())

    fun buildIconUrls(urls: Iterable<String?>): List<String> =
        urls
            .asSequence()
            .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
            .toList()

    private fun String.toJarIconPath(): String? =
        trim()
            .replace('\\', '/')
            .trimStart('/')
            .ifBlank { null }

    private val builtinDependencyIds = setOf(
        "minecraft", "forge", "neoforge", "fabricloader", "fabric-api", "fabric-language-kotlin"
    )

    fun List<File>.filterServerOnlyMods() =
        filterNot { file ->
            JarFile(file).use { jar ->
                jar.readModMeta()?.side?.let { return@use it == Mod.Side.CLIENT }
                val config = jar.readNeoForgeConfig() ?: return@use false
                config.mods.any { modConfig ->
                    val modId = modConfig.modId.trim().lowercase().ifEmpty { return@any false }
                    extractModsTomlDependencies(config, modId).any { dependency ->
                        val dependencyModId = dependency.modId.trim().lowercase()
                        val side = dependency.side?.trim()?.uppercase()
                        dependencyModId == "minecraft" && side == "CLIENT"
                    }
                }
            }
        }.toMutableList()

    data class UnmatchedDependencies(
        val modId: String,
        val missing: List<Missing>
    ) {
        data class Missing(val modId: String, val version: String? = null)
    }

    fun List<File>.checkDependencies(): List<UnmatchedDependencies> {
        val installedModIds = mutableSetOf<String>()
        this.forEach { file ->
            runCatching {
                JarFile(file).use { jar ->
                    collectModIdsFromJar(jar, installedModIds)
                }
            }.onFailure { err ->
                lgr.error { "Failed to read mod id from file: ${file.name + "\n" + err}" }
            }
        }

        val unmatched = mutableListOf<UnmatchedDependencies>()

        this.forEach { file ->
            runCatching {
                JarFile(file).use { jar ->
                    val config = jar.readNeoForgeConfig() ?: return@use null
                    config.mods.forEach { modConfig ->
                        val modId = modConfig.modId.trim().lowercase().ifEmpty { return@forEach }
                        val missingDependencies = extractModsTomlDependencies(config, modId).mapNotNull { dependency ->
                            val dependencyModId = dependency.modId.trim().lowercase().ifEmpty { return@mapNotNull null }
                            if (dependencyModId.isEmpty() || dependencyModId.isBuiltinDependencyId()) return@mapNotNull null

                            val side = dependency.side?.trim()?.uppercase()
                            if (side == "CLIENT") return@mapNotNull null

                            val dependencyType = dependency.type?.trim()?.lowercase()
                            val optional = dependency.optional
                            val mandatory = dependency.mandatory
                            val required = when {
                                dependencyType == "optional" -> false
                                dependencyType == "incompatible" -> false
                                mandatory != null -> mandatory
                                optional -> false
                                dependencyType == "required" -> true
                                dependencyType == "discouraged" -> false
                                dependencyType == null -> true

                                else -> true
                            }
                            if (!required) return@mapNotNull null

                            if (installedModIds.contains(dependencyModId)) return@mapNotNull null

                            val versionRange = dependency.versionRange?.takeIf { it.isNotBlank() }
                            UnmatchedDependencies.Missing(dependencyModId, versionRange)
                        }
                            .distinctBy { it.modId }
                        if (missingDependencies.isNotEmpty()) {
                            lgr.warn {
                                "Mod '$modId' is missing dependencies: ${
                                    missingDependencies.joinToString(", ") { missing ->
                                        missing.version?.let { ver -> "${missing.modId} ($ver)" } ?: missing.modId
                                    }
                                }"
                            }
                            unmatched += UnmatchedDependencies(modId, missingDependencies)
                        }
                    }
                }
            }.onFailure { err ->
                lgr.error { "Failed to check dependencies for mod file: ${file.name + "\n" + err}" }
            }
        }
        return unmatched
    }

    private fun collectModIdsFromJar(jar: JarFile, installedModIds: MutableSet<String>) {
        val meta = jar.readModMeta()
        meta?.let {
            installedModIds += meta.modIds
        }

        val visitedNestedJars = mutableSetOf<String>()
        val nestedJarBudget = NestedJarInspectionBudget()
        meta?.nestedJarPaths.orEmpty().forEach { path ->
            runCatching {
                val entry = jar.getJarEntry(path) ?: return@runCatching
                if (entry.isDirectory) return@runCatching
                val bytes = jar.getInputStream(entry).use { it.readBoundedBytes(MAX_NESTED_JAR_BYTES) }
                collectModIdsFromNestedJar(bytes, installedModIds, visitedNestedJars, 1, nestedJarBudget)
            }.onFailure { err ->
                lgr.warn(err) { "Failed to inspect Fabric nested jar '$path' inside ${jar.name}" }
            }
        }

        val entries = jar.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            if (!name.startsWith("META-INF/jarjar/", ignoreCase = true)) continue
            if (!name.endsWith(".jar", ignoreCase = true)) continue

            runCatching {
                jar.getInputStream(entry).use { nestedInput ->
                    collectModIdsFromNestedJar(nestedInput, installedModIds)
                }
            }.onFailure { err ->
                lgr.warn { "Failed to inspect nested jar '$name' inside ${jar.name}" }
                err.printStackTrace()
            }
        }
    }

    private fun collectModIdsFromNestedJar(inputStream: InputStream, installedModIds: MutableSet<String>) {
        JarInputStream(inputStream).use { nestedJar ->
            var entry = nestedJar.nextJarEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name
                    when {
                        supportedModsTomlPaths.any { it.equals(entryName, ignoreCase = false) } -> {
                            val configText = nestedJar.readBytes().toString(Charsets.UTF_8)
                            parseModsToml(configText)?.let { config ->
                                installedModIds += extractModIds(config)
                            }
                        }

                        entryName.startsWith("META-INF/jarjar/", ignoreCase = true) &&
                            entryName.endsWith(".jar", ignoreCase = true) -> {
                            collectModIdsFromNestedJar(ByteArrayInputStream(nestedJar.readBytes()), installedModIds)
                        }
                    }
                }
                nestedJar.closeEntry()
                entry = nestedJar.nextJarEntry
            }
        }
    }

    private fun collectModIdsFromNestedJar(
        bytes: ByteArray,
        installedModIds: MutableSet<String>,
        visited: MutableSet<String>,
        depth: Int,
        budget: NestedJarInspectionBudget
    ) {
        if (depth > MAX_NESTED_JAR_DEPTH) return
        budget.consume(bytes.size)
        val fingerprint = bytes.sha1
        if (!visited.add(fingerprint)) return
        val nestedPaths = mutableListOf<String>()
        JarInputStream(ByteArrayInputStream(bytes)).use { nestedJar ->
            var entry = nestedJar.nextJarEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val entryName = entry.name
                    when {
                        entryName == FABRIC_CONFIG_PATH -> {
                            val configText = nestedJar.readBoundedBytes(MAX_NESTED_METADATA_BYTES).toString(Charsets.UTF_8)
                            parseFabricModMeta(configText)?.let { meta ->
                                installedModIds += meta.modIds
                                nestedPaths += meta.nestedJarPaths
                            }
                        }
                        supportedModsTomlPaths.any { it.equals(entryName, ignoreCase = false) } -> {
                            val configText = nestedJar.readBoundedBytes(MAX_NESTED_METADATA_BYTES).toString(Charsets.UTF_8)
                            parseModsToml(configText)?.let { config ->
                                installedModIds += extractModIds(config)
                            }
                        }

                        entryName.startsWith("META-INF/jarjar/", ignoreCase = true) &&
                                entryName.endsWith(".jar", ignoreCase = true) -> {
                            val nestedBytes = nestedJar.readBoundedBytes(MAX_NESTED_JAR_BYTES)
                            collectModIdsFromNestedJar(nestedBytes, installedModIds, visited, depth + 1, budget)
                        }
                    }
                }
                nestedJar.closeEntry()
                entry = nestedJar.nextJarEntry
            }
        }
        if (depth < MAX_NESTED_JAR_DEPTH) {
            JarInputStream(ByteArrayInputStream(bytes)).use { nestedJar ->
                var entry = nestedJar.nextJarEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name in nestedPaths) {
                        val nestedBytes = nestedJar.readBoundedBytes(MAX_NESTED_JAR_BYTES)
                        collectModIdsFromNestedJar(nestedBytes, installedModIds, visited, depth + 1, budget)
                    }
                    nestedJar.closeEntry()
                    entry = nestedJar.nextJarEntry
                }
            }
        }
    }

    private fun parseFabricModMeta(raw: String): JarModMeta? = runCatching {
        val root = serdesJson.parseToJsonElement(raw) as? JsonObject ?: return null
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.takeIf(String::isNotBlank)
            ?: return null
        val icon = when (val iconElement = root["icon"]) {
            is JsonPrimitive -> iconElement.contentOrNull?.let(::normalizeFabricJarPath)
            is JsonObject -> iconElement.entries
                .mapNotNull { (size, value) ->
                    val path = (value as? JsonPrimitive)?.contentOrNull?.let(::normalizeFabricJarPath)
                        ?: return@mapNotNull null
                    size.toIntOrNull()?.let { Triple(it, size, path) } ?: Triple(-1, size, path)
                }
                .sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }
                    .thenBy { it.second }.thenBy { it.third })
                .firstOrNull()?.third
            else -> null
        }
        val side = when ((root["environment"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
            "client" -> Mod.Side.CLIENT
            "server" -> Mod.Side.SERVER
            else -> null
        }
        val dependencies = (root["depends"] as? JsonObject).orEmpty().mapNotNull { (dependencyId, value) ->
            val normalizedId = dependencyId.trim().lowercase().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val versionExpression = when (value) {
                is JsonPrimitive -> value.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                is kotlinx.serialization.json.JsonArray -> value.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                }.joinToString(" || ").takeIf(String::isNotBlank)
                else -> null
            }
            JarModDependency(normalizedId, versionExpression)
        }
        val nestedJars = (root["jars"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { entry ->
                val path = ((entry as? JsonObject)?.get("file") as? JsonPrimitive)?.contentOrNull
                    ?.let(::normalizeFabricJarPath)
                path
            }.distinct()
        JarModMeta(
            modIds = listOf(id),
            version = (root["version"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
            description = (root["description"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank),
            logoFile = icon,
            side = side,
            requiredDependencies = dependencies,
            nestedJarPaths = nestedJars
        )
    }.onFailure { err ->
        lgr.debug(err) { "Failed to parse fabric.mod.json" }
    }.getOrNull()

    private fun normalizeFabricJarPath(raw: String): String? {
        val normalized = raw.trim().replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || normalized.startsWith("//") ||
            Regex("^[A-Za-z]:").containsMatchIn(normalized) || ':' in normalized || '\u0000' in normalized
        ) return null
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) return null
        return segments.filter { it.isNotBlank() && it != "." }.joinToString("/").ifBlank { null }
    }

    private fun InputStream.readBoundedBytes(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Archive entry exceeds $maxBytes bytes" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private class NestedJarInspectionBudget(
        private var remainingBytes: Int = MAX_NESTED_JAR_TOTAL_BYTES
    ) {
        fun consume(bytes: Int) {
            require(bytes <= remainingBytes) { "Nested jar inspection exceeds $MAX_NESTED_JAR_TOTAL_BYTES bytes" }
            remainingBytes -= bytes
        }
    }

    private fun parseModsToml(raw: String): ModsTomlConfig? {
        if (raw.isBlank()) return null
        return runCatching {
            modsToml.decodeFromString<ModsTomlConfig>(raw)
        }.onFailure { err ->
            lgr.debug(err) { "Failed to parse mods.toml" }
        }.getOrNull()
    }

    private fun JarFile.readLegacyForgeModMeta(): JarModMeta? {
        val mcmodInfoEntry = getJarEntry(LEGACY_FORGE_CONFIG_PATH) ?: return null
        val entries = getInputStream(mcmodInfoEntry).bufferedReader().use { reader ->
            parseLegacyForgeModEntries(reader.readText())
        }
        if (entries.isEmpty()) return null
        val modIds = entries.mapNotNull { entry ->
            entry.modId.trim().lowercase().ifBlank { null }
        }.distinct()
        if (modIds.isEmpty()) return null
        if (modIds.size > 1) {
            lgr.warn { "Jar ${name}声明了多个legacy modId: ${modIds.joinToString()}，将使用第一个${modIds.first()}" }
        }
        val primary = entries.firstOrNull { it.modId.isNotBlank() }
        return JarModMeta(
            modIds = modIds,
            version = primary?.version?.trim()?.ifBlank { null },
            description = primary?.description?.trim()?.ifBlank { null },
            logoFile = primary?.logoFile?.toJarIconPath()
        )
    }

    private fun parseLegacyForgeModEntries(raw: String): List<LegacyMcmodInfoEntry> {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return emptyList()
        return when {
            trimmed.startsWith("[") -> raw.deser<List<LegacyMcmodInfoEntry>>().getOrElse { err ->
                lgr.debug(err) { "Failed to parse legacy mcmod.info array" }
                emptyList()
            }

            trimmed.startsWith("{") -> raw.deser<LegacyMcmodInfoContainer>().map { it.modList }.getOrElse { err ->
                lgr.debug(err) { "Failed to parse legacy mcmod.info object" }
                emptyList()
            }

            else -> emptyList()
        }
    }

    private fun extractModIds(config: ModsTomlConfig?): List<String> {
        return config?.mods.orEmpty()
            .mapNotNull { modConfig ->
                modConfig.modId
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotEmpty() }
            }
    }

    private fun extractModsTomlDependencies(config: ModsTomlConfig, modId: String): List<ModsTomlDependency> {
        val normalizedModId = modId.trim()
        if (normalizedModId.isEmpty()) return emptyList()
        return config.dependencies.entries
            .firstOrNull { (key, _) -> key.equals(normalizedModId, ignoreCase = true) }
            ?.value
            .orEmpty()
    }

    private fun String.isBuiltinDependencyId(): Boolean =
        this in builtinDependencyIds || startsWith("fabric-api-")


    val String.ofMirrorUrl
        get() = this.replace("edge.forgecdn.net", "mod.mcimirror.top")
            .replace("mediafilez.forgecdn.net", "mod.mcimirror.top")
            .replace("media.forgecdn.net", "mod.mcimirror.top")
            .replace("api.modrinth.com", "mod.mcimirror.top/modrinth")
            .replace("staging-api.modrinth.com", "mod.mcimirror.top/modrinth")
            .replace("cdn.modrinth.com", "mod.mcimirror.top")
            .replace("api.curseforge.com", "mod.mcimirror.top/curseforge")

    fun downloadModsTask2(mods: List<Mod>, targetDir: File = DL_MOD_DIR): Task2 {
        if (mods.isEmpty()) return Task2.Group("下载Mod", emptyList())
        val cfMods = mods.filter { it.platform == "cf" }
        val mrMods = mods.filter { it.platform == "mr" }
        val githubMods = mods.filter { it.platform == "github" }
        return Task2.Sequence(
            title = "下载${mods.size}个Mod",
            children = buildList {
                add(downloadCFModsTask2(cfMods, targetDir))
                add(downloadMRModsTask2(mrMods, targetDir))
                add(downloadGithubModsTask2(githubMods, targetDir))
            }
        )
    }

    fun isDownloadedModFileValid(mod: Mod, targetDir: File = DL_MOD_DIR): Boolean {
        val targetPath = mod.targetPath(targetDir)
        val validPath = findValidDownloadedModPath(mod, targetDir) ?: return false
        if (validPath != targetPath) {
            copyExistingModFile(validPath, targetPath)
        }
        return true
    }

    private fun findValidDownloadedModPath(mod: Mod, targetDir: File): Path? =
        mod.downloadedFileCandidates(targetDir).firstOrNull { path ->
            path.exists() && isDownloadedModFileValid(mod, path)
        }

    private fun isDownloadedModFileValid(mod: Mod, targetPath: Path): Boolean {
        val expectedHash = mod.hash.trim().lowercase()
        return runCatching {
            when (mod.platform.lowercase()) {
                "cf" -> {
                    val expectedFingerprint = expectedHash.toLongOrNull()
                    expectedFingerprint != null && targetPath.murmur2 == expectedFingerprint
                }

                "mr" -> targetPath.sha1 == expectedHash
                "github" -> if (githubUsesSha1(expectedHash)) {
                    targetPath.sha1 == expectedHash
                } else {
                    targetPath.toFile().sha256 == expectedHash
                }
                else -> true
            }
        }.getOrDefault(false)
    }

    private fun Mod.downloadedFileCandidates(targetDir: File): List<Path> =
        (candidateFiles(targetDir) + legacyGithubCandidateFiles(targetDir))
            .map(File::toPath)
            .distinctBy { it.toAbsolutePath().normalize() }

    /**
     * GitHub records can use historical SHA-1 or current SHA-256 filenames.
     * Keep looking at those aliases, but accept one only after validating the
     * bytes with the record-declared SHA-1/SHA-256 contract.
     */
    private fun Mod.legacyGithubCandidateFiles(targetDir: File): List<File> {
        if (!platform.equals("github", ignoreCase = true)) return emptyList()
        val prefixes = listOf(fileSlug, slug)
            .map { "${it}_github_".lowercase(Locale.ROOT) }
            .distinct()
        return targetDir.listFiles { file ->
            file.isFile &&
                file.extension.equals("jar", ignoreCase = true) &&
                prefixes.any { prefix -> file.name.lowercase(Locale.ROOT).startsWith(prefix) }
        }?.toList().orEmpty()
    }

    private fun copyExistingModFile(source: Path, target: Path) {
        if (source.toAbsolutePath().normalize() == target.toAbsolutePath().normalize()) return
        target.parent?.let { Files.createDirectories(it) }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    }

    fun downloadCFModsTask2(mods: List<Mod>, targetDir: File = DL_MOD_DIR): Task2 {
        if (mods.isEmpty()) return Task2.Group("下载CurseForge Mod", emptyList())
        val fileInfoMap = mutableMapOf<Int, CurseForgeFile>()
        val aggregateProgress = createBatchProgressTracker2(mods)
        val prepareTask = Task2.Leaf("获取CurseForge文件信息") { ctx ->
            val unresolvedMods = mods.filterNot { it.trustedCurseForgeFile() != null }
            val fileInfos = prepareCurseForgeFileInfos(unresolvedMods)
            fileInfoMap.clear()
            fileInfoMap.putAll(fileInfos)
            ctx.emit(Task2Progress("获取完成", 1f))
        }
        val tasks = mods.map { mod ->
            Task2.Leaf("下载 ${mod.slug}") { ctx ->
                val fileInfo = mod.trustedCurseForgeFile()
                    ?: fileInfoMap[mod.fileId.toIntOrNull()]
                    ?: throw IllegalStateException("未找到文件信息: ${mod.slug}")
                val result = downloadSingleCFMod(mod, fileInfo, targetDir) { progress ->
                    ctx.emit(aggregateProgress(mod, progress))
                }
                result.getOrElse { throw it }
                ctx.emit(aggregateProgress(mod, DownloadProgress(1, 1, 0.0)))
            }
        }
        return Task2.Sequence(
            title = "下载CurseForge Mod",
            children = listOf(
                prepareTask,
                Task2.Group("下载CurseForge Mod", tasks)
            )
        )
    }

    fun downloadMRModsTask2(mods: List<Mod>, targetDir: File = DL_MOD_DIR): Task2 {
        if (mods.isEmpty()) return Task2.Group("下载Modrinth Mod", emptyList())
        val modsWithUrls = mods.filter { it.downloadUrls.isNotEmpty() }
        val aggregateProgress = createBatchProgressTracker2(modsWithUrls)
        val tasks = modsWithUrls.map { mod ->
            Task2.Leaf("下载 ${mod.slug}") { ctx ->
                val result = downloadSingleMRMod(mod, targetDir) { progress ->
                    ctx.emit(aggregateProgress(mod, progress))
                }
                result.getOrElse { throw it }
                ctx.emit(aggregateProgress(mod, DownloadProgress(1, 1, 0.0)))
            }
        }
        return Task2.Group("下载Modrinth Mod", tasks)
    }

    fun downloadGithubModsTask2(mods: List<Mod>, targetDir: File = DL_MOD_DIR): Task2 {
        if (mods.isEmpty()) return Task2.Group("下载GitHub Mod", emptyList())
        val modsWithUrls = mods.filter { it.downloadUrls.isNotEmpty() }
        val aggregateProgress = createBatchProgressTracker2(modsWithUrls)
        val tasks = modsWithUrls.map { mod ->
            Task2.Leaf("下载 ${mod.slug}") { ctx ->
                val result = downloadSingleGithubMod(mod, targetDir) { progress ->
                    ctx.emit(aggregateProgress(mod, progress))
                }
                result.getOrElse { throw it }
                ctx.emit(aggregateProgress(mod, DownloadProgress(1, 1, 0.0)))
            }
        }
        return Task2.Group("下载GitHub Mod", tasks)
    }

    /**
     * Downloads one mod into the caller-owned temporary path while retaining the
     * existing platform/source ordering.  The normal task API and its default
     * DL_MOD_DIR are intentionally unchanged for server callers.
     */
    suspend fun downloadModToPath(
        mod: Mod,
        targetPath: Path,
        onProgress: (DownloadProgress) -> Unit = {},
        preparedCurseForgeFile: CurseForgeFile? = null
    ): Result<Path> {
        return runCatching {
            targetPath.parent?.let { Files.createDirectories(it) }
            val stagingDir = Files.createTempDirectory(
                targetPath.parent ?: targetPath.toAbsolutePath().parent ?: Paths.get(".").toAbsolutePath(),
                "mod-source-"
            )
            try {
                val downloaded = when (mod.platform.lowercase()) {
                    "cf" -> {
                        val info = preparedCurseForgeFile ?: CurseForgeService
                            .getModFilesInfo(listOf(mod.fileId.toInt()))
                            .associateBy { it.id }[mod.fileId.toInt()]
                            ?: error("未找到文件信息: ${mod.slug}")
                        downloadSingleCFMod(mod, info, stagingDir.toFile(), onProgress)
                    }

                    "mr" -> downloadSingleMRMod(mod, stagingDir.toFile(), onProgress)

                    "github" -> downloadSingleGithubMod(mod, stagingDir.toFile(), onProgress)

                    else -> error("不支持的Mod平台: ${mod.platform}")
                }.getOrThrow()
                if (downloaded.toAbsolutePath().normalize() != targetPath.toAbsolutePath().normalize()) {
                    Files.move(
                        downloaded,
                        targetPath,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                targetPath
            } finally {
                runCatching {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val stagingAttributes = Files.readAttributes(
                            stagingDir,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        if (!stagingAttributes.isDirectory || stagingAttributes.isSymbolicLink || stagingAttributes.isOther) {
                            throw IOException("Refusing to clean non-directory mod staging root: $stagingDir")
                        }
                        Files.newDirectoryStream(stagingDir).use { children ->
                            children.forEach { child ->
                                if (!Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                                    throw IOException("Refusing to delete non-file mod staging entry: $child")
                                }
                                Files.deleteIfExists(child)
                            }
                        }
                        Files.deleteIfExists(stagingDir)
                    }
                }.onFailure { error ->
                    lgr.error(error) { "Failed to clean mod staging directory: $stagingDir" }
                }
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            lgr.error(error) { "Failed to download mod ${mod.slug} to temporary content path" }
        }
    }

    /**
     * Resolve the CurseForge file metadata required by a batch download.
     * Mods which already carry a non-empty URL and a valid Murmur2 fingerprint
     * are trusted and do not need a metadata request.  All remaining file IDs
     * are looked up in one batched request (the service itself handles mirror
     * fallback), and the result is keyed by file ID rather than list position.
     */
    suspend fun prepareCurseForgeFileInfos(
        mods: List<Mod>,
        fetch: suspend (List<Int>) -> List<CurseForgeFile> = CurseForgeService::getModFilesInfo
    ): Map<Int, CurseForgeFile> {
        val fileIds = mods.asSequence()
            .filter { it.platform.equals("cf", ignoreCase = true) }
            .filterNot { it.trustedCurseForgeFile() != null }
            .mapNotNull { it.fileId.toIntOrNull() }
            .distinct()
            .toList()
        if (fileIds.isEmpty()) return emptyMap()
        return fetch(fileIds).associateBy { it.id }
    }

    /** A file descriptor is sufficient when the caller already has URL+hash. */
    fun Mod.trustedCurseForgeFile(): CurseForgeFile? {
        if (!platform.equals("cf", ignoreCase = true)) return null
        val fileId = fileId.toIntOrNull() ?: return null
        val fingerprint = hash.trim().toLongOrNull() ?: return null
        if (downloadUrls.none { it.trim().isNotEmpty() }) return null
        return CurseForgeFile(
            id = fileId,
            modId = projectId.toIntOrNull() ?: 0,
            fileName = fileName,
            downloadUrl = downloadUrls.first { it.trim().isNotEmpty() },
            fileFingerprint = fingerprint,
            isAvailable = true,
        )
    }

    private fun createBatchProgressTracker2(mods: List<Mod>): (Mod, DownloadProgress) -> Task2Progress {
        if (mods.isEmpty()) return { mod, _ -> Task2Progress("Mod下载中 ${mod.slug}", 1f) }
        val totalMods = mods.size.toDouble()
        val progressMap = linkedMapOf<String, DownloadProgress>().apply {
            mods.forEach { put(it.batchProgressKey, DownloadProgress(0, 0, 0.0)) }
        }
        val lock = Any()
        return { mod, progress ->
            val snapshot = synchronized(lock) {
                progressMap[mod.batchProgressKey] = progress
                val overallFraction = (
                    progressMap.values.sumOf { it.fraction.coerceIn(0f, 1f).toDouble() } / totalMods
                ).toFloat().coerceIn(0f, 1f)
                val totalSpeed = progressMap.values.sumOf { it.speedBytesPerSecond.coerceAtLeast(0.0) }
                val doneCount = progressMap.values.count { it.fraction >= 1f }
                Triple(overallFraction, totalSpeed, doneCount)
            }
            val (overallFraction, totalSpeed, doneCount) = snapshot
            Task2Progress(
                "Mod下载中 ${doneCount}/${mods.size} ${mod.slug} · 总速度${totalSpeed.humanSpeed}",
                overallFraction
            )
        }
    }

    private val Mod.batchProgressKey: String
        get() = "${platform}:${projectId}:${fileId}:${slug}"

    private suspend fun downloadSingleCFMod(
        mod: Mod,
        fileInfo: CurseForgeFile,
        targetDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Path> {
        val expectedFingerprint = fileInfo.fileFingerprint
        return downloadSingleModFromSources(
            mod = mod,
            targetDir = targetDir,
            officialUrls = mod.downloadUrls + fileInfo.realDownloadUrl,
            localRequest = LocalArtifactRequest(
                algorithm = LocalArtifactHashAlgorithm.CURSEFORGE_MURMUR2,
                hash = expectedFingerprint.toString()
            ),
            existingFileMatches = { path -> path.exists() && path.murmur2 == expectedFingerprint },
            validator = { path ->
                val actualFingerprint = path.murmur2
                if (actualFingerprint == expectedFingerprint) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        IllegalStateException(
                            "Downloaded mod ${mod.slug} fingerprint mismatch: expected $expectedFingerprint, got $actualFingerprint"
                        )
                    )
                }
            },
            onProgress = onProgress
        )
    }

    private suspend fun downloadSingleMRMod(
        mod: Mod,
        targetDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Path> {
        val expectedHash = mod.hash.trim().lowercase()
        return downloadSingleModFromSources(
            mod = mod,
            targetDir = targetDir,
            officialUrls = mod.downloadUrls,
            localRequest = LocalArtifactRequest(
                algorithm = LocalArtifactHashAlgorithm.SHA1,
                hash = expectedHash
            ),
            existingFileMatches = { path -> expectedHash.isNotBlank() && path.exists() && path.sha1 == expectedHash },
            validator = { path ->
                if (expectedHash.isBlank()) {
                    Result.success(Unit)
                } else {
                    val actualHash = path.sha1
                    if (actualHash == expectedHash) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            IllegalStateException(
                                "Downloaded mod ${mod.slug} SHA1 mismatch: expected $expectedHash, got $actualHash"
                            )
                        )
                    }
                }
            },
            onProgress = onProgress
        )
    }

    private suspend fun downloadSingleGithubMod(
        mod: Mod,
        targetDir: File,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Path> {
        val expectedHash = mod.hash.trim().lowercase()
        val usesSha1 = githubUsesSha1(expectedHash)
        val algorithm = if (usesSha1) LocalArtifactHashAlgorithm.SHA1 else LocalArtifactHashAlgorithm.SHA256
        val algorithmName = if (usesSha1) "SHA-1" else "SHA-256"
        val hashOf: (Path) -> String = { path ->
            if (usesSha1) path.sha1 else path.toFile().sha256
        }
        return downloadSingleModFromSources(
            mod = mod,
            targetDir = targetDir,
            officialUrls = mod.downloadUrls,
            localRequest = LocalArtifactRequest(
                algorithm = algorithm,
                hash = expectedHash
            ),
            existingFileMatches = {
                path -> expectedHash.isNotBlank() && path.exists() && hashOf(path) == expectedHash
            },
            validator = { path ->
                if (expectedHash.isBlank()) {
                    Result.success(Unit)
                } else {
                    val actualHash = hashOf(path)
                    if (actualHash == expectedHash) {
                        Result.success(Unit)
                    } else {
                        Result.failure(
                            IllegalStateException(
                                "Downloaded mod ${mod.slug} $algorithmName mismatch: expected $expectedHash, got $actualHash"
                            )
                        )
                    }
                }
            },
            onProgress = onProgress
        )
    }

    private suspend fun downloadSingleModFromSources(
        mod: Mod,
        targetDir: File,
        officialUrls: List<String>,
        localRequest: LocalArtifactRequest,
        existingFileMatches: (Path) -> Boolean,
        validator: suspend (Path) -> Result<Unit>,
        onProgress: (DownloadProgress) -> Unit
    ): Result<Path> {
        val targetPath = mod.targetPath(targetDir)
        val existingPath = mod.downloadedFileCandidates(targetDir).firstOrNull(existingFileMatches)
        if (existingPath != null) {
            copyExistingModFile(existingPath, targetPath)
            lgr.debug { "Mod file already exists and hash matches: $existingPath" }
            return Result.success(targetPath)
        }

        val localSource = if (localRequest.hash.isBlank()) {
            null
        } else {
            LocalArtifactReuse.reuse(localRequest, targetPath)
                .onFailure { lgr.warn(it) { "查找本地Mod失败，将使用网络下载：${mod.slug}" } }
                .getOrNull()
        }
        if (localSource != null) {
            val size = Files.size(targetPath)
            onProgress(DownloadProgress(size, size, 0.0))
            lgr.info { "从本地复用Mod${mod.slug}：$localSource" }
            return Result.success(targetPath)
        }

        val cleanOfficialUrls = officialUrls
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
        val mirrorUrls = cleanOfficialUrls.asSequence()
            .map { it.ofMirrorUrl }
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it in cleanOfficialUrls }
            .distinct()
            .toList()
        val rdiUrls = listOfNotNull(rdiModDownloadUrlProvider(mod))
        val allCandidateUrls = if (preferMirror) {
            rdiUrls + mirrorUrls + cleanOfficialUrls
        } else {
            cleanOfficialUrls + rdiUrls
        }.distinct()

        val result = runCatching {
            targetPath.downloadFileFrom(
                urls = allCandidateUrls,
                urlHeadersProvider = { url ->
                    if (url in rdiUrls) {
                        rdiModDownloadHeadersProvider(url)
                    } else {
                        CurseForgeService.downloadHeadersFor(url)
                    }
                },
                validator = validator,
                onProgress = onProgress
            ).getOrElse { throw it }
        }.onFailure { err ->
            lgr.warn { "Download failed for ${mod.slug} from ${allCandidateUrls.joinToString()}\n$err" }
        }

        result.onFailure { err ->
            lgr.error { "Failed to download mod ${mod.slug + "\n" + err}" }
        }
        return result
    }

    fun MutableList<Mod>.postProcessModSides(): MutableList<Mod> {
        if (isEmpty()) return this

        val forceBoth = setOf(
            "loot-beams-refork",
            "particular-reforged",
            "inventory-profiles-next",
            "inventory-tweaks-refoxed",
            "just-enough-resources-jer",
            "radiant-gear",
            "fusion-connected-textures",
            "apothic-attributes",
            "the-twilight-forest",
        )
        val forceClient = setOf(
            "status-effect-bars-reforged",
            "mafglib",
            "flighthud-reborn",
            "i18nupdatemod",
            "modern-ui",
            "controllable",
            "reforgedplay-mod"
        )

        forEach { mod ->
            val slug = mod.slug.trim().lowercase()
            when {
                slug.startsWith("ftb") -> mod.side = Mod.Side.BOTH
                slug in forceBoth -> mod.side = Mod.Side.BOTH
                slug in forceClient -> mod.side = Mod.Side.CLIENT
            }
        }
        return this
    }
}



