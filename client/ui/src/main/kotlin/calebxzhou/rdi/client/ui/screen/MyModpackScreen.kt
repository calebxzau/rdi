package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.service.ModpackService
import calebxzhou.rdi.client.service.ModpackService.modpackInstallTaskKey
import calebxzhou.rdi.client.service.ModpackService.startInstallTask2
import calebxzhou.rdi.client.service.getLocalPackDirs
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzau.rdi.client.ui.AlertErr
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.CursorPositionBox
import calebxzau.rdi.client.ui.FlowRowV
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.RDropdownMenuItem
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.Space8w
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.openFolder
import calebxzau.rdi.client.ui.themeNow
import calebxzau.rdi.client.codeeditor.CodeEditorValidation
import calebxzau.rdi.client.codeeditor.CodeLanguage
import calebxzau.rdi.client.codeeditor.validateCodeContent
import calebxzhou.rdi.client.ui.comp.CodeEditor
import calebxzhou.rdi.client.ui.comp.ModpackManageCard
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.common.isExcludedConfigPath
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Host
import calebxzau.rdi.client.lgr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

private sealed interface InstalledResourceItem {
    val key: String

    data class Legacy(val pack: ModpackLocalDir) : InstalledResourceItem {
        override val key: String = "legacy:${pack.versionId}"
    }

}

private data class PersonalDataCopyEntry(
    val key: String,
    val label: String,
    val path: String,
    val isDirectory: Boolean = true,
)

private val personalDataCopyEntries = listOf(
    PersonalDataCopyEntry("saves", "单机存档", "saves"),
    PersonalDataCopyEntry("resourcepacks", "资源包", "resourcepacks"),
    PersonalDataCopyEntry("shaderpacks", "光影包", "shaderpacks"),
    PersonalDataCopyEntry("schematics", "投影蓝图", "schematics"),
    PersonalDataCopyEntry("waypoints", "旅行地图坐标点", "waypoints"),
    PersonalDataCopyEntry("xaero", "Xaero小地图坐标点", "xaero"),
    PersonalDataCopyEntry("options", "键位画质设置", "options.txt", isDirectory = false),
)

private const val MAX_LOCAL_CONFIG_FILE_BYTES = 2L * 1024 * 1024
private val localConfigEditableExtensions = setOf(
    "cfg", "conf", "ini", "json", "json5", "js", "lang", "list", "properties",
    "snbt", "toml", "txt", "yaml", "yml", "zs",
)

/**
 * The installed-pack entry point for the released legacy modpack workflow.
 */
@Composable
fun ModpackLocalListScreen(
    onBack: () -> Unit = {},
    onOpenPlaza: () -> Unit = {},
    onOpenTask: (String) -> Unit = {},
    onOpenPlay: (McPlayArgs) -> Unit = {},
    onOpenContent: (ModpackLocalDir, ModpackContentType) -> Unit = { _, _ -> },
    onOpenOptions: (ModpackLocalDir) -> Unit = {},
    onOpenLocalInstall: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var legacyLoading by remember { mutableStateOf(true) }
    var legacyError by remember { mutableStateOf<String?>(null) }
    var legacyPacks by remember { mutableStateOf<List<ModpackLocalDir>>(emptyList()) }
    var reloadToken by remember { mutableStateOf(0) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var importPreparing by remember { mutableStateOf(false) }
    var packActionMessage by remember { mutableStateOf<String?>(null) }
    var deleteConfirmPack by remember { mutableStateOf<ModpackLocalDir?>(null) }
    var reinstallConfirmPack by remember { mutableStateOf<ModpackLocalDir?>(null) }
    var copyDataSourcePack by remember { mutableStateOf<ModpackLocalDir?>(null) }
    var copyDataTargetVersionId by remember { mutableStateOf<String?>(null) }
    var copyDataSelectedKeys by remember { mutableStateOf(personalDataCopyEntries.map { it.key }.toSet()) }
    var configEditorPack by remember { mutableStateOf<ModpackLocalDir?>(null) }
    var localConfigFilesLoading by remember { mutableStateOf(false) }
    var localConfigContentLoading by remember { mutableStateOf(false) }
    var localConfigSaving by remember { mutableStateOf(false) }
    var localConfigFiles by remember { mutableStateOf<List<Host.ConfigFileEntry>>(emptyList()) }
    var selectedLocalConfigPath by remember { mutableStateOf<String?>(null) }
    var localConfigEditorText by remember { mutableStateOf("") }
    var localConfigOriginalText by remember { mutableStateOf("") }
    var localConfigSyntaxErrorMessage by remember { mutableStateOf<String?>(null) }
    var localConfigStatusMessage by remember { mutableStateOf<String?>(null) }
    val localConfigDirty = selectedLocalConfigPath != null && localConfigEditorText != localConfigOriginalText

    fun copyPersonalData(sourceDir: File, targetDir: File, selectedKeys: Set<String>) {
        personalDataCopyEntries.filter { it.key in selectedKeys }.forEach { entry ->
            val sourceChild = sourceDir.resolve(entry.path)
            if (!sourceChild.exists()) return@forEach
            val targetChild = targetDir.resolve(entry.path)
            if (entry.isDirectory) {
                sourceChild.copyRecursively(targetChild, overwrite = true)
            } else {
                targetChild.parentFile?.mkdirs()
                sourceChild.copyTo(targetChild, overwrite = true)
            }
        }
    }

    fun resetCopyDialog() {
        copyDataSourcePack = null
        copyDataTargetVersionId = null
        copyDataSelectedKeys = personalDataCopyEntries.map { it.key }.toSet()
    }

    fun resetLocalConfigEditorState(clearPack: Boolean = false) {
        if (clearPack) configEditorPack = null
        localConfigFilesLoading = false
        localConfigContentLoading = false
        localConfigSaving = false
        localConfigFiles = emptyList()
        selectedLocalConfigPath = null
        localConfigEditorText = ""
        localConfigOriginalText = ""
        localConfigSyntaxErrorMessage = null
        localConfigStatusMessage = null
    }

    fun loadLocalConfigFile(pack: ModpackLocalDir, relativePath: String) {
        val versionId = pack.versionId
        localConfigContentLoading = true
        localConfigStatusMessage = "正在读取 $relativePath"
        localConfigSyntaxErrorMessage = null
        selectedLocalConfigPath = relativePath
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { resolveLocalConfigFile(pack.dir, relativePath).readText() }
            }
            if (configEditorPack?.versionId != versionId) return@launch
            result.onSuccess { content ->
                selectedLocalConfigPath = relativePath
                localConfigEditorText = content
                localConfigOriginalText = content
                localConfigSyntaxErrorMessage = validateCodeContent(
                    text = content,
                    language = CodeLanguage.fromPath(relativePath),
                )?.takeIf { !it.isValid }?.message
                localConfigStatusMessage = "已打开 $relativePath"
            }.onFailure { actionError = it.message ?: "读取配置文件失败" }
            localConfigContentLoading = false
        }
    }

    fun loadLocalConfigFiles(pack: ModpackLocalDir, preferredPath: String? = selectedLocalConfigPath) {
        val versionId = pack.versionId
        configEditorPack = pack
        localConfigFilesLoading = true
        localConfigStatusMessage = "正在扫描配置文件..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { collectLocalConfigFiles(pack.dir) }
            }
            if (configEditorPack?.versionId != versionId) return@launch
            result.onSuccess { files ->
                localConfigFiles = files
                if (files.isEmpty()) {
                    selectedLocalConfigPath = null
                    localConfigEditorText = ""
                    localConfigOriginalText = ""
                    localConfigSyntaxErrorMessage = null
                    localConfigStatusMessage = "当前整合包没有可编辑配置文件"
                } else when {
                    preferredPath != null && files.any { it.path == preferredPath } && selectedLocalConfigPath == null ->
                        loadLocalConfigFile(pack, preferredPath)

                    selectedLocalConfigPath == null -> loadLocalConfigFile(pack, files.first().path)
                    selectedLocalConfigPath != null && files.none { it.path == selectedLocalConfigPath } -> {
                        if (!localConfigDirty) loadLocalConfigFile(pack, files.first().path)
                        else localConfigStatusMessage = "当前文件已不在配置列表中，请先保存或还原内容"
                    }

                    else -> localConfigStatusMessage = "已加载${files.size}个配置文件"
                }
            }.onFailure {
                actionError = it.message ?: "加载配置文件列表失败"
                localConfigStatusMessage = null
            }
            localConfigFilesLoading = false
        }
    }

    fun saveLocalConfigFile(pack: ModpackLocalDir) {
        val relativePath = selectedLocalConfigPath ?: run {
            actionError = "请先选择配置文件"
            return
        }
        val versionId = pack.versionId
        localConfigSaving = true
        localConfigStatusMessage = "正在保存 $relativePath"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = resolveLocalConfigFile(pack.dir, relativePath)
                    file.writeText(localConfigEditorText)
                    Host.ConfigFileEntry(relativePath, file.length(), file.lastModified())
                }
            }
            if (configEditorPack?.versionId != versionId) return@launch
            result.onSuccess { saved ->
                localConfigOriginalText = localConfigEditorText
                localConfigSyntaxErrorMessage = null
                localConfigStatusMessage = "已保存 ${saved.path}"
                localConfigFiles = (localConfigFiles.filterNot { it.path == saved.path } + saved)
                    .sortedBy { it.path.lowercase() }
            }.onFailure { actionError = it.message ?: "保存配置文件失败" }
            localConfigSaving = false
        }
    }

    fun reloadLegacy() {
        legacyLoading = true
        legacyError = null
        reloadToken += 1
    }

    LaunchedEffect(reloadToken) {
        val result = withContext(Dispatchers.IO) {
            runCatching { ModpackService.getLocalPackDirs() }
        }
        result.onSuccess { legacyPacks = it }
            .onFailure { legacyError = it.message ?: "读取已安装整合包失败" }
        legacyLoading = false
    }

    val items = remember(legacyPacks) { legacyPacks.map(InstalledResourceItem::Legacy) }
    val isLoading = legacyLoading
    val errorMessage = listOf(legacyError, actionError)
        .filterNotNull()
        .filter(String::isNotBlank)
        .joinToString("\n")
        .takeIf(String::isNotBlank)

    errorMessage?.let {
        AlertErr(it) {
            legacyError = null
            actionError = null
        }
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.LARGE) {
            TitleRow("我的整合包", onBack) {
                Text("点包管理mod/材质/光影等")
                CircleIconButton("\uF021", "刷新", showText = false) {
                    reloadLegacy()
                }
                CircleIconButton(
                    icon = "\uDB86\uDDD5",
                    label = "整合广场",
                    onClick = onOpenPlaza,
                )
            }
            ContentBody {
                Column(modifier = modifier.fillMaxSize().padding(16.dp)) {

                    Column(Modifier.fillMaxWidth().weight(1f)) {
                        packActionMessage?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        if (isLoading) {
                            Text("正在读取整合包…")
                        } else if (items.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text("还没有整合包")
                            }
                        } else {
                            FlowRowV(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items.forEach { item ->
                                    Box(
                                        modifier = Modifier
                                            .width(IntrinsicSize.Max)
                                            .widthIn(max = 350.dp),
                                    ) {
                                        when (item) {
                                            is InstalledResourceItem.Legacy -> InstalledCard(
                                                pack = item.pack,
                                                onOpenPlay = {
                                                    onOpenPlay(item.pack.toPlayArgs())
                                                },
                                                isRunning = McPlayStore.aliveCount(item.pack.versionId) > 0,
                                                onOpenContent = { type -> onOpenContent(item.pack, type) },
                                                onOpenConfig = {
                                                    resetLocalConfigEditorState()
                                                    configEditorPack = item.pack
                                                    loadLocalConfigFiles(item.pack)
                                                },
                                                onOpenOptions = { onOpenOptions(item.pack) },
                                                onOpenCopyData = {
                                                    copyDataSourcePack = item.pack
                                                    copyDataTargetVersionId = legacyPacks.firstOrNull {
                                                        it.versionId != item.pack.versionId
                                                    }?.versionId
                                                    copyDataSelectedKeys = personalDataCopyEntries.map { it.key }.toSet()
                                                },
                                                onOpenFolder = {
                                                    openPackFolder(item.pack.dir, actionErrorSetter = { actionError = it })
                                                },
                                                onReinstall = { reinstallConfirmPack = item.pack },
                                                onExport = {
                                                    scope.launch {
                                                        runCatching {
                                                            val prepared = withContext(Dispatchers.IO) {
                                                                exportRdiModpack2(item.pack)
                                                            }.getOrThrow() ?: return@runCatching
                                                            onOpenTask(ClientTaskManager.submit(prepared.task, prepared.dedupeKey))
                                                        }.onFailure { actionError = it.message ?: "导出失败" }
                                                    }
                                                },
                                                onExportLogs = {
                                                    scope.launch {
                                                        exportLogsPack(item.pack)
                                                            .onFailure { actionError = it.message ?: "导出日志失败" }
                                                    }
                                                },
                                                onDelete = { deleteConfirmPack = item.pack },
                                            )

                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        CircleIconButton(
                            icon = "\uF019",
                            label = "导入rdipack2",
                            enabled = !importPreparing,
                            onClick = {
                                if (!importPreparing) {
                                    importPreparing = true
                                    actionError = null
                                    scope.launch {
                                        try {
                                            val prepared = prepareRdiPack2ImportFromPicker() ?: return@launch
                                            val runId = ClientTaskManager.submit(prepared.task, prepared.dedupeKey)
                                            onOpenTask(runId)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (exception: Exception) {
                                            lgr.error(exception) { "导入rdipack2失败" }
                                            actionError = exception.message ?: "导入整合包失败"
                                        } finally {
                                            importPreparing = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    /* reinstallRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { reinstallRecord = null },
            title = { Text("确认重装") },
            text = { Text("将重新下载并覆盖${record.name}的客户端文件。") },
            confirmButton = {
                TextButton(onClick = {
                    reinstallRecord = null
                    onOpenTask(viewModel.installTask(record, reinstall = true))
                }) { Text("确认重装") }
            },
            dismissButton = { TextButton(onClick = { reinstallRecord = null }) { Text("取消") } },
        )
    }
    deleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteRecord = null },
            title = { Text("删除${record.name}？") },
            text = { Text("这会删除本地整合包及其记录，不能撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteRecord = null
                    viewModel.delete(record)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteRecord = null }) { Text("取消") } },
        )
    }
    */
    configEditorPack?.let { pack ->
        Dialog(
            onDismissRequest = { resetLocalConfigEditorState(clearPack = true) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier.widthIn(min = 260.dp, max = 360.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("配置文件", style = MaterialTheme.typography.titleMedium)
                        if (localConfigFilesLoading) {
                            CircularProgressIndicator()
                        } else if (localConfigFiles.isEmpty()) {
                            Text(
                                localConfigStatusMessage ?: "当前整合包没有可编辑配置文件",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                items(localConfigFiles, key = { it.path }) { entry ->
                                    TextButton(
                                        onClick = {
                                            if (localConfigDirty && entry.path != selectedLocalConfigPath) {
                                                actionError = "当前配置文件有未保存修改，请先保存或还原"
                                            } else {
                                                loadLocalConfigFile(pack, entry.path)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            entry.path,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TitleRow(
                            title = "配置文件 ${pack.name} ${pack.verName}",
                            onBack = { resetLocalConfigEditorState(clearPack = true) },
                        ) {
                            Text(
                                text = when {
                                    localConfigSaving -> "保存中..."
                                    localConfigContentLoading -> "读取中..."
                                    localConfigSyntaxErrorMessage != null -> localConfigSyntaxErrorMessage.orEmpty()
                                    localConfigDirty -> "有未保存修改"
                                    else -> localConfigStatusMessage.orEmpty()
                                },
                                color = when {
                                    localConfigSyntaxErrorMessage != null || localConfigDirty ->
                                        MaterialTheme.colorScheme.error

                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 360.dp),
                            )
                            Space8w()
                            CircleIconButton(
                                icon = "\uDB81\uDC50",
                                tooltip = "还原",
                                enabled = selectedLocalConfigPath != null && !localConfigContentLoading && !localConfigSaving,
                                showText = false,
                            ) {
                                selectedLocalConfigPath?.let { loadLocalConfigFile(pack, it) }
                            }
                            Space8w()
                            CircleIconButton(
                                icon = "\uF0C7",
                                tooltip = "保存",
                                enabled = selectedLocalConfigPath != null && localConfigDirty &&
                                        !localConfigContentLoading && !localConfigSaving &&
                                        localConfigSyntaxErrorMessage == null,
                                showText = false,
                                bgColor = themeNow.primary,
                            ) {
                                saveLocalConfigFile(pack)
                            }
                        }

                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 4.dp),
                        ) {
                            when {
                                localConfigContentLoading -> Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }

                                selectedLocalConfigPath == null -> Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) { Text("请选择左侧配置文件", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                                else -> Box(
                                    modifier = Modifier.fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainerLowest,
                                            MaterialTheme.shapes.medium,
                                        )
                                        .padding(1.dp),
                                ) {
                                    CodeEditor(
                                        text = localConfigEditorText,
                                        enabled = !localConfigSaving,
                                        language = CodeLanguage.fromPath(selectedLocalConfigPath),
                                        modifier = Modifier.fillMaxSize(),
                                        onValueChange = { text ->
                                            localConfigEditorText = text
                                            localConfigSyntaxErrorMessage = validateCodeContent(
                                                text = text,
                                                language = CodeLanguage.fromPath(selectedLocalConfigPath),
                                            )?.takeIf { validation -> !validation.isValid }?.message
                                        },
                                        onValidationChange = { validation: CodeEditorValidation? ->
                                            localConfigSyntaxErrorMessage = validation
                                                ?.takeIf { !it.isValid }?.message
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    deleteConfirmPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { deleteConfirmPack = null },
            title = { Text("确认删除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "确认删除整合包${pack.name} ${pack.verName}吗？\n" +
                                "整个本地副本都会删除，截图、单机存档等数据将无法恢复，请先备份",
                    )
                    if (McPlayStore.aliveCount(pack.versionId) > 0) {
                        Text("整合包正在运行，结束游戏后才能删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmPack = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(
                    enabled = McPlayStore.aliveCount(pack.versionId) == 0,
                    onClick = {
                        deleteConfirmPack = null
                        scope.launch {
                            ModpackService.deleteLocalPack(pack)
                                .onFailure { actionError = "删除失败: ${it.message}" }
                                .onSuccess { reloadLegacy() }
                        }
                    },
                ) { Text("确认") }
            },
        )
    }

    reinstallConfirmPack?.let { pack ->
        AlertDialog(
            onDismissRequest = { reinstallConfirmPack = null },
            title = { Text("确认重装") },
            text = {
                Text(
                    "将重装${pack.name} ${pack.verName}。\n" +
                            "重装会清空该整合包的所有个人数据\n" +
                            "包括：单机存档、小地图坐标点、资源包、光影包和日志等\n" +
                            "确定继续吗？",
                )
            },
            dismissButton = {
                TextButton(onClick = { reinstallConfirmPack = null }) { Text("取消") }
            },
            confirmButton = {
                TextButton(onClick = {
                    reinstallConfirmPack = null
                    scope.launch {
                        reinstallLegacyPack(pack, onOpenTask) { message -> actionError = message }
                    }
                }) { Text("确认重装") }
            },
        )
    }

    copyDataSourcePack?.let { sourcePack ->
        val targetCandidates = legacyPacks.filter { it.versionId != sourcePack.versionId }
        val selectedTarget = targetCandidates.firstOrNull { it.versionId == copyDataTargetVersionId }
        Dialog(
            onDismissRequest = ::resetCopyDialog,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.75f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text("迁移个人数据", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("把个人数据复制到另一个整合包，请先勾选要复制的内容，再选择目标整合包。")
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        personalDataCopyEntries.forEach { entry ->
                            val checked = entry.key in copyDataSelectedKeys
                            Surface(
                                modifier = Modifier.clickable {
                                    copyDataSelectedKeys = if (checked) {
                                        copyDataSelectedKeys - entry.key
                                    } else {
                                        copyDataSelectedKeys + entry.key
                                    }
                                },
                                shape = MaterialTheme.shapes.medium,
                                color = if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    width = if (checked) 2.dp else 1.dp,
                                    color = if (checked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                ),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = { selected ->
                                            copyDataSelectedKeys = if (selected) {
                                                copyDataSelectedKeys + entry.key
                                            } else {
                                                copyDataSelectedKeys - entry.key
                                            }
                                        },
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(entry.label)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("来源：${sourcePack.name} ${sourcePack.verName}", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    ) {
                        if (targetCandidates.isEmpty()) {
                            Text("没有可复制的目标整合包")
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                targetCandidates.forEach { target ->
                                    val selected = copyDataTargetVersionId == target.versionId
                                    Surface(
                                        modifier = Modifier.widthIn(min = 180.dp, max = 220.dp).clickable {
                                            copyDataTargetVersionId = target.versionId
                                        },
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                        else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            RadioButton(
                                                selected = selected,
                                                onClick = { copyDataTargetVersionId = target.versionId },
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    target.name.ifBlank { "未命名整合包" },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontWeight = FontWeight.Medium,
                                                    fontSize = TextUnit(14f, TextUnitType.Sp),
                                                )
                                                Text(
                                                    target.verName,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = ::resetCopyDialog) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = selectedTarget != null && copyDataSelectedKeys.isNotEmpty(),
                            onClick = {
                                val targetPack = selectedTarget ?: return@TextButton
                                val selectedKeys = copyDataSelectedKeys
                                resetCopyDialog()
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { copyPersonalData(sourcePack.dir, targetPack.dir, selectedKeys) }
                                    }
                                    result.onSuccess {
                                        actionError = null
                                        packActionMessage = "已迁移个人数据到${targetPack.name} ${targetPack.verName}"
                                    }.onFailure {
                                        actionError = "迁移个人数据失败: ${it.message}"
                                    }
                                }
                            },
                        ) { Text("复制") }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledCard(
    pack: ModpackLocalDir,
    onOpenPlay: () -> Unit,
    isRunning: Boolean,
    onOpenContent: (ModpackContentType) -> Unit,
    onOpenConfig: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenCopyData: () -> Unit,
    onReinstall: () -> Unit,
    onOpenFolder: () -> Unit,
    onExport: () -> Unit,
    onExportLogs: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember(pack.versionId) { mutableStateOf(false) }
    CursorPositionBox(
        modifier = Modifier.widthIn(max = 350.dp),
        onSecondaryPress = { expanded = true },
        cursorContent = {

            DropdownMenu(expanded, { expanded = false }) {
                RDropdownMenuItem("单机", "\uEB9B", onClick = {
                    expanded = false
                    onOpenPlay()
                })
                ModpackContentType.entries.forEach { contentType ->
                    RDropdownMenuItem(contentType.label, contentType.icon, onClick = {
                        expanded = false
                        onOpenContent(contentType)
                    })
                }
                HorizontalDivider()
                RDropdownMenuItem("配置", "\uDB85\uDF81", onClick = {
                    expanded = false
                    onOpenConfig()
                })
                RDropdownMenuItem("高级", "\uEDD3", onClick = {
                    expanded = false
                    onOpenOptions()
                })
                RDropdownMenuItem("迁移", "\uE8C8", enabled = !isRunning, onClick = {
                    expanded = false
                    onOpenCopyData()
                })
                RDropdownMenuItem("目录", "\uEAED", onClick = {
                    expanded = false
                    onOpenFolder()
                })
                if (pack.vo != null) {
                    RDropdownMenuItem("重装", "\uDB81\uDC53", onClick = {
                        expanded = false
                        onReinstall()
                    })
                    RDropdownMenuItem("分享", "\uDB82\uDD5E", onClick = {
                        expanded = false
                        onExport()
                    })
                }
                RDropdownMenuItem("日志", "\uEF11", onClick = {
                    expanded = false
                    onExportLogs()
                })
                HorizontalDivider()
                RDropdownMenuItem("删除", "\uEA81", enabled = !isRunning, danger = true, onClick = {
                    expanded = false
                    onDelete()
                })
            }
        },
    ) {
        ModpackManageCard(
            modifier = Modifier.fillMaxWidth(),
            packdir = pack,
            isRunning = isRunning,
            onClick = { expanded = true },
        )
    }
}

private fun ModpackLocalDir.toPlayArgs(): McPlayArgs = McPlayArgs(
    title = "单机 - ${vo?.name.orEmpty()}",
    mcVer = requireNotNull(vo).mcVer,
    modLoader = requireNotNull(vo).modloader,
    versionId = versionId,
    playArg = "${server.hqUrl}\n127.0.0.1:55667\ntest\n55555\n${loggedAccount.uuid}\n${loggedAccount.name}",
    modpackName = vo?.name.orEmpty(),
    versionDir = dir.absolutePath,
    modpackIconUrl = iconUrl,
    versionName = verName,
)

/* @Composable
private fun UnlinkedModpackCard(
    directory: Path,
    onOpenFolder: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("未关联本地数据", style = MaterialTheme.typography.titleMedium)
            Text(
                directory.fileName?.toString() ?: directory.toString(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "这是可恢复的未关联本地数据，不能直接上传；如需发布，请重新选择CurseForge或Modrinth安装包。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenFolder) { Text("打开目录") }
            }
        }
    }
}

@Composable
private fun Modpack2InstalledCard(
    record: LocalModpack2InstanceRecord,
    isRunning: Boolean,
    onOpenPlay: () -> Unit,
    onReinstall: () -> Unit,
    onOpenFolder: () -> Unit,
    onCreateHost: (() -> Unit)?,
    onDelete: () -> Unit,
    onOpenContent: () -> Unit,
) {
    var expanded by remember(record.versionId) { mutableStateOf(false) }
    fun closeAnd(action: () -> Unit): () -> Unit = {
        expanded = false
        action()
    }

    val versionLabel = "MC${record.mcVersion.mcVer} ${record.modLoader.name}"
    CursorPositionBox(
        modifier = Modifier.widthIn(max = 350.dp),
        onSecondaryPress = { expanded = true },
        cursorContent = {
            DropdownMenu(expanded, { expanded = false }) {
                RDropdownMenuItem("游玩", "\uEB9B", closeAnd(onOpenPlay))
                RDropdownMenuItem("模组·材质·光影", "\uF1B2", closeAnd(onOpenContent))
                onCreateHost?.let { createHost ->
                    RDropdownMenuItem("创建多人房间", "\uF04B", closeAnd(createHost))
                }
                HorizontalDivider()
                RDropdownMenuItem("目录", "\uEAED", closeAnd(onOpenFolder))
                RDropdownMenuItem("重装", "\uF021", closeAnd(onReinstall))
                HorizontalDivider()
                RDropdownMenuItem("删除", "\uEA81", closeAnd(onDelete), enabled = !isRunning, danger = true)
            }
        },
    ) {
        ModpackManageCard(
            modifier = Modifier.fillMaxWidth(),
            presentation = ModpackManageCardPresentation(
                name = record.name,
                versionLabel = versionLabel,
                iconUrl = record.iconUrl,
            ),
            isRunning = isRunning,
            onClick = { expanded = true },
        )
    }
}

private fun LocalModpack2InstanceRecord.toPlayArgs(): McPlayArgs = McPlayArgs(
    title = "单机 - $name",
    mcVer = mcVersion,
    modLoader = modLoader,
    versionId = versionId.toString(),
    playArg = "${server.hqUrl}\n127.0.0.1:55667\ntest\n55555\n${loggedAccount.uuid}\n${loggedAccount.name}",
    modpackName = name,
    versionDir = ClientDirs.versionsDir.resolve(versionId.toString()).absolutePath,
)

private fun calebxzhou.rdi.common.model.McVersion.supportsHost2Loader(
    loader: calebxzhou.rdi.common.model.ModLoader,
): Boolean = (this == calebxzhou.rdi.common.model.McVersion.V201 &&
    loader == calebxzhou.rdi.common.model.ModLoader.forge) ||
    (this == calebxzhou.rdi.common.model.McVersion.V211 &&
        loader == calebxzhou.rdi.common.model.ModLoader.neoforge)
*/

private fun openPackFolder(packDir: java.io.File, actionErrorSetter: (String) -> Unit) {
    if (!packDir.exists()) {
        actionErrorSetter("目录不存在: ${packDir.absolutePath}")
        return
    }
    runCatching { openFolder(packDir.absolutePath) }
        .onFailure { actionErrorSetter("无法打开目录: ${it.message}") }
}

private suspend fun reinstallLegacyPack(
    pack: ModpackLocalDir,
    onOpenTask: (String) -> Unit,
    actionErrorSetter: (String) -> Unit,
) {
    val brief = pack.vo
    if (brief == null) {
        actionErrorSetter("整合包信息已失效")
        return
    }
    runCatching {
        val version = server.makeRequest<Modpack.Version>(
            "modpack/${brief.id}/version/${pack.verName}"
        ).data ?: error("未找到对应版本信息")
        ClientTaskManager.submit(
            version.startInstallTask2(brief.mcVer, brief.modloader, brief.name),
            dedupeKey = modpackInstallTaskKey(version.modpackId, version.name),
        )
    }.onSuccess(onOpenTask)
        .onFailure { actionErrorSetter(it.message ?: "重装失败") }
}

private fun collectLocalConfigFiles(packDir: File): List<Host.ConfigFileEntry> {
    val rootDir = packDir.resolve("config")
    if (!rootDir.exists() || !rootDir.isDirectory || Files.isSymbolicLink(rootDir.toPath())) {
        return emptyList()
    }
    return rootDir.walkTopDown()
        .onEnter { dir -> !Files.isSymbolicLink(dir.toPath()) }
        .filter { it.isEditableLocalConfigFile() }
        .mapNotNull { file ->
            val relativePath = file.relativeTo(rootDir).path.replace('\\', '/')
            if (relativePath.isExcludedConfigPath()) {
                null
            } else {
                Host.ConfigFileEntry(
                    path = relativePath,
                    size = file.length(),
                    updateTime = file.lastModified(),
                )
            }
        }
        .sortedBy { it.path.lowercase() }
        .toList()
}

private fun resolveLocalConfigFile(packDir: File, relativePath: String): File {
    val normalizedRelative = relativePath.replace('\\', '/').trimStart('/')
    val canonicalBase = packDir.resolve("config").canonicalFile
    val resolved = canonicalBase.resolve(normalizedRelative).canonicalFile
    val basePath = canonicalBase.path
    check(resolved.path == basePath || resolved.path.startsWith(basePath + File.separator)) {
        "非法配置文件路径: $relativePath"
    }
    return resolved
}

private fun File.isEditableLocalConfigFile(): Boolean =
    exists() && isFile && length() <= MAX_LOCAL_CONFIG_FILE_BYTES &&
            extension.lowercase() in localConfigEditableExtensions
