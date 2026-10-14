package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.ui.loadIconBitmap
import calebxzhou.rdi.client.service.rememberPlayerHeadState
import org.bson.types.ObjectId

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
 * What a hovered card wants shown next to the cursor: a full-length [title] (the card itself cuts it
 * to one line), an optional one-line [detail], an optional owner row and the mouse [actions].
 * A version string follows its line in a muted color.
 */
internal data class CardHint(
    val title: String,
    val titleVersion: String = "",
    val detail: String = "",
    val detailVersion: String = "",
    val ownerId: ObjectId? = null,
    val actions: List<CardHintAction> = emptyList(),
)

private data class ShownCardHint(val owner: Any, val cursorInWindow: Offset, val hint: CardHint)

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

    fun show(owner: Any, cursorInWindow: Offset, hint: CardHint) {
        val shown = ShownCardHint(owner, cursorInWindow, hint)
        if (current != shown) current = shown
    }

    fun clear(owner: Any) {
        if (current?.owner === owner) current = null
    }

    @Composable
    fun Overlay(modifier: Modifier) {
        current?.let { CardHintOverlay(it, this, modifier) }
    }
}

internal val LocalCardHintState = staticCompositionLocalOf<CardHintState?> { null }

/** Wrap a screen with this so the cards inside it can show their cursor-following hint. */
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

/** Places the hint below-right of the cursor (clearing the pointer glyph), flipping sides when it would leave the container. */
internal fun cardMouseHintPosition(
    cursor: IntOffset,
    hintSize: IntSize,
    containerSize: IntSize,
    gap: Int,
    belowGap: Int,
): IntOffset {
    val rightX = cursor.x + gap
    val x = if (rightX + hintSize.width <= containerSize.width) rightX else cursor.x - gap - hintSize.width
    val belowY = cursor.y + belowGap
    val y = if (belowY + hintSize.height <= containerSize.height) belowY else cursor.y - gap - hintSize.height
    return IntOffset(
        x.coerceIn(0, (containerSize.width - hintSize.width).coerceAtLeast(0)),
        y.coerceIn(0, (containerSize.height - hintSize.height).coerceAtLeast(0)),
    )
}

@Composable
private fun CardHintOverlay(shown: ShownCardHint, state: CardHintState, modifier: Modifier) {
    Layout(
        modifier = modifier,
        content = { CardHintContent(shown.hint) },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val cursor = state.containerCoordinates
            ?.takeIf { it.isAttached }
            ?.windowToLocal(shown.cursorInWindow)
            ?.round()
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (cursor != null) {
                placeable.place(
                    cardMouseHintPosition(
                        cursor = cursor,
                        hintSize = IntSize(placeable.width, placeable.height),
                        containerSize = IntSize(constraints.maxWidth, constraints.maxHeight),
                        gap = 12.dp.roundToPx(),
                        belowGap = 20.dp.roundToPx(),
                    ),
                )
            }
        }
    }
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
                hint.ownerId?.let { OwnerHintItem(it) }
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
private fun OwnerHintItem(ownerId: ObjectId) {
    val state = rememberPlayerHeadState(ownerId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "房主", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PlayerHead(
            skinImage = state.skinImage,
            modifier = Modifier.size(14.dp).clip(RoundedCornerShape(2.dp)),
        )
        Text(
            text = state.name,
            modifier = Modifier.widthIn(max = 240.dp),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
