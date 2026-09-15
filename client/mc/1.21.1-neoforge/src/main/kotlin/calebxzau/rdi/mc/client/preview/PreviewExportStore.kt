@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.preview

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PreviewExportStore(
    val root: Path,
    private val atomicMove: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    }
) {
    private val generationLock = Any()
    private var currentExportId: String? = null

    fun beginGeneration(): String = synchronized(generationLock) {
        Uuid.generateV7().toString().also { currentExportId = it }
    }

    fun invalidate() {
        synchronized(generationLock) {
            currentExportId = null
        }
    }

    fun isCurrent(exportId: String): Boolean = synchronized(generationLock) {
        currentExportId == exportId
    }

    fun readReusable(): Result<PreviewManifest?> = runCatching {
        val manifestPath = root.resolve(MANIFEST_FILE)
        if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            return@runCatching null
        }
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            error("preview manifest is not a regular file: $manifestPath")
        }

        val manifest = Json.decodeFromString<PreviewManifest>(
            Files.readString(manifestPath, StandardCharsets.UTF_8)
        )
        validateManifest(manifest, expectedExportId = null)
        manifest
    }

    fun writeLanguage(exportId: String, language: Map<String, String>): Result<String> = runCatching {
        validateExportId(exportId)
        val relativePath = languagePath(exportId)
        val destination = resolveSafe(relativePath)
        rejectSymbolicLinkParents(destination)
        destination.parent.createDirectories()
        require(!Files.isSymbolicLink(destination)) { "symbolic link language file is not allowed: $destination" }

        val json = buildJsonObject {
            language.toSortedMap().forEach { (key, value) -> put(key, value) }
        }.toString()
        Files.writeString(destination, json, StandardCharsets.UTF_8)
        relativePath
    }

    fun publish(exportId: String, manifest: PreviewManifest): Result<Boolean> = runCatching {
        validateExportId(exportId)
        validateManifest(manifest, expectedExportId = exportId)

        root.createDirectories()
        val temp = root.resolve("manifest.$exportId.${Uuid.generateV7()}.tmp")
        Files.writeString(temp, JSON.encodeToString(manifest), StandardCharsets.UTF_8)

        synchronized(generationLock) {
            if (currentExportId != exportId) {
                return@runCatching false
            }
            atomicMove(temp, root.resolve(MANIFEST_FILE))
            true
        }
    }

    private fun validateManifest(manifest: PreviewManifest, expectedExportId: String?) {
        require(manifest.formatVersion == FORMAT_VERSION) {
            "unsupported preview manifest format: ${manifest.formatVersion}"
        }
        require(manifest.iconSize == PreviewPacking.ICON_SIZE) {
            "unsupported preview icon size: ${manifest.iconSize}"
        }

        val generationIds = linkedSetOf<String>()
        manifest.pages.forEachIndexed { index, page ->
            require(page.width in MIN_PAGE_SIZE..MAX_PAGE_SIZE && page.width % PreviewPacking.ICON_SIZE == 0) {
                "invalid preview page width: ${page.width}"
            }
            require(page.height in MIN_PAGE_SIZE..MAX_PAGE_SIZE && page.height % PreviewPacking.ICON_SIZE == 0) {
                "invalid preview page height: ${page.height}"
            }

            val path = validateRelativePath(page.file)
            val parts = pathParts(path)
            require(parts.size == 3 && parts[0] == TEXTURE_PAGES_DIR && parts[2] == "$index.png") {
                "preview page path must be texture_pages/<uuid>/$index.png: ${page.file}"
            }
            val exportId = parts[1]
            validateExportId(exportId)
            generationIds += exportId
            if (expectedExportId != null) {
                require(exportId == expectedExportId) { "preview page belongs to another generation" }
            }
            requireRegularFile(path)
        }

        manifest.languageFile?.let { languageFile ->
            val path = validateRelativePath(languageFile)
            val parts = pathParts(path)
            require(parts.size == 3 && parts[0] == LANGUAGE_DIR && parts[2] == LANGUAGE_FILE) {
                "language file path must be lang/<uuid>/zh_cn.json: $languageFile"
            }
            val exportId = parts[1]
            validateExportId(exportId)
            generationIds += exportId
            if (expectedExportId != null) {
                require(exportId == expectedExportId) { "language file belongs to another generation" }
            }
            requireRegularFile(path)
        }

        require(generationIds.size <= 1) { "preview references belong to multiple generations" }

        val seenCells = HashSet<String>()
        manifest.items.forEach { (id, item) ->
            require(id.isNotBlank()) { "item ID must not be blank" }
            require(item.translationKey.isNotBlank()) { "translation key must not be blank" }
            require(item.page in manifest.pages.indices) { "item page is outside manifest pages: ${item.page}" }
            val page = manifest.pages[item.page]
            require(item.x >= 0 && item.y >= 0) { "item coordinates must be non-negative" }
            require(item.x % PreviewPacking.ICON_SIZE == 0 && item.y % PreviewPacking.ICON_SIZE == 0) {
                "item coordinates must be aligned to ${PreviewPacking.ICON_SIZE}"
            }
            require(item.x <= page.width - PreviewPacking.ICON_SIZE && item.y <= page.height - PreviewPacking.ICON_SIZE) {
                "item coordinates are outside page bounds"
            }
            require(seenCells.add("${item.page}:${item.x}:${item.y}")) {
                "duplicate preview cell for item: $id"
            }
        }

        manifest.failedItems.forEach { (id, reason) ->
            require(id.isNotBlank()) { "failed item ID must not be blank" }
            require(reason.isNotBlank()) { "failed item reason must not be blank" }
            require(!manifest.items.containsKey(id)) { "item is both successful and failed: $id" }
        }
    }

    private fun validateRelativePath(raw: String): Path {
        require(raw.isNotBlank()) { "path must not be blank" }
        require('\\' !in raw) { "backslash is not allowed in paths: $raw" }
        val path = Path.of(raw)
        require(!path.isAbsolute) { "path must be relative: $raw" }
        require(path.normalize() == path) { "path must be normalized: $raw" }
        require(path.nameCount > 0 && path.none { it.toString().isEmpty() }) { "invalid relative path: $raw" }
        require(!path.startsWith("..")) { "path traversal is not allowed: $raw" }
        resolveSafe(path.toString())
        return path
    }

    private fun resolveSafe(relativePath: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(relativePath).normalize()
        require(resolved.startsWith(normalizedRoot)) { "path escapes preview root: $relativePath" }
        return resolved
    }

    private fun requireRegularFile(relativePath: Path) {
        val resolved = resolveSafe(relativePath.toString())
        rejectSymbolicLinkParents(resolved)
        require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            "referenced preview file is missing or not regular: $relativePath"
        }
    }

    private fun rejectSymbolicLinkParents(path: Path) {
        var parent = path.parent
        val normalizedRoot = root.toAbsolutePath().normalize()
        while (parent != null && parent != normalizedRoot) {
            require(!Files.isSymbolicLink(parent)) { "symbolic link parent is not allowed: $parent" }
            parent = parent.parent
        }
    }

    private fun pathParts(path: Path): List<String> = (0 until path.nameCount).map { path.getName(it).toString() }

    private fun languagePath(exportId: String): String = "$LANGUAGE_DIR/$exportId/$LANGUAGE_FILE"

    private fun validateExportId(exportId: String) {
        val uuid = try {
            UUID.fromString(exportId)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("export ID must be a UUIDv7: $exportId", exception)
        }
        require(uuid.toString() == exportId) { "export ID must be canonical: $exportId" }
        require(uuid.version() == 7) { "export ID must be a UUIDv7: $exportId" }
        require(uuid.variant() == 2) { "export ID must use the RFC 4122 UUID variant: $exportId" }
    }

    private companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val TEXTURE_PAGES_DIR = "texture_pages"
        const val LANGUAGE_DIR = "lang"
        const val LANGUAGE_FILE = "zh_cn.json"
        const val MIN_PAGE_SIZE = PreviewPacking.ICON_SIZE
        const val MAX_PAGE_SIZE = 8192

        val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
