package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import calebxzau.rdi.client.modcatalog.CatalogFile
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.ui.*
import calebxzau.rdi.client.ui.viewmodel.*
import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.client.ui.comp.CatalogModCard
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ModCatalogInfoScreen(
    route: ModCatalogInfoRoute,
    onBack: () -> Unit,
    onOpenDependencyMod: (CatalogMod) -> Unit,
    onOpenTaskList: ((String) -> Unit)? = null,
    onTargetUnavailable: () -> Unit = onBack,
    viewModel: ModCatalogInfoViewModel = koinViewModel(
        key = modCatalogInfoViewModelKey(route)
    ) {
        parametersOf(route)
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ModCatalogInfoEvent.TargetUnavailable -> onTargetUnavailable()
                is ModCatalogInfoEvent.ShowSnackbar -> {
                    val result = snackbar.showSnackbar(
                        message = event.message,
                        actionLabel = event.runId?.let { "查看任务" },
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed && event.runId != null) {
                        onOpenTaskList?.invoke(event.runId)
                    }
                }
            }
        }
    }

    uiState.errorMessage?.let { message ->
        AlertErr(message) { viewModel.clearError() }
    }

    MaxBox {
        ScreenContentSurface(size = ScreenContentSize.LARGE) {
            when {
                uiState.loading -> MainColumn { CircularProgressIndicator() }
                uiState.errorMessage != null && uiState.mod == null -> MainColumn {
                    TitleRow("模组详情", onBack)
                }

                uiState.mod != null -> ModCatalogInfoContent(
                    state = uiState,
                    viewModel = viewModel,
                    onBack = onBack,
                    onOpenDependencyMod = onOpenDependencyMod,
                )
            }
        }
        BottomSnakebarM3(snackbar)
    }

    val selection = uiState.downloadSelection
    val dialog = uiState.downloadDialog
    if (selection != null && dialog != null) {
        CatalogDownloadDialog(
            fileName = selection.resolved.fileName,
            state = dialog,
            onDismiss = viewModel::dismissDownloadDialog,
            onSelectTarget = viewModel::selectDownloadTarget,
            onSubmit = viewModel::submitDownload,
            onOpenDependencyMod = onOpenDependencyMod,
        )
    }
}

private fun modCatalogInfoViewModelKey(route: ModCatalogInfoRoute): String =
    "${route.platform}:${route.projectId}:${route.requiredMcVer}:${route.requiredLoader}:${route.targetLocalVersionId}:${route.targetLocalKind}:${route.targetLocalId}:${route.targetHostId}:${route.fromAllHosts}:${route.fromHostMods}"

@Composable
private fun ModCatalogInfoContent(
    state: ModCatalogInfoUiState,
    viewModel: ModCatalogInfoViewModel,
    onBack: () -> Unit,
    onOpenDependencyMod: (CatalogMod) -> Unit,
) {
    val mod = state.mod ?: return
    val mcmodUrl = mod.mcmodId?.let { "https://www.mcmod.cn/class/${it}.html" }
    val title = mod.nameCn ?: mod.name
    val latestFile = state.files.maxByOrNull { it.publishedAt }
    val olderFiles = state.files.filterNot { it.ref == latestFile?.ref }
    val gridState = rememberLazyGridState()
    var showOtherVersions by remember { mutableStateOf(false) }

    TitleRow(title, onBack) {
        if (state.filesLoading || state.resolvingDownload) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        Switch(
            checked = state.includeAlpha,
            onCheckedChange = viewModel::setIncludeAlpha,
        )
        Text("显示内测版")
        mcmodUrl?.let { url ->
            CircleIconButton(
                icon = "\uF02D",
                label = "打开百科",
                onClick = { openUrl(url) },
            )
        }
    }
    Space8h()
    Column(Modifier.fillMaxSize()) {
        latestFile?.let { file ->
            LatestCatalogFileCard(
                file = file,
                details = state.latestFileDetails,
                detailsLoading = state.latestFileDetailsLoading,
                installStatus = state.installStatuses[file.ref],
                downloadEnabled = !state.resolvingDownload &&
                        state.installStatuses[file.ref] !in setOf("下载中", "已安装", "运行中不可更新"),
                onInstall = { viewModel.requestDownload(file) },
                onOpenDependencyMod = onOpenDependencyMod,
            )
            Space8h()
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showOtherVersions) {
                    gridItems(olderFiles, key = { it.ref.fileId }) { file ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            val installStatus = state.installStatuses[file.ref]
                            CatalogFileCard(
                                file = file,
                                installStatus = installStatus,
                                downloadEnabled = !state.resolvingDownload &&
                                        installStatus !in setOf("下载中", "已安装", "运行中不可更新"),
                                modifier = Modifier.widthIn(max = 300.dp),
                                onClick = { viewModel.requestDownload(file) },
                            )
                        }
                    }
                }
                if (!state.filesLoading && state.files.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text("没有兼容版本") }
                }
                if (showOtherVersions && state.fileCursor != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            if (state.loadingMore) {
                                CircularProgressIndicator()
                            } else {
                                TextButton(onClick = viewModel::loadMoreFiles) { Text("加载更多") }
                            }
                        }
                    }
                }
                if (!showOtherVersions && (olderFiles.isNotEmpty() || state.fileCursor != null)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { showOtherVersions = true }) { Text("显示其他版本") }
                        }
                    }
                }
            }
            RVerticalScrollbar(
                gridState = gridState,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun LatestCatalogFileCard(
    file: CatalogFile,
    details: CatalogFileDetails?,
    detailsLoading: Boolean,
    installStatus: String?,
    downloadEnabled: Boolean,
    onInstall: () -> Unit,
    onOpenDependencyMod: (CatalogMod) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    RRow {
                        Text("最新 ${file.versionNumber ?: file.displayName}", fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,)
                        CircleIconButton(
                            icon = "\uF019",
                            label = "安装",
                            enabled = downloadEnabled,
                            onClick = onInstall,
                        )
                    }
                    Text(
                        "${file.fileSize.humanFileSize}  ${file.publishedAt.displayDate()} ${installStatus ?: ""}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            }
            Column(
                Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (detailsLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("加载依赖和更新说明...")
                    }
                } else if (details != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {

                            Text("更新说明", fontWeight = FontWeight.Bold)
                            if (details.changelog.isNotBlank()) {
                                Text(details.changelog.toReadableDescription())
                            } else {
                                Text("没有更新说明", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                        }
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (details.dependencyMods.isNotEmpty()) {
                                details.dependencySummary?.let {
                                    Text(it,fontWeight = FontWeight.Bold)
                                }
                                details.dependencyMods.forEach { dependency ->
                                    CatalogModCard(
                                        dependency,
                                        compact = true,
                                        onClick = { onOpenDependencyMod(dependency) },
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


@Composable
private fun CatalogFileCard(
    file: CatalogFile,
    installStatus: String?,
    downloadEnabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = downloadEnabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(file.versionNumber?:file.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${file.fileSize.humanFileSize}  ${file.publishedAt.displayDate()} ${installStatus?:""}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogDownloadDialog(
    fileName: String,
    state: CatalogDownloadUiState,
    onDismiss: () -> Unit,
    onSelectTarget: (CatalogInstallTarget) -> Unit,
    onSubmit: () -> Unit,
    onOpenDependencyMod: (CatalogMod) -> Unit,
) {
    val selectedLocalPack = (state.target as? CatalogInstallTarget.Local)?.pack
    val listState = rememberLazyListState()
    Dialog(onDismissRequest = { if (!state.submitting) onDismiss() }) {
        BoxWithConstraints(Modifier.padding(vertical = 24.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "安装${fileName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            state.errorMessage?.let { message ->
                                item { Text(message, color = MaterialTheme.colorScheme.error) }
                            }
                            if (state.loading) {
                                item { CircularProgressIndicator() }
                            }
                            if (state.fileDetailsLoading) {
                                item { CircularProgressIndicator() }
                            } else {
                                state.dependencySummary?.let { summary ->
                                    item { Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                items(state.dependencyMods) { dependency ->
                                    CatalogModCard(
                                        dependency,
                                        compact = true,
                                        onClick = { onOpenDependencyMod(dependency) },
                                    )
                                }
                                if (state.changelog.isNotBlank()) {
                                    item { Text("更新说明", fontWeight = FontWeight.Bold) }
                                    item { Text(state.changelog.toReadableDescription()) }
                                }
                                if (state.changelog.isBlank() && state.dependencyMods.isEmpty()) {
                                    item { Text("没有更多说明") }
                                }
                            }

                            if (state.allowTargetSelection) {
                                item { Text("本地整合包", fontWeight = FontWeight.Bold) }
                                items(state.localPacks, key = { "local:${it.versionId}" }) { pack ->
                                    TextButton(
                                        onClick = { onSelectTarget(CatalogInstallTarget.Local(pack)) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if ((state.target as? CatalogInstallTarget.Local)?.pack?.versionId == pack.versionId) {
                                                "✓ ${pack.name}"
                                            } else {
                                                pack.name
                                            }
                                        )
                                    }
                                }
                                item { Text("房间", fontWeight = FontWeight.Bold) }
                                items(state.hosts, key = { "host:${it.brief._id}" }) { host ->
                                    TextButton(
                                        onClick = {
                                            onSelectTarget(
                                                CatalogInstallTarget.HostTarget(
                                                    host.brief._id,
                                                    host.brief.name,
                                                    host.detail.modpack.mcVer,
                                                )
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(
                                            if ((state.target as? CatalogInstallTarget.HostTarget)?.id == host.brief._id) {
                                                "✓ ${host.brief.name}"
                                            } else {
                                                host.brief.name
                                            }
                                        )
                                    }
                                }
                            }

                            if (selectedLocalPack != null && !state.dependenciesLoading && state.localInstallMods.isNotEmpty()) {
                                item { Text("自动安装前置mod:${state.localInstallMods.size - 1}个") }
                                if (state.updateCount > 0) {
                                    item { Text("其中${state.updateCount}个已安装Mod将被替换") }
                                }
                            }
                            if (!state.loading && state.target == null) {
                                item { Text("没有兼容的安装目标") }
                            }
                        }
                        // Keep the scrollbar from stretching a short list to the maximum dialog height.
                        Box(Modifier.matchParentSize()) {
                            RVerticalScrollbar(
                                listState = listState,
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
                    }

                    HorizontalDivider()
                    when (val target = state.target) {
                        is CatalogInstallTarget.Local -> Text(
                            "安装到整合包：${target.pack.name} ${target.pack.verName}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        is CatalogInstallTarget.HostTarget -> Text(
                            "安装到房间：${target.name}",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        null -> Unit
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss, enabled = !state.submitting) { Text("取消") }
                        TextButton(
                            enabled = state.target != null &&
                                    !state.loading &&
                                    !state.fileDetailsLoading &&
                                    !state.submitting &&
                                    !state.dependenciesLoading &&
                                    (selectedLocalPack == null || state.localInstallMods.isNotEmpty()),
                            onClick = onSubmit,
                        ) {
                            Text(if (state.submitting) "提交中..." else "开始安装")
                        }
                    }
                }
            }
        }
    }
}

private fun java.time.Instant.displayDate(): String =
    DateTimeFormatter.ISO_LOCAL_DATE.format(atZone(ZoneId.systemDefault()))

private fun String.toReadableDescription(): String =
    replace(Regex("<iframe[\\s\\S]*?</iframe>", RegexOption.IGNORE_CASE), "")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\n"," ")
        .replace("\\s+".toRegex(), " ")
        .take(350)
        .trim()
