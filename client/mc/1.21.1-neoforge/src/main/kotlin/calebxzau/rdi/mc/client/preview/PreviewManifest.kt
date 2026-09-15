package calebxzau.rdi.mc.client.preview

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable
data class PreviewManifest(
    @Required
    val formatVersion: Int = FORMAT_VERSION,
    @Required
    val iconSize: Int = PreviewPacking.ICON_SIZE,
    val pages: List<PreviewPage>,
    val languageFile: String? = null,
    val items: Map<String, PreviewItem>,
    val failedItems: Map<String, String> = emptyMap()
)

@Serializable
data class PreviewPage(
    val file: String,
    val width: Int,
    val height: Int
)

@Serializable
data class PreviewItem(
    val page: Int,
    val x: Int,
    val y: Int,
    val translationKey: String
)

data class PreviewPosition(
    val page: Int,
    val x: Int,
    val y: Int
)

data class PreviewPagePlan(
    val page: Int,
    val firstItemIndex: Int,
    val itemCount: Int,
    val width: Int,
    val height: Int
) {
    fun position(offset: Int): PreviewPosition {
        require(offset in 0 until itemCount) { "offset must be within page item count" }
        val columns = width / PreviewPacking.ICON_SIZE
        return PreviewPosition(page, (offset % columns) * PreviewPacking.ICON_SIZE, (offset / columns) * PreviewPacking.ICON_SIZE)
    }
}

object PreviewPacking {
    const val ICON_SIZE: Int = 64
    const val MAX_PAGE_EDGE: Int = 2048

    fun plan(itemCount: Int, maxEdge: Int = MAX_PAGE_EDGE): List<PreviewPagePlan> {
        require(itemCount >= 0) { "itemCount must be non-negative" }
        require(maxEdge in ICON_SIZE..MAX_PAGE_EDGE && maxEdge % ICON_SIZE == 0) {
            "maxEdge must be between $ICON_SIZE and $MAX_PAGE_EDGE and divisible by $ICON_SIZE"
        }
        if (itemCount == 0) return emptyList()
        val cellsPerEdge = maxEdge / ICON_SIZE
        val capacity = cellsPerEdge * cellsPerEdge
        val pages = ArrayList<PreviewPagePlan>((itemCount - 1) / capacity + 1)
        var first = 0
        var page = 0
        while (first < itemCount) {
            val count = minOf(itemCount - first, capacity)
            val columns = minOf(cellsPerEdge, count)
            val rows = (count + columns - 1) / columns
            val width = columns * ICON_SIZE
            val height = rows * ICON_SIZE
            pages += PreviewPagePlan(page, first, count, width, height)
            first += count
            page++
        }
        return pages
    }

    fun position(index: Int, pageSize: Int): PreviewPosition {
        require(index >= 0) { "index must be non-negative" }
        require(pageSize in ICON_SIZE..MAX_PAGE_SIZE && pageSize % ICON_SIZE == 0) {
            "pageSize must be between $ICON_SIZE and $MAX_PAGE_SIZE and divisible by $ICON_SIZE"
        }

        val cellsPerRow = pageSize / ICON_SIZE
        val cellsPerPage = cellsPerRow * cellsPerRow
        val page = index / cellsPerPage
        val cell = index % cellsPerPage
        return PreviewPosition(
            page = page,
            x = (cell % cellsPerRow) * ICON_SIZE,
            y = (cell / cellsPerRow) * ICON_SIZE
        )
    }
}

private const val MAX_PAGE_SIZE: Int = 8192
internal const val FORMAT_VERSION: Int = 1
