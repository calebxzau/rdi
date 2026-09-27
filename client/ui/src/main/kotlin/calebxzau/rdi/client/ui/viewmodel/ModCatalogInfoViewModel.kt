package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.modcatalog.CatalogDigestAlgorithm
import calebxzau.rdi.client.modcatalog.CatalogFile
import calebxzau.rdi.client.modcatalog.CatalogFileCursor
import calebxzau.rdi.client.modcatalog.CatalogFileRef
import calebxzau.rdi.client.modcatalog.CatalogFileRequest
import calebxzau.rdi.client.modcatalog.DependencyGraph
import calebxzau.rdi.client.modcatalog.CatalogMod
import calebxzau.rdi.client.modcatalog.CatalogProjectRef
import calebxzau.rdi.client.modcatalog.CatalogTarget
import calebxzau.rdi.client.modcatalog.DependencyRequest
import calebxzau.rdi.client.modcatalog.DependencyRequirement
import calebxzau.rdi.client.modcatalog.DependencyTarget
import calebxzau.rdi.client.modcatalog.EnvironmentCompatibility
import calebxzau.rdi.client.modcatalog.EnvironmentRequirement
import calebxzau.rdi.client.modcatalog.effectiveEnvironment
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.modcatalog.ModPlatform
import calebxzau.rdi.client.modcatalog.ReleaseChannel
import calebxzau.rdi.client.modcatalog.ResolvedDownload
import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.LocalContentInstallRecord
import calebxzhou.rdi.client.service.LocalContentInstallStore
import calebxzhou.rdi.client.service.LocalContentType
import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.service.ModpackService
import calebxzhou.rdi.client.service.ModCatalogDownloadService
import calebxzhou.rdi.client.service.getLocalPackDirs
import calebxzhou.rdi.client.ui.McPlayStore
import calebxzhou.rdi.client.ui.screen.ModCatalogInfoRoute
import calebxzhou.rdi.client.ui.screen.CatalogLocalTargetKind
import calebxzhou.rdi.client.ui.screen.hasDisabledCatalogLocalTargetKind
import calebxzhou.rdi.client.ui.screen.localCatalogTarget
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.runtimeLoaders
import calebxzhou.rdi.common.model.Task2Entry
import calebxzhou.rdi.common.model.Task2Status
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.service.murmur2
import calebxzhou.rdi.model.Role
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId

data class ModCatalogInfoUiState(
    val loading: Boolean = true,
    val mod: CatalogMod? = null,
    val targetLocalPack: ModpackLocalDir? = null,
    val targetMcVersion: McVersion? = null,
    val targetLoader: ModLoader? = null,
    val errorMessage: String? = null,
    val selectedMcVersion: McVersion = McVersion.V211,
    val selectedLoader: ModLoader = McVersion.V211.runtimeLoaders.first(),
    val includeAlpha: Boolean = false,
    val files: List<CatalogFile> = emptyList(),
    val fileCursor: CatalogFileCursor? = null,
    val filesLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val latestFileDetailsLoading: Boolean = false,
    val latestFileDetails: CatalogFileDetails? = null,
    val installStatuses: Map<CatalogFileRef, String> = emptyMap(),
    val resolvingDownload: Boolean = false,
    val downloadSelection: DownloadSelection? = null,
    val downloadDialog: CatalogDownloadUiState? = null,
)

sealed interface ModCatalogInfoEvent {
    data object TargetUnavailable : ModCatalogInfoEvent

    data class ShowSnackbar(
        val message: String,
        val runId: String? = null,
    ) : ModCatalogInfoEvent
}

data class DownloadSelection(
    val file: CatalogFile,
    val resolved: ResolvedDownload,
)

data class CatalogFileDetails(
    val changelog: String = "",
    val dependencyMods: List<CatalogMod> = emptyList(),
    val dependencySummary: String? = null,
)

sealed interface CatalogInstallTarget {
    data class Local(val pack: ModpackLocalDir) : CatalogInstallTarget

    data class HostTarget(
        val id: ObjectId,
        val name: String,
        val mcVersion: McVersion?,
    ) : CatalogInstallTarget

}

data class CatalogHost(
    val brief: Host.BriefVo,
    val detail: Host.DetailVo,
)

data class CatalogDownloadUiState(
    val localPacks: List<ModpackLocalDir> = emptyList(),
    val hosts: List<CatalogHost> = emptyList(),
    val target: CatalogInstallTarget? = null,
    val allowTargetSelection: Boolean = false,
    val loading: Boolean = true,
    val fileDetailsLoading: Boolean = false,
    val changelog: String = "",
    val dependencyMods: List<CatalogMod> = emptyList(),
    val dependencySummary: String? = null,
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val localInstallMods: List<Mod> = emptyList(),
    val dependenciesLoading: Boolean = false,
    val updateCount: Int = 0,
)

class ModCatalogInfoViewModel(
    private val route: ModCatalogInfoRoute,
    internal val catalog: ModCatalog,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ModCatalogInfoUiState())
    val uiState: StateFlow<ModCatalogInfoUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<ModCatalogInfoEvent>(Channel.BUFFERED)
    val events: Flow<ModCatalogInfoEvent> = eventChannel.receiveAsFlow()

    private var filesJob: Job? = null
    private var installStateJob: Job? = null
    private var downloadTargetsJob: Job? = null
    private var dependencyJob: Job? = null
    private var latestDetailsJob: Job? = null

    private var taskEntries: List<Task2Entry> = emptyList()
    private var localInstallRecords: List<LocalContentInstallRecord> = emptyList()
    private var localInstallHealth: Map<String, String> = emptyMap()

    init {
        observeTaskEntries()
        loadRoute()
    }

    override fun onCleared() {
        eventChannel.close()
        super.onCleared()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun setIncludeAlpha(includeAlpha: Boolean) {
        if (_uiState.value.includeAlpha == includeAlpha) return
        _uiState.update { it.copy(includeAlpha = includeAlpha) }
        loadFiles(reset = true)
    }

    fun loadMoreFiles() {
        loadFiles(reset = false)
    }

    fun requestDownload(file: CatalogFile) {
        val state = _uiState.value
        val targetPack = state.targetLocalPack
        if (state.resolvingDownload) return
        if (targetPack != null && !targetPack.dir.isDirectory) {
            emit(ModCatalogInfoEvent.TargetUnavailable)
            return
        }
        _uiState.update { it.copy(resolvingDownload = true) }
        viewModelScope.launch {
            try {
                val resolved = catalog.resolveDownload(file).getOrElse { cause ->
                    lgr.warn(cause) { "解析模组下载信息失败" }
                    emit(ModCatalogInfoEvent.ShowSnackbar("无法获取下载信息，请稍后重试"))
                    null
                }
                if (resolved != null) {
                    _uiState.update {
                        it.copy(
                            downloadSelection = DownloadSelection(file, resolved),
                            downloadDialog = CatalogDownloadUiState(fileDetailsLoading = true),
                        )
                    }
                    loadDownloadTargets()
                    val details = loadCatalogFileDetails(file)
                    if (currentCoroutineContext().isActive) {
                        _uiState.update {
                            it.copy(
                                downloadDialog = it.downloadDialog?.copy(
                                    fileDetailsLoading = false,
                                    changelog = details.changelog,
                                    dependencyMods = details.dependencyMods,
                                    dependencySummary = details.dependencySummary,
                                )
                            )
                        }
                    }
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                lgr.warn(cause) { "解析模组下载信息失败" }
                emit(ModCatalogInfoEvent.ShowSnackbar("无法获取下载信息，请稍后重试"))
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(resolvingDownload = false) }
                }
            }
        }
    }

    fun loadLatestFileDetails(file: CatalogFile) {
        latestDetailsJob?.cancel()
        _uiState.update {
            it.copy(
                latestFileDetailsLoading = true,
                latestFileDetails = null,
            )
        }
        latestDetailsJob = viewModelScope.launch {
            try {
                val details = loadCatalogFileDetails(file)
                if (uiState.value.files.maxByOrNull { it.publishedAt }?.ref == file.ref) {
                    _uiState.update { it.copy(latestFileDetails = details) }
                }
            } catch (cause: CancellationException) {
                throw cause
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(latestFileDetailsLoading = false) }
                }
            }
        }
    }

    private suspend fun loadCatalogFileDetails(file: CatalogFile): CatalogFileDetails {
        var changelog = ""
        var dependencyMods = emptyList<CatalogMod>()
        var dependencySummary: String? = null

        try {
            changelog = catalog.getChangelog(file.ref).getOrElse { cause ->
                lgr.warn(cause) { "加载模组更新说明失败" }
                reportError("加载更新说明失败")
                ""
            }
            val state = uiState.value
            val graph = catalog.resolveDependencies(
                DependencyRequest(
                    listOf(file),
                    CatalogTarget(state.selectedMcVersion, state.selectedLoader),
                    requirements = setOf(DependencyRequirement.REQUIRED),
                )
            ).getOrElse { cause ->
                lgr.warn(cause) { "解析模组依赖失败" }
                reportError("解析依赖失败")
                null
            }
            if (graph != null) {
                val dependencyFiles = requiredDependencyRefs(graph.value, file.ref)
                    .filter { it != file.ref }
                    .mapNotNull { graph.value.nodes[it]?.file }
                val refs = dependencyFiles.mapTo(linkedSetOf(), CatalogFile::project)
                dependencyMods = if (refs.isEmpty()) {
                    emptyList()
                } else {
                    catalog.getMods(refs).getOrElse { cause ->
                        lgr.warn(cause) { "加载依赖模组信息失败" }
                        reportError("加载依赖信息失败")
                        null
                    }?.value?.values?.distinctBy { it.identity.stableKey }.orEmpty()
                }
                dependencySummary = "前置${dependencyFiles.size}个"
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            lgr.warn(cause) { "加载模组附加信息失败" }
            reportError("加载模组附加信息失败")
        }
        return CatalogFileDetails(changelog, dependencyMods, dependencySummary)
    }

    fun dismissDownloadDialog() {
        if (_uiState.value.downloadDialog?.submitting == true) return
        downloadTargetsJob?.cancel()
        dependencyJob?.cancel()
        _uiState.update {
            it.copy(
                downloadSelection = null,
                downloadDialog = null,
            )
        }
    }

    fun selectDownloadTarget(target: CatalogInstallTarget) {
        _uiState.update {
            it.copy(
                downloadDialog = it.downloadDialog?.copy(
                    target = target,
                    errorMessage = null,
                    localInstallMods = emptyList(),
                    // 暂停自动解析并安装依赖，依赖由用户手动安装。
                    // dependenciesLoading = target is CatalogInstallTarget.Local,
                    updateCount = 0,
                )
            )
        }
        if (target !is CatalogInstallTarget.Local) {
            dependencyJob?.cancel()
        }
    }

    fun submitDownload() {
        val state = _uiState.value
        val selection = state.downloadSelection ?: return
        val dialog = state.downloadDialog ?: return
        val target = dialog.target ?: return
        if (dialog.submitting || dialog.loading || dialog.fileDetailsLoading || dialog.dependenciesLoading) return

        _uiState.update { it.copy(downloadDialog = dialog.copy(submitting = true, errorMessage = null)) }
        viewModelScope.launch {
            try {
                val legacyMod = state.mod?.toLegacyMod(selection.file, selection.resolved)
                    ?: error("模组详情尚未加载")
                val result = when (target) {
                    is CatalogInstallTarget.Local -> {
                        val runId = ClientTaskManager.submit(
                            task = ModCatalogDownloadService.downloadToLocalModpackTask2(
                                // 自动依赖安装已暂停，只安装用户选择的主Mod。
                                // dialog.localInstallMods.ifEmpty { listOf(legacyMod) },
                                listOf(legacyMod),
                                target.pack
                            ),
                            dedupeKey = "catalog-mod-local:${target.pack.versionId}:${selection.file.project.projectId}"
                        )
                        "已加入任务列表" to runId
                    }

                    is CatalogInstallTarget.HostTarget -> {
                        validateTargetVersion(target.mcVersion, selection.file)
                        val response = server.makeRequest<Unit>(
                            "host/${target.id}/mods/extra",
                            HttpMethod.Post
                        ) {
                            contentType(ContentType.Application.Json)
                            setBody(serdesJson.encodeToString(listOf(legacyMod)))
                        }
                        if (!response.ok) error(response.msg)
                        "已提交房间附加Mod任务" to null
                    }

                }
                _uiState.update {
                    it.copy(
                        downloadSelection = null,
                        downloadDialog = null,
                    )
                }
                emit(ModCatalogInfoEvent.ShowSnackbar(result.first, result.second))
                if (target is CatalogInstallTarget.Local) refreshLocalInstallState()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                lgr.warn(cause) { "提交模组安装任务失败" }
                _uiState.update {
                    it.copy(
                        downloadDialog = it.downloadDialog?.copy(
                            submitting = false,
                            errorMessage = "提交安装任务失败，请稍后重试",
                        )
                    )
                }
            }
        }
    }

    private fun loadRoute() {
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            if (route.hasDisabledCatalogLocalTargetKind()) {
                _uiState.update { it.copy(loading = false) }
                emit(ModCatalogInfoEvent.TargetUnavailable)
                return@launch
            }
            val localTarget = route.localCatalogTarget()
            val loadedPack = localTarget?.takeIf { it.kind == CatalogLocalTargetKind.Legacy }?.let { target ->
                withContext(Dispatchers.IO) { ModpackService.getLocalPackDirs().firstOrNull { it.versionId == target.id } }
            }
            if (localTarget != null && loadedPack == null) {
                _uiState.update { it.copy(loading = false) }
                emit(ModCatalogInfoEvent.TargetUnavailable)
                return@launch
            }

            runCatching {
                val platform = ModPlatform.valueOf(route.platform.uppercase())
                val ref = CatalogProjectRef(platform, route.projectId)
                val loadedMod = catalog.getMods(setOf(ref)).getOrThrow().value[ref]
                    ?: error("模组不存在")
                val targetMcVersion = loadedPack?.mcVersion ?: route.requiredMcVer?.let(McVersion::from)
                val targetLoader = loadedPack?.modLoader ?: route.requiredLoader?.let(ModLoader::from)
                LoadedRoute(loadedMod, loadedPack, targetMcVersion, targetLoader)
            }.onSuccess { loaded ->
                val selectedMcVersion = loaded.targetMcVersion ?: McVersion.V211
                val selectedLoader = loaded.targetLoader
                    ?: selectedMcVersion.runtimeLoaders.first()
                _uiState.update {
                    it.copy(
                        loading = false,
                        mod = loaded.mod,
                        targetLocalPack = loaded.pack,
                        targetMcVersion = loaded.targetMcVersion,
                        targetLoader = loaded.targetLoader,
                        selectedMcVersion = selectedMcVersion,
                        selectedLoader = selectedLoader,
                        errorMessage = null,
                    )
                }
                loadFiles(reset = true)
                refreshLocalInstallState()
            }.onFailure { cause ->
                lgr.warn(cause) { "加载模组详情失败" }
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = cause.message ?: "加载模组详情失败",
                    )
                }
            }
        }
    }

    private fun loadFiles(reset: Boolean) {
        val state = uiState.value
        val mod = state.mod ?: return
        val cursor = if (reset) null else state.fileCursor ?: return
        if (!reset && state.loadingMore) return
        if (reset) {
            filesJob?.cancel()
            latestDetailsJob?.cancel()
            _uiState.update {
                it.copy(
                    latestFileDetailsLoading = false,
                    latestFileDetails = null,
                )
            }
        }

        filesJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    filesLoading = reset,
                    loadingMore = !reset,
                    errorMessage = null,
                )
            }
            try {
                val requestState = uiState.value
                val outcome = catalog.listFiles(
                    CatalogFileRequest(
                        mod = mod,
                        target = CatalogTarget(requestState.selectedMcVersion, requestState.selectedLoader),
                        channels = if (requestState.includeAlpha) ReleaseChannel.entries.toSet()
                        else setOf(ReleaseChannel.RELEASE, ReleaseChannel.BETA),
                        cursor = cursor,
                    )
                ).getOrElse { cause ->
                    lgr.warn(cause) { "加载模组可用版本失败" }
                    reportError("加载可用版本失败，请稍后重试")
                    null
                }
                if (outcome != null) {
                    val currentFiles = uiState.value.files
                    val nextFiles = (if (reset) outcome.value.items else currentFiles + outcome.value.items)
                        .sortedBy { file -> if (file.channel == ReleaseChannel.BETA) 0 else 1 }
                    _uiState.update {
                        it.copy(
                            files = nextFiles,
                            fileCursor = outcome.value.nextCursor,
                            errorMessage = if (outcome.issues.isNotEmpty()) {
                                "部分目录信息暂时不可用，已自动选择可用结果"
                            } else {
                                it.errorMessage
                            },
                        )
                    }
                    val latestFile = nextFiles.maxByOrNull { it.publishedAt }
                    val previousLatest = currentFiles.maxByOrNull { it.publishedAt }
                    if (reset || latestFile?.ref != previousLatest?.ref) {
                        latestFile?.let(::loadLatestFileDetails)
                    }
                    updateInstallStatuses()
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                lgr.warn(cause) { "加载模组可用版本失败" }
                reportError("加载可用版本失败，请稍后重试")
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(filesLoading = false, loadingMore = false) }
                }
            }
        }
    }

    private fun observeTaskEntries() {
        viewModelScope.launch {
            ClientTaskManager.entries.collectLatest { entries ->
                taskEntries = entries
                updateInstallStatuses()
                if (uiState.value.targetLocalPack != null) refreshLocalInstallState()
            }
        }
    }

    private fun refreshLocalInstallState() {
        val state = uiState.value
        val pack = state.targetLocalPack
        val mod = state.mod
        if (pack == null || mod == null) {
            localInstallRecords = emptyList()
            localInstallHealth = emptyMap()
            updateInstallStatuses()
            return
        }
        installStateJob?.cancel()
        installStateJob = viewModelScope.launch {
            val records = withContext(Dispatchers.IO) {
                LocalContentInstallStore.read(pack).getOrElse { cause ->
                    lgr.warn(cause) { "读取本地Mod安装状态失败" }
                    emptyList()
                }
            }
            val health = withContext(Dispatchers.IO) {
                val projectIds = mod.sources.map { it.ref.projectId }.toSet()
                records.filter {
                    it.type == LocalContentType.MOD && it.projectId in projectIds
                }.mapNotNull { record ->
                    val file = pack.dir.resolve("mods").resolve(record.fileName)
                    val problem = when {
                        !file.isFile -> "文件缺失"
                        record.hash == null -> null
                        record.source == "mr" && !file.toPath().sha1.equals(record.hash, ignoreCase = true) ->
                            "文件已被修改"

                        record.source == "cf" && file.toPath().murmur2.toString() != record.hash ->
                            "文件已被修改"

                        else -> null
                    }
                    problem?.let { record.projectKey to it }
                }.toMap()
            }
            if (uiState.value.targetLocalPack?.versionId != pack.versionId) return@launch
            localInstallRecords = records
            localInstallHealth = health
            updateInstallStatuses()
        }
    }

    private fun updateInstallStatuses() {
        val state = uiState.value
        val pack = state.targetLocalPack
        if (pack == null) {
            if (state.installStatuses.isNotEmpty()) _uiState.update { it.copy(installStatuses = emptyMap()) }
            return
        }
        val statuses = state.files.associate { file ->
            val baseStatus = taskEntries.localModTaskStatus(pack, file)
                ?: localInstallRecords.modStatus(file, localInstallHealth)
                ?: "未安装"
            val status = if (
                McPlayStore.aliveCount(pack.versionId) > 0 &&
                localInstallRecords.hasManagedMod(file) &&
                baseStatus != "已安装"
            ) {
                "运行中不可更新"
            } else {
                baseStatus
            }
            file.ref to status
        }
        if (state.installStatuses != statuses) _uiState.update { it.copy(installStatuses = statuses) }
    }

    private fun loadDownloadTargets() {
        val selection = uiState.value.downloadSelection ?: return
        downloadTargetsJob?.cancel()
        downloadTargetsJob = viewModelScope.launch {
            val state = uiState.value
            val targetPack = state.targetLocalPack
            val hostId = route.targetHostId
            try {
                val fixedTarget = when {
                    targetPack != null -> CatalogInstallTarget.Local(targetPack)
                    hostId != null -> CatalogInstallTarget.HostTarget(
                        ObjectId(hostId),
                        "当前房间",
                        state.targetMcVersion,
                    )

                    else -> null
                }
                val localPacks: List<ModpackLocalDir>
                val hosts: List<CatalogHost>
                val target: CatalogInstallTarget?
                if (fixedTarget != null) {
                    localPacks = emptyList()
                    hosts = emptyList()
                    target = fixedTarget
                } else {
                    localPacks = withContext(Dispatchers.IO) {
                        ModpackService.getLocalPackDirs().filter { pack ->
                            selection.file.minecraftVersions.isEmpty() ||
                                    pack.mcVersion.mcVer in selection.file.minecraftVersions
                        }
                    }
                    hosts = loadCatalogAdminHosts().filter { host ->
                        selection.file.minecraftVersions.isEmpty() ||
                                host.detail.modpack.mcVer.mcVer in selection.file.minecraftVersions
                    }
                    target = localPacks.firstOrNull()?.let { CatalogInstallTarget.Local(it) }
                        ?: hosts.firstOrNull()?.let {
                            CatalogInstallTarget.HostTarget(
                                it.brief._id,
                                it.brief.name,
                                it.detail.modpack.mcVer,
                            )
                        }
                }
                _uiState.update {
                    it.copy(
                        downloadDialog = it.downloadDialog?.copy(
                            localPacks = localPacks,
                            hosts = hosts,
                            target = target,
                            allowTargetSelection = fixedTarget == null,
                            loading = false,
                            errorMessage = null,
                        )
                    )
                }
                // 暂停自动解析并安装依赖，依赖由用户手动安装。
                // if (target is CatalogInstallTarget.Local) loadRequiredLocalMods(target.pack)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                lgr.warn(cause) { "加载模组安装目标失败" }
                _uiState.update {
                    it.copy(
                        downloadDialog = it.downloadDialog?.copy(
                            loading = false,
                            errorMessage = "加载安装目标失败，请稍后重试",
                        )
                    )
                }
            }
        }
    }

    private fun loadRequiredLocalMods(pack: ModpackLocalDir) {
        val selection = uiState.value.downloadSelection ?: return
        dependencyJob?.cancel()
        _uiState.update {
            it.copy(
                downloadDialog = it.downloadDialog?.copy(
                    target = CatalogInstallTarget.Local(pack),
                    dependenciesLoading = true,
                    localInstallMods = emptyList(),
                    updateCount = 0,
                    errorMessage = null,
                )
            )
        }
        dependencyJob = viewModelScope.launch {
            try {
                val mods = resolveRequiredLocalMods(
                    catalog = catalog,
                    rootFile = selection.file,
                    rootDownload = selection.resolved,
                    packdir = pack,
                ).getOrThrow()
                val records = withContext(Dispatchers.IO) {
                    LocalContentInstallStore.read(pack).getOrThrow()
                }
                val updateCount = mods.count { dependency ->
                    records.firstOrNull {
                        it.type == LocalContentType.MOD && it.source == dependency.platform &&
                                it.projectId == dependency.projectId
                    }?.versionId?.let { it != dependency.fileId } == true
                }
                _uiState.update {
                    it.copy(
                        downloadDialog = it.downloadDialog?.copy(
                            localInstallMods = mods,
                            dependenciesLoading = false,
                            updateCount = updateCount,
                        )
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                lgr.warn(cause) { "解析必需依赖失败" }
                _uiState.update {
                    it.copy(
                        downloadDialog = it.downloadDialog?.copy(
                            dependenciesLoading = false,
                            errorMessage = cause.message ?: "解析必需依赖失败",
                        )
                    )
                }
            }
        }
    }

    private fun availableLoadersFor(state: ModCatalogInfoUiState, version: McVersion): List<ModLoader> =
        state.targetLoader?.let(::listOf) ?: version.runtimeLoaders

    private fun validateTargetVersion(mcVersion: McVersion?, file: CatalogFile) {
        if (mcVersion != null && file.minecraftVersions.isNotEmpty() && mcVersion.mcVer !in file.minecraftVersions) {
            error("目标MC版本与当前模组版本不匹配")
        }
    }

    private fun reportError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    private fun emit(event: ModCatalogInfoEvent) {
        eventChannel.trySend(event)
    }

    private data class LoadedRoute(
        val mod: CatalogMod,
        val pack: ModpackLocalDir?,
        val targetMcVersion: McVersion?,
        val targetLoader: ModLoader?,
    )
}

private fun List<Task2Entry>.localModTaskStatus(
    packdir: ModpackLocalDir,
    file: CatalogFile,
): String? {
    val key = "catalog-mod-local:${packdir.versionId}:${file.project.projectId}"
    val entry = lastOrNull { it.dedupeKey == key } ?: return null
    return when (entry.status) {
        Task2Status.QUEUED, Task2Status.RUNNING -> "下载中"
        Task2Status.FAILED, Task2Status.CANCELLED -> "安装失败"
        Task2Status.DONE -> null
    }
}

private fun List<LocalContentInstallRecord>.modStatus(
    file: CatalogFile,
    health: Map<String, String>,
): String? {
    val source = when (file.ref.platform) {
        ModPlatform.CURSEFORGE -> "cf"
        ModPlatform.MODRINTH -> "mr"
    }
    val record = firstOrNull {
        it.type == LocalContentType.MOD && it.source == source && it.projectId == file.project.projectId
    } ?: return "未安装"
    return when {
        health[record.projectKey] != null -> health.getValue(record.projectKey)
        record.versionId == file.ref.fileId -> "已安装"
        else -> "可更新"
    }
}

private fun List<LocalContentInstallRecord>.hasManagedMod(file: CatalogFile): Boolean {
    val source = if (file.ref.platform == ModPlatform.CURSEFORGE) "cf" else "mr"
    return any {
        it.type == LocalContentType.MOD && it.source == source && it.projectId == file.project.projectId
    }
}

private suspend fun resolveRequiredLocalMods(
    catalog: ModCatalog,
    rootFile: CatalogFile,
    rootDownload: ResolvedDownload,
    packdir: ModpackLocalDir,
): Result<List<Mod>> = runCatching {
    val target = CatalogTarget(packdir.mcVersion, packdir.modLoader)
    val graph = catalog.resolveDependencies(
        DependencyRequest(
            listOf(rootFile),
            target,
            requirements = setOf(DependencyRequirement.REQUIRED),
        )
    ).getOrThrow().value
    val requiredRefs = requiredDependencyRefs(graph, rootFile.ref)

    val unresolvedRequired = graph.unresolved.filter { unresolved ->
        graph.edges.any {
            it.from in requiredRefs && it.to == unresolved.target &&
                    it.requirement == DependencyRequirement.REQUIRED
        }
    }
    if (unresolvedRequired.isNotEmpty()) error("有${unresolvedRequired.size}个必需依赖没有兼容版本")

    val files = requiredRefs.map { ref -> graph.nodes.getValue(ref).file }
    val mods = catalog.getMods(files.mapTo(linkedSetOf(), CatalogFile::project)).getOrThrow().value
    files.map { dependencyFile ->
        val catalogMod = mods.getValue(dependencyFile.project)
        val download = if (dependencyFile.ref == rootFile.ref) rootDownload
        else catalog.resolveDownload(dependencyFile).getOrThrow()
        catalogMod.toLegacyMod(dependencyFile, download)
    }.sortedBy { if (it.projectId == rootFile.project.projectId) 0 else 1 }
}

/* Disabled Modpack2 install planning; retained for later re-enable.
/** Builds one content request for the selected mod and every recursively-required dependency. */
private suspend fun ModCatalogInfoViewModel.resolveModpack2InstallItems(
    pack: LocalModpack2InstanceRecord,
    rootFile: CatalogFile,
    rootDownload: ResolvedDownload,
): List<Modpack2ContentRequest> {
    val graph = catalog.resolveDependencies(
        DependencyRequest(listOf(rootFile), CatalogTarget(pack.mcVersion, pack.modLoader))
    ).getOrThrow().value
    val refs = requiredDependencyRefs(graph, rootFile.ref)
    require(graph.cycles.isEmpty()) { "必需依赖存在循环，暂时无法安全安装" }
    val unresolved = graph.unresolved.filter { missing ->
        graph.edges.any { it.requirement == DependencyRequirement.REQUIRED && it.from in refs && it.to == missing.target }
    }
    require(unresolved.isEmpty()) { "有${unresolved.size}个必需依赖没有兼容版本" }
    // ContentService currently submits one row at a time; install dependencies first so the
    // dependency FK can be persisted with each dependent row.
    val files = refs.map { graph.nodes.getValue(it).file }
        .sortedByDescending { graph.nodes.getValue(it.ref).depth }
    val mods = catalog.getMods(files.mapTo(linkedSetOf(), CatalogFile::project)).getOrThrow().value
    val ids = files.associate { it.ref to Uuid.generateV7() }
    return files.map { file ->
        val mod = mods.getValue(file.project)
        val source = mod.sources.firstOrNull { it.ref == file.project } ?: error("模组来源信息缺失")
        val download = if (file.ref == rootFile.ref) rootDownload else catalog.resolveDownload(file).getOrThrow()
        val expectedDigest = when (file.ref.platform) {
            ModPlatform.MODRINTH -> CatalogDigestAlgorithm.SHA1
            ModPlatform.CURSEFORGE -> CatalogDigestAlgorithm.CURSEFORGE_MURMUR2
        }
        val digest = download.digests.firstOrNull { it.algorithm == expectedDigest }
            ?: file.digests.firstOrNull { it.algorithm == expectedDigest }
            ?: error("当前文件缺少校验信息，无法安全安装")
        val record = LocalModpack2ContentRecord(
            contentId = ids.getValue(file.ref), versionId = pack.versionId,
            platform = when (file.ref.platform) {
                ModPlatform.CURSEFORGE -> ContentPlatform.CurseForge
                ModPlatform.MODRINTH -> ContentPlatform.Modrinth
            },
            type = ModpackContentType.Mod, projectId = file.project.projectId, fileId = file.ref.fileId,
            slug = source.slug, hash = digest.value, targetPath = "mods/${download.fileName}",
            side = file.effectiveEnvironment(source).toHost2ContentSide(), fileSize = file.fileSize,
            logicalId = mod.identity.stableKey, origin = LocalModpack2ContentOrigin.Local,
        )
        val dependencies = graph.edges.filter { it.requirement == DependencyRequirement.REQUIRED && it.from == file.ref }
            .mapNotNull { edge ->
                val dependencyRef = when (val target = edge.to) {
                    is DependencyTarget.File -> target.ref
                    is DependencyTarget.Project -> files.firstOrNull { it.project == target.ref }?.ref
                } ?: return@mapNotNull null
                ids[dependencyRef]?.let { LocalModpack2ContentDependencyRecord(pack.versionId, record.contentId, it, true) }
            }
        Modpack2ContentRequest(
            request = ContentRequest(
                id = "catalog:${file.ref.platform}:${file.project.projectId}:${file.ref.fileId}",
                relativePath = record.targetPath!!, size = file.fileSize,
                digests = download.digests.mapNotNull { it.toContentDigest() },
                sources = listOf(ContentSource(download.url, download.headers, file.fileSize, name = "catalog")),
                displayName = mod.name,
            ),
            record = record, dependencies = dependencies,
        )
    }
}
*/

/* private fun calebxzau.rdi.client.modcatalog.CatalogDigest.toContentDigest(): ContentDigest? = when (algorithm) {
    CatalogDigestAlgorithm.SHA1 -> ContentDigest(ContentDigestAlgorithm.SHA1, value)
    CatalogDigestAlgorithm.CURSEFORGE_MURMUR2 -> ContentDigest(ContentDigestAlgorithm.MURMUR2, value)
    else -> null
} */

private fun requiredDependencyRefs(
    graph: DependencyGraph,
    rootRef: CatalogFileRef,
): Set<CatalogFileRef> {
    val requiredRefs = linkedSetOf(rootRef)
    var changed: Boolean
    do {
        changed = false
        graph.edges.filter {
            it.requirement == DependencyRequirement.REQUIRED && it.from in requiredRefs
        }.forEach { edge ->
            val dependencyRef = when (val dependency = edge.to) {
                is DependencyTarget.File -> dependency.ref
                is DependencyTarget.Project -> graph.nodes.values
                    .firstOrNull { it.file.project == dependency.ref }
                    ?.file?.ref
            }
            if (dependencyRef != null && requiredRefs.add(dependencyRef)) changed = true
        }
    } while (changed)
    return requiredRefs
}

private suspend fun loadCatalogAdminHosts(): List<CatalogHost> {
    val hosts = mutableListOf<CatalogHost>()
    val response = server.makeRequest<List<Host.BriefVo>>("host/my")
    if (!response.ok) error(response.msg)
    val briefs = response.data.orEmpty()
    briefs.forEach { brief ->
        val detailResponse = server.makeRequest<Host.DetailVo>("host/${brief._id}/detail")
        if (!detailResponse.ok) error(detailResponse.msg)
        val detail = detailResponse.data ?: return@forEach
        if (detail.ownerId == loggedAccount._id || detail.members.any {
                it.id == loggedAccount._id && it.role.level <= Role.ADMIN.level
            }
        ) {
            hosts += CatalogHost(brief, detail)
        }
    }

    return hosts
}

private fun CatalogMod.toLegacyMod(file: CatalogFile, resolved: ResolvedDownload): Mod {
    val source = sources.firstOrNull { it.ref == file.project } ?: sources.first()
    val platform = when (file.ref.platform) {
        ModPlatform.CURSEFORGE -> "cf"
        ModPlatform.MODRINTH -> "mr"
    }
    val digest = when (file.ref.platform) {

        ModPlatform.CURSEFORGE -> resolved.digests.firstOrNull {
            it.algorithm == CatalogDigestAlgorithm.CURSEFORGE_MURMUR2
        }

        ModPlatform.MODRINTH -> resolved.digests.firstOrNull {
            it.algorithm == CatalogDigestAlgorithm.SHA1
        }

    } ?: error("当前文件缺少校验信息")
    return Mod(
        platform = platform,
        projectId = file.project.projectId,
        slug = source.slug,
        fileId = file.ref.fileId,
        hash = digest.value,
        side = file.effectiveEnvironment(source).toLegacySide(),
        downloadUrls = listOf(resolved.url),
    )
}

private fun EnvironmentCompatibility.toLegacySide(): Mod.Side = when {
    client == EnvironmentRequirement.UNSUPPORTED && server != EnvironmentRequirement.UNSUPPORTED -> Mod.Side.SERVER
    server == EnvironmentRequirement.UNSUPPORTED && client != EnvironmentRequirement.UNSUPPORTED -> Mod.Side.CLIENT
    client == EnvironmentRequirement.UNKNOWN && server == EnvironmentRequirement.UNKNOWN -> Mod.Side.BOTH//Mod.Side.UNKNOWN
    else -> Mod.Side.BOTH
}
