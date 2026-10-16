package calebxzhou.rdi.client.ui.comp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.sp
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.ui.loadIconBitmap

/** Mouse buttons a hint can explain, each drawn with its own glyph. */
internal enum class HintMouseButton(iconName: String) {
    Left("mouse_left.avif"),
    Right("mouse_right.avif"),
    Middle("mouse_middle.avif");

    val icon: ImageBitmap? by lazy {
        loadIconBitmap(iconName).getOrElse {
            lgr.warn(it) { "加载鼠标提示图标失败: ${iconName}" }
            null
        }
    }
}

internal data class CardHintAction(
    val button: HintMouseButton,
    val label: String,
    val active: Boolean = true,
)

/**
 * What a hovered card wants shown below itself: a full-length [title], an optional one-line [detail]
 * and the mouse [actions]. A version string follows its line in a muted color.
 */
internal data class CardHint(
    val title: String,
    val titleVersion: String = "",
    val detail: String = "",
    val detailVersion: String = "",
    val actions: List<CardHintAction> = emptyList(),
)

private data class ShownCardHint(val owner: Any, val anchorInWindow: Rect, val hint: CardHint)

/**
 * Shared hint slot for every card under one [CardHintHost].
 * Cards only publish their hover state; the host draws the hint, so no Popup layer lives
 * inside lazy grid items (a lazy grid disposing a hovered item mid-layout would close a Popup
 * layer that Compose is still about to measure, crashing with "RootNodeOwner is already disposed").
 */
internal class CardHintState {
    private var current by mutableStateOf<ShownCardHint?>(null)

    /** Plain field: only read while laying out the overlay, never needs to trigger recomposition. */
    var containerCoordinates: LayoutCoordinates? = null

    /** Shows [hint] for [owner], pointing at [anchorInWindow]; call again whenever the card moves. */
    fun show(owner: Any, anchorInWindow: Rect, hint: CardHint) {
        val shown = ShownCardHint(owner, anchorInWindow, hint)
        if (current != shown) current = shown
    }

    fun clear(owner: Any) {
        if (current?.owner === owner) current = null
    }

    @Composable
    fun Overlay(modifier: Modifier) {
        current?.let { shown ->
            // 每换一张卡片重新淡入
            key(shown.owner) { CardHintOverlay(shown, this, modifier) }
        }
    }
}

internal val LocalCardHintState = staticCompositionLocalOf<CardHintState?> { null }

/** Wrap a screen with this so the cards inside it can show their hint below themselves. */
@Composable
fun CardHintHost(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = remember { CardHintState() }
    Box(modifier.onGloballyPositioned { state.containerCoordinates = it }) {
        CompositionLocalProvider(LocalCardHintState provides state) {
            content()
        }
        state.Overlay(Modifier.matchParentSize())
    }
}

/** 提示框与卡片之间的距离：避开选中框（卡片外8dp）。 */
private val HINT_ANCHOR_GAP = 10.dp
private val HINT_ARROW_WIDTH = 14.dp
private val HINT_ARROW_HEIGHT = 7.dp
/** 小三角离提示框左右边缘的最小距离，免得戳到圆角上。 */
private val HINT_ARROW_EDGE_INSET = 16.dp
private const val HINT_FADE_IN_MILLIS = 120

/** Where the hint box goes ([offset]), whether it sits [below] the card, and the arrow tip's x inside the box ([arrowX]). */
internal data class CardHintPlacement(val offset: IntOffset, val below: Boolean, val arrowX: Int)

/**
 * Centers the hint under [anchor] with an arrow of [arrowHeight] pointing up at it; flips above when it does not fit below
 * but fits above. The box stays inside the container and the arrow keeps [arrowInset] from the box edges.
 */
internal fun cardAnchoredHintPlacement(
    anchor: IntRect,
    hintSize: IntSize,
    containerSize: IntSize,
    gap: Int,
    arrowHeight: Int,
    arrowInset: Int,
): CardHintPlacement {
    val belowY = anchor.bottom + gap + arrowHeight
    val aboveY = anchor.top - gap - arrowHeight - hintSize.height
    val below = belowY + hintSize.height <= containerSize.height || aboveY < 0
    val y = if (below) belowY else aboveY
    val centerX = anchor.center.x
    val x = (centerX - hintSize.width / 2).coerceIn(0, (containerSize.width - hintSize.width).coerceAtLeast(0))
    val arrowX = (centerX - x).coerceIn(arrowInset, (hintSize.width - arrowInset).coerceAtLeast(arrowInset))
    return CardHintPlacement(
        offset = IntOffset(x, y.coerceIn(0, (containerSize.height - hintSize.height).coerceAtLeast(0))),
        below = below,
        arrowX = arrowX,
    )
}

@Composable
private fun CardHintOverlay(shown: ShownCardHint, state: CardHintState, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(HINT_FADE_IN_MILLIS)) }
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    Layout(
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        content = {
            CardHintContent(shown.hint)
            HintArrow(color, pointsUp = true)
            HintArrow(color, pointsUp = false)
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val (content, arrowUp, arrowDown) = measurables.map { it.measure(looseConstraints) }
        val anchor = state.containerCoordinates
            ?.takeIf { it.isAttached }
            ?.let { container ->
                val topLeft = container.windowToLocal(shown.anchorInWindow.topLeft)
                IntRect(topLeft.round(), shown.anchorInWindow.size.toIntSize())
            }
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (anchor != null) {
                val placement = cardAnchoredHintPlacement(
                    anchor = anchor,
                    hintSize = IntSize(content.width, content.height),
                    containerSize = IntSize(constraints.maxWidth, constraints.maxHeight),
                    gap = HINT_ANCHOR_GAP.roundToPx(),
                    arrowHeight = arrowUp.height,
                    arrowInset = HINT_ARROW_EDGE_INSET.roundToPx(),
                )
                val box = placement.offset
                val arrowLeft = box.x + placement.arrowX - arrowUp.width / 2
                // 只放置朝向卡片的那个小三角，另一个不放置就不会绘制
                if (placement.below) {
                    arrowUp.place(arrowLeft, box.y - arrowUp.height)
                } else {
                    arrowDown.place(arrowLeft, box.y + content.height)
                }
                content.place(box)
            }
        }
    }
}

/** 提示框指向卡片的小三角，颜色与提示框一致。 */
@Composable
private fun HintArrow(color: Color, pointsUp: Boolean) {
    Spacer(
        Modifier
            .size(HINT_ARROW_WIDTH, HINT_ARROW_HEIGHT)
            .drawWithCache {
                val path = Path().apply {
                    if (pointsUp) {
                        moveTo(0f, size.height)
                        lineTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height)
                    } else {
                        moveTo(0f, 0f)
                        lineTo(size.width / 2f, size.height)
                        lineTo(size.width, 0f)
                    }
                    close()
                }
                onDrawBehind { drawPath(path, color) }
            },
    )
}

/** Plain Box rather than Surface: a non-clickable M3 Surface blocks pointer input, which would steal hover from the card. */
@Composable
private fun CardHintContent(hint: CardHint) {
    val shape = TooltipDefaults.plainTooltipContainerShape
    Box(
        modifier = Modifier
            .shadow(2.dp, shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy((-1).dp),
            ) {
                VersionedHintText(
                    text = hint.title,
                    version = hint.titleVersion,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    singleLine = false,
                )
                if (hint.detail.isNotBlank() || hint.detailVersion.isNotBlank()) {
                    VersionedHintText(
                        text = hint.detail,
                        version = hint.detailVersion,
                        fontSize = 12.sp,
                        singleLine = true,
                    )
                }
                if (hint.actions.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        hint.actions.forEach { action ->
                            MouseHintItem(
                                icon = action.button.icon,
                                text = action.label,
                                modifier = Modifier.alpha(if (action.active) 1f else 0.45f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionedHintText(
    text: String,
    version: String,
    fontSize: TextUnit,
    singleLine: Boolean,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = buildAnnotatedString {
            append(text)
            if (version.isNotBlank()) {
                if (text.isNotBlank()) append(" ")
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                    append(version)
                }
            }
        },
        modifier = Modifier.widthIn(max = 280.dp),
        fontSize = fontSize,
        fontWeight = fontWeight,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

@Composable
private fun MouseHintItem(icon: ImageBitmap?, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        icon?.let {
            Image(bitmap = it, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        Text(text = text, fontSize = 12.sp)
    }
}
