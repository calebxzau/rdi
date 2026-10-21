package calebxzau.rdi.questviewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import calebxzau.rdi.quests.ftb.model.FtbChapter
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal data class QuestGraphNode(
    val id: String,
    val questId: String?,
    val center: Offset,
    val radius: Float,
    val shape: String,
    val title: String,
    val icon: PreviewIcon?,
    val link: Boolean = false,
)

internal data class QuestGraphEdge(val from: QuestGraphNode, val to: QuestGraphNode)
internal data class QuestGraph(val nodes: List<QuestGraphNode>, val edges: List<QuestGraphEdge>)

/** Label widths in map coordinates, independent of zoom and display density. */
internal fun questLabelWidths(nodes: List<QuestGraphNode>, labelHeight: Float): Map<String, Float> {
    require(labelHeight.isFinite() && labelHeight > 0f)
    val widths = FloatArray(nodes.size) { 150f }
    // Staggered bands ensure labels with overlapping vertical spans share a band.
    // Sorting within each band avoids comparing every pair of nodes in a large chapter.
    for (shift in listOf(0f, labelHeight)) {
        val bands = nodes.indices.groupBy { index ->
            val node = nodes[index]
            floor((node.center.y.toDouble() + node.radius + shift) / (2.0 * labelHeight)).toLong()
        }
        bands.values.forEach { band ->
            band.sortedBy { nodes[it].center.x }.zipWithNext { left, right ->
                val available = (nodes[right].center.x - nodes[left].center.x - 8f).coerceIn(1f, 150f)
                widths[left] = min(widths[left], available)
                widths[right] = min(widths[right], available)
            }
        }
    }
    return nodes.indices.associate { nodes[it].id to widths[it] }
}

internal fun QuestPreviewData.graph(chapter: FtbChapter): QuestGraph {
    val chapterShape = chapter.data.string("default_quest_shape").orEmpty()
    val defaultShape = chapterShape.takeUnless { it.isBlank() || it == "default" }
        ?: book.settings.string("default_quest_shape")?.takeUnless { it.isBlank() || it == "default" } ?: "circle"
    val defaultSize = chapter.data.number("default_quest_size") ?: 1.0
    fun node(id: String, questId: String?, x: Double, y: Double, size: Double?, shape: String?, link: Boolean): QuestGraphNode? {
        if (!x.isFinite() || !y.isFinite() || abs(x) > 1e6 || abs(y) > 1e6) return null
        val quest = quests[questId]
        val inheritedSize = if (link) 1.0 else defaultSize
        val actualSize = (size?.takeUnless { it == 0.0 } ?: inheritedSize).takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return QuestGraphNode(
            id, questId, Offset((x * 72).toFloat(), (y * 72).toFloat()),
            (actualSize.coerceIn(0.15, 8.0) * 22).toFloat(),
            shape?.takeUnless { it.isBlank() || it == "default" } ?: defaultShape,
            quest?.let(::questTitle) ?: "无法找到链接任务", quest?.let(::questIcon), link,
        )
    }
    val nodes = chapter.quests.mapNotNull { q -> node(q.id, q.id, q.x, q.y, q.size, q.shape, false) } +
        chapter.links.mapNotNull { link ->
            val target = resolve(link.linkedQuestId.orEmpty()).singleOrNull()
            node(link.id, target?.questId, link.x, link.y, link.size, link.shape, true)
        }
    val nodeIndex = nodes.filterNot { it.link }.associateBy { it.questId }
    val edges = chapter.quests.flatMap { quest ->
        val destination = nodeIndex[quest.id] ?: return@flatMap emptyList()
        if ((quest.data.flag("hide_dependency_lines") ?: chapter.data.flag("default_hide_dependency_lines")) == true) return@flatMap emptyList()
        quest.dependencies.flatMap references@{ reference ->
            // Ambiguous tags remain explicit in the detail pane; do not invent a unique edge.
            val target = resolve(reference).singleOrNull() ?: return@references emptyList()
            // Book, group and chapter dependencies have no node; never match an unresolved link.
            val targetQuestId = target.questId ?: return@references emptyList()
            val origin = nodeIndex[targetQuestId] ?: nodes.firstOrNull { it.link && it.questId == targetQuestId }
            if (origin == null || quests[origin.questId]?.data?.flag("hide_dependent_lines") == true) emptyList()
            else listOf(QuestGraphEdge(origin, destination))
        }
    }.distinctBy { it.from.id to it.to.id }
    return QuestGraph(nodes, edges)
}

/** The same transform is used by painting, hit testing and cursor-anchored zooming. */
internal data class QuestViewport(val scale: Float = 1f, val pan: Offset = Offset.Zero) {
    fun screen(world: Offset): Offset = world * scale + pan
    fun world(screen: Offset): Offset = (screen - pan) / scale
    fun zoom(factor: Float, anchor: Offset): QuestViewport {
        val nextScale = (scale * factor).coerceIn(0.01f, 6f)
        return QuestViewport(nextScale, anchor - world(anchor) * nextScale)
    }
    fun hit(nodes: List<QuestGraphNode>, position: Offset): QuestGraphNode? {
        val point = world(position)
        return nodes.asReversed().firstOrNull { node ->
            val relative = point - node.center
            when (node.shape) {
                "square" -> abs(relative.x) <= node.radius && abs(relative.y) <= node.radius
                "diamond" -> abs(relative.x) + abs(relative.y) <= node.radius
                else -> relative.getDistance() <= node.radius
            }
        }
    }
}

/** Nodes whose shape or label may intersect the canvas. */
internal fun visibleQuestNodes(nodes: List<QuestGraphNode>, viewport: QuestViewport, size: IntSize, density: Float): List<QuestGraphNode> =
    nodes.filter { node ->
        val center = viewport.screen(node.center)
        val margin = node.radius * viewport.scale + 120 * maxOf(density, viewport.scale)
        center.x >= -margin && center.y >= -margin && center.x <= size.width + margin && center.y <= size.height + margin
    }

/** Keeps the world point at the canvas center fixed when the canvas is resized. */
internal fun QuestViewport.resized(old: IntSize, new: IntSize): QuestViewport =
    copy(pan = pan + Offset((new.width - old.width) / 2f, (new.height - old.height) / 2f))

internal fun fitQuestGraph(nodes: List<QuestGraphNode>, size: IntSize, density: Float): QuestViewport {
    if (size.width <= 0 || size.height <= 0) return QuestViewport()
    if (nodes.isEmpty()) return QuestViewport(density, Offset(size.width / 2f, size.height / 2f))
    val left = nodes.minOf { it.center.x - it.radius } - 32
    val top = nodes.minOf { it.center.y - it.radius } - 32
    val right = nodes.maxOf { it.center.x + it.radius } + 32
    val bottom = nodes.maxOf { it.center.y + it.radius } + 32
    val scale = min(size.width / (right - left), size.height / (bottom - top)).coerceIn(0.01f, 2f * density)
    return QuestViewport(scale, Offset(size.width / 2f - (left + right) / 2 * scale, size.height / 2f - (top + bottom) / 2 * scale))
}
