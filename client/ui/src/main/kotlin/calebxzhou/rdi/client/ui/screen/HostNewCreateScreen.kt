package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ConfirmDialog
import calebxzau.rdi.client.ui.FlowRowV
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.ScrollableContentBody
import calebxzau.rdi.client.ui.RowV
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzau.rdi.client.ui.Space8h
import calebxzau.rdi.client.ui.Space8w
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.AlertErr
import calebxzau.rdi.client.ui.AlertOk
import calebxzau.rdi.client.ui.RRow
import calebxzau.rdi.client.ui.themeNow
import calebxzau.rdi.client.ui.viewmodel.HostCreateEvent
import calebxzau.rdi.client.ui.viewmodel.HostCreateViewModel
import calebxzau.rdi.client.ui.viewmodel.HostCreateViewModelArgs
import calebxzau.rdi.client.ui.viewmodel.HostCreateWorldSource
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.client.ui.*
import calebxzhou.rdi.client.ui.comp.GameRuleModal
import calebxzhou.rdi.client.ui.comp.ImageCard
import calebxzhou.rdi.client.ui.comp.HeadButton
import calebxzhou.rdi.client.ui.comp.BaseWorldSelectionModal
import kotlinx.coroutines.flow.collect
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * calebxzhou @ 2026-02-28 22:46
 */

@Composable
fun HostNewCreateScreen(
    arg: HostCreateRoute,
    onBack: () -> Unit,
) {
    val defaultHostName = remember(arg.hostId, arg.displayName) {
        val packPrefix = arg.displayName?.trim().orEmpty().take(4)
        if (packPrefix.isNotEmpty()) "${loggedAccount.name}的${packPrefix}"
        else "${loggedAccount.name}的世界"
    }
    val routeKey = arg.hostId?.trim()?.ifBlank { "create" }
        ?: "create:${arg.kind}:${arg.sourceId}:${arg.legacyVersionName}:${arg.legacyMcVersion}"
    val viewModel = koinViewModel<HostCreateViewModel>(key = "host-create:$routeKey") {
        parametersOf(
            HostCreateViewModelArgs(
                hostId = arg.hostId,
                defaultHostName = defaultHostName,
                kind = HostKind.fromRouteValue(arg.kind),
                sourceId = arg.sourceId,
                legacyVersionName = arg.legacyVersionName,
                displayName = arg.displayName,
                legacyMcVersion = arg.legacyMcVersion,
            )
        )
    }
    val account by AccountSessionStore.account.collectAsStateWithLifecycle()
    val serverRoute by RServer.routeState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsState()
    var showRules by remember { mutableStateOf(false) }
    var returnSuccessMessage by remember { mutableStateOf<String?>(null) }
    var staySuccessMessage by remember { mutableStateOf<String?>(null) }
    var showUpdateConfirm by remember { mutableStateOf(false) }
    var showCustomLevelTypeDialog by remember { mutableStateOf(false) }
    var showBaseWorldSelection by remember { mutableStateOf(false) }
    LaunchedEffect(account._id, serverRoute) {
        showBaseWorldSelection = false
        viewModel.updateSessionIdentity("${account._id}:${RServer.now.hqUrl}", account._id)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel, state.worldSource, state.isLegacyCreate, showBaseWorldSelection) {
        if (!state.isLegacyCreate || (!showBaseWorldSelection && state.worldSource != HostCreateWorldSource.Template)) {
            onDispose { }
        } else {
            var hasResumed = false
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (hasResumed) viewModel.refreshBaseWorlds()
                    hasResumed = true
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HostCreateEvent.LegacyCreateSubmitted -> {
                    returnSuccessMessage = event.message
                }
                is HostCreateEvent.EditSaved -> {
                    returnSuccessMessage = event.message
                }
                is HostCreateEvent.HostPackUpdateSubmitted -> staySuccessMessage = event.message
            }
        }
    }

    LaunchedEffect(showBaseWorldSelection) {
        if (showBaseWorldSelection) viewModel.openBaseWorldSelection()
        else viewModel.cancelBaseWorldSelection()
    }

    if (showCustomLevelTypeDialog) {
        AlertDialog(
            onDismissRequest = { showCustomLevelTypeDialog = false },
            title = { Text("自定义地形") },
            text = {
                OutlinedTextField(
                    value = state.customLevelTypeText,
                    onValueChange = viewModel::updateCustomLevelTypeText,
                    singleLine = true,
                    isError = state.customLevelTypeError != null,
                    label = { Text("level type") },
                    supportingText = { state.customLevelTypeError?.let { Text(it) } }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.applyCustomLevelType()) {
                            showCustomLevelTypeDialog = false
                        }
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.cancelCustomLevelType()
                        showCustomLevelTypeDialog = false
                    }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (state.friendDialogOpen && state.isLegacyCreate) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFriendDialog,
            title = { Text("添加好友") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.friendQq,
                        onValueChange = viewModel::updateFriendQq,
                        label = { Text("好友的QQ号") },
                        singleLine = true,
                        enabled = !state.friendLookupLoading,
                        isError = state.friendLookupError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.friendLookupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::addFriend,
                    enabled = !state.friendLookupLoading && state.friendQq.isNotBlank(),
                ) {
                    Text(if (state.friendLookupLoading) "添加中…" else "添加")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFriendDialog) { Text("取消") }
            },
        )
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.MEDIUM) {
            TitleRow(state.title, onBack) {
                if (state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (state.editHost != null) {
                    CircleIconButton(
                        icon = "\uDB80\uDFD5",
                        label = "更新整合包",
                        showText = true,
                    ) {
                        showUpdateConfirm = true
                    }
                }
                CircleIconButton("\uDB82\uDE50", "提交", bgColor = themeNow.primary, enabled = state.canSubmit) {
                    viewModel.submit()
                }
            }
            ScrollableContentBody {
                Text("${state.selectedPackTitle} v${state.selectedVersionName}")
                Space8h()

            /*if (false) { // Host2 creation is disabled for this release.
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text("整合包：${state.selectedPackTitle}", fontWeight = FontWeight.Bold)
                            if (state.isLegacyCreate && state.selectedVersionName.isNotBlank()) {
                                Text("版本：${state.selectedVersionName}", color = themeNow.onSurfaceVariant)
                            }

                            OutlinedTextField(
                                label = { Text("房间名称") },
                                value = state.hostName,
                                onValueChange = viewModel::updateHostName,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                label = { Text("简介") },
                                value = state.intro,
                                onValueChange = viewModel::updateIntro,
                                minLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            RowV {
                                Checkbox(state.whitelist, viewModel::updateWhitelist)
                                SimpleTooltip("仅限受邀玩家游玩") {
                                    Text("不允许陌生人游玩此房间")
                                }
                            }
                        }
                    } else */

                        Column(modifier = Modifier.fillMaxWidth()) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val compactTopLayout = maxWidth < 1280.dp
                            val basicSettingSectionModifier = if (compactTopLayout) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.widthIn(min = 320.dp, max = 460.dp)
                            }
                            val gameplaySettingSectionModifier = if (compactTopLayout) {
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.widthIn(min = 520.dp, max = 980.dp)
                            }
                            FlowRow(
                                modifier = basicSettingSectionModifier,
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                maxItemsInEachRow = if (compactTopLayout) 1 else 3
                            ) {
                                Column(
                                    modifier = basicSettingSectionModifier,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FlowRowV(
                                        modifier = basicSettingSectionModifier,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedTextField(
                                            label = { Text("房间名称") },
                                            value = state.hostName,
                                            onValueChange = viewModel::updateHostName,
                                            singleLine = true
                                        )
                                        RowV {

                                            Checkbox(state.whitelist, viewModel::updateWhitelist)
                                            SimpleTooltip("仅限受邀玩家游玩") {
                                                Text("不允许陌生人游玩此房间")
                                            }
                                        }
                                        RowV {

                                            Checkbox(state.allowCheats, viewModel::updateAllowCheats)
                                            Text("允许作弊")
                                        }
                                        Space8w()
                                        Button(
                                            onClick = { showRules = true },
                                            enabled = !state.loading,
                                        ) {
                                            Text("修改游戏规则(${state.gameRules.size})")
                                        }


                                    }
                                }

                                Row(
                                    modifier = gameplaySettingSectionModifier,
                                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text("难度", fontWeight = FontWeight.Bold)
                                            Space8h()
                                            Row(
                                                modifier = Modifier
                                                    .horizontalScroll(rememberScrollState())
                                                    .padding(horizontal = 2.dp),
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                ImageCard(
                                                    title = "和平",
                                                    iconPath = "assets/icons/difficulty_peaceful.avif",
                                                    selected = state.difficulty == 0,
                                                    onClick = { viewModel.updateDifficulty(0) }
                                                )
                                                ImageCard(
                                                    title = "简单",
                                                    iconPath = "assets/icons/difficulty_easy.avif",
                                                    selected = state.difficulty == 1,
                                                    onClick = { viewModel.updateDifficulty(1) }
                                                )
                                                ImageCard(
                                                    title = "普通",
                                                    iconPath = "assets/icons/difficulty_normal.avif",
                                                    selected = state.difficulty == 2,
                                                    onClick = { viewModel.updateDifficulty(2) }
                                                )
                                                ImageCard(
                                                    title = "困难",
                                                    iconPath = "assets/icons/difficulty_hard.avif",
                                                    selected = state.difficulty == 3,
                                                    onClick = { viewModel.updateDifficulty(3) }
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text("模式", fontWeight = FontWeight.Bold)
                                            Space8h()
                                            Row(
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                ImageCard(
                                                    title = "生存",
                                                    iconPath = "assets/icons/gamemode_survival.avif",
                                                    selected = state.gameMode == 0,
                                                    onClick = { viewModel.updateGameMode(0) }
                                                )
                                                ImageCard(
                                                    title = "创造",
                                                    iconPath = "assets/icons/gamemode_creative.avif",
                                                    selected = state.gameMode == 1,
                                                    onClick = { viewModel.updateGameMode(1) }
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text("地形", fontWeight = FontWeight.Bold)
                                            if (state.configuredBaseWorld?.required == true) {
                                                Text(
                                                    "此版本强制使用指定的地图模板",
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                                state.baseWorldBindingErrorMessage?.let { message ->
                                                    Text(message, color = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                            Space8h()
                                            Row(
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                ImageCard(
                                                    title = "普通",
                                                    iconPath = "assets/icons/worldtype_normal.avif",
                                                    selected = state.worldSource == HostCreateWorldSource.Generate && state.levelChoice == 0,
                                                    onClick = { viewModel.selectLevelChoice(0) },
                                                    enabled = state.configuredBaseWorld?.required != true,
                                                )
                                                ImageCard(
                                                    title = "超平坦",
                                                    iconPath = "assets/icons/worldtype_flat.avif",
                                                    selected = state.worldSource == HostCreateWorldSource.Generate && state.levelChoice == 1,
                                                    onClick = { viewModel.selectLevelChoice(1) },
                                                    enabled = state.configuredBaseWorld?.required != true,
                                                )
                                                ImageCard(
                                                    title = "空岛",
                                                    iconPath = "assets/icons/worldtype_skyblock.avif",
                                                    selected = state.worldSource == HostCreateWorldSource.Generate && state.levelChoice == 2,
                                                    onClick = { viewModel.selectLevelChoice(2) },
                                                    enabled = state.configuredBaseWorld?.required != true,
                                                )
                                                ImageCard(
                                                    title = "自定义",
                                                    iconPath = "assets/icons/worldtype_normal.avif",
                                                    selected = state.worldSource == HostCreateWorldSource.Generate && state.levelChoice == 3,
                                                    onClick = {
                                                        viewModel.selectLevelChoice(3)
                                                        viewModel.beginCustomLevelType()
                                                        showCustomLevelTypeDialog = true
                                                    },
                                                    enabled = state.configuredBaseWorld?.required != true,
                                                )
                                                if (state.isLegacyCreate) ImageCard(
                                                    title = state.selectedBaseWorldId?.let { id -> state.baseWorlds.firstOrNull { it.id == id }?.let { "模板·${it.name}" } } ?: "模板",
                                                    iconPath = "assets/icons/worldtype_normal.avif",
                                                    selected = state.worldSource == HostCreateWorldSource.Template,
                                                    onClick = { if (!state.submitting && state.configuredBaseWorld?.required != true) showBaseWorldSelection = true },
                                                    enabled = state.configuredBaseWorld?.required != true,
                                                )
                                            }
                                        }
                                    }
                                    if (state.isLegacyCreate) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            RRow {

                                                Text("拉朋友玩", fontWeight = FontWeight.Bold)
                                                CircleIconButton(
                                                    icon = "\uDB80\uDD2C",
                                                    label = "添加",
                                                    enabled = !state.submitting,
                                                    onClick = viewModel::openFriendDialog,
                                                )
                                            }
                                            state.initialMembers.forEach { friend ->
                                                key(friend.id) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        HeadButton(uid = friend.id, showName = false)
                                                        Text(friend.name, modifier = Modifier.weight(1f))
                                                        CircleIconButton(
                                                            icon = "\uDB80\uDD56", label = "移除",
                                                            enabled = !state.submitting,
                                                        ) { viewModel.removeFriend(friend.id) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

            }
            Text(
                "注意，一个月没人玩房间就自动删了",
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(end = 12.dp, bottom = 8.dp),
            )
        }
    }

    if (showRules) {
        GameRuleModal(
            show = true,
            initialOverrideRules = state.gameRules,
            onSave = viewModel::updateGameRules,
            onClose = { showRules = false },
        )
    }

    if (showBaseWorldSelection && state.isLegacyCreate) {
        BaseWorldSelectionModal(
            worlds = state.baseWorlds,
            loading = state.baseWorldsLoading,
            errorMessage = state.baseWorldsErrorMessage,
            initialSelectedId = state.selectedBaseWorldId,
            submitting = state.submitting,
            onRetry = viewModel::loadBaseWorldChoices,
            onClearError = viewModel::clearBaseWorldsError,
            onConfirm = { id ->
                viewModel.commitBaseWorldSelection(id)
                showBaseWorldSelection = false
            },
            onDismiss = { showBaseWorldSelection = false },
        )
    }

    if (showUpdateConfirm) {
        val currentHost = state.editHost
        ConfirmDialog(
            title = "确认更新",
            message = "将更新房间当前的整合包《${currentHost?.modpack?.name.orEmpty()}》到最新版本。所有修改过的配置都会丢失。",
            onConfirm = {
                showUpdateConfirm = false
                viewModel.requestHostPackUpdate()
            },
            onDismiss = { showUpdateConfirm = false }
        )
    }

    when {
        state.errorMessage != null -> AlertErr(state.errorMessage!!, viewModel::clearErrorMessage)
        state.statusMessage != null -> AlertErr(state.statusMessage!!, viewModel::clearStatusMessage)
        returnSuccessMessage != null -> AlertOk(returnSuccessMessage!!) {
            returnSuccessMessage = null
            onBack()
        }
        staySuccessMessage != null -> AlertOk(staySuccessMessage!!) {
            staySuccessMessage = null
        }
    }
}
