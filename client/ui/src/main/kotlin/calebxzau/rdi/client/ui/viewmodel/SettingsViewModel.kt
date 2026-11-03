package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzhou.rdi.client.AppConfig
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.LocalMcProxyService
import calebxzhou.rdi.client.service.NodeRefreshCoordinator
import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.service.ModpackService
import calebxzhou.rdi.client.service.content.submitDownloadCacheCleanupTask2
import calebxzhou.rdi.client.service.getInvalidLocalPackDirs
import calebxzhou.rdi.client.service.SettingsService
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.DownloadQuota
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val lgr by Loggers

data class SettingsDraft(
    val preferModMirror: Boolean = false,
    val preferMcMirror: Boolean = false,
    val maxMemoryText: String = "",
    val proxyEnabled: Boolean = false,
    val proxySystem: Boolean = false,
    val proxyHost: String = "127.0.0.1",
    val proxyPortText: String = "10808",
    val proxyUsr: String = "",
    val proxyPwd: String = "",
    val solidWindow: Boolean = false,
)

data class SettingsInitialData(
    val draft: SettingsDraft,
    val totalMemoryMb: Int,
)

data class NodeSwitchRequest(
    val gameBackup: Boolean = false,
    val forceMain: Boolean = false,
)

data class NodeSwitchResult(
    val nodeName: String,
    /** A running game moved to the new node without leaving the world. */
    val movedRunningGame: Boolean = false,
)

data class SettingsUiState(
    val loading: Boolean = true,
    val draft: SettingsDraft = SettingsDraft(),
    val totalMemoryMb: Int = 0,
    val saving: Boolean = false,
    val switchingNode: Boolean = false,
    val downloadQuota: DownloadQuota.Vo? = null,
    val downloadQuotaLoading: Boolean = false,
    val downloadQuotaError: String? = null,
    val errorMessage: String? = null,
    val invalidModpacksChecking: Boolean = false,
    val invalidModpacksCleaning: Boolean = false,
    val pendingInvalidModpacks: List<ModpackLocalDir> = emptyList(),
    val invalidModpackSuccessMessage: String? = null,
    val invalidModpackErrorMessage: String? = null,
)

sealed interface SettingsEvent {
    data class ShowSnackbar(val message: String) : SettingsEvent
}

interface SettingsGateway {
    suspend fun loadSettings(): Result<SettingsInitialData>

    suspend fun saveSettings(draft: SettingsDraft): Result<Unit>

    suspend fun switchNode(request: NodeSwitchRequest): Result<NodeSwitchResult>

    suspend fun loadDownloadQuota(): Result<DownloadQuota.Vo>

    fun clearDownloadCache(): Result<String>

    suspend fun checkInvalidModpacks(): Result<List<ModpackLocalDir>>

    suspend fun deleteInvalidModpack(packdir: ModpackLocalDir): Result<Unit>
}

class RdiSettingsGateway : SettingsGateway {
    override suspend fun loadSettings(): Result<SettingsInitialData> = resultOf {
        val config = AppConfig.load()
        SettingsInitialData(
            draft = config.toSettingsDraft(),
            totalMemoryMb = SettingsService.getTotalPhysicalMemoryMb(),
        )
    }

    override suspend fun saveSettings(draft: SettingsDraft): Result<Unit> = resultOf {
        SettingsService.saveSettings(
            preferModMirror = draft.preferModMirror,
            preferMcMirror = draft.preferMcMirror,
            maxMemoryText = draft.maxMemoryText,
            proxyEnabled = draft.proxyEnabled,
            proxySystem = draft.proxySystem,
            proxyHost = draft.proxyHost,
            proxyPortText = draft.proxyPortText,
            proxyUsr = draft.proxyUsr,
            proxyPwd = draft.proxyPwd,
            solidWindow = draft.solidWindow,
        ).getOrThrow()
    }

    override suspend fun switchNode(request: NodeSwitchRequest): Result<NodeSwitchResult> = resultOf {
        val entry = NodeRefreshCoordinator.refreshCurrent(request.gameBackup, request.forceMain).getOrThrow()
        val moved = LocalMcProxyService.applyRoute().getOrElse { cause ->
            lgr.warn(cause) { "无法让运行中的游戏切换节点，下次进入房间时生效" }
            false
        }
        NodeSwitchResult(entry.nodeName, moved)
    }

    override suspend fun loadDownloadQuota(): Result<DownloadQuota.Vo> = resultOf {
        val response = server.makeRequest<DownloadQuota.Vo>("download/quota")
        if (!response.ok) throw RequestError(response.msg.ifBlank { "下载额度读取失败" })
        response.data ?: throw RequestError("下载额度读取失败")
    }

    override fun clearDownloadCache(): Result<String> = runCatching {
        submitDownloadCacheCleanupTask2()
    }

    override suspend fun checkInvalidModpacks(): Result<List<ModpackLocalDir>> =
        with(ModpackService) { getInvalidLocalPackDirs() }

    override suspend fun deleteInvalidModpack(packdir: ModpackLocalDir): Result<Unit> =
        ModpackService.deleteLocalPack(packdir)
}

class SettingsViewModel(
    private val gateway: SettingsGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events: Flow<SettingsEvent> = eventChannel.receiveAsFlow()

    init {
        loadSettings()
        refreshDownloadQuota()
    }

    fun updateDraft(draft: SettingsDraft) {
        _uiState.update { it.copy(draft = draft, errorMessage = null) }
    }

    fun save() {
        val state = _uiState.value
        if (state.loading || state.saving) return
        validate(state.draft, state.totalMemoryMb).onFailure { cause ->
            _uiState.update { it.copy(errorMessage = cause.message ?: "设置无效") }
            return
        }

        _uiState.update { it.copy(saving = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    gateway.saveSettings(state.draft).getOrThrow()
                }
                eventChannel.send(SettingsEvent.ShowSnackbar("设置已保存"))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "保存设置失败" }
                _uiState.update { it.copy(errorMessage = "保存失败: ${cause.message ?: "未知错误"}") }
            } finally {
                _uiState.update { it.copy(saving = false) }
            }
        }
    }

    fun switchNode(
        gameBackup: Boolean = false,
        forceMain: Boolean = false,
    ) {
        if (_uiState.value.switchingNode) return
        _uiState.update { it.copy(switchingNode = true) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    gateway.switchNode(NodeSwitchRequest(gameBackup, forceMain)).getOrThrow()
                }
                val message = if (result.movedRunningGame) {
                    "已切换到${result.nodeName}，不用退出地图"
                } else {
                    "已临时切换到${result.nodeName}"
                }
                eventChannel.send(SettingsEvent.ShowSnackbar(message))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "备用节点刷新失败" }
                eventChannel.send(SettingsEvent.ShowSnackbar(cause.message ?: "备用节点刷新失败"))
            } finally {
                _uiState.update { it.copy(switchingNode = false) }
            }
        }
    }

    fun refreshDownloadQuota() {
        if (_uiState.value.downloadQuotaLoading) return
        _uiState.update { it.copy(downloadQuotaLoading = true, downloadQuotaError = null) }
        viewModelScope.launch {
            try {
                val quota = withContext(Dispatchers.IO) {
                    gateway.loadDownloadQuota().getOrThrow()
                }
                _uiState.update { it.copy(downloadQuota = quota) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "下载额度读取失败" }
                _uiState.update {
                    it.copy(downloadQuotaError = cause.message ?: "下载额度读取失败")
                }
            } finally {
                _uiState.update { it.copy(downloadQuotaLoading = false) }
            }
        }
    }

    fun clearDownloadCache() {
        gateway.clearDownloadCache()
            .onSuccess {
                eventChannel.trySend(SettingsEvent.ShowSnackbar("清除下载缓存任务已提交，可在任务中查看进度"))
            }
            .onFailure { cause ->
                lgr.warn(cause) { "清除下载缓存任务提交失败" }
                eventChannel.trySend(SettingsEvent.ShowSnackbar("清除下载缓存任务提交失败：${cause.message ?: "未知错误"}"))
            }
    }

    fun checkInvalidModpacks() {
        val state = _uiState.value
        if (state.invalidModpacksChecking || state.invalidModpacksCleaning) return
        _uiState.update {
            it.copy(
                invalidModpacksChecking = true,
                pendingInvalidModpacks = emptyList(),
                invalidModpackSuccessMessage = null,
                invalidModpackErrorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val invalid = withContext(Dispatchers.IO) {
                    gateway.checkInvalidModpacks().getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        pendingInvalidModpacks = invalid,
                        invalidModpackSuccessMessage = if (invalid.isEmpty()) "没有无效整合包" else null,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "检查无效整合包失败" }
                _uiState.update {
                    it.copy(
                        pendingInvalidModpacks = emptyList(),
                        invalidModpackErrorMessage = "检查无效整合包失败：${cause.message ?: "未知错误"}",
                    )
                }
            } finally {
                _uiState.update { it.copy(invalidModpacksChecking = false) }
            }
        }
    }

    fun dismissInvalidModpackConfirmation() {
        _uiState.update { it.copy(pendingInvalidModpacks = emptyList()) }
    }

    fun confirmInvalidModpackCleanup() {
        val state = _uiState.value
        if (state.invalidModpacksChecking || state.invalidModpacksCleaning) return
        val packs = state.pendingInvalidModpacks
        if (packs.isEmpty()) return
        _uiState.update {
            it.copy(
                pendingInvalidModpacks = emptyList(),
                invalidModpacksCleaning = true,
                invalidModpackSuccessMessage = null,
                invalidModpackErrorMessage = null,
            )
        }
        viewModelScope.launch {
            var successCount = 0
            val failures = mutableListOf<String>()
            try {
                for (pack in packs) {
                    try {
                        withContext(Dispatchers.IO) {
                            gateway.deleteInvalidModpack(pack).getOrThrow()
                        }
                        successCount++
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (cause: Throwable) {
                        lgr.warn(cause) { "清理无效整合包${pack.versionId}失败" }
                        failures += "${pack.versionId}: ${cause.message ?: "未知错误"}"
                    }
                }
                if (failures.isEmpty()) {
                    _uiState.update {
                        it.copy(invalidModpackSuccessMessage = "已清理${successCount}个无效整合包")
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            invalidModpackErrorMessage =
                                "清理无效整合包完成：成功${successCount}个，失败${failures.size}个（${failures.joinToString("；")}）"
                        )
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                _uiState.update { it.copy(invalidModpacksCleaning = false) }
            }
        }
    }

    fun clearInvalidModpackSuccessMessage() {
        _uiState.update { it.copy(invalidModpackSuccessMessage = null) }
    }

    fun clearInvalidModpackErrorMessage() {
        _uiState.update { it.copy(invalidModpackErrorMessage = null) }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                val initialData = withContext(Dispatchers.IO) {
                    gateway.loadSettings().getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        loading = false,
                        draft = initialData.draft,
                        totalMemoryMb = initialData.totalMemoryMb,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "设置读取失败" }
                _uiState.update {
                    it.copy(
                        loading = false,
                        errorMessage = "设置读取失败: ${cause.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    private fun validate(draft: SettingsDraft, totalMemoryMb: Int): Result<Unit> = try {
        val memoryValidation = SettingsService.validateMemory(draft.maxMemoryText, totalMemoryMb)
        check(memoryValidation.success) { memoryValidation.errorMessage ?: "最大内存设置无效" }

        val proxyValidation = SettingsService.validateProxyPort(draft.proxyPortText)
        check(proxyValidation.success) { proxyValidation.errorMessage ?: "代理端口设置无效" }
        Result.success(Unit)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (cause: Throwable) {
        Result.failure(cause)
    }
}

private fun AppConfig.toSettingsDraft() = SettingsDraft(
    preferModMirror = preferModMirror,
    preferMcMirror = preferMcMirror,
    maxMemoryText = maxMemory.takeIf { it > 0 }?.toString().orEmpty(),
    proxyEnabled = proxyConfig?.enabled ?: false,
    proxySystem = proxyConfig?.systemProxy ?: false,
    proxyHost = proxyConfig?.host ?: "127.0.0.1",
    proxyPortText = (proxyConfig?.port ?: 10808).toString(),
    proxyUsr = proxyConfig?.usr.orEmpty(),
    proxyPwd = proxyConfig?.pwd.orEmpty(),
    solidWindow = solidWindow,
)

private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (cause: Throwable) {
    Result.failure(cause)
}
