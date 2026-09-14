package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import calebxzau.rdi.client.ui.*
import calebxzau.rdi.client.ui.viewmodel.ModpackInfoEvent
import calebxzau.rdi.client.ui.viewmodel.ModpackInfoViewModel
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.ui.comp.HeadButton
import calebxzhou.rdi.client.ui.comp.ModpackCategoryChips
import calebxzhou.rdi.client.ui.comp.ModpackCategorySelector
import calebxzhou.rdi.client.ui.comp.ModpackDownloadMethodDialog
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.common.util.toFriendlyDateTime
import org.bson.types.ObjectId
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * calebxzhou @ 2026-01-17 20:44
 */
private enum class ModpackDownloadDialogPhase {
    ConfirmRedownload,
    SelectMethod,
}

private data class ActiveModpackDownloadDialog(
    val versionName: String,
    val phase: ModpackDownloadDialogPhase,
)

@Composable
fun ModpackInfoScreen(
    modpackId: String,
    onBack: () -> Unit,
    onManageUploaders: () -> Unit = {},
    onOpenVersionInfo: ((String) -> Unit)? = null,
    onOpenUpload: (() -> Unit)? = null,
    onOpenTaskList: ((String) -> Unit)? = null,
    onCreateHost: ((Modpack.DetailVo, Modpack.Version) -> Unit)? = null,
    viewModel: ModpackInfoViewModel = koinViewModel(key = modpackId) {
        parametersOf(modpackId)
    },
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDeletePack by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var activeDownloadDialog by remember { mutableStateOf<ActiveModpackDownloadDialog?>(null) }
    var dialogMessage by remember<MutableState<String?>> { mutableStateOf(null) }
    var dialogErrorMessage by remember<MutableState<String?>> { mutableStateOf(null) }
    LaunchedEffect(dialogMessage, dialogErrorMessage) {
        (dialogErrorMessage ?: dialogMessage)?.let { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            dialogMessage = null
            dialogErrorMessage = null
        }
    }
    LaunchedEffect(viewModel, onBack) {
        viewModel.events.collect { event ->
            when (event) {
                is ModpackInfoEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
                }

                is ModpackInfoEvent.EditSaved -> {
                    showEditDialog = false
                    snackbarHostState.showSnackbar(event.message, duration = SnackbarDuration.Short)
                }

                ModpackInfoEvent.PackDeleted -> onBack()
                is ModpackInfoEvent.ConfirmRedownload -> activeDownloadDialog =
                    ActiveModpackDownloadDialog(event.versionName, ModpackDownloadDialogPhase.ConfirmRedownload)
                is ModpackInfoEvent.SelectDownloadMethod -> activeDownloadDialog =
                    ActiveModpackDownloadDialog(event.versionName, ModpackDownloadDialogPhase.SelectMethod)
                is ModpackInfoEvent.InstallQueued -> onOpenTaskList?.invoke(event.runId)
                    ?: snackbarHostState.showSnackbar("已加入任务列表", duration = SnackbarDuration.Short)
            }
        }
    }
    val pack = uiState.pack
    val isAuthor = pack?.let { it.authorId == loggedAccount._id || loggedAccount.isDav } ?: false
    val sourceUrl = pack?.sourceUrl?.trim()?.takeIf(String::isNotBlank)

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.SMALL) {
            TitleRow(pack?.name ?: "整合包详情", onBack) {
                uiState.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }

                sourceUrl?.let { url ->
                    CircleIconButton("\uDB81\uDD9F", tooltip = "打开原帖"){openUrl(url)}
                }
                if (isAuthor) {
                    CircleIconButton(
                        "\uF4FE",
                        tooltip = "谁能上传版本",
                    ) { onManageUploaders() }
                    CircleIconButton(
                        icon = "\uF01F",
                        tooltip = "修改信息",
                        bgColor = themeNow.tertiary
                    ) {
                        viewModel.beginEdit()
                        showEditDialog = true
                    }
                    CircleIconButton(
                        icon = "\uEA81",
                        tooltip = "删除整合包",
                        bgColor = MaterialTheme.colorScheme.error,
                    ) { confirmDeletePack = true }


                }
            }
            ContentBody {
                if (uiState.loading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                if (!uiState.loading && pack == null) {
                    Text("未找到整合包信息")
                }

                if (pack != null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ModpackDownloadVersions(
                            pack = pack,
                            isAuthor = isAuthor,
                            playerId = loggedAccount._id,
                            onOpenVersionInfo = onOpenVersionInfo,
                            onOpenUpload = onOpenUpload,
                            onCreateHost = onCreateHost,
                            onRequestDownload = viewModel::requestDownload,
                        )
                        ModpackIntroContent(pack)
                    }
                }
            }
        }
        BottomSnakebarM3(snackbarHostState)
    }

    val selectedDownloadVersion = activeDownloadDialog?.let { dialog ->
        pack?.versions?.firstOrNull { it.name == dialog.versionName }
    }
    LaunchedEffect(pack, activeDownloadDialog) {
        if (activeDownloadDialog != null && selectedDownloadVersion == null) {
            activeDownloadDialog = null
        }
    }
    if (activeDownloadDialog?.phase == ModpackDownloadDialogPhase.ConfirmRedownload &&
        pack != null && selectedDownloadVersion != null
    ) {
        ConfirmDialog(
            title = "确认重新下载",
            message = "整合包版本 ${selectedDownloadVersion.name} 已存在，是否重新下载？",
            onConfirm = {
                activeDownloadDialog = ActiveModpackDownloadDialog(
                    versionName = selectedDownloadVersion.name,
                    phase = ModpackDownloadDialogPhase.SelectMethod,
                )
            },
            onDismiss = { activeDownloadDialog = null },
        )
    }
    if (activeDownloadDialog?.phase == ModpackDownloadDialogPhase.SelectMethod &&
        pack != null && selectedDownloadVersion != null
    ) {
        ModpackDownloadMethodDialog(
            packName = pack.name,
            packVer = selectedDownloadVersion.name,
            onDismiss = { activeDownloadDialog = null },
            onDirectDownload = { includeClientExtras ->
                val versionName = selectedDownloadVersion.name
                activeDownloadDialog = null
                viewModel.installVersion(versionName, includeClientExtras)
            },
            onOpenTaskList = onOpenTaskList,
            onImportMessage = { dialogMessage = it },
            onImportError = { dialogErrorMessage = it },
        )
    }

    if (confirmDeletePack && pack != null) {
        AlertDialog(
            onDismissRequest = { confirmDeletePack = false },
            title = { Text("确认删除") },
            text = { Text("确定要永久删除整合包 ${pack.name} 吗？无法恢复！") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeletePack = false
                    viewModel.deletePack()
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeletePack = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showEditDialog && pack != null) {
        AlertDialog(
            onDismissRequest = {
                if (!uiState.savingEdit) showEditDialog = false
            },
            title = { Text("修改整合包信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedTextField(
                        value = uiState.editDraft.name,
                        onValueChange = {
                            viewModel.updateEditDraft(uiState.editDraft.copy(name = it))
                        },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = uiState.editDraft.iconUrl,
                        onValueChange = {
                            viewModel.updateEditDraft(uiState.editDraft.copy(iconUrl = it))
                        },
                        label = { Text("图标链接") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = uiState.editDraft.sourceUrl,
                        onValueChange = {
                            viewModel.updateEditDraft(uiState.editDraft.copy(sourceUrl = it))
                        },
                        label = { Text("来源链接") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = uiState.editDraft.info,
                        onValueChange = {
                            viewModel.updateEditDraft(uiState.editDraft.copy(info = it))
                        },
                        label = { Text("简介") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("分类 最多${Modpack.MAX_CATEGORY_COUNT}个")
                    ModpackCategorySelector(
                        selected = uiState.editDraft.categories,
                        onSelectedChange = {
                            viewModel.updateEditDraft(uiState.editDraft.copy(categories = it))
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::saveEdit,
                    enabled = !uiState.savingEdit,
                ) {
                    Text(if (uiState.savingEdit) "保存中..." else "保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEditDialog = false },
                    enabled = !uiState.savingEdit,
                ) {
                    Text("取消")
                }
            }
        )
    }

}

@Composable
private fun ModpackDownloadVersions(
    pack: Modpack.DetailVo,
    isAuthor: Boolean,
    playerId: ObjectId,
    onOpenVersionInfo: ((String) -> Unit)?,
    onOpenUpload: (() -> Unit)?,
    onCreateHost: ((Modpack.DetailVo, Modpack.Version) -> Unit)?,
    onRequestDownload: (Modpack.Version) -> Unit,
) {
    val visibleVersions = visibleModpackVersions(pack.versions, isAuthor, playerId)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

        RRow {
            HeadButton(pack.authorId, showName = false, avatarSize = 14.dp)
            Text(
                    text = "请选择版本",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(pack.mcVer.simpleVer, color = Color.LightGray)
                TinyClickCopyText("mid", pack._id.toString())

        }
        if (visibleVersions.isEmpty()) {
            Text(
                if (isAuthor) "暂无版本" else "暂无可用版本",
                color = Color.Gray,
            )
        }
        RRow {
            visibleVersions.forEach { version ->
                ModpackVersionCard(
                    pack = pack,
                    version = version,
                    onOpenVersionInfo = onOpenVersionInfo,
                    onCreateHost = onCreateHost,
                    onRequestDownload = onRequestDownload,
                )
            }
            if (pack.canUploadVersion) {
                CircleIconButton(
                    "\uDB85\uDC03",
                    "上传新版本",
                    showText = false,
                    onClick = { onOpenUpload?.invoke() },
                )
            }
        }
    }
}

internal fun visibleModpackVersions(
    versions: List<Modpack.Version>,
    isAuthor: Boolean,
    playerId: ObjectId,
): List<Modpack.Version> = if (isAuthor) {
    versions
} else {
    versions.filter { it.status == Modpack.Status.OK || it.uploaderId == playerId }
}

internal fun canManageModpackVersion(
    pack: Modpack.DetailVo,
    version: Modpack.Version,
    playerId: ObjectId,
    isDav: Boolean,
): Boolean = isDav || pack.authorId == playerId || version.uploaderId == playerId

@Composable
private fun ModpackVersionCard(
    pack: Modpack.DetailVo,
    version: Modpack.Version,
    onOpenVersionInfo: ((String) -> Unit)?,
    onCreateHost: ((Modpack.DetailVo, Modpack.Version) -> Unit)?,
    onRequestDownload: (Modpack.Version) -> Unit,
) {
    val statusText = when (version.status) {
        Modpack.Status.OK -> "\uDB82\uDE50"
        Modpack.Status.BUILDING -> "\uEEFF"
        Modpack.Status.FAIL -> "\uEA87"
        Modpack.Status.WAIT -> "\uE641"
    }
    val statusColor = when (version.status) {
        Modpack.Status.OK -> themeNow.primary
        Modpack.Status.BUILDING -> themeNow.tertiary
        Modpack.Status.FAIL -> MaterialTheme.colorScheme.error
        Modpack.Status.WAIT -> themeNow.onSurfaceVariant
    }

    var menuExpanded by remember(version.name) { mutableStateOf(false) }
    CursorPositionBox(
        onSecondaryPress = { menuExpanded = true },
        cursorContent = {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                offset = OffsetFirstItemUnderCursor,
            ) {
                RDropdownMenuItem(
                    text = "创建多人房间",
                    icon = "\uF04B",
                    enabled = version.status == Modpack.Status.OK,
                    onClick = {
                        menuExpanded = false
                        onCreateHost?.invoke(pack, version)
                    },
                )
                RDropdownMenuItem(
                    text = "下载",
                    icon = "\uF019",
                    enabled = version.status == Modpack.Status.OK,
                    onClick = {
                        menuExpanded = false
                        onRequestDownload(version)
                    },
                )
                RDropdownMenuItem(
                    text = "查看详情",
                    icon = "\uF05A",
                    onClick = {
                        menuExpanded = false
                        onOpenVersionInfo?.invoke(version.name)
                    },
                )
            }
        },
    ) {
        Card(onClick = { menuExpanded = true }) {
            ModpackVersionCardContent(version, statusText, statusColor)
        }
    }
}

@Composable
private fun ModpackVersionCardContent(
    version: Modpack.Version,
    statusText: String,
    statusColor: Color,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SimpleTooltip(
            version.time.toFriendlyDateTime()){
                Text(statusText+" ${version.name}", color = statusColor)

        }
    }
}

@Composable
private fun ModpackIntroContent(
    pack: Modpack.DetailVo
) {
    val displaySummary = pack.info?.takeIf(String::isNotBlank)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RRow {
            Text("简介", style = MaterialTheme.typography.titleMedium)
            if (pack.categories.isNotEmpty()) {
                ModpackCategoryChips(categories = pack.categories)
            }
        }
        displaySummary?.let {
            Text(it)
        }
        if (displaySummary == null) {
            Text("无")
        }
    }
}

/* The unpublished Modpack2 details entry point is retained for later re-enable.
@Composable
private fun Modpack2InfoContent(
    modpackId: String,
    onBack: () -> Unit,
    onOpenTaskList: ((String) -> Unit)?,
) {
    val gateway: Modpack2Gateway = koinInject()
    val service: Modpack2LocalService = koinInject()
    val scope = rememberCoroutineScope()
    val parsedId = remember(modpackId) { runCatching { Uuid.parse(modpackId) }.getOrNull() }
    val targetId = parsedId
    var brief by remember(modpackId) { mutableStateOf<Modpack2BriefVo?>(null) }
    var versions by remember(modpackId) { mutableStateOf<List<Modpack2VersionDetailVo>>(emptyList()) }
    var loading by remember(modpackId) { mutableStateOf(true) }
    var errorMessage by remember(modpackId) { mutableStateOf<String?>(null) }

    LaunchedEffect(parsedId) {
        if (parsedId == null) {
            errorMessage = "整合包链接无效"
            loading = false
            return@LaunchedEffect
        }
        val id = parsedId
        runCatching {
            val loadedBrief = findModpack2PublicBrief(id)
            val loadedVersions = gateway.listVersions(id).getOrThrow()
            loadedBrief to loadedVersions
        }.onSuccess { (loadedBrief, loadedVersions) ->
            brief = loadedBrief
            versions = loadedVersions
            errorMessage = null
        }.onFailure { error ->
            errorMessage = error.message ?: "加载整合包信息失败"
        }
        loading = false
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.LARGE) {
            TitleRow(brief?.name ?: "整合包详情", onBack)
            ContentBody {
                when {
                    loading -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }

                    errorMessage != null -> Text(
                        errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )

                    brief != null -> Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        brief?.intro?.takeIf(String::isNotBlank)?.let { Text(it) }
                        Text("版本", style = MaterialTheme.typography.titleMedium)
                        if (versions.isEmpty()) {
                            Text("暂无可用版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            versions.forEach { version ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(version.name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            version.status.toPlayerLabel(),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (version.status == Modpack2VersionStatus.Ok) {
                                        TextButton(onClick = {
                                            targetId?.let { installId ->
                                                scope.launch {
                                                    runCatching {
                                                        ClientTaskManager.submit(
                                                            service.installTask(
                                                                Modpack2InstallRequest(
                                                                    modpackId = installId,
                                                                    versionId = version.id.toKotlinUuid(),
                                                                )
                                                            ),
                                                            dedupeKey = "modpack-install:$installId:${version.id}",
                                                        )
                                                    }.onSuccess { runId ->
                                                        onOpenTaskList?.invoke(runId)
                                                    }.onFailure { error ->
                                                        errorMessage = error.message ?: "安装失败"
                                                    }
                                                }
                                            }
                                        }) { Text("安装") }
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

private const val MODPACK2_PUBLIC_PAGE_SIZE = 100
private const val MAX_MODPACK2_PUBLIC_PAGES = 100

/**
 * The public endpoint is paged and deliberately exposes only the brief DTO. Keep walking pages
 * until the target is found, a short page proves there is no more data, or the safety cap is hit.
 */
private suspend fun findModpack2PublicBrief(
    modpackId: Uuid,
): Modpack2BriefVo {
    for (page in 0 until MAX_MODPACK2_PUBLIC_PAGES) {
        val response = server.makeRequest<List<Modpack2BriefVo>>(
            path = "modpack2/list",
            params = mapOf("page" to page),
        )
        require(response.ok) { response.msg.ifBlank { "加载整合包信息失败" } }
        val entries = response.data.orEmpty()
        entries.firstOrNull { it.id == modpackId }?.let { return it }
        if (entries.size < MODPACK2_PUBLIC_PAGE_SIZE) {
            break
        }
    }
    error("未找到整合包信息")
}

private fun Modpack2VersionStatus.toPlayerLabel(): String = when (this) {
    Modpack2VersionStatus.Ok -> "可用"
    Modpack2VersionStatus.Building -> "构建中"
    Modpack2VersionStatus.Fail -> "构建失败"
} */
