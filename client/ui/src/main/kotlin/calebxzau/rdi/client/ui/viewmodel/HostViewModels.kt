package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.GithubExtraModService
import calebxzhou.rdi.client.service.GithubRelease
import calebxzhou.rdi.client.service.GithubReleaseAsset
import calebxzhou.rdi.client.service.GithubRepoRef
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.displaySlugOrProject
import calebxzhou.rdi.common.model.normalizedProjectId
import calebxzhou.rdi.common.model.normalizedSlug
import calebxzhou.rdi.common.model.sameMod
import calebxzhou.rdi.common.net.json
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import java.net.URI

private val lgr by Loggers

interface HostGateway {
    suspend fun loadHost(hostId: ObjectId): Result<Host.DetailVo?>

    suspend fun loadModpack(modpackId: ObjectId): Result<Modpack.DetailVo?>

    suspend fun deleteHost(hostId: ObjectId, deleteWorld: Boolean): Result<Unit>

    suspend fun changeDisabledMods(hostId: ObjectId, mods: List<Mod>, disabled: Boolean): Result<List<Mod>>

    suspend fun addExtraMods(hostId: ObjectId, mods: List<Mod>): Result<Unit>

    suspend fun removeExtraMods(hostId: ObjectId, projectIds: List<String>): Result<List<Mod>>

    suspend fun loadGithubReleases(repoUrl: String): Result<Pair<GithubRepoRef, List<GithubRelease>>>

    suspend fun buildGithubExtraMod(
        repo: GithubRepoRef,
        asset: GithubReleaseAsset,
        side: Mod.Side,
        onProgress: (String) -> Unit,
    ): Result<Mod>
}

class RdiHostGateway : HostGateway {
    override suspend fun loadHost(hostId: ObjectId): Result<Host.DetailVo?> = resultOf {
        request<Host.DetailVo>("host/${hostId.toHexString()}/detail")
    }

    override suspend fun loadModpack(modpackId: ObjectId): Result<Modpack.DetailVo?> = resultOf {
        request<Modpack.DetailVo>("modpack/${modpackId.toHexString()}")
    }

    override suspend fun deleteHost(hostId: ObjectId, deleteWorld: Boolean): Result<Unit> = resultOf {
        val response = server.makeRequest<Unit>(
            path = "host/${hostId.toHexString()}",
            method = HttpMethod.Delete,
        ) {
            json()
            setBody(Host.DeleteDto(deleteWorld))
        }
        if (!response.ok) throw RequestError(response.msg)
    }

    override suspend fun changeDisabledMods(
        hostId: ObjectId,
        mods: List<Mod>,
        disabled: Boolean,
    ): Result<List<Mod>> = resultOf {
        val response = server.makeRequest<List<Mod>>(
            path = "host/${hostId.toHexString()}/mods/disabled",
            method = if (disabled) HttpMethod.Post else HttpMethod.Delete,
        ) {
            json()
            setBody(mods)
        }
        if (!response.ok) throw RequestError(response.msg)
        response.data.orEmpty()
    }

    override suspend fun addExtraMods(hostId: ObjectId, mods: List<Mod>): Result<Unit> = resultOf {
        val response = server.makeRequest<Unit>(
            path = "host/${hostId.toHexString()}/mods/extra",
            method = HttpMethod.Post,
        ) {
            json()
            setBody(mods)
        }
        if (!response.ok) throw RequestError(response.msg)
    }

    override suspend fun removeExtraMods(hostId: ObjectId, projectIds: List<String>): Result<List<Mod>> = resultOf {
        val response = server.makeRequest<List<Mod>>(
            path = "host/${hostId.toHexString()}/mods/extra",
            method = HttpMethod.Delete,
        ) {
            json()
            setBody(projectIds)
        }
        if (!response.ok) throw RequestError(response.msg)
        response.data.orEmpty()
    }

    override suspend fun loadGithubReleases(
        repoUrl: String,
    ): Result<Pair<GithubRepoRef, List<GithubRelease>>> = GithubExtraModService.fetchReleases(repoUrl)

    override suspend fun buildGithubExtraMod(
        repo: GithubRepoRef,
        asset: GithubReleaseAsset,
        side: Mod.Side,
        onProgress: (String) -> Unit,
    ): Result<Mod> = GithubExtraModService.buildExtraModFromAsset(repo, asset, side, onProgress)

    private suspend inline fun <reified T> request(
        path: String,
    ): T? {
        val response = server.makeRequest<T>(path)
        if (!response.ok) throw RequestError(response.msg)
        return response.data
    }
}

data class HostInfoUiState(
    val loading: Boolean = true,
    val host: Host.DetailVo? = null,
    val modpack: Modpack.DetailVo? = null,
    val errorMessage: String? = null,
    val deleting: Boolean = false,
)

sealed interface HostInfoEvent {
    data object Deleted : HostInfoEvent

    data class DeleteFailed(val message: String) : HostInfoEvent
}

class HostInfoViewModel(
    private val hostId: ObjectId,
    private val gateway: HostGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostInfoUiState())
    val uiState: StateFlow<HostInfoUiState> = _uiState.asStateFlow()
    private val eventChannel = Channel<HostInfoEvent>(Channel.BUFFERED)
    val events: Flow<HostInfoEvent> = eventChannel.receiveAsFlow()
    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val host = withContext(Dispatchers.IO) { gateway.loadHost(hostId).getOrThrow() }
                    ?: throw IllegalStateException("无法加载房间信息")
                val modpack = withContext(Dispatchers.IO) {
                    gateway.loadModpack(host.modpack.id).getOrThrow()
                }
                _uiState.update { it.copy(host = host, modpack = modpack) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "加载房间详情失败" }
                _uiState.update {
                    it.copy(
                        host = null,
                        modpack = null,
                        errorMessage = "加载房间信息失败: ${cause.message ?: "未知错误"}",
                    )
                }
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun deleteHost(deleteWorld: Boolean) {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleting = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { gateway.deleteHost(hostId, deleteWorld) }.fold(
                    onSuccess = { eventChannel.send(HostInfoEvent.Deleted) },
                    onFailure = { cause ->
                        lgr.warn(cause) { "删除房间失败" }
                        eventChannel.send(HostInfoEvent.DeleteFailed(cause.message ?: "删除房间失败"))
                    },
                )
            } finally {
                _uiState.update { it.copy(deleting = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}

data class ExtraModDraft(
    val platform: String = "github",
    val projectId: String = "",
    val slug: String = "",
    val fileId: String = "",
    val hash: String = "",
    val downloadUrls: String = "",
    val githubRepoUrl: String = "",
    val githubRepo: GithubRepoRef? = null,
    val githubReleases: List<GithubRelease> = emptyList(),
    val selectedGithubAsset: GithubReleaseAsset? = null,
    val side: Mod.Side = Mod.Side.BOTH,
)

data class HostModsUiState(
    val loading: Boolean = true,
    val host: Host.DetailVo? = null,
    val modpack: Modpack.DetailVo? = null,
    val disabledMods: List<Mod> = emptyList(),
    val extraModDraft: ExtraModDraft = ExtraModDraft(),
    val extraModLoading: Boolean = false,
    val extraModLoadingText: String = "",
    val extraModError: String? = null,
    val pendingDisabledModKeys: Set<String> = emptySet(),
    val removingExtraMods: Boolean = false,
    val errorMessage: String? = null,
) {
    val extraMods: List<Mod> get() = host?.extraMods.orEmpty()

    val baseVersionMods: List<Mod>
        get() = modpack?.versions
            ?.firstOrNull { it.name == host?.packVer }
            ?.mods
            ?.filterNot { versionMod -> disabledMods.any { sameMod(it, versionMod) } }
            .orEmpty()

    val allBaseVersionMods: List<Mod>
        get() = modpack?.versions
            ?.firstOrNull { it.name == host?.packVer }
            ?.mods
            .orEmpty()
}

sealed interface HostModsEvent {
    data class ShowSnackbar(val message: String) : HostModsEvent

    data object ExtraModAdded : HostModsEvent
}

class HostModsViewModel(
    private val hostId: ObjectId,
    private val gateway: HostGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostModsUiState())
    val uiState: StateFlow<HostModsUiState> = _uiState.asStateFlow()
    private val eventChannel = Channel<HostModsEvent>(Channel.BUFFERED)
    val events: Flow<HostModsEvent> = eventChannel.receiveAsFlow()
    private var loadJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        _uiState.update { it.copy(loading = true, errorMessage = null) }
        loadJob = viewModelScope.launch {
            try {
                val host = withContext(Dispatchers.IO) { gateway.loadHost(hostId).getOrThrow() }
                    ?: throw IllegalStateException("无法加载房间信息")
                val modpack = withContext(Dispatchers.IO) {
                    gateway.loadModpack(host.modpack.id).getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        host = host,
                        modpack = modpack,
                        disabledMods = host.disabledMods,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("加载房间信息失败", cause)
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun updateExtraModDraft(draft: ExtraModDraft) {
        _uiState.update { it.copy(extraModDraft = draft, extraModError = null) }
    }

    fun switchExtraModPlatform(platform: String) {
        _uiState.update {
            it.copy(
                extraModDraft = it.extraModDraft.copy(
                    platform = platform,
                    githubRepo = if (platform == "github") it.extraModDraft.githubRepo else null,
                    githubReleases = if (platform == "github") it.extraModDraft.githubReleases else emptyList(),
                    selectedGithubAsset = if (platform == "github") it.extraModDraft.selectedGithubAsset else null,
                ),
                extraModError = null,
            )
        }
    }

    fun resetExtraModDraft() {
        _uiState.update {
            it.copy(
                extraModDraft = ExtraModDraft(),
                extraModLoading = false,
                extraModLoadingText = "",
                extraModError = null,
            )
        }
    }

    fun resetGithubRepo() {
        _uiState.update {
            it.copy(
                extraModDraft = it.extraModDraft.copy(
                    githubRepo = null,
                    githubReleases = emptyList(),
                    selectedGithubAsset = null,
                ),
                extraModError = null,
            )
        }
    }

    fun selectGithubAsset(asset: GithubReleaseAsset) {
        _uiState.update {
            it.copy(
                extraModDraft = it.extraModDraft.copy(selectedGithubAsset = asset),
                extraModError = null,
            )
        }
    }

    fun loadGithubReleases() {
        val repoUrl = _uiState.value.extraModDraft.githubRepoUrl
        setExtraModLoading("读取GitHub Release...")
        viewModelScope.launch {
            try {
                val (repo, releases) = withContext(Dispatchers.IO) {
                    gateway.loadGithubReleases(repoUrl).getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        extraModDraft = it.extraModDraft.copy(
                            githubRepo = repo,
                            githubReleases = releases,
                            selectedGithubAsset = null,
                        )
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportExtraModError("读取GitHub Release失败", cause)
            } finally {
                finishExtraModLoading()
            }
        }
    }

    fun submitGithubExtraMod() {
        val draft = _uiState.value.extraModDraft
        if (draft.githubRepo == null || draft.githubReleases.isEmpty()) {
            loadGithubReleases()
            return
        }
        val asset = draft.selectedGithubAsset
        if (asset == null) {
            _uiState.update { it.copy(extraModError = "请先选择1个Release文件") }
            return
        }
        setExtraModLoading("准备GitHub Mod...")
        viewModelScope.launch {
            try {
                val mod = withContext(Dispatchers.IO) {
                    gateway.buildGithubExtraMod(
                        repo = draft.githubRepo,
                        asset = asset,
                        side = draft.side,
                        onProgress = { progress ->
                            _uiState.update { it.copy(extraModLoadingText = progress) }
                        },
                    ).getOrThrow()
                }
                addExtraMod(mod)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportExtraModError("GitHub Mod处理失败", cause)
                finishExtraModLoading()
            }
        }
    }

    fun submitManualExtraMod() {
        val draft = _uiState.value.extraModDraft
        val mod = buildManualExtraMod(draft).getOrElse { cause ->
            lgr.warn(cause) { "附加Mod信息无效" }
            _uiState.update { it.copy(extraModError = cause.message ?: "附加Mod信息无效") }
            return
        }
        setExtraModLoading("添加中...")
        viewModelScope.launch { addExtraMod(mod) }
    }

    fun changeDisabledMods(
        mods: List<Mod>,
        disabled: Boolean,
        onComplete: () -> Unit = {},
    ) {
        val targets = mods.distinctBy(::extraModIdentity)
            .filterNot { extraModIdentity(it) in _uiState.value.pendingDisabledModKeys }
        if (targets.isEmpty()) {
            onComplete()
            return
        }
        val keys = targets.map(::extraModIdentity).toSet()
        _uiState.update { it.copy(pendingDisabledModKeys = it.pendingDisabledModKeys + keys) }
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    gateway.changeDisabledMods(hostId, targets, disabled).getOrThrow()
                }
                _uiState.update { state ->
                    state.copy(
                        disabledMods = updated,
                        host = state.host?.copy(disabledMods = updated),
                    )
                }
                eventChannel.send(
                    HostModsEvent.ShowSnackbar(
                        if (disabled) "已停用Mod，重启房间后生效" else "已恢复Mod，重启房间后生效"
                    )
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError(if (disabled) "停用Mod失败" else "启用Mod失败", cause)
            } finally {
                _uiState.update { it.copy(pendingDisabledModKeys = it.pendingDisabledModKeys - keys) }
                onComplete()
            }
        }
    }

    fun removeExtraMods(mods: List<Mod>) {
        if (_uiState.value.removingExtraMods) return
        val projectIds = mods.map(Mod::projectId).distinct()
        if (projectIds.isEmpty()) return
        _uiState.update { it.copy(removingExtraMods = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    gateway.removeExtraMods(hostId, projectIds).getOrThrow()
                }
                _uiState.update { state ->
                    state.copy(host = state.host?.copy(extraMods = updated))
                }
                eventChannel.send(HostModsEvent.ShowSnackbar("已删除附加Mod"))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("删除附加Mod失败", cause)
            } finally {
                _uiState.update { it.copy(removingExtraMods = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        eventChannel.close()
        super.onCleared()
    }

    private suspend fun addExtraMod(mod: Mod) {
        val state = _uiState.value
        val result = filterExtraModsForAdding(
            candidateMods = listOf(mod),
            existingMods = state.extraMods + state.allBaseVersionMods,
        )
        val acceptedMod = result.acceptedMods.singleOrNull()
        if (acceptedMod == null) {
            _uiState.update {
                it.copy(
                    extraModError = result.rejectedMessages.firstOrNull() ?: "该Mod无法添加",
                    extraModLoading = false,
                    extraModLoadingText = "",
                )
            }
            return
        }
        try {
            withContext(Dispatchers.IO) {
                gateway.addExtraMods(hostId, listOf(acceptedMod)).getOrThrow()
            }
            eventChannel.send(HostModsEvent.ShowSnackbar("已提交附加Mod添加任务，请在邮件中查看进度"))
            eventChannel.send(HostModsEvent.ExtraModAdded)
            resetExtraModDraft()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (cause: Throwable) {
            reportExtraModError("添加附加Mod失败", cause)
        } finally {
            finishExtraModLoading()
        }
    }

    private fun setExtraModLoading(message: String) {
        _uiState.update {
            it.copy(
                extraModLoading = true,
                extraModLoadingText = message,
                extraModError = null,
            )
        }
    }

    private fun finishExtraModLoading() {
        _uiState.update { it.copy(extraModLoading = false, extraModLoadingText = "") }
    }

    private fun reportExtraModError(prefix: String, cause: Throwable) {
        lgr.warn(cause) { prefix }
        _uiState.update { it.copy(extraModError = "$prefix: ${cause.message ?: "未知错误"}") }
    }

    private fun reportError(prefix: String, cause: Throwable) {
        lgr.warn(cause) { prefix }
        _uiState.update { it.copy(errorMessage = "$prefix: ${cause.message ?: "未知错误"}") }
    }
}

fun buildManualExtraMod(draft: ExtraModDraft): Result<Mod> = runCatching {
    val platform = draft.platform.trim().lowercase()
    if (platform !in listOf("mr", "cf")) error("平台只能是Modrinth或CurseForge")
    val hash = draft.hash.trim()
    if (hash.isBlank()) error("SHA1不能为空")
    val downloadUrls = draft.downloadUrls.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
    if (downloadUrls.isEmpty()) error("至少填写1个下载链接")
    downloadUrls.firstOrNull { !it.isValidExtraModDownloadUrl() }?.let {
        error("下载链接无效: $it")
    }
    val projectId = draft.projectId.trim()
    val slug = draft.slug.trim()
    val fileId = draft.fileId.trim()
    if (projectId.isBlank()) error("projectId不能为空")
    if (slug.isBlank()) error("slug不能为空")
    if (fileId.isBlank()) error("fileId不能为空")
    Mod(platform, projectId, slug, fileId, hash, draft.side, downloadUrls)
}

data class ExtraModAddFilterResult(
    val acceptedMods: List<Mod>,
    val rejectedMessages: List<String>,
)

fun filterExtraModsForAdding(
    candidateMods: List<Mod>,
    existingMods: List<Mod>,
): ExtraModAddFilterResult {
    val existingKeys = existingMods.map(::extraModIdentity).filter(String::isNotBlank).toSet()
    val pendingKeys = mutableSetOf<String>()
    val accepted = mutableListOf<Mod>()
    val rejected = mutableListOf<String>()
    candidateMods.forEach { mod ->
        val key = extraModIdentity(mod)
        when {
            key.isBlank() -> accepted += mod
            key in existingKeys -> rejected += "${mod.displaySlugOrProject}：主机或整合包中已存在同名Mod"
            !pendingKeys.add(key) -> rejected += "${mod.displaySlugOrProject}：本次选择中已有同名Mod"
            else -> accepted += mod
        }
    }
    return ExtraModAddFilterResult(accepted, rejected.distinct())
}

fun extraModIdentity(mod: Mod): String = mod.normalizedSlug.ifBlank {
    mod.normalizedProjectId.lowercase()
}

private fun String.isValidExtraModDownloadUrl(): Boolean = runCatching {
    val uri = URI(this)
    val scheme = uri.scheme?.lowercase()
    (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}.getOrDefault(false)

private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (cause: Throwable) {
    Result.failure(cause)
}
