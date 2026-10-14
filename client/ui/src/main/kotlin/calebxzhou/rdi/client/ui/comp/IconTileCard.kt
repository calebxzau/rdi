package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import calebxzau.rdi.client.ui.baseRoundCornerShape
import calebxzau.rdi.client.ui.comp.GlowingDot

/** Status dot color for "ready / running right now", shared by every icon tile. */
internal val TILE_ACTIVE_DOT_COLOR = Color(0xFF3DDC84)

private const val STATUS_DOT_ID = "tileStatusDot"

/**
 * Square icon with a one-line title below, shared by room and modpack tiles.
 * Left click runs [onPrimaryClick]; right and middle clicks run their callbacks and hide the hint.
 * While hovered (and [showHint] holds) the tile publishes [hint] to the enclosing [CardHintHost].
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
            },
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(baseRoundCornerShape)
                .clickable(enabled = primaryEnabled, onClick = onPrimaryClick),
            color = MaterialTheme.colorScheme.surface,
            shape = baseRoundCornerShape,
            tonalElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (dimmed) 0.45f else 1f)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    modifier = Modifier.fillMaxWidth(),
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
}
