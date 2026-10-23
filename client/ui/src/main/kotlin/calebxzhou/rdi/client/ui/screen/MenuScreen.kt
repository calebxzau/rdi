package calebxzhou.rdi.client.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import calebxzau.rdi.client.RDIClient
import calebxzau.rdi.client.ui.AlertErr
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.EdgeAutoScrollBox
import calebxzau.rdi.client.ui.GlassIconButton
import calebxzau.rdi.client.ui.GlassNavBar
import calebxzau.rdi.client.ui.GlassNavItem
import calebxzau.rdi.client.ui.GlassTileGrid
import calebxzau.rdi.client.ui.GlassTileGridState
import calebxzau.rdi.client.ui.LocalAppBackgroundHazeState
import calebxzau.rdi.client.ui.baseRoundCornerShape
import calebxzau.rdi.client.ui.loadImageBitmap
import calebxzau.rdi.client.ui.rememberGlassTileGridState
import calebxzau.rdi.client.ui.viewmodel.HostListEvent
import calebxzau.rdi.client.ui.viewmodel.HostListViewModel
import calebxzau.rdi.client.ui.viewmodel.OnlinePlayersViewModel
import calebxzau.rdi.client.ui.comp.OnlinePlayerHeadsRow
import calebxzhou.rdi.client.net.lgr
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.service.content.isClientModMigrationInProgress
import calebxzhou.rdi.client.service.rememberPlayerInfoPrefetch
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.comp.CardHintHost
import calebxzhou.rdi.client.ui.comp.ModpackDownloadMethodDialog
import calebxzhou.rdi.client.ui.comp.PlayerModel
import calebxzhou.rdi.client.ui.comp.TILE_GRID_SPACING
import calebxzhou.rdi.client.ui.comp.TileGlass
import calebxzhou.rdi.client.ui.comp.TileGridGlassStyle
import calebxzhou.rdi.client.ui.comp.UnifiedHostCard
import calebxzhou.rdi.client.ui.comp.iconTileSize
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.common.DEBUG
import calebxzau.rdi.client.ui.comp.DebugQuestPreviewDialog
import org.koin.compose.viewmodel.koinViewModel
import java.nio.file.Path

/**
 * calebxzhou @ 2026-02-25 17:51
 */

private val MenuNavItems = listOf(
    GlassNavItem("\uF0C0", "多人房间"),
    GlassNavItem("\uF1B2", "整合包"),
)

private const val MENU_TILE_ROWS = 3
private val MenuTileSize = iconTileSize(126.dp)
private val MenuGridPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)

/** 与[GlassTileGrid]自身高度一致，加载中、空列表时占位不跳动。 */
private val MenuGridHeight = MenuTileSize.height * MENU_TILE_ROWS + TILE_GRID_SPACING * (MENU_TILE_ROWS - 1) +
    MenuGridPadding.calculateTopPadding() + MenuGridPadding.calculateBottomPadding()

/** 切换标签时卡片区的平移与淡入淡出。 */
private val TabSlideSpec = tween<IntOffset>(durationMillis = 350, easing = FastOutSlowInEasing)
private val TabFadeSpec = tween<Float>(durationMillis = 350, easing = FastOutSlowInEasing)

/** 滚到离末尾不足这么多张瓷砖时加载更多房间。 */
private const val HOST_LOAD_MORE_THRESHOLD = MENU_TILE_ROWS * 2

/**
 * 主菜单：顶部导航在“多人房间”和“整合包”之间切换，中间是3行横向滚动的玻璃瓷砖网格，
 * 两侧箭头翻页；底部是创建房间与整合广场入口。
 */
@Composable
fun MenuScreen(
    selectedTab: MenuTab,
    onSelectTab: (MenuTab) -> Unit,
    onOpenMcPlay: (McPlayArgs) -> Unit,
    onOpenTaskList: (String) -> Unit,
    onOpenHostInfo: (HostTarget) -> Unit,
    onOpenModpackPlaza: () -> Unit,
    onOpenLocalModpackInstall: () -> Unit,
    onOpenModpackInfo: (ModpackLocalDir) -> Unit,
    onOpenModpackContent: (ModpackLocalDir, ModpackContentType) -> Unit,
    onOpenModpackOptions: (ModpackLocalDir) -> Unit,
    onOpenModpackQuests: (ModpackLocalDir) -> Unit,
    onOpenDebugQuestPreview: (Path) -> Unit,
    hostListViewModel: HostListViewModel = koinViewModel(),
    onlinePlayersViewModel: OnlinePlayersViewModel = koinViewModel(),
) {
    var showOldMainWarning by remember { mutableStateOf(RDIClient.OLD_MAIN) }
    var showCreateRoomGuide by remember { mutableStateOf(false) }
    var installConfirmTask by remember { mutableStateOf<StartPlayResult.NeedInstall?>(null) }
    var showDebugQuestPreview by remember { mutableStateOf(false) }
    val menuFocusRequester = remember { FocusRequester() }
    val sponsorBitmap = remember {
        loadImageBitmap("assets/sponsor.avif")
            .onFailure { lgr.warn(it) { "加载赞助图片失败" } }
            .getOrNull()
    }
    val gridHoverSource = remember { MutableInteractionSource() }
    val gridHovered by gridHoverSource.collectIsHoveredAsState()
    val taskEntries by ClientTaskManager.entries.collectAsState()
    val modMigrationInProgress = isClientModMigrationInProgress(taskEntries)
    val hostState by hostListViewModel.uiState.collectAsStateWithLifecycle()
    val onlinePlayerIds by onlinePlayersViewModel.onlinePlayerIds.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    // 只在主菜单可见时轮询在线玩家
    LaunchedEffect(lifecycleOwner, onlinePlayersViewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            onlinePlayersViewModel.pollOnlinePlayers()
        }
    }
    val modpacks = rememberLocalModpackManager(onOpenTaskList)
    val hostGrid = rememberGlassTileGridState()
    val modpackGrid = rememberGlassTileGridState()
    val activeGrid = if (selectedTab == MenuTab.Hosts) hostGrid else modpackGrid

    LaunchedEffect(showDebugQuestPreview) {
        if (DEBUG && !showDebugQuestPreview) menuFocusRequester.requestFocus()
    }

    LaunchedEffect(hostListViewModel) {
        hostListViewModel.events.collect { event ->
            when (event) {
                is HostListEvent.OpenMcPlay -> onOpenMcPlay(event.args)
                is HostListEvent.NeedInstall -> installConfirmTask = event.result
                is HostListEvent.OpenTaskList -> onOpenTaskList(event.runId)
            }
        }
    }

    // 可玩的房间在前，暂不可玩的默认隐藏，显示时排在后面并变暗
    val shownHosts = remember(hostState.hosts, hostState.showOtherHosts, loggedAccount.isDav) {
        val (playable, nonPlayable) = groupUnifiedHosts(applyDavPlayableOverride(hostState.hosts, loggedAccount.isDav))
        if (hostState.showOtherHosts) playable + nonPlayable else playable
    }
    rememberPlayerInfoPrefetch(shownHosts.flatMap { listOfNotNull(it.ownerId) + it.onlinePlayerIds })
    LaunchedEffect(hostGrid, shownHosts.size) {
        snapshotFlow { hostGrid.lazyGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { index ->
            if (index != null && index >= shownHosts.size - HOST_LOAD_MORE_THRESHOLD) hostListViewModel.loadMore()
        }
    }

    if (showOldMainWarning) {
        AlertDialog(
            onDismissRequest = { showOldMainWarning = false },
            title = { Text("旧版入口即将停止支持") },
            text = {
                Text(
                    "你正在使用旧版入口启动rdi\n" +
                        "此入口将在2026.8.10停止支持\n" +
                        "请打开rdi安装文件夹，从\"start.exe\"启动rdi 然后删掉桌面图标重新创建"
                )
            },
            confirmButton = {
                CircleIconButton(
                    icon = "\uF00C",
                    label = "知道了"
                ) {
                    showOldMainWarning = false
                }
            }
        )
    }

    // Only the menu content owns this shortcut. Dialog text fields use their own focus tree.
    val debugShortcut = if (DEBUG) {
        Modifier
            .focusRequester(menuFocusRequester)
            .onPreviewKeyEvent { event ->
                val space = event.key == Key.Spacebar && !event.isAltPressed && !event.isCtrlPressed &&
                    !event.isMetaPressed && !event.isShiftPressed
                if (DEBUG && space && !showDebugQuestPreview) {
                    // Open on release so the opening key cannot insert a space into the path field.
                    if (event.type == KeyEventType.KeyUp) showDebugQuestPreview = true
                    true
                } else false
            }
            .focusable()
    } else Modifier

    CardHintHost(Modifier.fillMaxSize().then(debugShortcut)) {
        /* PlayerPreviewCard(
             modifier = Modifier
                 .align(Alignment.BottomStart)
                 .padding(start = 24.dp, bottom = 24.dp)
                 .size(320.dp),
             onClick = onOpenWardrobe
         )*/
        EdgeAutoScrollBox(
            scrollState = activeGrid.lazyGridState,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(MenuGridHeight),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hoverable(gridHoverSource),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().alpha(if (modMigrationInProgress) 0.38f else 1f),
                    contentAlignment = Alignment.Center,
                ) {
                    // 切换标签时卡片区整体平移：往右边的标签切换时新内容从右侧滑入，反之从左侧
                    AnimatedContent(
                        targetState = selectedTab,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally(TabSlideSpec) { width -> width * direction } + fadeIn(TabFadeSpec)) togetherWith
                                (slideOutHorizontally(TabSlideSpec) { width -> -width * direction } + fadeOut(TabFadeSpec))
                        },
                        contentAlignment = Alignment.Center,
                        label = "menuTabContent",
                    ) { tab ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (tab == MenuTab.Hosts) {
                                when {
                                    hostState.initialLoading -> CircularProgressIndicator()
                                    shownHosts.isEmpty() && !hostState.showOtherHosts -> MenuEmptyText("暂无可游玩的房间，可点击显示其他房间")
                                    shownHosts.isEmpty() -> MenuEmptyText("暂无可展示的房间，等待朋友邀请")
                                    else -> MenuTileGrid(items = shownHosts, grid = hostGrid, key = { "${it.target.kind}:${it.target.id}" }) { index, host ->
                                        UnifiedHostCard(
                                            host = host,
                                            onPlay = hostListViewModel::startHost,
                                            onOpenDetail = onOpenHostInfo,
                                            playEnabled = hostState.launchingHost == null,
                                            playLoading = hostState.launchingHost == host.target,
                                            glass = TileGlass.Shared(hostGrid, index),
                                        )
                                    }
                                }
                                if (hostState.loadingMore) {
                                    CircularProgressIndicator(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).size(24.dp))
                                }
                            } else {
                                when {
                                    modpacks.legacyLoading -> CircularProgressIndicator()
                                    modpacks.legacyPacks.isEmpty() -> MenuEmptyText("还没有整合包，去整合广场看看吧")
                                    else -> MenuTileGrid(items = modpacks.legacyPacks, grid = modpackGrid, key = { it.versionId }) { index, pack ->
                                        modpacks.InstalledTile(
                                            pack = pack,
                                            glass = TileGlass.Shared(modpackGrid, index),
                                            onOpenPlay = onOpenMcPlay,
                                            onOpenModpackInfo = onOpenModpackInfo,
                                            onOpenContent = onOpenModpackContent,
                                            onOpenOptions = onOpenModpackOptions,
                                            onOpenQuests = onOpenModpackQuests,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (modMigrationInProgress) {
                    MigrationBlocker(Modifier.matchParentSize())
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassNavBar(
                hazeState = LocalAppBackgroundHazeState.current,
                items = MenuNavItems,
                selectedIndex = selectedTab.ordinal,
                onSelect = { onSelectTab(MenuTab.entries[it]) },
            )
            OnlinePlayerHeadsRow(onlinePlayerIds)
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            modpacks.packActionMessage?.let { message ->
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (selectedTab == MenuTab.Modpacks) {
                    GlassIconButton(icon = "\uDB86\uDDD5", label = "整合广场", onClick = onOpenModpackPlaza)
                    GlassIconButton(icon = "\uEAEF", label = "整合包本地安装", onClick = onOpenLocalModpackInstall)
                    GlassIconButton(
                        icon = "\uF019",
                        label = "导入rdipack2",
                        enabled = !modpacks.importPreparing,
                        onClick = modpacks::importRdiPack2,
                    )
                }else{
                    GlassIconButton(icon = "\uF067", label = "创建房间") { showCreateRoomGuide = true }
                    GlassIconButton(
                        icon = if (hostState.showOtherHosts) "\uF070" else "\uF06E",
                        label = if (hostState.showOtherHosts) "隐藏其他房间" else "显示其他房间",
                        onClick = hostListViewModel::toggleOtherHosts,
                    )

                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (gridHovered) {
                sponsorBitmap?.let { bitmap ->
                    /*Text(
                        "支持rdi继续走下去",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )*/
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(96.dp)
                            .alpha(0.85f)
                            .clip(baseRoundCornerShape),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    if (DEBUG && showDebugQuestPreview) {
        DebugQuestPreviewDialog(
            onDismiss = { showDebugQuestPreview = false },
            onOpen = { directory ->
                showDebugQuestPreview = false
                onOpenDebugQuestPreview(directory)
            },
        )
    }

    hostState.errorMessage?.let { AlertErr(it, hostListViewModel::clearError) }

    installConfirmTask?.let { install ->
        ModpackDownloadMethodDialog(
            packName = install.title,
            packVer = "",
            onDismiss = { installConfirmTask = null },
            onDirectDownload = { includeClientExtras ->
                installConfirmTask = null
                val runId = ClientTaskManager.submit(
                    install.createTask(includeClientExtras),
                    dedupeKey = install.dedupeKey,
                )
                onOpenTaskList(runId)
            },
            onOpenTaskList = onOpenTaskList,
            onImportError = hostListViewModel::showError,
        )
    }

    modpacks.Dialogs()

    if (showCreateRoomGuide) {
        CreateRoomGuideDialog(
            onDismiss = { showCreateRoomGuide = false },
            onOpenModpackPlaza = onOpenModpackPlaza,
        )
    }
}

@Composable
private fun <T> MenuTileGrid(
    items: List<T>,
    grid: GlassTileGridState,
    key: (T) -> Any,
    tile: @Composable (index: Int, item: T) -> Unit,
) {
    GlassTileGrid(
        items = items,
        state = grid,
        hazeState = LocalAppBackgroundHazeState.current,
        tileSize = MenuTileSize,
        modifier = Modifier.fillMaxWidth(),
        rows = MENU_TILE_ROWS,
        spacing = TILE_GRID_SPACING,
        contentPadding = MenuGridPadding,
        style = TileGridGlassStyle,
        key = key,
        tile = tile,
    )
}

@Composable
private fun MenuEmptyText(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
    )
}

/** 迁移mod文件期间盖住瓷砖网格，拦下所有点击。 */
@Composable
private fun MigrationBlocker(modifier: Modifier) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        MenuEmptyText("正在迁移mod文件，请稍候")
    }
}

@Composable
private fun PlayerPreviewCard(
    modifier: Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        PlayerModel(
            skinUrl = loggedAccount.cloth.skin,
            capeUrl = loggedAccount.cloth.cape,
            modifier = Modifier.fillMaxSize(),
            backgroundColor = Color.Transparent,
            autoRotate = true,
            animateWalk = true,
            showOuterLayer = true,
            isSlim = loggedAccount.cloth.isSlim,
            maxRenderSide = 480,
            noControl = true
        )
    }
}
