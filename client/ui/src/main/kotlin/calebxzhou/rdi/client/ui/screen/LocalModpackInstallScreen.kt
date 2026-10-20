package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.ContentBody
import calebxzau.rdi.client.ui.FlowRowV
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.RTextField
import calebxzau.rdi.client.ui.RVerticalScrollbar
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.SimpleTooltip
import calebxzau.rdi.client.ui.TitleRow
import calebxzau.rdi.client.ui.loadImageBitmap
import calebxzau.rdi.client.ui.pickLocalDirectory
import calebxzau.rdi.client.ui.pickLocalModpackFile
import calebxzau.rdi.client.ui.viewmodel.LocalModpackInstallEditorState
import calebxzau.rdi.client.ui.viewmodel.LocalModpackInstallViewModel
import calebxzau.rdi.client.ui.viewmodel.localModpackRuntimes
import calebxzhou.rdi.client.service.LocalModpackCandidate
import calebxzhou.rdi.client.service.LocalModpackInstallSource
import calebxzhou.rdi.client.service.ModpackArchiveEmbeddedMod
import calebxzhou.rdi.client.service.ModpackArchiveFile
import calebxzhou.rdi.client.service.ModpackArchiveReadProgress
import calebxzhou.rdi.client.service.toContentGridItems
import calebxzhou.rdi.client.ui.comp.ContentGrid
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.util.humanFileSize
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** Imports a pack into the launcher without uploading it to RDI: archive, game folder, or an empty pack. */
@Composable
fun LocalModpackInstallScreen(
    onBack: () -> Unit,
    onStarted: (String) -> Unit,
    viewModel: LocalModpackInstallViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val expandedDirectories = remember { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { onStarted(it.runId) }
    }

    MaxBox {
        val editor = state.editor
        if (editor == null) {
            ScreenContentSurface(size = ScreenContentSize.LARGE) {
                TitleRow("导入整合包", onBack)
                ContentBody(contentPadding = PaddingValues(0.dp)) {
                    val groups = state.candidates.groupBy { it.minecraftInstallation.toString() }
                        .entries.sortedBy { it.key.lowercase() }
                    Box(Modifier.fillMaxSize().padding(24.dp)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(key = "title") {
                                Text("此处装的包只保存在电脑本地 不会上传到RDI整合广场 开始MC时可选离线/正版登录")
                            }
                            item(key = "archive") {
                                FlowRowV(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("整合包安装包（CurseForge的zip或Modrinth的mrpack）")
                                    CircleIconButton("", "选取...", enabled = !state.archiveLoading) {
                                        scope.launch {
                                            pickLocalModpackFile()?.let { viewModel.openArchive(it.toPath()) }
                                        }
                                    }
                                    if (state.archiveLoading) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                            item(key = "directory") {
                                FlowRowV(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("其他启动器的mc文件夹（.minecraft或某个版本的文件夹）")
                                    CircleIconButton("", "选择文件夹", enabled = !state.directoryLoading) {
                                        scope.launch {
                                            pickLocalDirectory("选择整合包所在的文件夹")?.let {
                                                viewModel.openDirectory(it.toPath())
                                            }
                                        }
                                    }
                                    if (state.directoryLoading) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                            item(key = "empty") {
                                FlowRowV(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("创建空整合包")
                                    localModpackRuntimes.forEach { (version, loader) ->
                                        CandidateCard(
                                            name = "${version.mcVer} ${loader.label}",
                                            mcVersion = version,
                                            tooltip = "创建${version.mcVer} ${loader.label}的空整合包，之后自己往mods里放模组",
                                            onInstall = { viewModel.openCreate(version, loader) },
                                        )
                                    }
                                }
                            }
                            item(key = "existing-title") { Text("电脑里之前装过的包") }
                            when {
                                state.scanning -> item(key = "scanning") {
                                    CenterMessage { CircularProgressIndicator() }
                                }
                                state.candidates.isEmpty() -> item(key = "empty-candidates") {
                                    CenterMessage { Text("没有找到之前装过的1.20.1或1.21.1整合包，可以用上面的“选择文件夹”") }
                                }
                            }
                            groups.forEach { (directory, candidates) ->
                                val expanded = expandedDirectories[directory] ?: true
                                item(key = "directory:$directory") {
                                    GameDirectoryHeader(
                                        path = directory,
                                        candidateCount = candidates.size,
                                        expanded = expanded,
                                        onToggle = { expandedDirectories[directory] = !expanded },
                                    )
                                }
                                if (expanded) {
                                    item(key = "candidates:$directory") {
                                        CandidateCards(candidates, viewModel::openInstall)
                                    }
                                }
                            }
                        }
                        RVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        } else {
            LocalModpackInstallEditor(
                state = editor,
                viewModel = viewModel,
            )
        }

        if (state.pickedCandidates.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = viewModel::dismissPickedCandidates,
                title = { Text("选择要导入的版本") },
                text = { CandidateCards(state.pickedCandidates, viewModel::openInstall) },
                confirmButton = {},
                dismissButton = { TextButton(viewModel::dismissPickedCandidates) { Text("取消") } },
            )
        }

        if (editor?.confirmMissingFiles == true) {
            val missing = (editor.source as? LocalModpackInstallSource.Archive)?.preview?.unavailableFiles.orEmpty()
            AlertDialog(
                onDismissRequest = viewModel::dismissMissingFiles,
                title = { Text("有${missing.size}个文件无法自动下载") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("这些文件的作者不允许第三方下载，或者在CurseForge上找不到。继续导入将不包含它们，你可以之后自己放进整合包文件夹。")
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(missing, key = { it.key }) { file ->
                                Text(
                                    "· ${file.displayName}${if (file.required) "" else "（可选）"}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(viewModel::dismissMissingFiles) { Text("取消") } },
                confirmButton = { TextButton({ viewModel.submitInstall(missingFilesConfirmed = true) }) { Text("继续导入") } },
            )
        }

        (editor?.error ?: state.archiveError ?: state.scanError)?.let { error ->
            AlertDialog(
                onDismissRequest = viewModel::dismissError,
                title = { Text("发生错误") },
                text = { Text(error) },
                confirmButton = { TextButton(viewModel::dismissError) { Text("知道了") } },
            )
        }
    }
}

@Composable
private fun BoxScope.LocalModpackInstallEditor(
    state: LocalModpackInstallEditorState,
    viewModel: LocalModpackInstallViewModel,
) {
    val creating = state.source == LocalModpackInstallSource.Empty
    val archive = state.source as? LocalModpackInstallSource.Archive
    val directory = state.source as? LocalModpackInstallSource.CopyFrom
    ScreenContentSurface(size = ScreenContentSize.LARGE) {
        TitleRow(if (creating) "创建整合包" else "导入整合包", viewModel::closeEditor) {
            if (state.submitting) {
                CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            }
            if (archive != null) {
                val preview = archive.preview
                val selectedSize = preview.requiredFiles.sumOf(ModpackArchiveFile::size) +
                    preview.optionalFiles.filter { it.key in archive.selectedOptionalFiles }.sumOf(ModpackArchiveFile::size) +
                    preview.embeddedMods.sumOf(ModpackArchiveEmbeddedMod::size)
                Text(selectedSize.humanFileSize)
            }
            CircleIconButton(
                "󰩐",
                if (creating) "创建" else "导入",
                enabled = state.canSubmit,
            ) { viewModel.submitInstall() }
        }
        ContentBody(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FlowRowV(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RTextField(
                    label = "包名",
                    value = state.name,
                    enabled = !state.submitting,
                    modifier = Modifier.weight(1f),
                    onValueChange = viewModel::updateName,
                )
                RTextField(
                    label = "简介",
                    value = state.comment,
                    enabled = !state.submitting,
                    modifier = Modifier.weight(1f),
                    onValueChange = viewModel::updateComment,
                )
                RTextField(
                    label = "图标链接 可选",
                    value = state.iconUrl,
                    enabled = !state.submitting,
                    modifier = Modifier.weight(1f),
                    onValueChange = viewModel::updateIconUrl,
                )
            }
            if (state.versionSelectable) {
                Text("没有识别出游戏版本，请选择这个整合包对应的版本：")
                FlowRowV(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    localModpackRuntimes.forEach { (version, loader) ->
                        FilterChip(
                            selected = state.mcVersion == version && state.modLoader == loader,
                            onClick = { viewModel.updateRuntime(version, loader) },
                            enabled = !state.submitting,
                            label = { Text("${version.mcVer} ${loader.label}") },
                        )
                    }
                }
            } else {
                Text("${state.mcVersion.mcVer} ${state.modLoader.label}", style = MaterialTheme.typography.labelLarge)
            }
            if (directory != null) {
                Text(
                    "从${directory.directory}复制模组、配置、资源包等文件",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = directory.includeSaves,
                        onCheckedChange = { viewModel.toggleIncludeSaves() },
                        enabled = !state.submitting,
                    )
                    Text("同时导入单机存档")
                }
            }
            state.archiveProgress?.let { progress -> ArchiveLoadProgress(progress) }
            if (archive != null) {
                val preview = archive.preview
                if (preview.unavailableFiles.isNotEmpty()) {
                    Text(
                        "有${preview.unavailableFiles.size}个文件无法自动下载，导入时会列出",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val gridItems = remember(preview, state.resolvedMods, archive.selectedOptionalFiles) {
                    preview.toContentGridItems(state.resolvedMods, archive.selectedOptionalFiles)
                }
                ContentGrid(
                    items = gridItems,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSelectionChange = { item, _ ->
                        if (!state.submitting && !item.required) viewModel.toggleOptionalFile(item.key)
                    },
                    stateKey = preview.archive.toString(),
                    isResolving = state.contentResolving,
                )
            }
        }
    }
}

private val ModLoader.label: String
    get() = when (this) {
        ModLoader.forge -> "Forge"
        ModLoader.neoforge -> "NeoForge"
        ModLoader.Fabric -> "Fabric"
    }

@Composable
private fun ArchiveLoadProgress(progress: ModpackArchiveReadProgress) {
    val fraction = progress.fraction
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
        Text(
            buildString {
                append(progress.stage.displayName)
                if (progress.completed != null && progress.total != null) {
                    append(" ${progress.completed}/${progress.total}")
                }
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CandidateCards(candidates: List<LocalModpackCandidate>, onInstall: (LocalModpackCandidate) -> Unit) {
    FlowRowV(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        candidates.forEach { candidate ->
            CandidateCard(
                name = candidate.name,
                mcVersion = candidate.mcVersion,
                tooltip = "${candidate.mcVersion.mcVer} ${candidate.modLoader.label} 点击导入",
                onInstall = { onInstall(candidate) },
            )
        }
    }
}

@Composable
private fun CandidateCard(
    name: String,
    mcVersion: McVersion,
    tooltip: String,
    onInstall: () -> Unit,
) {
    val icon = remember(mcVersion) {
        loadImageBitmap(mcVersion.icon)
            .onFailure { lgr.warn(it) { "加载版本图标失败：${mcVersion.icon}" } }
            .getOrNull()
    }
    SimpleTooltip(tooltip) {
        Card(onClick = onInstall, modifier = Modifier.widthIn(max = 260.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let { Image(it, null, Modifier.size(24.dp)) }
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun GameDirectoryHeader(
    path: String,
    candidateCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Card(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "" else "", style = MaterialTheme.typography.titleMedium)
            Text(
                "${candidateCount}个包 · $path",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
