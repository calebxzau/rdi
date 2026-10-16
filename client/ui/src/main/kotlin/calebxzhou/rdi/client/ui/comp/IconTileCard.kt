package calebxzhou.rdi.client.ui.comp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
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
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Status dot color for "ready / running right now", shared by every icon tile. */
internal val TILE_ACTIVE_DOT_COLOR = Color(0xFF3DDC84)

private val TILE_CORNER_RADIUS = 22.dp

/** 玻璃框宽度，同时是液态玻璃的斜面宽度，折射与高光只出现在框上。 */
private val TILE_RIM_WIDTH = 9.dp
private val TileShape = RoundedCornerShape(TILE_CORNER_RADIUS)

/** 瓷砖图标紧贴玻璃框内沿，圆角与玻璃框同心。 */
internal val TileIconShape = RoundedCornerShape(TILE_CORNER_RADIUS - TILE_RIM_WIDTH)

/** 瓷砖网格的外边距与间距：给玻璃框的投影留位置，免得被网格裁掉或压到相邻瓷砖。 */
internal val TILE_GRID_SPACING = 12.dp

/** 宽度为[width]的瓷砖的完整尺寸：方形图标，四周是玻璃框。 */
internal fun iconTileSize(width: Dp): DpSize = DpSize(width, width)

/** 悬停多久后显示提示框。 */
private const val TILE_HINT_DELAY_MILLIS = 17L

/** 状态点的光晕直径；核心下垫一圈底色，放在任何图标上都看得清。 */
private val STATUS_DOT_SIZE = 16.dp
private val STATUS_DOT_RING_SIZE = 10.dp
private val STATUS_DOT_CORE_RADIUS = 3.5.dp

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

/** 悬停选中框：瓷砖外一圈青色圆角框，四角有深色角标；出现时从外向内弹入，之后颜色呼吸。 */
private val SELECTION_OUTSET = 5.dp
/** 选中框弹入的起始外扩量；加上[SELECTION_OUTSET]不超过[TILE_GRID_SPACING]，免得被网格裁掉。 */
private val SELECTION_POP_OUTSET = 5.dp
private val SELECTION_STROKE_WIDTH = 3.dp
private val SELECTION_CORNER_STROKE_WIDTH = 4.5.dp
/** 角标沿边伸出圆角的长度。 */
private val SELECTION_CORNER_LENGTH = 12.dp
private val SelectionColor = Color(0xFF3FD6F5)
private val SelectionPulseColor = Color(0xFFA8F2FF)
private val SelectionCornerColor = Color(0xFF12A6E0)
/** 选中框光晕：框下面一道更宽的模糊描边，亮度随呼吸在两个不透明度之间变化。 */
private val SELECTION_GLOW_STROKE_WIDTH = 6.dp
private val SELECTION_GLOW_BLUR = 3.dp
private const val SELECTION_GLOW_ALPHA_DIM = 0.5f
private const val SELECTION_GLOW_ALPHA_BRIGHT = 0.9f
private val SelectionBounceSpec = spring<Float>(dampingRatio = 0.4f, stiffness = 600f)
private val SelectionPulseSpec = infiniteRepeatable<Float>(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse)

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
 * Square icon shared by room and modpack tiles, framed by a glass rim with a drop shadow; the name lives in the hover [hint].
 * [statusDotColor] shows a glowing dot at the icon's top-right corner; [icon] places its own corner badges.
 * Left click runs [onPrimaryClick]; right and middle clicks run their callbacks and hide the hint.
 * While hovered (and [showHint] holds) the tile publishes [hint] to the enclosing [CardHintHost], which shows it below the tile.
 *
 * [glass] chooses how the rim is drawn; see [TileGlass]. Inside a glass grid the tile is sized by the grid (see [iconTileSize]).
 */
@Composable
internal fun IconTileCard(
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
    val tileCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val hintState = LocalCardHintState.current
    val hintOwner = remember { Any() }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    // 提示框在悬停一小会儿后才出现，鼠标快速扫过一排瓷砖时不闪；右/中键点击后收起，直到下次悬停
    var hintReady by remember { mutableStateOf(false) }
    var hintDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        hintReady = false
        if (hovered) {
            hintDismissed = false
            delay(TILE_HINT_DELAY_MILLIS)
            hintReady = true
        }
    }
    val hintVisible = hovered && hintReady && showHint && !hintDismissed
    val publishHint = {
        tileCoordinates[0]?.takeIf { it.isAttached }?.let { hintState?.show(hintOwner, it.boundsInWindow(), hint) }
    }
    SideEffect {
        if (hintVisible) publishHint() else hintState?.clear(hintOwner)
    }
    DisposableEffect(hintState, hintOwner) {
        onDispose { hintState?.clear(hintOwner) }
    }

    val pressed by interactionSource.collectIsPressedAsState()
    val hoverProgress = animateFloatAsState(if (hovered) 1f else 0f)
    val pressScale = animateFloatAsState(
        targetValue = if (pressed) TILE_PRESSED_SCALE else 1f,
        animationSpec = if (pressed) TilePressInSpec else TilePressOutSpec,
    )
    val selectionAlpha = remember { Animatable(0f) }
    val selectionPop = remember { Animatable(0f) }
    val selectionPulse = remember { Animatable(0f) }
    LaunchedEffect(hovered) {
        if (hovered) {
            launch { selectionAlpha.animateTo(1f, tween(90)) }
            launch {
                selectionPop.snapTo(1f)
                selectionPop.animateTo(0f, SelectionBounceSpec)
            }
            selectionPulse.snapTo(0f)
            selectionPulse.animateTo(1f, SelectionPulseSpec)
        } else {
            selectionAlpha.animateTo(0f, tween(120))
        }
    }
    if (glass is TileGlass.Shared) {
        glass.grid.TrackTileInteractions(glass.index, interactionSource)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                tileCoordinates[0] = it
                // 网格滚动时瓷砖移动，提示框跟着走
                if (hintVisible) publishHint()
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                val action = when {
                    event.buttons.isSecondaryPressed -> onSecondaryClick
                    event.buttons.isTertiaryPressed -> onTertiaryClick
                    else -> null
                }
                if (action != null) {
                    hintDismissed = true
                    action()
                }
            }
            // 整块瓷砖一起缩放，玻璃和内容不会错位
            .graphicsLayer {
                val scale = if (glass is TileGlass.Shared) glass.grid.tileScale(glass.index) else pressScale.value
                scaleX = scale
                scaleY = scale
            }
            // 选中框画在瓷砖外沿，跟随按压缩放；只在绘制阶段读动画值，不触发重组
            .drawWithCache {
                // 模糊画笔只随尺寸/密度重建，不在每帧分配
                val glowPaint = Paint().apply {
                    style = PaintingStyle.Stroke
                    strokeWidth = SELECTION_GLOW_STROKE_WIDTH.toPx()
                    skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, SELECTION_GLOW_BLUR.toPx())
                }
                onDrawWithContent {
                    drawContent()
                    val alpha = selectionAlpha.value
                    if (alpha > 0f) {
                        val pulse = selectionPulse.value
                        drawTileSelection(
                            outset = (SELECTION_OUTSET + SELECTION_POP_OUTSET * selectionPop.value).toPx(),
                            color = lerp(SelectionColor, SelectionPulseColor, pulse),
                            alpha = alpha,
                            glowPaint = glowPaint,
                            glowAlpha = alpha * (SELECTION_GLOW_ALPHA_DIM + (SELECTION_GLOW_ALPHA_BRIGHT - SELECTION_GLOW_ALPHA_DIM) * pulse),
                        )
                    }
                }
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
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TILE_RIM_WIDTH)
                .aspectRatio(1f)
                .alpha(if (dimmed) 0.45f else 1f),
        ) {
            icon()
            if (statusDotColor != null) {
                TileStatusDot(statusDotColor, Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
            }
        }
    }
}

/**
 * 在瓷砖外[outset]处画选中框：与瓷砖同心的圆角描边，四角叠加更粗的深色L形角标。
 * 描边下面先用[glowPaint]画一道同色模糊光晕，不透明度为[glowAlpha]。
 */
private fun DrawScope.drawTileSelection(outset: Float, color: Color, alpha: Float, glowPaint: Paint, glowAlpha: Float) {
    val strokeWidth = SELECTION_STROKE_WIDTH.toPx()
    // 描边中线所在的外扩矩形
    val inset = -(outset + strokeWidth / 2f)
    val left = inset
    val top = inset
    val right = size.width - inset
    val bottom = size.height - inset
    val radius = TILE_CORNER_RADIUS.toPx() + outset + strokeWidth / 2f
    glowPaint.color = color.copy(alpha = color.alpha * glowAlpha)
    drawIntoCanvas { it.drawRoundRect(left, top, right, bottom, radius, radius, glowPaint) }
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(right - left, bottom - top),
        cornerRadius = CornerRadius(radius),
        style = Stroke(strokeWidth),
        alpha = alpha,
    )

    val cornerStroke = Stroke(SELECTION_CORNER_STROKE_WIDTH.toPx(), cap = StrokeCap.Round)
    val length = SELECTION_CORNER_LENGTH.toPx()
    val arcSize = Size(radius * 2f, radius * 2f)
    // 左上、右上、右下、左下：圆角弧及其两端沿边伸出的线段
    drawArc(SelectionCornerColor, 180f, 90f, false, Offset(left, top), arcSize, alpha, cornerStroke)
    drawArc(SelectionCornerColor, 270f, 90f, false, Offset(right - 2f * radius, top), arcSize, alpha, cornerStroke)
    drawArc(SelectionCornerColor, 0f, 90f, false, Offset(right - 2f * radius, bottom - 2f * radius), arcSize, alpha, cornerStroke)
    drawArc(SelectionCornerColor, 90f, 90f, false, Offset(left, bottom - 2f * radius), arcSize, alpha, cornerStroke)
    val cornerWidth = cornerStroke.width
    fun segment(start: Offset, end: Offset) =
        drawLine(SelectionCornerColor, start, end, cornerWidth, StrokeCap.Round, alpha = alpha)
    segment(Offset(left, top + radius), Offset(left, top + radius + length))
    segment(Offset(left + radius, top), Offset(left + radius + length, top))
    segment(Offset(right - radius, top), Offset(right - radius - length, top))
    segment(Offset(right, top + radius), Offset(right, top + radius + length))
    segment(Offset(right, bottom - radius), Offset(right, bottom - radius - length))
    segment(Offset(right - radius, bottom), Offset(right - radius - length, bottom))
    segment(Offset(left + radius, bottom), Offset(left + radius + length, bottom))
    segment(Offset(left, bottom - radius), Offset(left, bottom - radius - length))
}

/** 图标右上角的状态点，略微探出图标边缘。 */
@Composable
private fun TileStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(STATUS_DOT_SIZE), contentAlignment = Alignment.Center) {
        Box(Modifier.size(STATUS_DOT_RING_SIZE).background(MaterialTheme.colorScheme.surface, CircleShape))
        GlowingDot(color, Modifier.fillMaxSize(), coreRadius = STATUS_DOT_CORE_RADIUS)
    }
}

/** 版本角标与图标边缘的间距；圆角与图标同心。 */
private val VERSION_BADGE_INSET = 5.dp
private val VersionBadgeShape = RoundedCornerShape(8.dp)
private val VersionBadgeBackground = Color.White.copy(alpha = 0.7f)
private val VersionBadgeText = Color(0xFF1F1F1F)

/** 整合包瓷砖右下角的版本号：半透明白底，最宽占图标的70%，过长省略。 */
@Composable
internal fun TileVersionBadge(version: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(0.7f).padding(end = VERSION_BADGE_INSET, bottom = VERSION_BADGE_INSET),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Text(
            text = version,
            modifier = Modifier
                .background(VersionBadgeBackground, VersionBadgeShape)
                .padding(horizontal = 6.dp, vertical = 1.dp),
            color = VersionBadgeText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
