@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.preview

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class PreviewExportStore(
    val root: Path,
    private val atomicMove: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, ATOMIC_MOVE)
    }
) {
    private val generationLock = Any()
    private var currentExportId: String? = null
    private var pendingExportId: String? = null
    val pendingRoot: Path = root.resolveSibling(".pending")
    private val backupRoot: Path = root.resolveSibling(".preview-backup")

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

    fun recover(): Result<Unit> = runCatching {
        synchronized(generationLock) { recoverPublication() }
    }

    fun readReusable(): Result<PreviewManifest?> = runCatching {
        synchronized(generationLock) {
            recoverPublication()
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
            validateManifest(manifest, root, allowLegacy = true)
            manifest
        }
    }

    /** Called by the single I/O worker after any previous page writer has drained. */
    fun pageDestination(exportId: String, pageIndex: Int): Path = synchronized(generationLock) {
        require(pageIndex >= 0) { "page index must be non-negative" }
        preparePending(exportId)
        val destination = pendingRoot.resolve("$TEXTURE_PAGES_DIR/$pageIndex.png")
        rejectSymbolicLinkParents(destination)
        destination.parent.createDirectories()
        require(!Files.isSymbolicLink(destination)) { "symbolic link page is not allowed: $destination" }
        destination
    }

    fun writeLanguage(exportId: String, language: Map<String, String>): Result<String> = runCatching {
        synchronized(generationLock) {
            preparePending(exportId)
            val relativePath = "$LANGUAGE_DIR/$LANGUAGE_FILE"
            val destination = pendingRoot.resolve(relativePath)
            rejectSymbolicLinkParents(destination)
            destination.parent.createDirectories()
            require(!Files.isSymbolicLink(destination)) { "symbolic link language file is not allowed: $destination" }
            val json = buildJsonObject {
                language.toSortedMap().forEach { (key, value) -> put(key, value) }
            }.toString()
            Files.writeString(destination, json, StandardCharsets.UTF_8)
            relativePath
        }
    }

    fun writeRecipes(exportId: String, json: String): Result<String> = runCatching {
        synchronized(generationLock) {
            preparePending(exportId)
            val destination = pendingRoot.resolve(RECIPE_FILE)
            rejectSymbolicLinkParents(destination)
            require(!Files.isSymbolicLink(destination)) { "symbolic link recipe file is not allowed: $destination" }
            Files.writeString(destination, json, StandardCharsets.UTF_8)
            RECIPE_FILE
        }
    }

    fun publish(exportId: String, manifest: PreviewManifest): Result<Boolean> = runCatching {
        synchronized(generationLock) {
            validateExportId(exportId)
            if (currentExportId != exportId) return@runCatching false
            preparePending(exportId)
            validateManifest(manifest, pendingRoot, allowLegacy = false)
            Files.writeString(pendingRoot.resolve(MANIFEST_FILE), JSON.encodeToString(manifest), StandardCharsets.UTF_8)

            // Keep the previous complete export until the whole pending directory has moved.
            recoverPublication()
            removeGeneratedDirectory(backupRoot)
            val hadPrevious = Files.exists(root, LinkOption.NOFOLLOW_LINKS)
            if (hadPrevious) atomicMove(root, backupRoot)
            try {
                atomicMove(pendingRoot, root)
            } catch (failure: Exception) {
                if (hadPrevious) {
                    try {
                        atomicMove(backupRoot, root)
                    } catch (recoveryFailure: Exception) {
                        failure.addSuppressed(recoveryFailure)
                    }
                }
                throw failure
            }
            pendingExportId = null
            true
        }
    }

    private fun preparePending(exportId: String) {
        validateExportId(exportId)
        check(currentExportId == exportId) { "preview generation is no longer current" }
        if (pendingExportId == exportId) return
        recoverPublication()
        removeGeneratedDirectory(pendingRoot)
        pendingRoot.createDirectories()
        pendingExportId = exportId
    }

    private fun recoverPublication() {
        for (directory in listOf(root, pendingRoot, backupRoot)) {
            require(!Files.isSymbolicLink(directory)) { "symbolic link preview directory is not allowed: $directory" }
            require(!Files.exists(directory, LinkOption.NOFOLLOW_LINKS) ||
                Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) { "preview path is not a directory: $directory" }
        }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.exists(backupRoot, LinkOption.NOFOLLOW_LINKS)) {
            atomicMove(backupRoot, root)
        }
    }

    /** Only obsolete generated output is removed; Files.walk does not follow symbolic links. */
    private fun removeGeneratedDirectory(directory: Path) {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(directory)) { "symbolic link preview directory is not allowed: $directory" }
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }

    private fun validateManifest(manifest: PreviewManifest, contentRoot: Path, allowLegacy: Boolean) {
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
            require(parts[0] == TEXTURE_PAGES_DIR && parts.last() == "$index.png" &&
                (parts.size == 2 || (allowLegacy && parts.size == 3))) {
                "preview page path must be texture_pages/$index.png: ${page.file}"
            }
            if (parts.size == 3) {
                validateExportId(parts[1])
                generationIds += parts[1]
            } else generationIds += "current"
            requireRegularFile(path, contentRoot)
        }

        manifest.languageFile?.let { languageFile ->
            val path = validateRelativePath(languageFile)
            val parts = pathParts(path)
            require(parts[0] == LANGUAGE_DIR && parts.last() == LANGUAGE_FILE &&
                (parts.size == 2 || (allowLegacy && parts.size == 3))) {
                "language file path must be lang/zh_cn.json: $languageFile"
            }
            if (parts.size == 3) {
                validateExportId(parts[1])
                generationIds += parts[1]
            } else generationIds += "current"
            requireRegularFile(path, contentRoot)
        }

        manifest.recipeFile?.let { recipeFile ->
            require(recipeFile == RECIPE_FILE) { "recipe file path must be recipes.json: $recipeFile" }
            requireRegularFile(validateRelativePath(recipeFile), contentRoot)
            generationIds += "current"
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

    private fun requireRegularFile(relativePath: Path, contentRoot: Path) {
        val resolved = contentRoot.toAbsolutePath().normalize().resolve(relativePath)
        rejectSymbolicLinkParents(resolved)
        require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            "referenced preview file is missing or not regular: $relativePath"
        }
    }

    private fun rejectSymbolicLinkParents(path: Path) {
        var parent = path.parent
        while (parent != null) {
            require(!Files.isSymbolicLink(parent)) { "symbolic link parent is not allowed: $parent" }
            parent = parent.parent
        }
    }

    private fun pathParts(path: Path): List<String> = (0 until path.nameCount).map { path.getName(it).toString() }

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
        const val RECIPE_FILE = "recipes.json"
        const val MIN_PAGE_SIZE = PreviewPacking.ICON_SIZE
        const val MAX_PAGE_SIZE = 8192

        val JSON = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
