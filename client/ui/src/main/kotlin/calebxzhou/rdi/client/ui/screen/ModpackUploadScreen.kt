package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import calebxzau.rdi.client.ui.AlertErr
import calebxzau.rdi.client.ui.AlertWarn
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ConfirmDialog
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.RColumn
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzau.rdi.client.ui.Space8h
import calebxzau.rdi.client.ui.Space8w
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.themeNow
import calebxzau.rdi.modpacktest.CLIENT_TEST_SUCCESS_MARKER
import calebxzau.rdi.modpacktest.ModpackTestStatus
import calebxzhou.rdi.client.ui.comp.Console
import calebxzhou.rdi.client.ui.comp.ConsoleState
import calebxzhou.rdi.client.ui.comp.ModGrid
import calebxzhou.rdi.client.ui.comp.ModpackCategorySelector
import calebxzhou.rdi.client.ui.comp.Task2DetailDialog
import calebxzau.rdi.client.ui.pickLocalZipFile
import calebxzau.rdi.client.ui.pickLocalModpackFile
import calebxzau.rdi.client.ui.viewmodel.ModpackUploadEvent
import calebxzau.rdi.client.ui.viewmodel.ModpackUploadMode
import calebxzau.rdi.client.ui.viewmodel.ModpackUploadUiState
import calebxzau.rdi.client.ui.viewmodel.ModpackUploadViewModel
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.modpackInfoCharacterCount
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ModpackUploadScreen(
    onBack: () -> Unit,
    onUploadSubmitted: (String) -> Unit = { onBack() },
    viewModel: ModpackUploadViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ModpackUploadEvent.ClientPackLoaded -> selectedTab = event.initialTab
                ModpackUploadEvent.OpenUploadModeDialog -> Unit
                is ModpackUploadEvent.UploadSubmitted -> onUploadSubmitted(event.runId)
                ModpackUploadEvent.NavigateBack -> onBack()
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    fun selectClientPack() {
        scope.launch {
            pickLocalModpackFile()?.let(viewModel::loadClientPack)
        }
    }

    fun selectServerPack() {
        scope.launch {
            pickLocalZipFile("选择服务端ZIP文件")?.let(viewModel::loadServerPack)
        }
    }

    MaxBox {
        ScreenContentSurface(
            size = ScreenContentSize.FULL,
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.MoveHome) {
                        viewModel.enableIgnoreModpackTest()
                        true
                    } else {
                        false
                    }
                }
        ) {
            TitleRow(
                title = uiState.title,
                onBack = viewModel::closeScreen,
            ) {
                if (uiState.editMode) {
                    if (uiState.serverPackName == null) {
                        Text("如果选择了整合包服务端，就不需要进行测试。")
                    }
                    Space8w()
                    CircleIconButton(
                        "\uF07C",
                        if (uiState.serverPackName == null) "选择服务端ZIP" else "重选服务端ZIP",
                        enabled = uiState.canSelectServerPack,
                        onClick = ::selectServerPack,
                    )
                    if (uiState.loading) {
                        Space8w()
                        PackLoadingProgress(
                            progressText = uiState.progressText,
                            progressFraction = uiState.progressFraction,
                        )
                    } else {
                        uiState.serverPackName?.let {
                            Space8w()
                            Text(it)
                        }
                    }
                    Space8w()
                    UploadSubmitButton(
                        enabled = uiState.canSubmitUpload,
                        disabledReason = uiState.uploadDisabledReason,
                        onClick = viewModel::submitUpload,
                    )
                }
            }

            ContentBody {
                uiState.errorMessage?.let {
                    AlertErr(it)
                }
                uiState.warningMessage?.let { message ->
                    AlertWarn(message, onClose = viewModel::clearWarningMessage)
                }
                if (uiState.ignoreModpackTest) {
                    AlertWarn("已启用rdi.ignoreModpackTest=true，当前允许跳过客户端测试(client test)和服务端测试(server test)直接上传")
                }

                if (!uiState.editMode) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,  verticalArrangement = Arrangement.spacedBy(8.dp),) {

                            uiState.selectedUpdateTarget?.let {
                                Text("准备为整合包《${it.name}》更新版本")
                            }?:
                                Text("准备创建《全新整合包》\n如果你要更新已有包 前往对应包的详情页面点\uDB85\uDC03")



                            CircleIconButton(
                                icon = "\uF07C",
                                label = "选择客户端安装包",
                                enabled = uiState.uploadInitializationReady && !uiState.loading,
                                onClick = ::selectClientPack,
                            )
                            Box(
                                modifier = Modifier.height(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (uiState.loading) {
                                    PackLoadingProgress(
                                        progressText = uiState.progressText,
                                        progressFraction = uiState.progressFraction,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Space8h()
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("基本信息") },
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("测试") },
                        )
                    }
                    Space8h()

                    when (selectedTab) {
                        0 -> Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(0.4f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState())
                                    .padding(end = 12.dp),
                            ) {
                                ModpackUploadInfoTab(
                                    state = uiState,
                                    viewModel = viewModel,
                                )
                            }
                            VerticalDivider()
                            Column(
                                modifier = Modifier
                                    .weight(0.6f)
                                    .fillMaxHeight()
                                    .padding(start = 12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Mod列表(${uiState.uiMods.size})")
                                    if (uiState.uiModsLoading) {
                                        Space8w()
                                        Text(
                                            "正在补充Mod详细信息...",
                                            color = themeNow.onSurfaceVariant,
                                        )
                                    }
                                }
                                Space8h()
                                ModGrid(
                                    mods = uiState.uiMods,
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    emptyText = "没有可显示的mod",
                                    onSideChange = viewModel::updateModSide,
                                    initialIconOnly = true,
                                )
                            }
                        }

                        1 -> Row(modifier = Modifier.fillMaxSize()) {
                            TestConsolePane(
                                title = "客户端测试",
                                statusText = testStatusText(
                                    uiState.clientTestStatus,
                                    uiState.clientTestPassSeconds,
                                ),
                                consoleState = viewModel.clientTestConsoleState,
                                hint = "创建单机存档，在聊天框发送${CLIENT_TEST_SUCCESS_MARKER}",
                                onStart = viewModel::startClientTest,
                                onStop = viewModel::stopClientTest,
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp),
                            )
                            VerticalDivider()
                            TestConsolePane(
                                title = "服务端测试",
                                statusText = testStatusText(
                                    uiState.serverTestStatus,
                                    uiState.serverTestPassSeconds,
                                ),
                                consoleState = viewModel.serverTestConsoleState,
                                hint = "",
                                onStart = viewModel::startServerTest,
                                onStop = viewModel::stopServerTest,
                                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        uiState.downloadTaskEntry?.let { entry ->
            Task2DetailDialog(entry = entry, onClose = {})
        }

        uiState.pendingMissingModDownload?.let { pending ->
            ConfirmDialog(
                title = "下载缺失Mod",
                message = "${pending.usage}需要先下载缺失Mod${pending.mods.size}个。下载完成后再启动${pending.usage}。",
                onConfirm = viewModel::confirmMissingModDownload,
                onDismiss = viewModel::dismissMissingModDownload,
            )
        }

    }
}

@Composable
private fun PackLoadingProgress(
    progressText: String?,
    progressFraction: Float?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progressFraction != null) {
            CircularProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Space8w()
        val text = progressText ?: "正在处理..."
        Text(
            text = if (progressFraction != null) "${text} ${(progressFraction * 100).toInt()}%" else text,
            color = themeNow.onSurfaceVariant,
        )
    }
}

@Composable
private fun UploadSubmitButton(
    enabled: Boolean,
    disabledReason: String?,
    onClick: () -> Unit,
) {
    val button: @Composable () -> Unit = {
        CircleIconButton(
            icon = "\uF058",
            label = "开始传包",
            bgColor = themeNow.primary,
            enabled = enabled,
            onClick = onClick,
        )
    }
    if (enabled) {
        button()
    } else {
        SimpleTooltip(disabledReason ?: "当前暂时不能上传") {
            button()
        }
    }
}

@Composable
private fun ModpackUploadInfoTab(
    state: ModpackUploadUiState,
    viewModel: ModpackUploadViewModel,
) {
    val metadataEnabled = state.uploadMode == ModpackUploadMode.CREATE
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.selectedUpdateTarget?.let { target ->
            Text("为已有整合包 上传新版本：${target.name}")
        } ?: Text("准备创建全新整合包")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.draft.name,
                onValueChange = { viewModel.updateDraft(state.draft.copy(name = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("整合包名称") },
                enabled = state.uploadMode == ModpackUploadMode.CREATE,
            )
            OutlinedTextField(
                value = state.draft.versionName,
                onValueChange = viewModel::updateVersionName,
                modifier = Modifier.weight(1f),
                label = { Text("版本") },
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.draft.iconUrl,
                onValueChange = { viewModel.updateDraft(state.draft.copy(iconUrl = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("图标链接") },
                singleLine = true,
                enabled = metadataEnabled,
                isError = state.draftErrors.iconUrl != null,
                supportingText = { state.draftErrors.iconUrl?.let { Text(it) } },
            )
            OutlinedTextField(
                value = state.draft.sourceUrl,
                onValueChange = { viewModel.updateDraft(state.draft.copy(sourceUrl = it)) },
                modifier = Modifier.weight(1f),
                label = { Text("原帖发布链接") },
                singleLine = true,
                enabled = metadataEnabled,
            )
        }
        OutlinedTextField(
            value = state.draft.info,
            onValueChange = { viewModel.updateDraft(state.draft.copy(info = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("简介(10~100字符)") },
            singleLine = true,
            enabled = metadataEnabled,
            isError = state.draftErrors.info != null,
            supportingText = {
                Text(
                    state.draftErrors.info
                        ?: "${state.draft.info.trim().modpackInfoCharacterCount()}/100"
                )
            },
        )
        Text("分类(至少1个，最多${Modpack.MAX_CATEGORY_COUNT}个)")
        ModpackCategorySelector(
            selected = state.draft.categories,
            onSelectedChange = { viewModel.updateDraft(state.draft.copy(categories = it)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = metadataEnabled,
        )
        state.draftErrors.categories?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}


@Composable
private fun TestConsolePane(
    title: String,
    statusText: String,
    consoleState: ConsoleState,
    hint: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${title} · ${statusText}")
            Spacer(Modifier.weight(1f))
            CircleIconButton("\uF04B", "开始", onClick = onStart)
            CircleIconButton("\uF04D", "停止", onClick = onStop)
        }
        if (hint.isNotBlank()) {
            Text(
                text = hint,
                color = themeNow.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Space8h()
        Box(modifier = Modifier.fillMaxSize().padding(bottom = 8.dp)) {
            Console(state = consoleState, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun testStatusText(status: ModpackTestStatus, passSeconds: String?): String = when (status) {
    ModpackTestStatus.NOT_RUN -> "未测试"
    ModpackTestStatus.RUNNING -> "测试中"
    ModpackTestStatus.PASSED -> "通过${passSeconds?.let { "(${it}s)" } ?: ""}"
    ModpackTestStatus.FAILED -> "失败"
    ModpackTestStatus.STOPPED -> "已停止"
}
