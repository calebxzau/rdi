package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzhou.rdi.client.database.ModpackLaunchOptionsRecord
import calebxzhou.rdi.client.database.ModpackLaunchOptionsStore
import calebxzhou.rdi.client.service.SettingsService
import io.github.oshai.kotlinlogging.KotlinLogging
import calebxzau.rdi.client.service.normalizeModpackJvmParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val lgr = KotlinLogging.logger {}

const val DEFAULT_JDWP_PARAM = "transport=dt_socket,server=y,suspend=y,address=*:5005"

@kotlinx.serialization.Serializable
data class ModpackOptionDraft(
    val javaCustomEnabled: Boolean = false,
    val javaPath: String = "",
    val memoryCustomEnabled: Boolean = false,
    val maxMemoryText: String = "",
    val jdwpEnabled: Boolean = false,
    val jdwpParam: String = DEFAULT_JDWP_PARAM,
    val customJvmParams: String = "",
    val forgeguardDisabled: Boolean = false,
)

data class ModpackOptionUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val totalMemoryMb: Int = 0,
    val draft: ModpackOptionDraft = ModpackOptionDraft(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val errorMessage: String? = null,
    val completedAction: ModpackOptionAction? = null,
)

enum class ModpackOptionAction {
    NAVIGATE_BACK,
}

interface ModpackOptionRuntime {
    fun getTotalPhysicalMemoryMb(): Int

    fun validateJdkPath(rawPath: String): Result<String>

    fun validateMaxMemory(maxMemoryText: String, totalMemoryMb: Int): Result<Int>
}

object SettingsModpackOptionRuntime : ModpackOptionRuntime {
    override fun getTotalPhysicalMemoryMb(): Int =
        SettingsService.getTotalPhysicalMemoryMb()

    override fun validateJdkPath(rawPath: String): Result<String> =
        SettingsService.validateJdkPath(rawPath)

    override fun validateMaxMemory(maxMemoryText: String, totalMemoryMb: Int): Result<Int> = runCatching {
        val validation = SettingsService.validateMemory(maxMemoryText, totalMemoryMb)
        check(validation.success) { validation.errorMessage ?: "最大内存设置无效" }
        maxMemoryText.trim().toIntOrNull()?.takeIf { it > 4096 }
            ?: error("自定义最大内存必须大于4096MB")
    }
}

class ModpackOptionViewModel(
    private val versionId: String,
    private val store: ModpackLaunchOptionsStore,
    private val runtime: ModpackOptionRuntime,
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ModpackOptionUiState())
    val uiState: StateFlow<ModpackOptionUiState> = _uiState.asStateFlow()

    private var savedDraft = ModpackOptionDraft()

    init {
        load()
    }

    fun updateDraft(draft: ModpackOptionDraft) {
        _uiState.update {
            it.copy(
                draft = draft,
                dirty = !it.loading && draft != savedDraft,
                errorMessage = null,
            )
        }
    }

    fun resetDefaults() {
        updateDraft(ModpackOptionDraft())
    }

    fun save(returnAfterSave: Boolean = false) {
        val state = _uiState.value
        if (state.loading || state.loadFailed || state.saving) return

        val draft = state.draft
        _uiState.update { it.copy(saving = true, errorMessage = null, completedAction = null) }
        (scope ?: viewModelScope).launch(Dispatchers.IO) {
            try {
                val record = buildRecord(draft, _uiState.value.totalMemoryMb)
                if (record == null) {
                    store.delete(versionId).getOrThrow()
                } else {
                    store.upsert(record).getOrThrow()
                }
                savedDraft = draft
                _uiState.update { current ->
                    current.copy(
                        saving = false,
                        dirty = current.draft != savedDraft,
                        errorMessage = null,
                        completedAction = if (returnAfterSave) {
                            ModpackOptionAction.NAVIGATE_BACK
                        } else {
                            null
                        },
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                lgr.warn(cause) { "保存整合包设置失败" }
                _uiState.update {
                    it.copy(
                        saving = false,
                        errorMessage = "保存整合包设置失败：${cause.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearCompletedAction() {
        _uiState.update { it.copy(completedAction = null) }
    }

    private fun load() {
        (scope ?: viewModelScope).launch(Dispatchers.IO) {
            try {
                val totalMemoryMb = runtime.getTotalPhysicalMemoryMb()
                val record = store.find(versionId).getOrThrow()
                val draft = record.toDraft()
                savedDraft = draft
                _uiState.update {
                    it.copy(
                        loading = false,
                        loadFailed = false,
                        totalMemoryMb = totalMemoryMb,
                        draft = draft,
                        dirty = false,
                        errorMessage = null,
                    )
                }
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                lgr.warn(cause) { "读取整合包设置失败" }
                _uiState.update {
                    it.copy(
                        loading = false,
                        loadFailed = true,
                        errorMessage = "读取整合包设置失败：${cause.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    private fun buildRecord(
        draft: ModpackOptionDraft,
        totalMemoryMb: Int,
    ): ModpackLaunchOptionsRecord? {
        val customJavaPath = if (draft.javaCustomEnabled) {
            runtime.validateJdkPath(draft.javaPath.trim()).getOrThrow()
        } else {
            null
        }
        val customMemory = if (draft.memoryCustomEnabled) {
            runtime.validateMaxMemory(draft.maxMemoryText, totalMemoryMb).getOrThrow()
        } else {
            null
        }
        val rawJdwpParam = draft.jdwpParam.trim().takeIf(String::isNotBlank)
        if (draft.jdwpEnabled) {
            require(!rawJdwpParam.isNullOrBlank()) { "请输入JDWP参数" }
            require(!rawJdwpParam.contains('\n') && !rawJdwpParam.contains('\r')) {
                "JDWP参数不能包含换行"
            }
            require(!rawJdwpParam.startsWith("-agentlib:jdwp=")) {
                "JDWP参数只填写等号右侧内容"
            }
        }
        val hasJdwpOverride = draft.jdwpEnabled ||
            (rawJdwpParam != null && rawJdwpParam != DEFAULT_JDWP_PARAM)
        val customJvmParams = normalizeModpackJvmParams(draft.customJvmParams).getOrThrow()
        if (customJavaPath == null &&
            customMemory == null &&
            !hasJdwpOverride &&
            customJvmParams.isBlank() &&
            !draft.forgeguardDisabled
        ) {
            return null
        }
        return ModpackLaunchOptionsRecord(
            versionId = versionId,
            javaPath = customJavaPath,
            maxMemoryMb = customMemory,
            jdwpEnabled = draft.jdwpEnabled,
            jdwpParam = rawJdwpParam,
            customJvmParams = customJvmParams,
            forgeguardDisabled = draft.forgeguardDisabled,
        )
    }
}

private fun ModpackLaunchOptionsRecord?.toDraft(): ModpackOptionDraft = ModpackOptionDraft(
    javaCustomEnabled = this?.javaPath != null,
    javaPath = this?.javaPath.orEmpty(),
    memoryCustomEnabled = this?.maxMemoryMb != null,
    maxMemoryText = this?.maxMemoryMb?.toString().orEmpty(),
    jdwpEnabled = this?.jdwpEnabled == true,
    jdwpParam = this?.jdwpParam?.takeIf(String::isNotBlank) ?: DEFAULT_JDWP_PARAM,
    customJvmParams = this?.customJvmParams.orEmpty(),
    forgeguardDisabled = this?.forgeguardDisabled == true,
)
