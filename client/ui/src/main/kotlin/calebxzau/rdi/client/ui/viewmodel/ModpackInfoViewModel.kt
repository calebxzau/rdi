package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.client.lgr
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.ModpackService
import calebxzhou.rdi.client.service.ModpackService.modpackInstallTaskKey
import calebxzhou.rdi.client.service.ModpackService.startInstallTask2
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.model.Modpack
import calebxzau.rdi.client.service.currentBaseWorldApi
import calebxzhou.rdi.common.net.json
import calebxzhou.rdi.common.service.validate
import calebxzhou.rdi.common.util.validateModpackName
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
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

data class ModpackInfoEditDraft(
    val name: String = "",
    val iconUrl: String = "",
    val info: String = "",
    val sourceUrl: String = "",
    val categories: List<Modpack.Category> = emptyList(),
)

data class ModpackInfoUiState(
    val loading: Boolean = true,
    val pack: Modpack.DetailVo? = null,
    val editDraft: ModpackInfoEditDraft = ModpackInfoEditDraft(),
    val savingEdit: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface ModpackInfoEvent {
    data class ShowSnackbar(val message: String) : ModpackInfoEvent

    data class EditSaved(val message: String) : ModpackInfoEvent

    data class SelectDownloadMethod(val versionName: String) : ModpackInfoEvent

    data class ConfirmRedownload(val versionName: String) : ModpackInfoEvent

    data class InstallQueued(val runId: String) : ModpackInfoEvent

    data object PackDeleted : ModpackInfoEvent
}

sealed interface ModpackInfoMutation {
    data class UpdateOptions(val options: Modpack.OptionsDto) : ModpackInfoMutation
    data object DeletePack : ModpackInfoMutation
    data class DeleteVersion(val versionName: String) : ModpackInfoMutation
    data class RebuildVersion(val versionName: String) : ModpackInfoMutation
    data class UpdateVersionBaseWorld(
        val versionName: String,
        val baseWorld: Modpack.Version.BaseWorldBinding?,
    ) : ModpackInfoMutation
}

interface ModpackInfoGateway {
    suspend fun loadDetail(modpackId: String): Result<Modpack.DetailVo?>

    suspend fun loadReadyBaseWorlds(): Result<List<calebxzau.rdi.common.model.BaseWorld>> =
        Result.failure(UnsupportedOperationException("地图模板加载暂不可用"))

    suspend fun mutate(modpackId: String, mutation: ModpackInfoMutation): Result<Unit>

    suspend fun isVersionInstalled(pack: Modpack.DetailVo, version: Modpack.Version): Result<Boolean>

    fun queueInstall(
        pack: Modpack.DetailVo,
        version: Modpack.Version,
        includeClientExtras: Boolean = true,
    ): Result<String>
}

class RdiModpackInfoGateway : ModpackInfoGateway {
    override suspend fun loadDetail(modpackId: String): Result<Modpack.DetailVo?> = resultOf {
        val response = server.makeRequest<Modpack.DetailVo>("modpack/$modpackId/detail")
        if (!response.ok) throw RequestError(response.msg)
        response.data
    }

    override suspend fun loadReadyBaseWorlds(): Result<List<calebxzau.rdi.common.model.BaseWorld>> = resultOf {
        currentBaseWorldApi().listReady()
    }

    override suspend fun mutate(
        modpackId: String,
        mutation: ModpackInfoMutation,
    ): Result<Unit> = resultOf {
        val response = when (mutation) {
            is ModpackInfoMutation.UpdateOptions -> server.makeRequest<Unit>(
                path = "modpack/$modpackId/options",
                method = HttpMethod.Put,
            ) {
                json()
                setBody(mutation.options.json)
            }

            ModpackInfoMutation.DeletePack -> server.makeRequest<Unit>(
                path = "modpack/$modpackId",
                method = HttpMethod.Delete,
            )

            is ModpackInfoMutation.DeleteVersion -> server.makeRequest<Unit>(
                path = "modpack/$modpackId/version/${mutation.versionName.encodeURLPathPart()}",
                method = HttpMethod.Delete,
            )

            is ModpackInfoMutation.RebuildVersion -> server.makeRequest<Unit>(
                path = "modpack/$modpackId/version/${mutation.versionName.encodeURLPathPart()}/rebuild",
                method = HttpMethod.Post,
            )

            is ModpackInfoMutation.UpdateVersionBaseWorld -> server.makeRequest<Unit>(
                path = "modpack/$modpackId/version/${mutation.versionName.encodeURLPathPart()}/baseworld",
                method = HttpMethod.Put,
            ) {
                json()
                setBody(Modpack.VersionBaseWorldUpdateDto(mutation.baseWorld).json)
            }

        }
        if (!response.ok) throw RequestError(response.msg)
    }

    override fun queueInstall(
        pack: Modpack.DetailVo,
        version: Modpack.Version,
        includeClientExtras: Boolean,
    ): Result<String> = runCatching {
        ClientTaskManager.submit(
            task = version.startInstallTask2(
                mcVersion = pack.mcVer,
                modLoader = pack.modloader,
                modpackName = pack.name,
                includeClientExtras = includeClientExtras,
            ),
            dedupeKey = modpackInstallTaskKey(version.modpackId, version.name),
        )
    }

    override suspend fun isVersionInstalled(
        pack: Modpack.DetailVo,
        version: Modpack.Version,
    ): Result<Boolean> = runCatching {
        ModpackService.getVersionDir(pack._id, version.name).exists()
    }
}

class ModpackInfoViewModel(
    private val modpackId: String,
    private val gateway: ModpackInfoGateway,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ModpackInfoUiState())
    val uiState: StateFlow<ModpackInfoUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<ModpackInfoEvent>(Channel.BUFFERED)
    val events: Flow<ModpackInfoEvent> = eventChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var downloadCheckJob: Job? = null
    private var downloadCheckGeneration = 0L

    init {
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        _uiState.update {
            it.copy(
                loading = true,
                errorMessage = null,
            )
        }
        loadJob = viewModelScope.launch {
            try {
                val pack = withContext(Dispatchers.IO) {
                    gateway.loadDetail(modpackId).getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        pack = pack,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "加载整合包信息失败" }
                _uiState.update {
                    it.copy(errorMessage = "加载整合包信息失败: ${cause.message ?: "未知错误"}")
                }
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(loading = false) }
                }
            }
        }
    }

    fun beginEdit() {
        val pack = _uiState.value.pack ?: return
        _uiState.update {
            it.copy(
                editDraft = ModpackInfoEditDraft(
                    name = pack.name,
                    iconUrl = pack.icon.orEmpty(),
                    info = pack.info.orEmpty(),
                    sourceUrl = pack.sourceUrl.orEmpty(),
                    categories = pack.categories,
                ),
                errorMessage = null,
            )
        }
    }

    fun updateEditDraft(draft: ModpackInfoEditDraft) {
        _uiState.update { it.copy(editDraft = draft, errorMessage = null) }
    }

    fun saveEdit() {
        val state = _uiState.value
        if (state.savingEdit || state.pack == null) return
        val nameValidation = state.editDraft.name.validateModpackName()
        if (nameValidation.isFailure) {
            reportError("更新失败", nameValidation.exceptionOrNull() ?: RequestError("整合包名称无效"))
            return
        }
        _uiState.update { it.copy(savingEdit = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val options = state.editDraft.toOptions()
                    options.validate().getOrThrow()
                    gateway.mutate(modpackId, ModpackInfoMutation.UpdateOptions(options)).getOrThrow()
                }
                eventChannel.send(ModpackInfoEvent.EditSaved("已更新"))
                reload()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("更新失败", cause)
            } finally {
                if (currentCoroutineContext().isActive) {
                    _uiState.update { it.copy(savingEdit = false) }
                }
            }
        }
    }

    fun deletePack() {
        mutate(
            mutation = ModpackInfoMutation.DeletePack,
            errorPrefix = "删除失败",
            onSuccess = { eventChannel.send(ModpackInfoEvent.PackDeleted) },
        )
    }

    fun requestDownload(version: Modpack.Version) {
        val state = _uiState.value
        val pack = state.pack
        val currentVersion = pack?.versions?.firstOrNull { it.name == version.name }
        if (pack == null) {
            reportError("检查整合包版本失败", IllegalStateException("整合包信息尚未加载"))
            return
        }
        if (currentVersion == null) {
            reportError("检查整合包版本失败", IllegalStateException("未找到版本 V${version.name}"))
            return
        }
        downloadCheckGeneration++
        val requestGeneration = downloadCheckGeneration
        downloadCheckJob?.cancel()
        if (currentVersion.status != Modpack.Status.OK) {
            reportError("检查整合包版本失败", IllegalStateException("当前版本不可下载"))
            return
        }
        downloadCheckJob = viewModelScope.launch {
            try {
                val installed = withContext(Dispatchers.IO) {
                    gateway.isVersionInstalled(pack, currentVersion).getOrThrow()
                }
                if (requestGeneration != downloadCheckGeneration) return@launch
                eventChannel.send(
                    if (installed) ModpackInfoEvent.ConfirmRedownload(currentVersion.name)
                    else ModpackInfoEvent.SelectDownloadMethod(currentVersion.name)
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                if (requestGeneration == downloadCheckGeneration) {
                    reportError("检查整合包版本失败", cause)
                }
            } finally {
                if (requestGeneration == downloadCheckGeneration) {
                    downloadCheckJob = null
                }
            }
        }
    }

    fun installVersion(versionName: String, includeClientExtras: Boolean = false) {
        val state = _uiState.value
        val pack = state.pack
        val version = pack?.versions?.firstOrNull { it.name == versionName }
        if (pack == null) {
            reportError("加入任务列表失败", IllegalStateException("整合包信息尚未加载"))
            return
        }
        if (version == null) {
            reportError("加入任务列表失败", IllegalStateException("未找到版本 V$versionName"))
            return
        }
        if (version.status != Modpack.Status.OK) {
            reportError("加入任务列表失败", IllegalStateException("当前版本不可下载"))
            return
        }
        viewModelScope.launch {
            try {
                val runId = gateway.queueInstall(pack, version, includeClientExtras).getOrThrow()
                eventChannel.send(ModpackInfoEvent.InstallQueued(runId))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("加入任务列表失败", cause)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    override fun onCleared() {
        downloadCheckJob?.cancel()
        eventChannel.close()
        super.onCleared()
    }

    private fun mutate(
        mutation: ModpackInfoMutation,
        errorPrefix: String,
        onSuccess: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    gateway.mutate(modpackId, mutation).getOrThrow()
                }
                onSuccess()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError(errorPrefix, cause)
            }
        }
    }

    private fun reportError(prefix: String, cause: Throwable) {
        lgr.warn(cause) { prefix }
        _uiState.update {
            it.copy(errorMessage = "$prefix: ${cause.message ?: "未知错误"}")
        }
    }
}

private fun ModpackInfoEditDraft.toOptions() = Modpack.OptionsDto(
    name = name.trim().ifBlank { null },
    iconUrl = iconUrl.trim().ifBlank { null },
    info = info.trim().ifBlank { null },
    sourceUrl = sourceUrl.trim().ifBlank { null },
    categories = Modpack.normalizeCategories(categories),
)

private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (cause: Throwable) {
    Result.failure(cause)
}
