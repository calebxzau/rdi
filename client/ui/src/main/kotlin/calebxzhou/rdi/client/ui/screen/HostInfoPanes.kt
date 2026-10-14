package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ConfirmDialog
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.lgr
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.model.uiModKey
import calebxzhou.rdi.client.net.rdiRequest
import calebxzhou.rdi.client.net.rdiRequestU
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.hydrateToUiModsInBatches
import calebxzhou.rdi.client.service.toUiMods
import calebxzau.rdi.client.ui.themeNow
import calebxzhou.rdi.client.ui.comp.Console
import calebxzhou.rdi.client.ui.comp.ConsoleState
import calebxzhou.rdi.client.ui.comp.ContentGrid
import calebxzhou.rdi.client.ui.comp.ContentGridDragEvent
import calebxzhou.rdi.client.ui.comp.ContentGridItem
import calebxzhou.rdi.client.ui.comp.rememberModGridItems
import calebxzhou.rdi.client.ui.comp.UiModIcon
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.service.ModService
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import org.bson.types.ObjectId
import kotlin.math.roundToInt

@Composable
internal fun HostModsPane(
    modCatalog: ModCatalog,
    hostId: ObjectId,
    extraMods: List<Mod>,
    baseVersionMods: List<Mod>,
    disabledMods: List<Mod>,
    canManage: Boolean,
    addExtraModLoading: Boolean,
    addExtraModLoadingText: String,
    onChangeDisabledMods: (List<Mod>, Boolean, () -> Unit) -> Unit,
    onRemoveExtraMods: (List<Mod>) -> Unit,
    onOpenResourceMods: () -> Unit,
    onAddExtraModAdvanced: () -> Unit,
    onOk: (String) -> Unit,
    onError: (String) -> Unit,
    onOpenTaskList: (String) -> Unit
) {
    var selectedExtraKeys by remember(hostId) { mutableStateOf(emptySet<String>()) }
    val visibleMods = remember(baseVersionMods, disabledMods, extraMods) {
        (baseVersionMods + disabledMods + extraMods)
            .distinctBy(::extraModKey)
            .sortedBy(::extraModKey)
    }
    var hydratedUiMods by remember(hostId) { mutableStateOf(visibleMods.toUiMods()) }
    var modDetailsLoading by remember(hostId) { mutableStateOf(visibleMods.isNotEmpty()) }
    var dragState by remember(hostId) { mutableStateOf<HostModDragState?>(null) }
    var rootBounds by remember(hostId) { mutableStateOf<Rect?>(null) }
    var enabledAreaBounds by remember(hostId) { mutableStateOf<Rect?>(null) }
    var disabledAreaBounds by remember(hostId) { mutableStateOf<Rect?>(null) }
    var pendingMutationKeys by remember(hostId) { mutableStateOf(emptySet<String>()) }
    fun changeDisabledMods(mods: List<Mod>, disabled: Boolean) {
        if (!canManage) return
        val targets = mods.distinctBy(::extraModKey)
            .filterNot { extraModKey(it) in pendingMutationKeys }
        if (targets.isEmpty()) return
        val targetKeys = targets.map(::extraModKey).toSet()
        pendingMutationKeys += targetKeys
        onChangeDisabledMods(targets, disabled) {
            pendingMutationKeys -= targetKeys
        }
    }

    fun handleDragEvent(source: HostModAreaType, event: ContentGridDragEvent) {
        if (event is ContentGridDragEvent.Start && (!canManage || event.item.key in pendingMutationKeys)) return
        val update = hostModDragUpdate(
            dragState = dragState,
            source = source,
            event = event,
            enabledAreaBounds = enabledAreaBounds,
            disabledAreaBounds = disabledAreaBounds,
        )
        dragState = update.state
        update.mutation?.let { mutation ->
            changeDisabledMods(listOf(mutation.mod), disabled = mutation.disabled)
        }
    }

    LaunchedEffect(visibleMods, modCatalog) {
        modDetailsLoading = visibleMods.isNotEmpty()
        try {
            visibleMods.hydrateToUiModsInBatches(modCatalog)
                .flowOn(Dispatchers.IO)
                .collect { hydratedUiMods = it }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (cause: Throwable) {
            lgr.warn(cause) { "加载房间Mod展示信息失败，将使用基础Mod数据" }
            hydratedUiMods = visibleMods.toUiMods()
        } finally {
            modDetailsLoading = false
        }
    }
    LaunchedEffect(extraMods) {
        selectedExtraKeys = selectedExtraKeys.intersect(extraMods.map(::extraModKey).toSet())
    }
    val selectedExtraMods = extraMods.filter { extraModKey(it) in selectedExtraKeys }
    val uiModsByKey = hydratedUiMods.associateBy { it.key }
    val activeUiMods = baseVersionMods.mapNotNull { uiModsByKey[extraModKey(it)] }
    val disabledUiMods = disabledMods.mapNotNull { uiModsByKey[extraModKey(it)] }
    val extraUiMods = extraMods.mapNotNull { uiModsByKey[extraModKey(it)] }

    val activeArea: @Composable (Modifier) -> Unit = { areaModifier ->
        HostModArea(
            title = "启用Mod",
            count = baseVersionMods.size,
            modifier = areaModifier,
            highlighted = dragState?.dropTarget == HostModAreaType.Enabled,
            onBoundsChanged = { enabledAreaBounds = it },
            headerActions = {
                if (canManage) {
                    Text("拖到右侧可停用")
                }
            },
        ) {
            HostModListPane(
                canManage = canManage,
                baseVersionMods = baseVersionMods,
                uiMods = activeUiMods,
                draggedKey = dragState?.mod?.key,
                onDragEvent = { event -> handleDragEvent(HostModAreaType.Enabled, event) },
                iconOnly = true
            )
        }
    }
    val disabledArea: @Composable (Modifier) -> Unit = { areaModifier ->
        HostModArea(
            title = "停用",
            count = disabledMods.size,
            modifier = areaModifier,
            highlighted = dragState?.dropTarget == HostModAreaType.Disabled,
            onBoundsChanged = { disabledAreaBounds = it },
            headerActions = {
                if (canManage) {
                    Text("拖到左侧可启用")
                }
            },
        ) {
            HostDisabledModsPane(
                canManage = canManage,
                disabledMods = disabledMods,
                uiMods = disabledUiMods,
                draggedKey = dragState?.mod?.key,
                onDragEvent = { event -> handleDragEvent(HostModAreaType.Disabled, event) },
            )
        }
    }
    val extraArea: @Composable (Modifier) -> Unit = { areaModifier ->
        HostModArea(
            title = "附加Mod",
            count = extraMods.size,
            modifier = areaModifier,
            headerActions = {
                var addExtraModShiftPressed by remember { mutableStateOf(false) }
                HostModSelectionControls(
                    selectedCount = selectedExtraMods.size,
                    allSelected = extraMods.isNotEmpty() && selectedExtraKeys.size == extraMods.size,
                    onToggleAll = {
                        selectedExtraKeys = if (it) extraMods.map(::extraModKey).toSet() else emptySet()
                    },
                ) {
                    /*CircleIconButton(
                        icon = "\uF019",
                        label = "下载",
                        enabled = selectedExtraMods.isNotEmpty(),
                        bgColor = themeNow.primary,
                    ) {
                        onOpenTaskList(ClientTaskManager.submit(ModService.downloadModsTask2(selectedExtraMods)))
                    }*/
                    if (canManage) {
                        CircleIconButton(
                            icon = "\uEA81",
                            label = "删除",
                            showText = false,
                            enabled = selectedExtraMods.isNotEmpty(),
                            bgColor = MaterialTheme.colorScheme.error,
                        ) {
                            onRemoveExtraMods(selectedExtraMods)
                        }
                        Box(
                            modifier = Modifier.pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) {
                                        addExtraModShiftPressed = awaitPointerEvent().keyboardModifiers.isShiftPressed
                                    }
                                }
                            }
                        ) {
                            CircleIconButton(
                                icon = "\uF067",
                                label = if (addExtraModLoading) {
                                    addExtraModLoadingText.ifBlank { "匹配中..." }
                                } else {
                                    "附加Mod"
                                },
                                enabled = !addExtraModLoading,
                                bgColor = themeNow.secondary,
                            ) {
                                if (addExtraModShiftPressed) {
                                    onAddExtraModAdvanced()
                                } else {
                                    onOpenResourceMods()
                                }
                            }
                        }
                    }
                }
            },
        ) {
            HostExtraModsPane(
                extraUiMods = extraUiMods,
                selectedExtraModKeys = selectedExtraKeys,
                extraModsLoading = modDetailsLoading,
                onToggleSelected = { mod, selected ->
                    selectedExtraKeys = selectedExtraKeys.toggle(extraModKey(mod), selected)
                },
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootBounds = it.boundsInWindow() }
    ) {
        if (maxWidth >= 900.dp) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                activeArea(Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    disabledArea(Modifier.weight(1f).fillMaxWidth())
                    extraArea(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                activeArea(Modifier.weight(2f).fillMaxWidth())
                disabledArea(Modifier.weight(1f).fillMaxWidth())
                extraArea(Modifier.weight(1f).fillMaxWidth())
            }
        }

        dragState?.let { dragging ->
            val root = rootBounds ?: return@let
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (dragging.pointerInWindow.x - root.left + 12f).roundToInt(),
                            (dragging.pointerInWindow.y - root.top + 12f).roundToInt(),
                        )
                    }
                    .width(280.dp)
                    .zIndex(10f),
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    UiModIcon(dragging.mod, Modifier.size(44.dp))
                    Column {
                        Text(dragging.mod.primaryName, maxLines = 1)
                        Text(
                            when (dragging.dropTarget) {
                                HostModAreaType.Enabled -> "松开以启用"
                                HostModAreaType.Disabled -> "松开以停用"
                                null -> if (dragging.source == HostModAreaType.Enabled) {
                                    "拖到停用Mod区域.."
                                } else {
                                    "拖到启用Mod区域.."
                                }
                            },
                            color = themeNow.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

internal data class HostModDragState(
    val mod: UiMod,
    val source: HostModAreaType,
    val pointerInWindow: Offset,
    val dropTarget: HostModAreaType?,
)

internal data class HostModDragMutation(
    val mod: Mod,
    val disabled: Boolean,
)

internal data class HostModDragUpdate(
    val state: HostModDragState?,
    val mutation: HostModDragMutation? = null,
)

internal enum class HostModAreaType {
    Enabled,
    Disabled,
}

internal fun hostModDropTarget(
    source: HostModAreaType,
    pointerInWindow: Offset,
    enabledAreaBounds: Rect?,
    disabledAreaBounds: Rect?,
): HostModAreaType? = when {
    source != HostModAreaType.Enabled && enabledAreaBounds?.contains(pointerInWindow) == true -> HostModAreaType.Enabled
    source != HostModAreaType.Disabled && disabledAreaBounds?.contains(pointerInWindow) == true -> HostModAreaType.Disabled
    else -> null
}

internal fun hostModDragUpdate(
    dragState: HostModDragState?,
    source: HostModAreaType,
    event: ContentGridDragEvent,
    enabledAreaBounds: Rect?,
    disabledAreaBounds: Rect?,
): HostModDragUpdate = when (event) {
    // Host mod panes only hold mods; anything else is not draggable here.
    is ContentGridDragEvent.Start -> (event.item as? ContentGridItem.ModItem)?.let { item ->
        HostModDragUpdate(
            state = HostModDragState(
                mod = item.mod,
                source = source,
                pointerInWindow = event.pointerInWindow,
                dropTarget = hostModDropTarget(
                    source,
                    event.pointerInWindow,
                    enabledAreaBounds,
                    disabledAreaBounds,
                ),
            )
        )
    } ?: HostModDragUpdate(state = dragState)

    is ContentGridDragEvent.Move -> HostModDragUpdate(
        state = dragState?.let { dragging ->
            dragging.copy(
                pointerInWindow = event.pointerInWindow,
                dropTarget = hostModDropTarget(
                    dragging.source,
                    event.pointerInWindow,
                    enabledAreaBounds,
                    disabledAreaBounds,
                ),
            )
        }
    )

    is ContentGridDragEvent.End -> {
        if (dragState == null) {
            HostModDragUpdate(state = null)
        } else {
            val mutation = when (hostModDropTarget(
                dragState.source,
                event.pointerInWindow,
                enabledAreaBounds,
                disabledAreaBounds,
            )) {
                HostModAreaType.Enabled -> HostModDragMutation(dragState.mod.mod, disabled = false)
                HostModAreaType.Disabled -> HostModDragMutation(dragState.mod.mod, disabled = true)
                null -> null
            }
            HostModDragUpdate(state = null, mutation = mutation)
        }
    }

    ContentGridDragEvent.Cancel -> HostModDragUpdate(state = null)
}

@Composable
private fun HostModArea(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onBoundsChanged: ((Rect) -> Unit)? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val borderColor = if (highlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = modifier.then(
            if (onBoundsChanged == null) Modifier else Modifier.onGloballyPositioned {
                onBoundsChanged(it.boundsInWindow())
            }
        ),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, borderColor),
        tonalElevation = if (highlighted) 4.dp else 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("$title · $count", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    headerActions()
                }
            }
            Column(Modifier.fillMaxWidth().weight(1f)) {
                content()
            }
        }
    }
}

@Composable
private fun RowScope.HostModSelectionControls(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleAll: (Boolean) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Text("(${selectedCount}已选)", color = themeNow.onSurfaceVariant)
    //Checkbox(allSelected, onCheckedChange = onToggleAll)
    //Text("全选")
    actions()
}

private fun Set<String>.toggle(key: String, selected: Boolean): Set<String> =
    if (selected) this + key else this - key

private fun extraModKey(mod: Mod): String = mod.uiModKey

@Composable
internal fun HostConsolePane(
    hostId: ObjectId,
    state: ConsoleState,
    canManage: Boolean,
    onOk: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var showCommandDialog by remember(hostId) { mutableStateOf(false) }
    var command by remember(hostId) { mutableStateOf("") }
    var commandSending by remember(hostId) { mutableStateOf(false) }
    var commandResult by remember(hostId) { mutableStateOf<String?>(null) }
    var pendingAction by remember(hostId) { mutableStateOf<HostRuntimeAction?>(null) }

    fun sendAction(action: HostRuntimeAction) {
        scope.rdiRequestU(
            path = "host/$hostId/${action.path}",
            method = HttpMethod.Post,
            onOk = { onOk(action.successMessage) },
            onErr = { onError(it.message ?: action.errorMessage) }
        )
    }

    Box(modifier) {
        Console(state = state, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            CircleIconButton(
                "\uF04B",
                "启动",
                showText = false,
                bgColor = themeNow.primary
            ) { sendAction(HostRuntimeAction.Start) }
            if (canManage) {
                CircleIconButton(
                    "\uF120",
                    "发送命令",
                    showText = false,
                    bgColor = themeNow.tertiary
                ) {
                    commandResult = null
                    showCommandDialog = true
                }
                CircleIconButton("\uF01E", "重启", showText = false, bgColor = MaterialTheme.colorScheme.primary) {
                    pendingAction = HostRuntimeAction.Restart
                }
                CircleIconButton("\uF04D", "停止", showText = false, bgColor = MaterialTheme.colorScheme.error) {
                    pendingAction = HostRuntimeAction.Stop
                }
                CircleIconButton("\uF05E", "强制停止", showText = false, bgColor = MaterialTheme.colorScheme.error) {
                    pendingAction = HostRuntimeAction.ForceStop
                }
            }
        }
    }

    if (showCommandDialog) {
        val normalizedCommand = command.trim().removePrefix("/")
        AlertDialog(
            onDismissRequest = { if (!commandSending) showCommandDialog = false },
            title = { Text("发送服务器命令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("命令会以服务器控制台身份执行，不需要输入开头的/")
                    OutlinedTextField(
                        value = command,
                        onValueChange = {
                            command = it
                            commandResult = null
                        },
                        enabled = !commandSending,
                        singleLine = true,
                        label = { Text("命令") },
                        placeholder = { Text("say hello") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    commandResult?.let { result ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("命令返回：")
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFF1B1D1F), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(result, color = Color(0xFFE0E0E0), fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = normalizedCommand.isNotBlank() && !commandSending,
                    onClick = {
                        commandSending = true
                        commandResult = null
                        scope.rdiRequest<String>(
                            path = "host/$hostId/command",
                            method = HttpMethod.Post,
                            params = mapOf("command" to normalizedCommand),
                            onOk = {
                                commandResult = it.data?.ifBlank { "OK" } ?: "OK"
                                onOk("命令已执行")
                            },
                            onErr = { onError(it.message ?: "发送命令失败") },
                            onDone = { commandSending = false }
                        )
                    }
                ) {
                    Text(if (commandSending) "发送中..." else "发送")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !commandSending,
                    onClick = { showCommandDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    pendingAction?.let { action ->
        ConfirmDialog(
            title = action.confirmTitle,
            message = action.confirmMessage,
            onConfirm = {
                pendingAction = null
                sendAction(action)
            },
            onDismiss = { pendingAction = null }
        )
    }
}

private enum class HostRuntimeAction(
    val path: String,
    val confirmTitle: String,
    val confirmMessage: String,
    val successMessage: String,
    val errorMessage: String
) {
    Start("start", "", "", "启动指令已发送", "启动失败"),
    Restart("restart", "确认重启", "确定重启该房间吗？", "重启指令已发送", "重启失败"),
    Stop("stop", "确认停止", "确定停止该房间吗？", "停止指令已发送", "停止失败"),
    ForceStop("force-stop", "确认强制停止", "确定强制停止该房间吗？", "强制停止指令已发送", "强制停止失败")
}

@Composable
internal fun HostFilesPane(
    hostId: ObjectId,
    canManage: Boolean,
    onOpenTaskList: ((String) -> Unit)? = null
) {
    if (canManage) {
        HostFileExplorer(
            hostId = hostId,
            enableTaczUpload = true,
            onOpenTaskList = onOpenTaskList
        )
    } else {
        Text("仅房间管理员可查看文件", color = themeNow.onSurfaceVariant)
    }
}

@Composable
internal fun HostExtraModsPane(
    extraUiMods: List<UiMod>,
    selectedExtraModKeys: Set<String>,
    extraModsLoading: Boolean,
    onToggleSelected: (Mod, Boolean) -> Unit,
) {
    if (extraModsLoading && extraUiMods.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator()
                Text("正在载入附加Mod信息...", color = themeNow.onSurfaceVariant)
            }
        }
    } else if (extraUiMods.isEmpty()) {
        Text("当前没有附加Mod", color = themeNow.onSurfaceVariant)
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            if (extraModsLoading) {
                Text("正在补充附加Mod详细信息...", color = themeNow.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            ContentGrid(
                items = rememberModGridItems(extraUiMods),
                modifier = Modifier.fillMaxWidth().weight(1f),
                highlightedKeys = selectedExtraModKeys,
                emptyText = "当前没有附加Mod",
                onItemClick = { item ->
                    val selected = item.key in selectedExtraModKeys
                    onToggleSelected((item as ContentGridItem.ModItem).mod.mod, !selected)
                },
                showTypeFilters = false,
            )
        }
    }
}


@Composable
internal fun HostModListPane(
    canManage: Boolean,
    baseVersionMods: List<Mod>,
    uiMods: List<UiMod>,
    iconOnly: Boolean,
    draggedKey: String?,
    onDragEvent: (ContentGridDragEvent) -> Unit,
) {
    if (baseVersionMods.isEmpty()) {
        Text("没有Mod", color = themeNow.onSurfaceVariant)
    } else {
        ContentGrid(
            items = rememberModGridItems(uiMods),
            modifier = Modifier.fillMaxSize(),
            draggedKey = draggedKey,
            emptyText = "当前整合包版本没有可显示的Mod",
            onDragEvent = if (canManage) onDragEvent else null,
            initialIconOnly = iconOnly,
            showTypeFilters = false,
            topBar = true,
            searchPlaceholder = "搜索已启用的Mod",
        )
    }
}


@Composable
internal fun HostDisabledModsPane(
    canManage: Boolean,
    disabledMods: List<Mod>,
    uiMods: List<UiMod>,
    draggedKey: String?,
    onDragEvent: (ContentGridDragEvent) -> Unit,
) {
    if (disabledMods.isEmpty()) {
        Text("没有停用mod", color = themeNow.onSurfaceVariant)
    } else {
        ContentGrid(
            items = rememberModGridItems(uiMods),
            modifier = Modifier.fillMaxSize(),
            draggedKey = draggedKey,
            emptyText = "当前没有停用mod",
            onDragEvent = if (canManage) onDragEvent else null,
            showTypeFilters = false,
        )
    }
}
