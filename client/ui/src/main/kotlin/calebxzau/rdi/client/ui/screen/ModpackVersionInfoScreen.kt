package calebxzau.rdi.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import calebxzau.rdi.client.ui.BottomSnakebarM3
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ConfirmDialog
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.ErrorText
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.RRow
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.Space8w
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.themeNow
import calebxzau.rdi.client.ui.viewmodel.ModpackVersionInfoAction
import calebxzau.rdi.client.ui.viewmodel.ModpackVersionInfoEvent
import calebxzau.rdi.client.ui.viewmodel.ModpackVersionInfoViewModel
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.ui.comp.HeadButton
import calebxzhou.rdi.client.ui.comp.ModGrid
import calebxzhou.rdi.client.ui.screen.canManageModpackVersion
import calebxzhou.rdi.client.ui.pickAwtOpenFilesAsync
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import calebxzhou.rdi.common.model.ModRef
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.util.millisToHumanDateTime
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private val modpackVersionInfoLogger = KotlinLogging.logger {}

/** Selected-version details and Mod management. */
@Composable
fun ModpackVersionInfoScreen(
    modpackId: String,
    verName: String,
    onBack: () -> Unit,
    onVersionDeleted: () -> Unit = onBack,
    onOpenBaseWorldManage: () -> Unit = {},
    viewModel: ModpackVersionInfoViewModel = koinViewModel(key = "$modpackId:$verName") {
        parametersOf(modpackId, verName)
    },
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedModKeys by remember<MutableState<Set<String>>> { mutableStateOf(emptySet()) }
    var modFilePickerOpen by remember { mutableStateOf(false) }
    var editingMods by remember<MutableState<List<UiMod>>> { mutableStateOf(emptyList()) }
    var deleteConfirmMods by remember<MutableState<List<UiMod>>> { mutableStateOf(emptyList()) }
    LaunchedEffect(uiState.okMessage) {
        uiState.okMessage?.let<String, Unit> {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearOkMessage()
        }
    }
    LaunchedEffect(uiState.completedAction) {
        when (uiState.completedAction) {
            ModpackVersionInfoAction.EDIT -> editingMods = emptyList<UiMod>()
            ModpackVersionInfoAction.DELETE -> {
                selectedModKeys = emptySet<String>()
                deleteConfirmMods = emptyList<UiMod>()
            }

            ModpackVersionInfoAction.ADD, null -> Unit
        }
        if (uiState.completedAction != null) viewModel.clearCompletedAction()
    }
    var confirmDeleteVersion by remember { mutableStateOf(false) }
    var confirmRebuildVersion by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ModpackVersionInfoEvent.ShowSnackbar -> snackbarHostState.showSnackbar(
                    event.message,
                    duration = SnackbarDuration.Short
                )
                ModpackVersionInfoEvent.VersionDeleted -> onVersionDeleted()
            }
        }
    }
    LaunchedEffect(uiState.reloadToken) {
        selectedModKeys = emptySet()
    }
    val currentVersion = uiState.version
    val pack = uiState.pack
    val canManageVersion = pack?.let { selectedPack ->
        currentVersion?.let { selectedVersion ->
            canManageModpackVersion(
                pack = selectedPack,
                version = selectedVersion,
                playerId = loggedAccount._id,
                isDav = loggedAccount.isDav,
            )
        }
    } ?: false
    val selectedMods = uiState.uiMods.filter { it.key in selectedModKeys }
    val canMutate = canManageVersion && !uiState.versionActionPending && currentVersion?.status == Modpack.Status.OK
    val versionActionsEnabled =
        canManageVersion && !uiState.versionActionPending && currentVersion?.status?.let<Modpack.Status, Boolean> {
            it != Modpack.Status.WAIT && it != Modpack.Status.BUILDING
        } ?: false
    val versionStatusText = if (uiState.versionActionPending) {
        "重构提交中"
    } else when (currentVersion?.status) {
        Modpack.Status.OK -> if (canManageVersion) "可编辑" else "可用"
        Modpack.Status.FAIL -> if (canManageVersion) "构建失败，可重构" else "构建失败"
        Modpack.Status.WAIT -> "等待构建中"
        Modpack.Status.BUILDING -> "构建中"
        null -> "未加载"
    }
    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.LARGE) {
            TitleRow(
                title = buildString {
                    // append("整合包版本")
                    uiState.pack?.name?.takeIf<String> { it.isNotBlank() }?.let {
                        this.append(it)
                    }
                    this.append(" ")
                    this.append(verName)
                },
                onBack = onBack
            ) {
                Text(
                    versionStatusText,
                    color = if (canMutate) themeNow.primary else themeNow.onSurfaceVariant
                )
                if (currentVersion != null) {
                    if (canManageVersion) {
                        CircleIconButton(
                            icon = "\uEA81",
                            label = if (versionActionsEnabled) "删除版本" else "删除版本（处理中）",
                            showText = false,
                            bgColor = MaterialTheme.colorScheme.error,
                            enabled = versionActionsEnabled
                        ) {
                            confirmDeleteVersion = true
                        }
                        CircleIconButton(
                            icon = "\uF0AD",
                            label = if (versionActionsEnabled) "重构" else "重构（处理中）",
                            showText = false,
                            bgColor = MaterialTheme.colorScheme.tertiary,
                            enabled = versionActionsEnabled
                        ) {
                            confirmRebuildVersion = true
                        }
                    }
                }
                uiState.errorMessage?.let<String, Unit> { ErrorText(it) }
            }
            ContentBody {
                if (uiState.loading) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                if (!uiState.loading && currentVersion == null) {
                    Text("未找到版本信息", color = MaterialTheme.colorScheme.error)
                }

                if (currentVersion != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RRow (
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("上传 "+currentVersion.time.millisToHumanDateTime)
                            currentVersion.totalSize?.let { Text(it.humanFileSize) }
                            Text(" by")
                            val uploaderId = currentVersion.uploaderId ?: pack?.authorId ?: loggedAccount._id
                            HeadButton(uploaderId, showName = true)

                        }
                        if (canManageVersion) {
                            RRow(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        currentVersion.baseWorld?.let { binding ->
                                            val name = binding.id.toString()
                                            "初始地图模板：已设置${if (binding.required) "（必须使用）" else ""}"
                                        } ?: "初始地图模板 可设置",
                                        color = themeNow.onSurfaceVariant,
                                    )
                                }
                                CircleIconButton(
                                    icon = "\uEE69",
                                    label = "配置初始地图",
                                ) {
                                    onOpenBaseWorldManage()
                                }
                            }
                        }
                        if (uiState.versionActionPending) {
                            Text(
                                "重构请求处理中，暂时不能增删改Mod。",
                                color = themeNow.onSurfaceVariant,
                            )
                        } else if (currentVersion.status == Modpack.Status.WAIT || currentVersion.status == Modpack.Status.BUILDING) {
                            Text(
                                "当前版本正在重构，暂时不能增删改Mod。等状态回到可编辑后再操作。",
                                color = themeNow.onSurfaceVariant
                            )
                        }
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RRow {
                                Text(
                                    text = if (canManageVersion && selectedMods.isEmpty()) "模组列表(${currentVersion.mods.size})" else if (canManageVersion) "已选中${selectedMods.size}个Mod" else "当前版本Mod",
                                    color = themeNow.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (canManageVersion){
                                    CircleIconButton(
                                        icon = "\uF021",
                                        label = "刷新",
                                        showText = false,
                                        bgColor = MaterialTheme.colorScheme.primary,
                                        enabled = !uiState.loading && !uiState.uiModsLoading
                                    ) {
                                        viewModel.reload()
                                    }
                                    CircleIconButton(
                                        icon = "\uF067",
                                        label = if (canMutate) {
                                            if (uiState.addDialogLoading) uiState.addDialogLoadingText.ifBlank<String, String> { "匹配中..." } else "添加Mod"
                                        } else {
                                            "当前版本不可修改"
                                        },
                                        bgColor = themeNow.secondary,
                                        showText = false,
                                        enabled = canMutate && !uiState.addDialogLoading && !modFilePickerOpen
                                    ) {
                                        if (modFilePickerOpen) return@CircleIconButton
                                        val mcVersion = uiState.pack?.mcVer ?: return@CircleIconButton
                                        modFilePickerOpen = true
                                        scope.launch {
                                            try {
                                                val files = pickAwtOpenFilesAsync(
                                                    title = "选择额外Mod JAR",
                                                    filenameFilter = { _, name -> name.endsWith(".jar", ignoreCase = true) }
                                                )?.filter { it.extension.equals("jar", ignoreCase = true) }
                                                    ?.takeIf { it.isNotEmpty() } ?: return@launch
                                                viewModel.matchFiles(files, mcVersion)
                                            } catch (cancel: CancellationException) {
                                                throw cancel
                                            } catch (cause: Exception) {
                                                modpackVersionInfoLogger.error(cause) { "选择额外Mod文件失败" }
                                                snackbarHostState.showSnackbar("选择Mod文件失败，请重试")
                                            } finally {
                                                modFilePickerOpen = false
                                            }
                                        }
                                    }
                                    CircleIconButton(
                                        icon = "\uF044",
                                        label = if (canMutate) "编辑选中Mod" else "当前版本不可修改",
                                        showText = false,
                                        bgColor = themeNow.tertiary,
                                        enabled = canMutate && selectedMods.isNotEmpty<UiMod>() && !uiState.editDialogSaving
                                    ) {
                                        editingMods = selectedMods
                                    }
                                    CircleIconButton(
                                        icon = "\uEA81",
                                        label = if (canMutate) "批量删除选中Mod" else "当前版本不可修改",
                                        showText = false,
                                        bgColor = MaterialTheme.colorScheme.error,
                                        enabled = canMutate && selectedMods.isNotEmpty<UiMod>()
                                    ) {
                                        deleteConfirmMods = selectedMods
                                    }
                                }
                            }
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                when {
                                    uiState.uiModsLoading && uiState.uiMods.isEmpty() -> {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            CircularProgressIndicator()
                                            Text("正在载入Mod信息...")
                                        }
                                    }

                                    uiState.uiMods.isEmpty() -> {
                                        Text("当前版本没有Mod")
                                    }

                                    else -> {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            if (uiState.uiModsLoading) {
                                                Text(
                                                    "正在补充Mod详细信息...",
                                                    color = themeNow.onSurfaceVariant,
                                                )
                                                Spacer(Modifier.height(8.dp))
                                            }
                                            ModGrid(
                                                mods = uiState.uiMods,
                                                modifier = Modifier.fillMaxWidth().weight(1f),
                                                selectedKeys = if (canManageVersion) selectedModKeys else emptySet(),
                                                emptyText = "当前版本没有Mod",
                                                onModClick = if (canManageVersion) {
                                                    { uiMod ->
                                                        selectedModKeys = if (uiMod.key in selectedModKeys) {
                                                            selectedModKeys - uiMod.key
                                                        } else {
                                                            selectedModKeys + uiMod.key
                                                        }
                                                    }
                                                } else null,
                                                initialIconOnly = true
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        BottomSnakebarM3(snackbarHostState)
    }
    if (uiState.addDialogOpen) {
        val selectedPendingUiMods = uiState.pendingAddUiMods.filter<UiMod> {
            it.key in uiState.selectedPendingAddKeys
        }
        Dialog(
            onDismissRequest = {
                if (!uiState.addDialogLoading) {
                    viewModel.resetAddDialog()
                }
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.9f),
                color = Color.White
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("添加版本Mod", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "会复用房间附加Mod(extra mod)的本地jar匹配逻辑。当前会把选中的Mod一次性提交并发布更新后的Mod列表。",
                        color = themeNow.onSurfaceVariant
                    )
                    Text(
                        if (selectedPendingUiMods.isEmpty()) "点击下方Mod卡片选择要添加的Mod" else "当前准备添加${selectedPendingUiMods.size}个Mod",
                        color = themeNow.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        ModGrid(
                            mods = uiState.pendingAddUiMods,
                            modifier = Modifier.fillMaxSize(),
                            selectedKeys = uiState.selectedPendingAddKeys,
                            emptyText = "当前没有可添加的Mod",
                            onModClick = viewModel::togglePendingAdd,
                            onSideChange = if (uiState.addDialogLoading) {
                                null
                            } else {
                                viewModel::changePendingAddSide
                            }
                        )
                    }
                    if (uiState.rejectedAddFiles.isNotEmpty<String>()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(255, 244, 244))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("以下文件未能加入候选", color = MaterialTheme.colorScheme.error)
                            Text(
                                uiState.rejectedAddFiles.joinToString<String>("\n") { "• $it" },
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        uiState.addDialogError?.let<String, Unit> {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            Space8w()
                        }
                        TextButton(
                            enabled = !uiState.addDialogLoading,
                            onClick = viewModel::resetAddDialog,
                        ) {
                            Text("取消")
                        }
                        Space8w()
                        TextButton(
                            enabled = !uiState.addDialogLoading && selectedPendingUiMods.isNotEmpty<UiMod>() && canMutate,
                            onClick = {
                                viewModel.addSelectedMods(selectedPendingUiMods)
                            }
                        ) {
                            Text(if (uiState.addDialogLoading) "添加中..." else "添加")
                        }
                    }
                }
            }
        }
    }
    if (editingMods.isNotEmpty<UiMod>()) {
        VersionModBatchEditDialog(
            uiMods = editingMods,
            saving = uiState.editDialogSaving,
            onDismiss = {
                if (!uiState.editDialogSaving) editingMods = emptyList<UiMod>()
            },
            onSave = viewModel::saveEdits,
        )
    }
    if (deleteConfirmMods.isNotEmpty<UiMod>()) {
        ConfirmDialog(
            title = "删除版本Mod",
            message = if (deleteConfirmMods.size == 1) {
                "确定删除《${deleteConfirmMods.first<UiMod>().displayName}》吗？删除后会发布更新后的Mod列表。"
            } else {
                "确定删除选中的${deleteConfirmMods.size}个Mod吗？删除后会发布更新后的Mod列表。"
            },
            onConfirm = {
                val targetRefs = deleteConfirmMods.map<UiMod, ModRef> { ModRef(it.projectId, it.fileId) }
                viewModel.deleteMods(targetRefs)
                deleteConfirmMods = emptyList<UiMod>()
            },
            onDismiss = { deleteConfirmMods = emptyList<UiMod>() }
        )
    }
    if (confirmDeleteVersion && currentVersion != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteVersion = false },
            title = { Text("确认删除版本") },
            text = { Text("确定要永久删除版本 V${currentVersion.name} 吗？无法恢复！") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteVersion = false
                    viewModel.deleteVersion()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteVersion = false }) { Text("取消") } },
        )
    }
    if (confirmRebuildVersion && currentVersion != null) {
        AlertDialog(
            onDismissRequest = { confirmRebuildVersion = false },
            title = { Text("确认重构版本") },
            text = { Text("整合包出现mod不完整等问题，可重构以解决。确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRebuildVersion = false
                    viewModel.rebuildVersion()
                }) { Text("重构") }
            },
            dismissButton = { TextButton(onClick = { confirmRebuildVersion = false }) { Text("取消") } },
        )
    }
}
