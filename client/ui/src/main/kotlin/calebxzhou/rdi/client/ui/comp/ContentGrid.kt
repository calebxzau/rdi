package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.RVerticalScrollbar as SharedRVerticalScrollbar
import calebxzau.rdi.client.ui.SearchField
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.common.model.Mod
import java.util.Locale
import kotlin.math.roundToInt

enum class ContentGridType(
    val displayName: String,
    val icon: String,
) {
    MOD("模组", "\uF1B2"),
    RESOURCE_PACK("资源包", "\uF03E"),
    SHADER_PACK("光影包", "\uF03D"),
    DATA_PACK("数据包", "\uF1C0"),
    OTHER("其他", "\uF15B"),
}

enum class ContentSelectionState {
    NONE,
    SELECTED,
    UNSELECTED,
    REQUIRED,
}

sealed interface ContentGridItem {
    val key: String
    val type: ContentGridType
    val name: String
    val searchText: String
    val fileName: String
    val targetPath: String
    val required: Boolean
    val selectionState: ContentSelectionState

    data class ModItem(
        val mod: UiMod,
        val archiveKey: String = mod.key,
        val archiveFileName: String = mod.mod.fileName,
        val archiveTargetPath: String = mod.mod.targetPath.toString(),
        override val required: Boolean = false,
        override val selectionState: ContentSelectionState = ContentSelectionState.NONE,
    ) : ContentGridItem {
        override val key: String get() = archiveKey
        override val type: ContentGridType get() = ContentGridType.MOD
        override val name: String get() = mod.primaryName
        override val fileName: String get() = archiveFileName
        override val targetPath: String get() = archiveTargetPath
        override val searchText: String = listOf(name, fileName, targetPath)
            .joinToString("\n")
            .lowercase(Locale.ROOT)
    }

    data class ContentItem(
        override val key: String,
        override val type: ContentGridType,
        override val name: String,
        override val fileName: String,
        override val targetPath: String,
        override val required: Boolean,
        override val selectionState: ContentSelectionState = ContentSelectionState.NONE,
        val card: Mod.CardVo? = null,
        val status: String? = null,
        val archiveDisplayName: String? = null,
        val archiveSummary: String? = null,
    ) : ContentGridItem {
        override val searchText: String = listOfNotNull(
            name,
            fileName,
            targetPath,
            card?.name,
            card?.nameCn,
            card?.intro,
            archiveDisplayName,
            archiveSummary,
        )
            .joinToString("\n")
            .lowercase(Locale.ROOT)
    }
}

data class ContentCardPresentation(
    val name: String,
    val description: String,
    val iconData: ByteArray?,
    val iconUrls: List<String>,
    val version: String? = null,
)

private data class ContentGridTooltipAnchor(
    val key: String,
    val text: String,
    val boundsInWindow: Rect,
)

internal fun contentGridTooltipPosition(
    anchorBounds: Rect,
    tooltipSize: IntSize,
    containerSize: IntSize,
    gapPx: Int,
): IntOffset {
    val maxX = (containerSize.width - tooltipSize.width).coerceAtLeast(0)
    val maxY = (containerSize.height - tooltipSize.height).coerceAtLeast(0)
    val x = (anchorBounds.center.x - tooltipSize.width / 2f)
        .roundToInt()
        .coerceIn(0, maxX)
    val aboveY = anchorBounds.top - gapPx - tooltipSize.height
    val belowY = anchorBounds.bottom + gapPx
    val y = (if (aboveY >= 0f) aboveY else belowY)
        .roundToInt()
        .coerceIn(0, maxY)
    return IntOffset(x, y)
}

internal fun ContentGridItem.ContentItem.toCardPresentation(): ContentCardPresentation {
    val displayName = card?.nameCn?.takeIf(String::isNotBlank)
        ?: card?.name?.takeIf(String::isNotBlank)
        ?: name.takeIf(String::isNotBlank)
        ?: archiveDisplayName?.takeIf(String::isNotBlank)
        ?: fileName
    val intro = card?.intro?.takeIf(String::isNotBlank)
        ?: archiveSummary?.takeIf(String::isNotBlank)
    val description = listOfNotNull(
        status?.takeIf(String::isNotBlank),
        intro,
    ).joinToString(" · ").ifBlank {
        targetPath.takeIf(String::isNotBlank) ?: "暂无简介"
    }
    return ContentCardPresentation(
        name = displayName,
        description = description,
        iconData = card?.iconData,
        iconUrls = card?.iconUrls.orEmpty(),
        version = card?.version?.takeIf(String::isNotBlank),
    )
}

sealed interface ContentGridDragEvent {
    data class Start(val item: ContentGridItem, val pointerInWindow: Offset) : ContentGridDragEvent
    data class Move(val pointerInWindow: Offset) : ContentGridDragEvent
    data class End(val pointerInWindow: Offset) : ContentGridDragEvent
    data object Cancel : ContentGridDragEvent
}

/** Optional item-specific actions layered on top of the shared copy/delete menu. */
data class ContentGridContextAction(
    val label: String,
    val enabled: Boolean = true,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Shared archive-content grid.  Selection is deliberately a value on each
 * item; the grid only reports the requested new value and never owns it.
 */
@Composable
fun ContentGrid(
    items: List<ContentGridItem>,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    emptyText: String = "没有可显示的内容",
    noSourceEmptyText: String = "没有可识别的内容",
    onSelectionChange: (ContentGridItem, Boolean) -> Unit = { _, _ -> },
    onItemClick: ((ContentGridItem) -> Unit)? = null,
    onDelete: ((ContentGridItem) -> Unit)? = null,
    canDelete: (ContentGridItem) -> Boolean = { true },
    contextActions: (ContentGridItem) -> List<ContentGridContextAction> = { emptyList() },
    onDragEvent: ((ContentGridDragEvent) -> Unit)? = null,
    draggedKey: String? = null,
    initialIconOnly: Boolean = false,
    showTypeFilters: Boolean = true,
    stateKey: String? = null,
    isResolving: Boolean = false,
    resolvingModName: String? = null,
    emptySource: Boolean = false,
    /**
     * Replaces the default type/name ordering. An adapter can return the
     * supplied list unchanged when its caller already owns ordering.
     */
    sortItems: ((List<ContentGridItem>, Boolean) -> List<ContentGridItem>)? = null,
    onModSideChange: ((UiMod, Mod.Side) -> Unit)? = null,
    legacySelectedKeys: Set<String> = emptySet(),
    legacySelectedHighlight: Boolean = false,
    onIconOnlyChange: ((Boolean) -> Unit)? = null,
    onModItemSideChange: ((ContentGridItem.ModItem, Mod.Side) -> Unit)? = null,
) {
    var search by rememberSaveable(stateKey) { mutableStateOf("") }
    var showSearch by rememberSaveable(stateKey) { mutableStateOf(false) }
    var iconOnly by rememberSaveable(stateKey, initialIconOnly) { mutableStateOf(initialIconOnly) }
    var selectedType by rememberSaveable(stateKey) { mutableStateOf<ContentGridType?>(null) }
    var tooltipAnchor by remember { mutableStateOf<ContentGridTooltipAnchor?>(null) }
    var gridBoundsInWindow by remember { mutableStateOf<Rect?>(null) }
    val clipboard = LocalClipboardManager.current
    val currentDragEvent by rememberUpdatedState(onDragEvent)

    val publishTooltip: (String, String, Rect) -> Unit = { key, text, boundsInWindow ->
        val next = ContentGridTooltipAnchor(key, text, boundsInWindow)
        if (tooltipAnchor != next) tooltipAnchor = next
    }
    val clearTooltip: (String) -> Unit = { key ->
        if (tooltipAnchor?.key == key) tooltipAnchor = null
    }

    LaunchedEffect(iconOnly) { onIconOnlyChange?.invoke(iconOnly) }

    val counts = remember(items) {
        ContentGridType.entries.associateWith { type -> items.count { it.type == type } }
    }
    val selectedCounts = remember(items) {
        ContentGridType.entries.associateWith { type ->
            items.count { it.type == type && it.selectionState.isSelected() }
        }
    }
    val ordered = remember(items, sortItems, iconOnly) {
        sortItems?.invoke(items, iconOnly)
            ?: items.sortedWith(compareBy<ContentGridItem>({ it.type.ordinal }, { it.name.lowercase(Locale.ROOT) }))
    }
    val query = search.trim().lowercase(Locale.ROOT)
    val visible = remember(ordered, query, selectedType) {
        ordered.filter { item ->
            (selectedType == null || item.type == selectedType) &&
                (query.isBlank() || item.searchText.contains(query))
        }
    }

    val visibleKeys = remember(visible) { visible.mapTo(mutableSetOf(), ContentGridItem::key) }
    LaunchedEffect(iconOnly, visibleKeys) {
        if (!iconOnly || tooltipAnchor?.key !in visibleKeys) tooltipAnchor = null
    }

    LaunchedEffect(query, selectedType) {
        gridState.scrollToItem(0)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { gridBoundsInWindow = it.boundsInWindow() },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(if (iconOnly) 64.dp else 320.dp),
            horizontalArrangement = Arrangement.spacedBy(if (iconOnly) 4.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (iconOnly) 8.dp else 20.dp),
            contentPadding = PaddingValues(
                end = 18.dp,
                bottom = 12.dp,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (visible.isEmpty()) {
                item(key = "content-grid-empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = if (emptySource) noSourceEmptyText else emptyText,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(visible, key = ContentGridItem::key) { item ->
                ContentGridEntry(
                    item = item,
                    iconOnly = iconOnly,
                    dragged = draggedKey == item.key,
                    onSelectionChange = onSelectionChange,
                    onItemClick = onItemClick,
                    onDelete = onDelete,
                    canDelete = canDelete,
                    contextActions = contextActions(item),
                    onModSideChange = onModSideChange,
                    onModItemSideChange = onModItemSideChange,
                    onDragEvent = currentDragEvent,
                    clipboard = { value -> clipboard.setText(AnnotatedString(value)) },
                    legacySelected = legacySelectedHighlight && item.key in legacySelectedKeys,
                    onTooltipEnter = publishTooltip,
                    onTooltipExit = clearTooltip,
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(12.dp)
                .padding(bottom = 12.dp),
        ) {
            SharedRVerticalScrollbar(
                gridState,
                Modifier.fillMaxHeight().padding(vertical = 2.dp),
            )
        }
        FlowRow(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = 8.dp,
                    end = 18.dp + 32.dp + 8.dp + if (showSearch) 220.dp + 8.dp else 0.dp,
                )
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTypeFilters) {
                ContentTypeFilterChip(
                    selected = selectedType == null,
                    label = "全部",
                    count = items.size,
                    selectedCount = items.count { it.selectionState.isSelected() },
                    allNone = items.all { it.selectionState == ContentSelectionState.NONE },
                    onClick = { selectedType = null },
                )
                ContentGridType.entries.filter { counts[it] != 0 }.forEach { type ->
                    ContentTypeFilterChip(
                        selected = selectedType == type,
                        label = type.displayName,
                        count = counts.getValue(type),
                        selectedCount = selectedCounts.getValue(type),
                        onClick = { selectedType = type },
                    )
                }
            }
            if (isResolving) {
                Column(modifier = Modifier.width(180.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    resolvingModName?.let {
                        Text(
                            "正在识别$it",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showSearch) {
                    SearchField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = "名称、文件名或安装路径",
                        onSearch = null,
                        modifier = Modifier.width(220.dp),
                    )
                }
                CircleIconButton(
                    icon = if (showSearch) "\uF00D" else "\uF002",
                    tooltip = if (showSearch) "隐藏搜索" else "搜索",
                    bgColor = MaterialTheme.colorScheme.primary,
                    size = 32.dp,
                    showText = false,
                ) {
                    if (showSearch) {
                        showSearch = false
                        search = ""
                    } else {
                        showSearch = true
                    }
                }
            }
            CircleIconButton(
                icon = if (iconOnly) "\uF03A" else "\uF00A",
                tooltip = if (iconOnly) "卡片模式" else "仅图标模式",
                bgColor = MaterialTheme.colorScheme.primary,
                size = 32.dp,
                showText = false,
            ) { iconOnly = !iconOnly }
        }
        val anchor = tooltipAnchor?.takeIf { iconOnly && it.key in visibleKeys }
        val gridBounds = gridBoundsInWindow
        if (anchor != null && gridBounds != null) {
            ContentGridInlineTooltip(
                text = anchor.text,
                anchorBounds = Rect(
                    left = anchor.boundsInWindow.left - gridBounds.left,
                    top = anchor.boundsInWindow.top - gridBounds.top,
                    right = anchor.boundsInWindow.right - gridBounds.left,
                    bottom = anchor.boundsInWindow.bottom - gridBounds.top,
                ),
                gap = 4.dp,
                modifier = Modifier.fillMaxSize().zIndex(1f),
            )
        }
    }
}

@Composable
private fun ContentGridInlineTooltip(
    text: String,
    anchorBounds: Rect,
    gap: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            Box(
                modifier = Modifier.background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    TooltipDefaults.plainTooltipContainerShape,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .sizeIn(
                            minWidth = 40.dp,
                            maxWidth = 200.dp,
                            minHeight = 24.dp,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = text,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(
            constraints.copy(minWidth = 0, minHeight = 0),
        )
        val position = contentGridTooltipPosition(
            anchorBounds = anchorBounds,
            tooltipSize = IntSize(placeable.width, placeable.height),
            containerSize = IntSize(constraints.maxWidth, constraints.maxHeight),
            gapPx = gap.roundToPx(),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(position)
        }
    }
}

@Composable
private fun ContentTypeFilterChip(
    selected: Boolean,
    label: String,
    count: Int,
    selectedCount: Int,
    allNone: Boolean = false,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(if (allNone) "$label $count" else "$label $selectedCount/$count")
        },
    )
}

@Composable
private fun ContentGridEntry(
    item: ContentGridItem,
    iconOnly: Boolean,
    dragged: Boolean,
    onSelectionChange: (ContentGridItem, Boolean) -> Unit,
    onItemClick: ((ContentGridItem) -> Unit)?,
    onDelete: ((ContentGridItem) -> Unit)?,
    canDelete: (ContentGridItem) -> Boolean,
    contextActions: List<ContentGridContextAction>,
    onModSideChange: ((UiMod, Mod.Side) -> Unit)?,
    onModItemSideChange: ((ContentGridItem.ModItem, Mod.Side) -> Unit)?,
    onDragEvent: ((ContentGridDragEvent) -> Unit)?,
    clipboard: (String) -> Unit,
    legacySelected: Boolean,
    onTooltipEnter: (String, String, Rect) -> Unit,
    onTooltipExit: (String) -> Unit,
) {
    var showContextMenu by remember(item.key) { mutableStateOf(false) }
    var primaryPressed by remember(item.key) { mutableStateOf(false) }
    var hovered by remember(item.key) { mutableStateOf(false) }
    var iconBoundsInWindow by remember(item.key) { mutableStateOf<Rect?>(null) }
    var coordinates by remember(item.key) { mutableStateOf<LayoutCoordinates?>(null) }
    val currentOnDragEvent by rememberUpdatedState(onDragEvent)
    val currentTooltipEnter by rememberUpdatedState(onTooltipEnter)
    val currentTooltipExit by rememberUpdatedState(onTooltipExit)
    val tooltipText = itemTooltip(item)
    val selectable = item.selectionState == ContentSelectionState.SELECTED ||
        item.selectionState == ContentSelectionState.UNSELECTED
    val outerClick = when {
        selectable -> Modifier.clickable {
            onSelectionChange(item, item.selectionState == ContentSelectionState.UNSELECTED)
        }
        onItemClick != null -> Modifier.clickable { onItemClick(item) }
        else -> Modifier
    }
    val pointerModifier = Modifier.pointerInput(item.key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                primaryPressed = event.buttons.isPrimaryPressed
                if (event.buttons.isSecondaryPressed) showContextMenu = true
                when (event.type) {
                    PointerEventType.Enter -> if (iconOnly) {
                        hovered = true
                        iconBoundsInWindow?.let { bounds ->
                            currentTooltipEnter(item.key, tooltipText, bounds)
                        }
                    }
                    PointerEventType.Exit -> {
                        hovered = false
                        currentTooltipExit(item.key)
                    }
                }
            }
        }
    }
    LaunchedEffect(item.key, iconOnly, hovered, tooltipText, iconBoundsInWindow) {
        if (iconOnly && hovered) {
            iconBoundsInWindow?.let { bounds ->
                currentTooltipEnter(item.key, tooltipText, bounds)
            }
        }
    }
    LaunchedEffect(iconOnly) {
        if (!iconOnly) {
            hovered = false
            currentTooltipExit(item.key)
        }
    }
    DisposableEffect(item.key) {
        onDispose { currentTooltipExit(item.key) }
    }
    val dragModifier = if (onDragEvent == null) Modifier else Modifier
        .onGloballyPositioned { coordinates = it }
        .pointerInput(item.key) {
            var dragging = false
            var lastPointer: Offset? = null
            detectDragGestures(
                onDragStart = { position ->
                    if (primaryPressed) {
                        coordinates?.localToWindow(position)?.let { pointer ->
                            dragging = true
                            lastPointer = pointer
                            currentOnDragEvent?.invoke(ContentGridDragEvent.Start(item, pointer))
                        }
                    }
                },
                onDrag = { change, _ ->
                    if (dragging) {
                        change.consume()
                        coordinates?.localToWindow(change.position)?.let { pointer ->
                            lastPointer = pointer
                            currentOnDragEvent?.invoke(ContentGridDragEvent.Move(pointer))
                        }
                    }
                },
                onDragEnd = {
                    val pointer = lastPointer
                    if (dragging && pointer != null) {
                        currentOnDragEvent?.invoke(ContentGridDragEvent.End(pointer))
                    } else {
                        currentOnDragEvent?.invoke(ContentGridDragEvent.Cancel)
                    }
                },
                onDragCancel = { currentOnDragEvent?.invoke(ContentGridDragEvent.Cancel) },
            )
        }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(
                if (dragged ||
                    (item.selectionState == ContentSelectionState.UNSELECTED && !legacySelected)
                ) 0.6f else 1f
            )
            .background(
                if (legacySelected ||
                    item.selectionState == ContentSelectionState.SELECTED ||
                    item.selectionState == ContentSelectionState.REQUIRED
                ) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(18.dp),
            )
            .padding(2.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(outerClick)
            .then(pointerModifier)
            .then(dragModifier),
    ) {
        if (iconOnly) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .size(64.dp)
                    .onGloballyPositioned { iconBoundsInWindow = it.boundsInWindow() }
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when (item) {
                    is ContentGridItem.ModItem -> UiModIcon(item.mod, Modifier.fillMaxSize())
                    is ContentGridItem.ContentItem -> ContentIcon(item, Modifier.fillMaxSize())
                }
                Text(
                    text = item.type.icon,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            when (item) {
                is ContentGridItem.ModItem -> UiModCard(
                    mod = item.mod,
                    modifier = Modifier.fillMaxWidth(),
                    onSideChange = when {
                        onModItemSideChange != null -> { nextSide -> onModItemSideChange(item, nextSide) }
                        onModSideChange != null -> { nextSide -> onModSideChange(item.mod, nextSide) }
                        else -> null
                    },
                    trailingContent = selectionTrailing(item, onSelectionChange),
                )
                is ContentGridItem.ContentItem -> ContentCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = selectionTrailing(item, onSelectionChange),
                )
            }
        }
        ContentGridContextMenu(
            expanded = showContextMenu,
            item = item,
            onDismissRequest = { showContextMenu = false },
            onCopy = { value ->
                clipboard(value)
                showContextMenu = false
            },
            onDelete = onDelete?.let { callback ->
                if (canDelete(item)) {
                    {
                        callback(item)
                        showContextMenu = false
                    }
                } else null
            },
            contextActions = contextActions,
        )
    }
}

private fun selectionTrailing(
    item: ContentGridItem,
    onSelectionChange: (ContentGridItem, Boolean) -> Unit,
): (@Composable RowScope.() -> Unit)? = when (item.selectionState) {
    ContentSelectionState.NONE -> null
    ContentSelectionState.REQUIRED -> {
        {
            SelectionButton("\uf00c", checked = true, onClick = null)
        }
    }
    ContentSelectionState.SELECTED, ContentSelectionState.UNSELECTED -> {
        {
            SelectionButton(
                icon = "\uf00c",
                checked = item.selectionState == ContentSelectionState.SELECTED,
                onClick = { onSelectionChange(item, true) },
            )
            SelectionButton(
                icon = "×",
                checked = item.selectionState == ContentSelectionState.UNSELECTED,
                onClick = { onSelectionChange(item, false) },
            )
        }
    }
}

@Composable
private fun RowScope.SelectionButton(icon: String, checked: Boolean, onClick: (() -> Unit)?) {
    ToggleButton(icon = icon, checked = checked, onClick = onClick)
}

private fun ContentSelectionState.isSelected(): Boolean = this == ContentSelectionState.SELECTED ||
    this == ContentSelectionState.REQUIRED

private fun itemTooltip(item: ContentGridItem): String = buildString {
    append(item.name)
    append(" · ")
    append(item.type.displayName)
    append(" · ")
    append(if (item.required) "必需" else "可选")
    when (item.selectionState) {
        ContentSelectionState.NONE -> Unit
        ContentSelectionState.SELECTED -> append(" · 已选择")
        ContentSelectionState.UNSELECTED -> append(" · 未选择")
        ContentSelectionState.REQUIRED -> append(" · 固定选择")
    }
}

@Composable
private fun ContentGridContextMenu(
    expanded: Boolean,
    item: ContentGridItem,
    onDismissRequest: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (() -> Unit)?,
    contextActions: List<ContentGridContextAction>,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("复制文件名") },
            onClick = { onCopy(item.fileName) },
        )
        if (item.targetPath.isNotBlank()) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("复制安装路径") },
                onClick = { onCopy(item.targetPath) },
            )
        }
        contextActions.forEach { action ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(action.label) },
                enabled = action.enabled,
                onClick = {
                    onDismissRequest()
                    action.onClick()
                },
            )
        }
        onDelete?.let { delete ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("删除") },
                onClick = delete,
            )
        }
    }
}
