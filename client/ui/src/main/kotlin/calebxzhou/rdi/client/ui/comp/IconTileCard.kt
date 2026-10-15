package calebxzhou.rdi.client.ui.comp

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calebxzau.rdi.client.ui.GlassTileGridState
import calebxzau.rdi.client.ui.GlassTileGridStyle
import calebxzau.rdi.client.ui.LiquidGlassStyle
import calebxzau.rdi.client.ui.TrackTileInteractions
import calebxzau.rdi.client.ui.comp.GlowingDot
import calebxzau.rdi.client.ui.liquidGlass
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState

/** Status dot color for "ready / running right now", shared by every icon tile. */
internal val TILE_ACTIVE_DOT_COLOR = Color(0xFF3DDC84)

private const val STATUS_DOT_ID = "tileStatusDot"

private val TILE_CORNER_RADIUS = 22.dp

/** 玻璃框宽度，同时是液态玻璃的斜面宽度，折射与高光只出现在框上。 */
private val TILE_RIM_WIDTH = 9.dp
private val TileShape = RoundedCornerShape(TILE_CORNER_RADIUS)

/** 瓷砖图标紧贴玻璃框内沿，圆角与玻璃框同心。 */
internal val TileIconShape = RoundedCornerShape(TILE_CORNER_RADIUS - TILE_RIM_WIDTH)

/** 瓷砖网格的外边距与间距：给玻璃框的投影留位置，免得被网格裁掉或压到相邻瓷砖。 */
internal val TILE_GRID_SPACING = 12.dp

private val TILE_TITLE_GAP = 6.dp
private val TILE_TITLE_HEIGHT = 16.dp

/** 宽度为[width]的瓷砖的完整尺寸：方形图标加一行标题，四周是玻璃框。 */
internal fun iconTileSize(width: Dp): DpSize = DpSize(width, width + TILE_TITLE_GAP + TILE_TITLE_HEIGHT)

private const val TILE_PRESSED_SCALE = 0.96f
private val TilePressInSpec = spring<Float>(stiffness = Spring.StiffnessHigh)
private val TilePressOutSpec = spring<Float>(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

/** 紧贴边缘的细投影，勾出玻璃框的轮廓。 */
private val TileEdgeShadow = Shadow(radius = 1.5.dp, color = Color.Black, alpha = 0.16f)

/** 直接放在App背景上的玻璃框：磨砂并折射背景，白色着色让框在花哨背景上依然干净。 */
private val TileBackdropGlassStyle = LiquidGlassStyle(
    bevel = TILE_RIM_WIDTH,
    frost = 8.dp,
    cornerRadius = TILE_CORNER_RADIUS,
    tint = Color.White.copy(alpha = 0.2f),
    hairline = Color.White.copy(alpha = 0.7f),
)

/** 悬停时高光增强的幅度。 */
private const val TILE_HOVER_SPECULAR_BOOST = 0.8f

/** [calebxzau.rdi.client.ui.GlassTileGrid]里的瓷砖外观，与单独放在背景上的瓷砖一致。 */
internal val TileGridGlassStyle = GlassTileGridStyle(
    glass = TileBackdropGlassStyle,
    hoverSpecularBoost = TILE_HOVER_SPECULAR_BOOST,
    edgeShadowAlpha = TileEdgeShadow.alpha,
    pressedScale = TILE_PRESSED_SCALE,
)

/** 瓷砖玻璃框的绘制方式。 */
sealed interface TileGlass {
    /** 单独放在App背景上：磨砂并折射[hazeState]对应的背景。 */
    data class Backdrop(val hazeState: HazeState) : TileGlass

    /** 放在[calebxzau.rdi.client.ui.GlassTileGrid]里：玻璃与投影由网格统一绘制，瓷砖只画内容并上报交互。 */
    data class Shared(val grid: GlassTileGridState, val index: Int) : TileGlass
}

/**
 * Square icon with a one-line title below, shared by room and modpack tiles, framed by a glass rim with a drop shadow.
 * Left click runs [onPrimaryClick]; right and middle clicks run their callbacks and hide the hint.
 * While hovered (and [showHint] holds) the tile publishes [hint] to the enclosing [CardHintHost].
 *
 * [glass] chooses how the rim is drawn; see [TileGlass]. Inside a glass grid the tile is sized by the grid (see [iconTileSize]).
 */
@Composable
internal fun IconTileCard(
    title: String,
    hint: CardHint,
    modifier: Modifier = Modifier,
    statusDotColor: Color? = null,
    dimmed: Boolean = false,
    showHint: Boolean = true,
    primaryEnabled: Boolean = true,
    glass: TileGlass,
    onPrimaryClick: () -> Unit,
    onSecondaryClick: (() -> Unit)? = null,
    onTertiaryClick: (() -> Unit)? = null,
    icon: @Composable BoxScope.() -> Unit,
) {
    var cursorInWindow by remember { mutableStateOf<Offset?>(null) }
    val tileCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val hintState = LocalCardHintState.current
    val hintOwner = remember { Any() }
    val updateCursor: (PointerEvent) -> Unit = { event ->
        cursorInWindow = tileCoordinates[0]
            ?.takeIf { it.isAttached }
            ?.localToWindow(event.changes.first().position)
    }

    val shownCursor = cursorInWindow?.takeIf { showHint }
    SideEffect {
        if (shownCursor != null) hintState?.show(hintOwner, shownCursor, hint) else hintState?.clear(hintOwner)
    }
    DisposableEffect(hintState, hintOwner) {
        onDispose { hintState?.clear(hintOwner) }
    }

    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val hoverProgress = animateFloatAsState(if (hovered) 1f else 0f)
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) TILE_PRESSED_SCALE else 1f,
        animationSpec = if (pressed) TilePressInSpec else TilePressOutSpec,
    )
    if (glass is TileGlass.Shared) {
        glass.grid.TrackTileInteractions(glass.index, interactionSource)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { tileCoordinates[0] = it }
            .onPointerEvent(PointerEventType.Enter) { updateCursor(it) }
            .onPointerEvent(PointerEventType.Move) { updateCursor(it) }
            .onPointerEvent(PointerEventType.Exit) { cursorInWindow = null }
            .onPointerEvent(PointerEventType.Press) { event ->
                val action = when {
                    event.buttons.isSecondaryPressed -> onSecondaryClick
                    event.buttons.isTertiaryPressed -> onTertiaryClick
                    else -> null
                }
                if (action != null) {
                    cursorInWindow = null
                    action()
                }
            }
            // 整块瓷砖一起缩放，玻璃和内容不会错位
            .graphicsLayer {
                val scale = if (glass is TileGlass.Shared) glass.grid.tileScale(glass.index) else pressScale.value
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (glass is TileGlass.Shared) {
                    Modifier
                } else {
                    Modifier
                        // 悬停时投影变大变远，瓷砖像被抬起
                        .dropShadow(TileShape) {
                            val lift = hoverProgress.value
                            radius = (10.dp + 6.dp * lift).toPx()
                            offset = Offset(0f, (3.dp + 3.dp * lift).toPx())
                            alpha = 0.18f
                        }
                        .dropShadow(TileShape, TileEdgeShadow)
                }
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                // 反馈由玻璃高光和按压缩放呈现，不叠加波纹
                indication = null,
                enabled = primaryEnabled,
                onClick = onPrimaryClick,
            ),
    ) {
        if (glass is TileGlass.Backdrop) {
            TileGlassRim(
                hazeState = glass.hazeState,
                hoverProgress = hoverProgress.value,
                modifier = Modifier.matchParentSize(),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TILE_RIM_WIDTH)
                .alpha(if (dimmed) 0.45f else 1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TILE_TITLE_GAP),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), content = icon)
            Text(
                text = buildAnnotatedString {
                    if (statusDotColor != null) {
                        appendInlineContent(STATUS_DOT_ID, "●")
                    }
                    append(title)
                },
                inlineContent = statusDotColor?.let {
                    mapOf(
                        STATUS_DOT_ID to InlineTextContent(
                            Placeholder(16.sp, 14.sp, PlaceholderVerticalAlign.TextCenter),
                        ) {
                            Box(Modifier.fillMaxSize()) {
                                GlowingDot(statusDotColor, Modifier.fillMaxHeight().aspectRatio(1f))
                            }
                        },
                    )
                } ?: emptyMap(),
                modifier = Modifier.fillMaxWidth().height(TILE_TITLE_HEIGHT),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 单独放在App背景上的瓷砖的玻璃框：磨砂并折射[hazeState]对应的背景。 */
@Composable
private fun TileGlassRim(
    hazeState: HazeState,
    hoverProgress: Float,
    modifier: Modifier = Modifier,
) {
    val input = remember(hazeState) { HazeInput.Sources(hazeState) }
    val specularIntensity = 1f + TILE_HOVER_SPECULAR_BOOST * hoverProgress
    Box(modifier.liquidGlass(input, TileBackdropGlassStyle.copy(specularIntensity = specularIntensity)))
}
