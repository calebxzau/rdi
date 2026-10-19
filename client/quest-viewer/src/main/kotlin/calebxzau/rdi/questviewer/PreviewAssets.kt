package calebxzau.rdi.questviewer

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

// Wire DTOs for the existing Forge/NeoForge export. This module never loads Minecraft classes.
@Serializable
internal data class PreviewManifest(
    val formatVersion: Int,
    val iconSize: Int,
    val pages: List<PreviewPage>,
    val items: Map<String, PreviewItem>,
    val languageFile: String? = null,
    val failedItems: Map<String, String> = emptyMap(),
    val recipeFile: String? = null,
)

@Serializable
internal data class PreviewPage(val file: String, val width: Int, val height: Int)

@Serializable
internal data class PreviewItem(val page: Int, val x: Int, val y: Int, val translationKey: String)

internal data class PreviewAssets(
    val root: Path,
    val manifest: PreviewManifest? = null,
    val language: Map<String, String> = emptyMap(),
) {
    fun itemName(id: String): String = manifest?.items?.get(id)?.translationKey
        ?.let { language[it] }?.takeIf(String::isNotBlank) ?: id
}

internal object PreviewAssetReader {
    private val logger = KotlinLogging.logger {}
    private val json = Json { ignoreUnknownKeys = true }

    fun read(root: Path, issues: MutableList<String>): PreviewAssets {
        if (!Files.exists(root.resolve("manifest.json"), NOFOLLOW_LINKS)) {
            issues += "未找到物品预览素材，任务内容仍可浏览。可在游戏中运行/rdi preview export后刷新。"
            return PreviewAssets(root)
        }
        val manifest = runCatching {
            json.decodeFromString<PreviewManifest>(readPreviewText(root, "manifest.json")).also(::validate)
        }.getOrElse { exception ->
            logger.warn(exception) { "读取任务预览素材失败：$root" }
            issues += "物品预览素材无法读取：${exception.message}"
            return PreviewAssets(root)
        }
        val language = manifest.languageFile?.let { relative ->
            runCatching {
                json.decodeFromString<Map<String, String>>(readPreviewText(root, relative))
            }.getOrElse { exception ->
                logger.warn(exception) { "读取预览语言文件失败：$relative" }
                issues += "物品名称翻译无法读取，将显示物品ID：${exception.message}"
                emptyMap()
            }
        } ?: emptyMap()
        if (manifest.languageFile == null) issues += "素材中没有物品名称翻译，将显示物品ID。"
        if (manifest.failedItems.isNotEmpty()) issues += "${manifest.failedItems.size}个物品未能导出图标，将显示占位图。"
        return PreviewAssets(root, manifest, language)
    }

    private fun validate(manifest: PreviewManifest) {
        require(manifest.formatVersion == 1) { "不支持的素材版本：${manifest.formatVersion}" }
        require(manifest.iconSize == 64) { "不支持的图标尺寸：${manifest.iconSize}" }
        manifest.pages.forEach { page ->
            require(page.width in 64..8192 && page.height in 64..8192 &&
                page.width % 64 == 0 && page.height % 64 == 0) { "图集尺寸无效：${page.file}" }
            validatePreviewRelativePath(page.file)
        }
        manifest.items.forEach { (id, item) ->
            require(item.page in manifest.pages.indices) { "物品图集索引无效：$id" }
            val page = manifest.pages[item.page]
            require(item.x >= 0 && item.y >= 0 && item.x % 64 == 0 && item.y % 64 == 0 &&
                item.x.toLong() + 64 <= page.width && item.y.toLong() + 64 <= page.height) {
                "物品图标超出图集范围：$id"
            }
        }
    }
}

internal fun validatePreviewRelativePath(relative: String): Path {
    require(relative.isNotBlank() && '\\' !in relative && ':' !in relative) { "素材路径无效：$relative" }
    val path = Path.of(relative)
    require(path.root == null && !relative.startsWith('/') && path.none { it.toString() in setOf("..", ".") }) { "素材路径越界：$relative" }
    return path
}

internal fun previewFile(root: Path, relative: String): Path {
    val path = validatePreviewRelativePath(relative)
    val base = root.toAbsolutePath().normalize()
    require(Files.isDirectory(base, NOFOLLOW_LINKS)) { "素材目录不可用：$base" }
    var current = base
    for (part in path) {
        current = current.resolve(part)
        require(!Files.isSymbolicLink(current)) { "素材路径不能包含符号链接：$relative" }
    }
    require(Files.isRegularFile(current, NOFOLLOW_LINKS)) { "素材文件不存在：$relative" }
    require(current.toRealPath().startsWith(base.toRealPath())) { "素材路径越界：$relative" }
    return current
}

internal fun readPreviewText(root: Path, relative: String): String {
    val limit = 32 * 1024 * 1024
    val bytes = Files.newInputStream(previewFile(root, relative)).use { it.readNBytes(limit + 1) }
    require(bytes.size <= limit) { "素材文件过大：$relative" }
    return bytes.toString(Charsets.UTF_8)
}
