package calebxzau.rdi.questviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.nio.file.Files
import kotlin.math.roundToInt

/** One cache per opened book. Both the graph and detail pane reuse whole atlas pages. */
internal class PreviewAtlasCache(private val assets: PreviewAssets) {
    private val logger = KotlinLogging.logger {}
    private val mutex = Mutex()
    private val pages = LinkedHashMap<Int, Result<ImageBitmap>>(16, 0.75f, true)
    var failedPages by mutableStateOf(0)
        private set

    suspend fun page(index: Int): Result<ImageBitmap> = withContext(Dispatchers.IO) {
        mutex.withLock {
            pages[index]?.let { return@withLock it }
            val result = runCatching {
                val descriptor = requireNotNull(assets.manifest).pages[index]
                val file = previewFile(assets.root, descriptor.file)
                val limit = 64 * 1024 * 1024
                val bytes = Files.newInputStream(file).use { it.readNBytes(limit + 1) }
                require(bytes.size <= limit) { "图集文件过大：${descriptor.file}" }
                val image = Image.makeFromEncoded(bytes)
                if (image.width != descriptor.width || image.height != descriptor.height) {
                    image.close()
                    error("图集尺寸与manifest不一致：${descriptor.file}")
                }
                image.toComposeImageBitmap()
            }.onFailure { exception ->
                logger.warn(exception) { "读取物品图集失败：${assets.root}，page=$index" }
                failedPages++
            }
            pages[index] = result
            fun cacheBytes(): Long = pages.values.sumOf { entry ->
                entry.fold({ it.width.toLong() * it.height * 4 }, { 0L })
            }
            // Keep at most one oversize legacy page; current exports use <=2048px edges.
            while (pages.size > 1 && (pages.size > 16 || cacheBytes() > 64L * 1024 * 1024)) {
                pages.remove(pages.keys.first())
            }
            result
        }
    }
}

internal fun DrawScope.drawAtlasItem(image: ImageBitmap, item: PreviewItem, iconSize: Int, x: Float, y: Float, edge: Float) {
    drawImage(
        image = image,
        srcOffset = IntOffset(item.x, item.y),
        srcSize = IntSize(iconSize, iconSize),
        dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
        dstSize = IntSize(edge.roundToInt().coerceAtLeast(1), edge.roundToInt().coerceAtLeast(1)),
        filterQuality = FilterQuality.None,
    )
}

@Composable
internal fun PreviewItemIcon(id: String?, assets: PreviewAssets, cache: PreviewAtlasCache) {
    val item = assets.manifest?.items?.get(id)
    val bitmap by produceState<ImageBitmap?>(null, cache, item?.page) {
        value = item?.let { cache.page(it.page).getOrElse { null } }
    }
    val placeholder = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(36.dp)) {
        val image = bitmap
        if (image != null && item != null) {
            drawAtlasItem(image, item, assets.manifest.iconSize, 0f, 0f, size.minDimension)
        } else {
            drawCircle(placeholder, radius = size.minDimension / 3)
        }
    }
}
