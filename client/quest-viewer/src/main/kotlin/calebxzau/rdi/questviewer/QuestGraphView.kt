package calebxzau.rdi.questviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import kotlin.math.pow

/** Each navigation creates a new instance, so repeating the same target still re-centers the graph. */
internal class QuestFocus(val questId: String)

@Composable
internal fun QuestGraphView(
    data: QuestPreviewData,
    graph: QuestGraph,
    cache: PreviewAtlasCache,
    selectedQuestId: String?,
    focus: QuestFocus?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayDensity = LocalDensity.current
    val density = displayDensity.density
    val currentOnSelect by rememberUpdatedState(onSelect)
    var viewport by remember(graph) { mutableStateOf(QuestViewport()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var hovered by remember(graph) { mutableStateOf<QuestGraphNode?>(null) }
    val sizeReady = canvasSize.width > 0 && canvasSize.height > 0
    // Resizes only shift the view (see onSizeChanged); fitting happens per chapter or on request.
    LaunchedEffect(graph, sizeReady, fitRequest) {
        if (sizeReady) viewport = fitQuestGraph(graph.nodes, canvasSize, density)
    }
    LaunchedEffect(graph, sizeReady, focus) {
        if (!sizeReady) return@LaunchedEffect
        val node = focus?.let { target -> graph.nodes.firstOrNull { it.questId == target.questId } } ?: return@LaunchedEffect
        val scale = density.coerceIn(0.5f, 3f)
        viewport = QuestViewport(scale, Offset(canvasSize.width / 2f, canvasSize.height / 2f) - node.center * scale)
    }
    // Only a change of the needed atlas pages recomposes; panning itself just redraws.
    val neededPages by remember(graph, data, density) {
        derivedStateOf {
            visibleQuestNodes(graph.nodes, viewport, canvasSize, density).mapNotNullTo(mutableSetOf()) {
                data.assets.manifest?.items?.get((it.icon as? PreviewIcon.Item)?.itemId)?.page
            }
        }
    }
    val checkmark = remember(graph) {
        if (graph.nodes.any { it.icon == PreviewIcon.Checkmark }) BuiltinQuestIcons.checkmark else null
    }
    var images by remember(cache) { mutableStateOf<Map<Int, ImageBitmap>>(emptyMap()) }
    LaunchedEffect(cache, neededPages) {
        // Pages that stay visible are kept, so icons do not blink while new pages load.
        val loaded = images.filterKeys(neededPages::contains).toMutableMap()
        neededPages.filterNot(loaded::containsKey).forEach { page ->
            cache.page(page).onSuccess {
                loaded[page] = it
                images = loaded.toMap()
            }
        }
        images = loaded.toMap()
    }
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labels = remember(graph, displayDensity, colors.onSurface, measurer) {
        val style = TextStyle(color = colors.onSurface, fontSize = 11.sp)
        val labelHeight = measurer.measure("Ag任务", style = style, maxLines = 1).size.height / density
        val widths = questLabelWidths(graph.nodes, labelHeight)
        graph.nodes.associate { node ->
            node.id to measurer.measure(
                questAnnotatedText(node.title),
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (widths.getValue(node.id) * density).toInt().coerceAtLeast(1)),
            )
        }
    }
    Box(modifier.clipToBounds().background(colors.surfaceContainerLow)) {
        Canvas(
            Modifier.fillMaxSize().onSizeChanged { size ->
                if (canvasSize.width > 0 && canvasSize.height > 0) viewport = viewport.resized(canvasSize, size)
                canvasSize = size
            }
                .pointerInput(graph) {
                    detectTapGestures { position -> viewport.hit(graph.nodes, position)?.questId?.let(currentOnSelect) }
                }
                .pointerInput(graph) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        viewport = viewport.copy(pan = viewport.pan + drag)
                    }
                }
                .pointerInput(graph) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            if (event.type == PointerEventType.Scroll) {
                                viewport = viewport.zoom(1.15f.pow(-change.scrollDelta.y.coerceIn(-5f, 5f)), change.position)
                                change.consume()
                            }
                            if (event.type == PointerEventType.Move) hovered = viewport.hit(graph.nodes, change.position)
                            if (event.type == PointerEventType.Exit) hovered = null
                        }
                    }
                }
        ) {
            graph.edges.forEach { edge ->
                val from = viewport.screen(edge.from.center)
                val to = viewport.screen(edge.to.center)
                val selected = edge.from.questId == selectedQuestId || edge.to.questId == selectedQuestId
                drawLine(if (selected) colors.primary else colors.outlineVariant, from, to, if (selected) 2.5f * density else density)
                val delta = to - from
                val length = delta.getDistance()
                if (length > 1) {
                    val unit = delta / length
                    val tip = to - unit * (edge.to.radius * viewport.scale)
                    val side = Offset(-unit.y, unit.x) * (4 * density)
                    drawLine(colors.outline, tip, tip - unit * (8 * density) + side, density)
                    drawLine(colors.outline, tip, tip - unit * (8 * density) - side, density)
                }
            }
            visibleQuestNodes(graph.nodes, viewport, canvasSize, density).forEach { node ->
                val center = viewport.screen(node.center)
                val radius = node.radius * viewport.scale
                val active = node.questId != null && node.questId == selectedQuestId
                val fill = if (active) colors.primaryContainer else colors.surfaceContainerHighest
                val outline = when {
                    active -> colors.primary
                    node.link -> colors.tertiary
                    else -> colors.outline
                }
                when (node.shape) {
                    "square" -> {
                        drawRect(fill, center - Offset(radius, radius), Size(radius * 2, radius * 2))
                        drawRect(outline, center - Offset(radius, radius), Size(radius * 2, radius * 2), style = Stroke(2 * density))
                    }
                    "diamond" -> {
                        val path = Path().apply {
                            moveTo(center.x, center.y - radius)
                            lineTo(center.x + radius, center.y)
                            lineTo(center.x, center.y + radius)
                            lineTo(center.x - radius, center.y)
                            close()
                        }
                        drawPath(path, fill)
                        drawPath(path, outline, style = Stroke(2 * density))
                    }
                    else -> {
                        drawCircle(fill, radius, center)
                        drawCircle(outline, radius, center, style = Stroke(2 * density))
                    }
                }
                val item = data.assets.manifest?.items?.get((node.icon as? PreviewIcon.Item)?.itemId)
                val image = item?.let { images[it.page] }
                val edge = radius * if (node.shape == "diamond") 0.95f else 1.3f
                if (node.icon == PreviewIcon.Checkmark && checkmark != null) {
                    drawBuiltinIcon(checkmark, center.x - edge / 2, center.y - edge / 2, edge)
                } else if (image != null) {
                    drawAtlasItem(image, item, data.assets.manifest.iconSize, center.x - edge / 2, center.y - edge / 2, edge)
                } else drawCircle(outline.copy(alpha = 0.6f), radius / 4, center)
                if (viewport.scale >= density * 0.45f) {
                    labels[node.id]?.let { label ->
                        val labelScale = viewport.scale / density
                        withTransform({
                            translate(center.x, center.y + radius + 4 * viewport.scale)
                            scale(labelScale, labelScale, pivot = Offset.Zero)
                        }) {
                            drawText(label, topLeft = Offset(-label.size.width / 2f, 0f))
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { viewport = viewport.zoom(1 / 1.25f, Offset(canvasSize.width / 2f, canvasSize.height / 2f)) }) { Text("缩小") }
            TextButton(onClick = { fitRequest++ }) { Text("适应窗口") }
            TextButton(onClick = { viewport = viewport.zoom(1.25f, Offset(canvasSize.width / 2f, canvasSize.height / 2f)) }) { Text("放大") }
        }
        if (graph.nodes.isEmpty()) Text("此章节没有任务", Modifier.align(Alignment.Center))
        Text(
            questAnnotatedText(
                hovered?.let { (if (it.link) "任务链接 · " else "") + it.title }
                    ?: "拖动平移 · 滚轮缩放 · 点击查看任务",
            ),
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
