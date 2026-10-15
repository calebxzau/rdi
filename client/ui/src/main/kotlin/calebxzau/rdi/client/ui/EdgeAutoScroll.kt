package calebxzau.rdi.client.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.exp

/**
 * 边缘自动滚动的参数。
 *
 * @property zoneWidth 左右边缘区宽度：鼠标停在区内即自动滚动，按住鼠标加速
 * @property approachDistance 离边缘多远开始发光，越靠近越亮，进入边缘区最亮
 * @property glowWidth 边缘光从边缘向内延伸的宽度
 * @property slowSpeed 停留时的滚动速度（每秒）
 * @property fastSpeed 按住鼠标时的滚动速度（每秒）
 * @property acceleration 速度趋近目标的快慢（每秒），起停都有短暂的加减速
 */
@Immutable
data class EdgeAutoScrollStyle(
    val zoneWidth: Dp = 56.dp,
    val approachDistance: Dp = 160.dp,
    val glowWidth: Dp = 96.dp,
    val glowColor: Color = Color.White,
    val glowAlpha: Float = 0.45f,
    val heldGlowAlpha: Float = 0.7f,
    val chevronAlpha: Float = 0.85f,
    val slowSpeed: Dp = 300.dp,
    val fastSpeed: Dp = 1200.dp,
    val acceleration: Float = 10f,
)

private enum class EdgeSide { Start, End }

/** 速度低于此值（像素/秒）且已无目标时停止滚动循环。 */
private const val STOP_SPEED_PX = 1f
private val GlowFadeSpec = tween<Float>(durationMillis = 150)

/**
 * 横向滚动内容两侧的边缘区：鼠标靠近边缘时该侧发光，停在边缘区内自动慢速滚动，
 * 按住鼠标快速滚动；已滚到头的一侧不发光也不滚动。
 *
 * 内容占满宽度；边缘区盖在内容两端并吞掉点击，按住鼠标快速滚动时不会误点到底下的内容。
 */
@Composable
fun EdgeAutoScrollBox(
    scrollState: ScrollableState,
    modifier: Modifier = Modifier,
    style: EdgeAutoScrollStyle = EdgeAutoScrollStyle(),
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    var pointerX by remember { mutableStateOf<Float?>(null) }
    var boxWidth by remember { mutableIntStateOf(0) }
    var heldSide by remember { mutableStateOf<EdgeSide?>(null) }
    val zonePx = with(density) { style.zoneWidth.toPx() }
    val approachPx = with(density) { style.approachDistance.toPx() }

    val canScrollStart = scrollState.canScrollBackward
    val canScrollEnd = scrollState.canScrollForward
    val x = pointerX
    val startDistance = x
    val endDistance = x?.let { boxWidth - it }
    val activeSide = when {
        startDistance != null && startDistance <= zonePx && canScrollStart -> EdgeSide.Start
        endDistance != null && endDistance <= zonePx && canScrollEnd -> EdgeSide.End
        else -> null
    }
    val speedPx = with(density) { (if (heldSide == activeSide) style.fastSpeed else style.slowSpeed).toPx() }
    val targetVelocity by rememberUpdatedState(
        when (activeSide) {
            EdgeSide.Start -> -speedPx
            EdgeSide.End -> speedPx
            null -> 0f
        }
    )

    LaunchedEffect(scrollState, style.acceleration) {
        var velocity = 0f
        snapshotFlow { targetVelocity != 0f }.filter { it }.collect {
            var lastFrame = 0L
            while (targetVelocity != 0f || abs(velocity) > STOP_SPEED_PX) {
                withFrameNanos { now ->
                    val dt = if (lastFrame == 0L) 0f else (now - lastFrame) / 1_000_000_000f
                    lastFrame = now
                    velocity = approachVelocity(velocity, targetVelocity, dt, style.acceleration)
                    if (dt > 0f) scrollState.dispatchRawDelta(velocity * dt)
                }
            }
            velocity = 0f
        }
    }

    val startGlow = animateFloatAsState(
        targetValue = if (canScrollStart && startDistance != null) {
            edgeGlowProximity(startDistance, zonePx, approachPx) *
                (if (heldSide == EdgeSide.Start) style.heldGlowAlpha else style.glowAlpha)
        } else {
            0f
        },
        animationSpec = GlowFadeSpec,
    )
    val endGlow = animateFloatAsState(
        targetValue = if (canScrollEnd && endDistance != null) {
            edgeGlowProximity(endDistance, zonePx, approachPx) *
                (if (heldSide == EdgeSide.End) style.heldGlowAlpha else style.glowAlpha)
        } else {
            0f
        },
        animationSpec = GlowFadeSpec,
    )
    val startChevron = animateFloatAsState(
        if (activeSide == EdgeSide.Start) style.chevronAlpha else 0f,
        animationSpec = GlowFadeSpec,
    )
    val endChevron = animateFloatAsState(
        if (activeSide == EdgeSide.End) style.chevronAlpha else 0f,
        animationSpec = GlowFadeSpec,
    )

    Box(
        modifier
            .onSizeChanged { boxWidth = it.width }
            // Initial阶段只观察指针位置，不消费，内容照常收到事件
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        pointerX = if (event.type == PointerEventType.Exit) {
                            null
                        } else {
                            event.changes.firstOrNull()?.position?.x ?: pointerX
                        }
                    }
                }
            }
    ) {
        // 内容占满整个宽度，滚到两端的卡片一直延伸到边缘，不提前截断
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
        // 边缘区盖在内容两端，只在该方向还能滚动时出现：开头和末尾的卡片完整可点
        if (canScrollStart) {
            EdgeZone(
                onHeldChange = { held -> heldSide = if (held) EdgeSide.Start else null },
                modifier = Modifier.align(Alignment.CenterStart).width(style.zoneWidth),
            )
        }
        if (canScrollEnd) {
            EdgeZone(
                onHeldChange = { held -> heldSide = if (held) EdgeSide.End else null },
                modifier = Modifier.align(Alignment.CenterEnd).width(style.zoneWidth),
            )
        }
        EdgeGlow(
            side = EdgeSide.Start,
            glowAlpha = startGlow,
            chevronAlpha = startChevron,
            color = style.glowColor,
            modifier = Modifier.align(Alignment.CenterStart).width(style.glowWidth).fillMaxHeight(),
        )
        EdgeGlow(
            side = EdgeSide.End,
            glowAlpha = endGlow,
            chevronAlpha = endChevron,
            color = style.glowColor,
            modifier = Modifier.align(Alignment.CenterEnd).width(style.glowWidth).fillMaxHeight(),
        )
    }
}

/** 边缘区：记录鼠标是否在区内按住，并吞掉按下与松开，避免点到底下的内容。 */
@Composable
private fun EdgeZone(onHeldChange: (Boolean) -> Unit, modifier: Modifier) {
    val currentOnHeldChange by rememberUpdatedState(onHeldChange)
    // 按住滚到头时边缘区会消失，收不到松开事件，这里补上
    DisposableEffect(Unit) {
        onDispose { currentOnHeldChange(false) }
    }
    Box(
        modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Press -> currentOnHeldChange(true)
                            PointerEventType.Release -> currentOnHeldChange(false)
                        }
                        event.changes.forEach { change ->
                            if (change.pressed != change.previousPressed) change.consume()
                        }
                    }
                }
            }
    )
}

/** 从边缘向内渐隐、上下两端收窄的柔光，鼠标停在边缘区时浮出方向箭头；不接收指针事件。 */
@Composable
private fun EdgeGlow(
    side: EdgeSide,
    glowAlpha: State<Float>,
    chevronAlpha: State<Float>,
    color: Color,
    modifier: Modifier,
) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                val horizontal = if (side == EdgeSide.Start) {
                    Brush.horizontalGradient(listOf(color, Color.Transparent))
                } else {
                    Brush.horizontalGradient(listOf(Color.Transparent, color))
                }
                val verticalMask = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.2f to Color.Black,
                    0.8f to Color.Black,
                    1f to Color.Transparent,
                )
                onDrawBehind {
                    val alpha = glowAlpha.value
                    if (alpha > 0f) {
                        drawRect(horizontal, alpha = alpha)
                        drawRect(verticalMask, blendMode = BlendMode.DstIn)
                    }
                }
            }
    ) {
        Text(
            text = if (side == EdgeSide.Start) "\uF053" else "\uF054",
            fontFamily = IconFontFamily,
            fontSize = 28.sp,
            color = Color.White,
            modifier = Modifier
                .align(if (side == EdgeSide.Start) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = chevronAlpha.value },
        )
    }
}

/** 离边缘[distance]像素处的发光强度：边缘区内为1，超过[approach]为0，中间平滑过渡。 */
internal fun edgeGlowProximity(distance: Float, zone: Float, approach: Float): Float {
    if (distance <= zone) return 1f
    if (distance >= approach || approach <= zone) return 0f
    val t = (distance - zone) / (approach - zone)
    return 1f - t * t * (3f - 2f * t)
}

/** 速度按指数方式趋近目标，[rate]越大越快到位；与帧率无关。 */
internal fun approachVelocity(current: Float, target: Float, dt: Float, rate: Float): Float =
    current + (target - current) * (1f - exp(-rate * dt))
