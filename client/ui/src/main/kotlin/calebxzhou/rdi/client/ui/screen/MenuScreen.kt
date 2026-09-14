package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.IconMarqueeCard
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.MarqueeIcon
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzau.rdi.client.RDIClient
import calebxzau.rdi.client.ui.FlowRowV
import calebxzau.rdi.client.ui.baseRoundCornerShape
import calebxzau.rdi.client.ui.loadImageBitmap
import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzhou.rdi.client.auth.updateLastPlayHost
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.lgr
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.HttpImageState
import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.service.content.isClientModMigrationInProgress
import calebxzhou.rdi.client.service.rememberPlayerInfoPrefetch
import calebxzhou.rdi.client.service.startHostPlay
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.comp.HeadButton
import calebxzhou.rdi.client.ui.comp.ModpackDownloadMethodDialog
import calebxzhou.rdi.client.ui.comp.PlayerModel
import calebxzhou.rdi.client.ui.comp.UnifiedHostCard
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.Modpack
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.bson.types.ObjectId

/**
 * calebxzhou @ 2026-02-25 17:51
 */

@Composable
fun MenuScreen(
    onOpenModpacks: () -> Unit,
    onOpenHostLobby: () -> Unit,
    onOpenMcPlay: (McPlayArgs) -> Unit,
    onOpenTaskList: (String) -> Unit
) {
    val lastPlayHost = remember { LocalCredentials.read().lastPlayHost }
    val scope = rememberCoroutineScope()
    var lastPlayHostBrief by remember { mutableStateOf<Host.BriefVo?>(null) }
    var menuModpackBriefs by remember { mutableStateOf<List<Modpack.ListSimpleVo>>(emptyList()) }
    var menuSkinHeads by remember { mutableStateOf<List<MarqueeIcon.SkinHead>>(emptyList()) }
    var launchingHostId by remember { mutableStateOf<String?>(null) }
    var installConfirmTask by remember { mutableStateOf<StartPlayResult.NeedInstall?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    var showOldMainWarning by remember { mutableStateOf(RDIClient.OLD_MAIN) }
    val sponsorBitmap = remember {
        loadImageBitmap("assets/sponsor.avif")
            .onFailure { lgr.warn(it) { "加载赞助图片失败" } }
            .getOrNull()
    }
    val menuActionInteractionSource = remember { MutableInteractionSource() }
    val menuActionsHovered by menuActionInteractionSource.collectIsHoveredAsState()
    val taskEntries by ClientTaskManager.entries.collectAsState()
    val modMigrationInProgress = isClientModMigrationInProgress(taskEntries)

    LaunchedEffect(lastPlayHost?.id) {
        lastPlayHostBrief = null
        val hostId = lastPlayHost?.id ?: return@LaunchedEffect
        runCatching {
            server.makeRequest<Host.BriefVo>("host/$hostId/brief").let { response ->
                if (!response.ok) throw RequestError(response.msg)
                response.data
            }
        }.onSuccess { host ->
            lastPlayHostBrief = host
        }.onFailure { error ->
            lgr.warn(error) { "加载最近游玩房间失败" }
        }
    }


    LaunchedEffect(Unit) {
        menuModpackBriefs = runCatching {
            loadMenuModpackBriefs()
        }.onFailure { error ->
            lgr.warn(error) { "加载整合包推荐失败" }
        }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) {
        runCatching {
            loadMenuSkinHeads { heads ->
                menuSkinHeads = heads
            }
        }.onFailure { error ->
            lgr.warn(error) { "加载多人房间皮肤失败" }
        }
    }

    fun startHost(host: Host.BriefVo) {
        if (launchingHostId != null) return
        val hostId = host._id.toHexString()
        launchingHostId = hostId
        scope.launch {
            startHostPlay(hostId)
                .onSuccess { result ->
                    when (result) {
                        is StartPlayResult.Ready -> {
                            LocalCredentials.read().updateLastPlayHost(hostId, host.name)
                            onOpenMcPlay(result.args)
                        }
                        is StartPlayResult.NeedMod -> {
                            playError = "房间缺少必要Mod：${result.modSlugs.joinToString("、")}。请先前往模组界面添加。"
                        }
                        is StartPlayResult.NeedInstall -> installConfirmTask = result
                        is StartPlayResult.Installing -> {
                            playError = "整合包正在下载，请等待下载完成后再启动"
                            onOpenTaskList(result.runId)
                        }
                    }
                }
                .onFailure { playError = it.message ?: "无法开始游玩" }
            launchingHostId = null
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

    MaxBox {
        /* PlayerPreviewCard(
             modifier = Modifier
                 .align(Alignment.BottomStart)
                 .padding(start = 24.dp, bottom = 24.dp)
                 .size(320.dp),
             onClick = onOpenWardrobe
         )*/
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(1f / 3f),
            contentAlignment = Alignment.CenterStart
        ) {
            MenuActionButtons(
                modifier = Modifier
                    .widthIn(min = 220.dp)
                    .alpha(
                        if (modMigrationInProgress) 0.38f
                        else if (menuActionsHovered) 1f else 0.6f
                    )
                    .hoverable(menuActionInteractionSource),
                enabled = !modMigrationInProgress,
                modpackBriefs = menuModpackBriefs,
                skinHeads = menuSkinHeads,
                onOpenModpacks = onOpenModpacks,
                onOpenHostLobby = onOpenHostLobby,
                lastPlayHostBrief = lastPlayHostBrief,
                launchingHostId = launchingHostId,
                onStartHost = ::startHost
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (menuActionsHovered) {
                sponsorBitmap?.let { bitmap ->
                    Text(
                        "支持rdi继续走下去",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .size(112.dp)
                            .alpha(0.85f)
                            .clip(baseRoundCornerShape),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }

    playError?.let { error ->
        AlertDialog(
            onDismissRequest = { playError = null },
            title = { Text("无法开始游玩") },
            text = { Text(error) },
            confirmButton = {
                CircleIconButton(
                    icon = "\uF00C",
                    label = "知道了"
                ) {
                    playError = null
                }
            }
        )
    }

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
            onImportError = { playError = it }
        )
    }
}

private suspend fun loadMenuModpackBriefs(): List<Modpack.ListSimpleVo> {
    val response = server.makeRequest<List<Modpack.ListSimpleVo>>(
        path = "modpack/list-simple",
        params = mapOf("hasIconOnly" to true)
    )
    if (!response.ok) throw RequestError(response.msg)
    return response.data.orEmpty()
}

private suspend fun loadMenuSkinHeads(
    onHeadsChanged: (List<MarqueeIcon.SkinHead>) -> Unit
) {
    val response = server.makeRequest<List<String>>("player/unique-skin-list")
    if (!response.ok) throw RequestError(response.msg)

    val skinUrls = response.data.orEmpty()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
    var heads = skinUrls.map {
        MarqueeIcon.SkinHead(
            image = null,
            contentDescription = "玩家皮肤"
        )
    }
    onHeadsChanged(heads)

    coroutineScope {
        skinUrls.forEachIndexed { index, skinUrl ->
            launch {
                val image = HttpImageState.fetch(skinUrl).bitmap
                    ?.takeIf { it.width >= 64 && it.height >= 32 }
                heads = heads.mapIndexed { currentIndex, head ->
                    if (currentIndex == index) head.copy(image = image) else head
                }
                onHeadsChanged(heads)
            }
        }
    }
}


@Composable
private fun MenuActionButtons(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    modpackBriefs: List<Modpack.ListSimpleVo>,
    skinHeads: List<MarqueeIcon.SkinHead>,
    onOpenModpacks: () -> Unit,
    onOpenHostLobby: () -> Unit,
    lastPlayHostBrief: Host.BriefVo?,
    launchingHostId: String?,
    onStartHost: (Host.BriefVo) -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        val marqueeIcons = remember(modpackBriefs) {
            modpackBriefs.mapNotNull { brief ->
                brief.iconUrl
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                    ?.let { iconUrl ->
                        MarqueeIcon.Url(
                            url = iconUrl,
                            contentDescription = brief.name
                        )
                    }
            }
        }
        ModpackMenuActionCard(
            enabled = enabled,
            icons = marqueeIcons,
            onClick = onOpenModpacks,
        )
        HostLobbyMenuActionCard(
            enabled = enabled,
            icons = skinHeads,
            onClick = onOpenHostLobby,
        )
        lastPlayHostBrief?.let { host ->
            RecentHostMenuActionCard(
                enabled = enabled,
                host = host,
                launchingHostId = launchingHostId,
                onStartHost = onStartHost,
            )
        }
    }
}

@Composable
private fun ModpackMenuActionCard(
    enabled: Boolean,
    icons: List<MarqueeIcon>,
    onClick: () -> Unit,
) {
    MenuActionCardTooltip(enabled) {
        IconMarqueeCard(
            title = "整合包",
            subtitle = "管理和安装你的整合包",
            icons = icons,
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surfaceVariant
                )
            ),
            modifier = Modifier
                .height(80.dp)
                .width(320.dp)
                .clip(baseRoundCornerShape)
                .clickable(enabled = enabled, onClick = onClick)
        )
    }
}

@Composable
private fun HostLobbyMenuActionCard(
    enabled: Boolean,
    icons: List<MarqueeIcon>,
    onClick: () -> Unit,
) {
    MenuActionCardTooltip(enabled) {
        IconMarqueeCard(
            title = "多人房间",
            subtitle = "选择创建房间跟大家一起玩",
            icons = icons,
            Brush.linearGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surfaceVariant
                )
            ),
            modifier = Modifier
                .height(80.dp)
                .width(320.dp)
                .clip(baseRoundCornerShape)
                .clickable(enabled = enabled, onClick = onClick)
        )
    }
}

@Composable
private fun RecentHostMenuActionCard(
    enabled: Boolean,
    host: Host.BriefVo,
    launchingHostId: String?,
    onStartHost: (Host.BriefVo) -> Unit,
) {
    MenuActionCardTooltip(enabled) {
        UnifiedHostCard(
            host = UnifiedHostBrief.fromLegacy(host).copy(name = "继续游玩·${host.name}"),
            modifier = Modifier.width(320.dp),
            onDirectClick = { onStartHost(host) },
            onClick = { onStartHost(host) },
            playEnabled = enabled && launchingHostId == null,
            playLoading = launchingHostId == host._id.toHexString(),
        )
    }
}

@Composable
private fun MenuActionCardTooltip(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        content()
    } else {
        SimpleTooltip("正在迁移mod文件") {
            content()
        }
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
