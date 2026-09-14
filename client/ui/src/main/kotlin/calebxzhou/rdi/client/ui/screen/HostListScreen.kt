package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells.Adaptive
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.AlertErr
import calebxzau.rdi.client.ui.AlertOk
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.RVerticalScrollbar
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.TitleRow
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.service.rememberPlayerInfoPrefetch
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.comp.ModpackDownloadMethodDialog
import calebxzhou.rdi.client.ui.comp.UnifiedHostCard
import calebxzau.rdi.client.ui.viewmodel.HostListEvent
import calebxzau.rdi.client.ui.viewmodel.HostListViewModel
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import calebxzhou.rdi.common.model.isDav

@Composable
fun HostListScreen(
    onBack: () -> Unit,
    onOpenModpackPlaza: () -> Unit,
    onOpenHostInfo: (HostTarget, Boolean) -> Unit,
    onOpenHostMembers: (HostTarget, Boolean) -> Unit,
    onOpenHostMods: (HostTarget, Boolean) -> Unit,
    onOpenHostFiles: (HostTarget, Boolean) -> Unit,
    onOpenHostBackend: (HostTarget, Boolean) -> Unit,
    onOpenHostSettings: (HostTarget, Boolean) -> Unit,
    onOpenMcPlay: (McPlayArgs, Boolean) -> Unit,
    onOpenTaskList: (String) -> Unit,
    onOpenWorlds: () -> Unit,
    viewModel: HostListViewModel = koinViewModel(),
) {
    val screenFocusRequester = remember { FocusRequester() }
    var showCreateRoomGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        screenFocusRequester.requestFocus()
    }

    MaxBox {
        ScreenContentSurface(
            size = ScreenContentSize.LARGE,
            modifier = Modifier
                .focusRequester(screenFocusRequester)
                .focusable()
                .onKeyEvent { event ->
                    handleHomeNavigationKeyEvent(event.key, event.type, onOpenWorlds)
                },
        ) {
            TitleRow("房间", onBack) {
                CircleIconButton(
                    icon = "\uF067",
                    label = "创建房间的方法",
                    showText = false,
                    onClick = { showCreateRoomGuide = true },
                )
            }
            ContentBody {
                val onOpenHostInfo1 = { it: HostTarget -> onOpenHostInfo(it, false) }
                val onOpenHostMembers1 = { it: HostTarget -> onOpenHostMembers(it, false) }
                val onOpenHostMods1 = { it: HostTarget -> onOpenHostMods(it, false) }
                val onOpenHostFiles1 = { it: HostTarget -> onOpenHostFiles(it, false) }
                val onOpenHostBackend1 = { it: HostTarget -> onOpenHostBackend(it, false) }
                val onOpenHostSettings1 = { it: HostTarget -> onOpenHostSettings(it, false) }
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                var installConfirmTask by remember<MutableState<StartPlayResult.NeedInstall?>> { mutableStateOf(null) }
                var deleteHost by remember<MutableState<UnifiedHostBrief?>> { mutableStateOf(null) }
                var deleteWorld by remember { mutableStateOf(true) }
                var deleteName by remember { mutableStateOf("") }
                val gridState = rememberLazyGridState()
                LaunchedEffect(key1 = viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is HostListEvent.OpenMcPlay -> {
                                onOpenMcPlay(event.args, false)
                            }

                            is HostListEvent.NeedInstall -> installConfirmTask = event.result
                            is HostListEvent.OpenTaskList -> onOpenTaskList(event.runId)
                            is HostListEvent.HostDeleted -> {
                                if (deleteHost?.target == event.target) deleteHost = null
                            }
                        }
                    }
                }
                LaunchedEffect(gridState, state.hosts.size, state.initialLoading) {
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
                        if (index != null && index >= state.hosts.size - 4) viewModel.loadMore()
                    }
                }
                Column(Modifier.fillMaxSize().fillMaxSize()) {
                    if (state.initialLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (state.hosts.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "暂无可展示的房间，等待朋友邀请",
                                style = MaterialTheme.typography.titleLarge)
                        }
                    } else {
                        val shownHosts = remember(state.hosts, loggedAccount.isDav) {
                            applyDavPlayableOverride(state.hosts, loggedAccount.isDav)
                        }
                        rememberPlayerInfoPrefetch(shownHosts.flatMap { listOfNotNull(it.ownerId) + it.onlinePlayerIds })
                        val (playableHosts, nonPlayableHosts) = remember(shownHosts) { groupUnifiedHosts(shownHosts) }
                        val hostCard: @Composable (UnifiedHostBrief) -> Unit = { host ->
                            UnifiedHostCard(
                                host = host,
                                onClick = onOpenHostInfo1,
                                onPlay = viewModel::startHost,
                                onOpenMembers = onOpenHostMembers1,
                                onOpenMods = onOpenHostMods1,
                                onOpenFiles = onOpenHostFiles1,
                                onOpenBackend = onOpenHostBackend1,
                                onOpenSettings = onOpenHostSettings1,
                                onDelete = { deleteHost = it; deleteWorld = false; deleteName = "" },
                                playEnabled = state.launchingHost == null,
                                playLoading = state.launchingHost == host.target,
                            )
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyVerticalGrid(
                                state = gridState,
                                columns = Adaptive(minSize = 300.dp),
                                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (playableHosts.isNotEmpty()) {
                                    items(
                                        playableHosts,
                                        key = { "playable:${it.target.kind}:${it.target.id}" }) { host ->
                                        hostCard(host)
                                    }
                                } else {
                                    item(span = { GridItemSpan(maxLineSpan) }) { Text("暂无可游玩的房间") }
                                }
                                if (nonPlayableHosts.isNotEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            HorizontalDivider()
                                            Text("暂不可游玩的房间", style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                    items(
                                        nonPlayableHosts,
                                        key = { "unplayable:${it.target.kind}:${it.target.id}" }) { host ->
                                        hostCard(host)
                                    }
                                }
                                if (state.loadingMore) {
                                    item {
                                        Box(
                                            Modifier.fillMaxWidth().padding(16.dp),
                                            contentAlignment = Alignment.Center
                                        ) { CircularProgressIndicator() }
                                    }
                                }
                            }
                            RVerticalScrollbar(gridState = gridState, modifier = Modifier.align(Alignment.CenterEnd))
                        }
                    }
                }
                when {
                    state.errorMessage != null -> AlertErr(state.errorMessage!!, viewModel::clearError)
                    state.okMessage != null -> AlertOk(state.okMessage!!, viewModel::clearOkMessage)
                    installConfirmTask != null -> {
                        val install = installConfirmTask!!
                        ModpackDownloadMethodDialog(
                            packName = install.title,
                            packVer = "",
                            onDismiss = { installConfirmTask = null },
                            onDirectDownload = { includeClientExtras ->
                                installConfirmTask = null
                                onOpenTaskList(
                                    ClientTaskManager.submit(
                                        install.createTask(includeClientExtras),
                                        install.dedupeKey,
                                    )
                                )
                            },
                            onOpenTaskList = onOpenTaskList,
                            onImportError = viewModel::showError,
                        )
                    }

                    deleteHost != null -> {
                        val host = deleteHost!!
                        AlertDialog(
                            onDismissRequest = { deleteHost = null },
                            title = { Text("确认删除") },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("确认删除房间吗？所有的数据，包括存档都会丢失（不可恢复）")
                                    if (host.target.kind == HostKind.Legacy) {
                                        if (host.version >= 2) {
                                            Text("请输入房间名称确认。")
                                            OutlinedTextField(
                                                value = deleteName,
                                                onValueChange = { deleteName = it },
                                                label = { Text(host.name) },
                                                singleLine = true,
                                            )
                                        } else {
                                            Checkbox(checked = deleteWorld, onCheckedChange = { deleteWorld = it })
                                            Text("同时删除关联存档")
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                            enabled = state.deletingHost == null &&
                                            (host.target.kind != HostKind.Legacy ||
                                                    (host.version >= 2 && deleteName == host.name) ||
                                                    host.version < 2),
                                    onClick = { viewModel.deleteHost(host, deleteWorld) },
                                ) { Text("删除") }
                            },
                            dismissButton = { TextButton(onClick = { deleteHost = null }) { Text("取消") } },
                        )
                    }
                }
            }
        }
        if (showCreateRoomGuide) {
            AlertDialog(
                onDismissRequest = { showCreateRoomGuide = false },
                title = { Text("创建多人房间") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("1.前往整合广场")
                        Text("2.选择喜欢玩的包")
                        Text("3.在最新版本上点创建房间")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showCreateRoomGuide = false
                            onOpenModpackPlaza()
                        },
                    ) { Text("立刻前往") }
                },
            )
        }
    }
}

internal fun allHostSourcesEnded(
    legacyAvailableEnded: Boolean,
    legacyUnavailableEnded: Boolean,
): Boolean = legacyAvailableEnded && legacyUnavailableEnded

internal fun groupUnifiedHosts(hosts: List<UnifiedHostBrief>): Pair<List<UnifiedHostBrief>, List<UnifiedHostBrief>> =
    hosts.partition { it.playable }

internal fun applyDavPlayableOverride(
    hosts: List<UnifiedHostBrief>,
    isDav: Boolean,
): List<UnifiedHostBrief> = if (isDav) hosts.map { it.copy(playable = true) } else hosts

internal fun handleHomeNavigationKeyEvent(
    key: Key,
    type: KeyEventType,
    onNavigate: () -> Unit,
): Boolean {
    if (key != Key.MoveHome) return false
    if (type == KeyEventType.KeyUp) onNavigate()
    return true
}

/** Keeps the two server pages in one stable UI list without coercing either id type. */
internal fun mergeUnifiedHosts(
    current: List<UnifiedHostBrief>,
    incoming: List<UnifiedHostBrief>,
): List<UnifiedHostBrief> = (current + incoming)
    .distinctBy { it.target }
    .sortedWith(compareByDescending<UnifiedHostBrief> { it.target.id }.thenBy { it.target.kind })
