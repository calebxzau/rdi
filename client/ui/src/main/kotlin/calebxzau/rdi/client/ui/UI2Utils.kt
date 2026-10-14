package calebxzau.rdi.client.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.material3.OutlinedTextField as M3OutlinedTextField

/**
 * calebxzhou @ 2026-02-15 21:10
 */
val DEFAULT_MODPACK_ICON by lazy { loadIconBitmap("modpack.avif").getOrElse { ImageBitmap(0,0) } }
val DEFAULT_HOST_ICON by lazy { loadIconBitmap("host.avif").getOrElse { ImageBitmap(0,0) }  }
data class TitleTabItem<T>(
    val value: T,
    val icon: String,
    val label: String
)

private const val TAB_FADE_DURATION_MS = 140
private const val TAB_SLIDE_DURATION_MS = 180

@Composable
fun <T> KeepAliveAnimatedTabHost(
    selected: T,
    order: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    val visitedTabs = remember { mutableStateListOf(selected) }
    val entryDirections = remember { mutableStateMapOf<T, Int>() }
    var previousSelected by remember { mutableStateOf(selected) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(selected) {
        focusManager.clearFocus()
        if (selected !in visitedTabs) {
            entryDirections[selected] = (order(selected) - order(previousSelected)).compareTo(0)
            visitedTabs += selected
        }
        previousSelected = selected
    }

    BoxWithConstraints(modifier) {
        val slideDistance = with(LocalDensity.current) { maxWidth.toPx() / 10f }
        visitedTabs.forEach { tab ->
            key(tab) {
                val active = tab == selected
                var entered by remember { mutableStateOf(tab == visitedTabs.first()) }
                LaunchedEffect(Unit) { entered = true }
                val direction = when {
                    active -> entryDirections[tab] ?: 0
                    order(tab) < order(selected) -> -1
                    else -> 1
                }
                val alpha by animateFloatAsState(
                    targetValue = if (active && entered) 1f else 0f,
                    animationSpec = tween(TAB_FADE_DURATION_MS),
                    label = "TabAlpha"
                )
                val offset by animateFloatAsState(
                    targetValue = if (active && entered) 0f else direction.toFloat(),
                    animationSpec = tween(TAB_SLIDE_DURATION_MS),
                    label = "TabOffset"
                )
                val inactiveModifier = if (active) {
                    Modifier
                } else {
                    Modifier
                        .clearAndSetSemantics {}
                        .pointerInput(tab) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                                }
                            }
                        }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (active) 1f else 0f)
                        .graphicsLayer {
                            this.alpha = alpha
                            translationX = offset * slideDistance
                            clip = false
                        }
                        .then(inactiveModifier)
                ) {
                    content(tab)
                }
            }
        }
    }
}

val Int.wM: Modifier
    get() = Modifier.width(this.dp)

val Int.hM: Modifier
    get() = Modifier.height(this.dp)

@Composable
fun Space8w() {
    Spacer(modifier = Modifier.width(8.dp))
}

@Composable
fun Space24w() {
    Spacer(modifier = Modifier.width(24.dp))
}

@Composable
fun Space8h() {
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
fun Space24h() {
    Spacer(modifier = Modifier.height(24.dp))

}

val space8
    get() = Arrangement.spacedBy(8.dp)

val space16
    get() = Arrangement.spacedBy(16.dp)
@Composable
fun MainColumn(content: @Composable (ColumnScope.() -> Unit)) {
    Column(
        modifier = Modifier.fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 8.dp)
    ) { content() }
}

@Composable
fun MaxBox(content: @Composable (BoxScope.() -> Unit)) {
    Box(modifier = Modifier.fillMaxSize()) { content() }
}

@Composable
fun TinyClickCopyText(
    label: String,
    value: String?,
    onCopied: () -> Unit = {}
) {
    Text(
        text = "$label ${value ?: "-"}",
        modifier = Modifier.clickable(enabled = value != null) {
            value?.let {
                copyToClipboard(it)
                onCopied()
            }
        },
        fontSize = 8.sp,
        lineHeight = 8.sp,
        maxLines = 1,
        color = Color.LightGray,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun RThinTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = "",
    lineLimits: TextFieldLineLimits = TextFieldLineLimits.SingleLine,
    shape: Shape = RoundedCornerShape(baseShapeRadius.dp),
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    M3OutlinedTextField(
        state = state,
        enabled = enabled,
        lineLimits = lineLimits,
        label = { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape
    )
}


@Composable
fun RTextField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    M3OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        label = { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        shape = RoundedCornerShape(baseShapeRadius.dp),
        leadingIcon=leadingIcon,
        trailingIcon = trailingIcon,
    )
}

@Composable
fun RSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.scale(0.8f)
    )
}

@Composable
inline fun RowV(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    content: @Composable (RowScope.() -> Unit)
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier, horizontalArrangement = horizontalArrangement, content = content
    )
}
//flow row V with 8dp arr.
@Composable
fun RRow(
    modifier: Modifier = Modifier,
    itemVerticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    horizontalArrangement: Arrangement.Horizontal = space8,
    verticalArrangement: Arrangement.Vertical = space8,
    content: @Composable (FlowRowScope.() -> Unit) = {}
){
    FlowRow(
        modifier,
        itemVerticalAlignment = itemVerticalAlignment,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
        content = content
    )
}

@Composable
fun FlowRowV(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable (FlowRowScope.() -> Unit)
) {
    FlowRow(
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = verticalArrangement,
        content = content
    )
}


@Composable
inline fun RColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = space8,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier,
        verticalArrangement,
        horizontalAlignment,
        content,
    )
}

//pua codepoint识别并应用图标字体
val String.asIconText
    get() = buildAnnotatedString {
        val str = this@asIconText
        var i = 0
        while (i < str.length) {
            val codePoint = str.codePointAt(i)
            val isPua = (codePoint in 0xE000..0xF8FF) ||
                    (codePoint in 0xF0000..0xFFFFD) ||
                    (codePoint in 0x100000..0x10FFFD)

            val charCount = Character.charCount(codePoint)
            if (isPua) {
                withStyle(style = SpanStyle(fontFamily = IconFontFamily)) {
                    append(str.substring(i, i + charCount))
                }
            } else {
                append(str.substring(i, i + charCount))
            }
            i += charCount
        }
    }



@Composable
fun SimpleTooltip(
    text: String,
    position: TooltipAnchorPosition = TooltipAnchorPosition.Above,
    content: @Composable () -> Unit
) {
    val state = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(position, 4.dp),
        tooltip = {
            PlainTooltip(
                caretShape = null,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Text(text = text, fontSize = 12.sp)
            }
        },
        state = state
    ) {
        content()
    }
}

@Composable
fun CopyButton(value: String) {
    CircleIconButton("\uF0C5", "复制", size = 24.dp, showText = false) {
        copyToClipboard(value)
    }
}


@Composable
fun BoxScope.BottomSnakebarM3(state: SnackbarHostState) {
    SnackbarHost(
        hostState = state,
        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
    )
}

@Composable
fun RowScope.SpacerFullW() {
    Spacer(Modifier.weight(1f))
}

@Composable
fun ErrorText(text: String) {
    Text("\uF467 $text".asIconText, color = MaterialTheme.colorScheme.error)
}
