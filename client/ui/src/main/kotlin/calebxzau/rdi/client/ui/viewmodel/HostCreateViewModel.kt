package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.client.service.currentBaseWorldApi
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.RAccount
import calebxzau.rdi.common.model.BaseWorld
import calebxzhou.rdi.client.ui.screen.HostKind
import calebxzhou.rdi.common.net.json
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
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
import org.bson.types.ObjectId
import java.util.UUID
import io.ktor.http.encodeURLPathPart

private val lgr by Loggers

data class HostCreateViewModelArgs(
    val hostId: String?,
    val defaultHostName: String,
    val kind: HostKind = HostKind.Legacy,
    val sourceId: String? = null,
    val legacyVersionName: String? = null,
    val displayName: String? = null,
    val legacyMcVersion: String? = null,
)

enum class HostCreateLoadTarget {
    HOST,
}

enum class HostCreateWorldSource {
    Generate,
    Template,
}

data class HostCreateLoadIssue(
    val target: HostCreateLoadTarget,
    val cause: Throwable,
)

data class HostCreateInitialData(
    val host: Host.DetailVo? = null,
    val issues: List<HostCreateLoadIssue> = emptyList(),
)

sealed interface HostCreateSubmission {
    data class CreateLegacy(val dto: Host.CreateDto) : HostCreateSubmission

    data class EditLegacy(
        val hostId: ObjectId,
        val options: Host.OptionsDto,
    ) : HostCreateSubmission
}

sealed interface HostCreateEvent {
    data class LegacyCreateSubmitted(val message: String) : HostCreateEvent

    data class EditSaved(val message: String) : HostCreateEvent

    data class HostPackUpdateSubmitted(val message: String) : HostCreateEvent
}

data class HostCreateUiState(
    val loading: Boolean = true,
    val editHost: Host.DetailVo? = null,
    val editHostId: ObjectId? = null,
    val title: String = "创建新房间",
    val hostName: String,
    val intro: String? = null,
    val selectedModpackId: String = "",
    val hostKind: HostKind = HostKind.Legacy,
    val packSourceId: String? = null,
    val packDisplayName: String = "",
    val selectedVersionName: String = "",
    val difficulty: Int = 3,
    val gameMode: Int = 0,
    val currentMcVersion: McVersion? = null,
    val levelType: String = "minecraft:normal",
    val levelChoice: Int = 0,
    val customLevelTypeText: String = "",
    val customLevelTypeError: String? = null,
    val levelTypeDirty: Boolean = false,
    val worldSource: HostCreateWorldSource = HostCreateWorldSource.Generate,
    val selectedBaseWorldId: UUID? = null,
    val baseWorlds: List<BaseWorld> = emptyList(),
    val baseWorldsLoading: Boolean = false,
    val baseWorldsErrorMessage: String? = null,
    val baseWorldSelectionOpen: Boolean = false,
    val configuredBaseWorld: Modpack.Version.BaseWorldBinding? = null,
    val baseWorldBindingErrorMessage: String? = null,
    val baseWorldSelectionOverridden: Boolean = false,
    val whitelist: Boolean = true,
    val allowCheats: Boolean = false,
    val gameRules: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val submitting: Boolean = false,
    val updatingPack: Boolean = false,
    val friendQq: String = "",
    val friendLookupLoading: Boolean = false,
    val friendLookupError: String? = null,
    val friendDialogOpen: Boolean = false,
    val initialMembers: List<RAccount.Dto> = emptyList(),
) {
    val isEditMode: Boolean get() = editHostId != null
    val isLegacyCreate: Boolean get() = !isEditMode && hostKind == HostKind.Legacy
    val canSubmit: Boolean
        get() = !loading && !submitting && !friendLookupLoading &&
            (!isLegacyCreate || initialMembers.isNotEmpty())
    val selectedPackTitle: String
        get() = packDisplayName.ifBlank { "未选择整合包" }
}

interface HostCreateGateway {
    suspend fun loadInitial(hostId: ObjectId?): Result<HostCreateInitialData>

    suspend fun findFriend(qq: String): Result<RAccount.Dto> =
        Result.failure(UnsupportedOperationException("好友查询暂不可用"))

    suspend fun loadBaseWorlds(): Result<List<BaseWorld>> =
        Result.failure(UnsupportedOperationException("地图模板加载暂不可用"))

    suspend fun loadVersionBaseWorld(
        modpackId: String,
        versionName: String,
    ): Result<Modpack.Version.BaseWorldBinding?> = Result.success(null)

    suspend fun createOrUpdate(submission: HostCreateSubmission): Result<Unit>

    suspend fun requestHostPackUpdate(hostId: ObjectId): Result<Unit>
}

class RdiHostCreateGateway : HostCreateGateway {
    override suspend fun findFriend(qq: String): Result<RAccount.Dto> =
        loadRemote("player/by-qq/${qq.encodeURLPathPart()}")

    override suspend fun loadInitial(hostId: ObjectId?): Result<HostCreateInitialData> = resultOf {
        val host = hostId?.let { loadRemote<Host.DetailVo>("host/${it.toHexString()}/detail") }
        val issues = buildList {
            host?.exceptionOrNull()?.let { add(HostCreateLoadIssue(HostCreateLoadTarget.HOST, it)) }
        }
        val loadedHost = when {
            host == null -> null
            host.isSuccess -> host.getOrThrow()
            else -> null
        }
        HostCreateInitialData(
            host = loadedHost,
            issues = issues,
        )
    }

    override suspend fun loadBaseWorlds(): Result<List<BaseWorld>> = resultOf {
        currentBaseWorldApi().listReady()
    }

    override suspend fun loadVersionBaseWorld(
        modpackId: String,
        versionName: String,
    ): Result<Modpack.Version.BaseWorldBinding?> = resultOf {
        val detail = loadRemote<Modpack.DetailVo>(
            "modpack/$modpackId/detail",
        ).getOrThrow()
        detail.versions.firstOrNull { it.name == versionName }?.baseWorld
    }

    override suspend fun createOrUpdate(submission: HostCreateSubmission): Result<Unit> = resultOf {
        when (submission) {
            is HostCreateSubmission.CreateLegacy -> {
                val response = server.makeRequest<Unit>(
                    path = "host/v2",
                    method = HttpMethod.Post,
                ) {
                    json()
                    setBody(submission.dto)
                }
                if (!response.ok) throw RequestError(response.msg)
                Unit
            }

            is HostCreateSubmission.EditLegacy -> {
                val response = server.makeRequest<Unit>(
                    path = "host/${submission.hostId.toHexString()}/options",
                    method = HttpMethod.Put,
                ) {
                    json()
                    setBody(submission.options)
                }
                if (!response.ok) throw RequestError(response.msg)
                Unit
            }
        }
    }

    override suspend fun requestHostPackUpdate(hostId: ObjectId): Result<Unit> = resultOf {
        val response = server.makeRequest<Unit>(
            path = "host/${hostId.toHexString()}/update",
            method = HttpMethod.Post,
        )
        if (!response.ok) throw RequestError(response.msg)
    }

    private suspend inline fun <reified T> loadRemote(path: String): Result<T> = resultOf {
        val response = server.makeRequest<T>(path)
        if (!response.ok) throw RequestError(response.msg)
        response.data ?: throw RequestError("服务器未返回数据")
    }
}

class HostCreateViewModel(
    args: HostCreateViewModelArgs,
    private val gateway: HostCreateGateway,
) : ViewModel() {
    private val requestedHostId = args.hostId
        ?.trim()
        ?.takeIf(ObjectId::isValid)
        ?.let(::ObjectId)

    private val _uiState = MutableStateFlow(
        HostCreateUiState(
            hostName = args.defaultHostName,
            editHostId = requestedHostId,
            hostKind = args.kind,
            packSourceId = args.sourceId?.trim()?.takeIf(String::isNotBlank),
            selectedVersionName = args.legacyVersionName?.trim().orEmpty(),
            packDisplayName = args.displayName?.trim().orEmpty(),
            currentMcVersion = if (args.kind == HostKind.Legacy && requestedHostId == null) {
                parseMcVersion(args.legacyMcVersion)
            } else {
                null
            },
        )
    )
    val uiState: StateFlow<HostCreateUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<HostCreateEvent>(Channel.BUFFERED)
    val events: Flow<HostCreateEvent> = eventChannel.receiveAsFlow()

    private var baseWorldGeneration = 0L
    private var baseWorldLoadJob: kotlinx.coroutines.Job? = null
    private var sessionIdentity: String? = null
    private var currentPlayerId: ObjectId? = null
    private var friendLookupGeneration = 0L
    private var friendLookupJob: kotlinx.coroutines.Job? = null

    fun openFriendDialog() {
        if (!_uiState.value.isLegacyCreate || _uiState.value.submitting) return
        updateFriendQq("")
        _uiState.update { it.copy(friendDialogOpen = true) }
    }

    fun dismissFriendDialog() {
        updateFriendQq("")
        _uiState.update { it.copy(friendDialogOpen = false) }
    }

    fun updateFriendQq(value: String) {
        if (_uiState.value.submitting) return
        friendLookupGeneration++
        friendLookupJob?.cancel()
        _uiState.update {
            it.copy(friendQq = value, friendLookupLoading = false, friendLookupError = null)
        }
    }

    fun addFriend() {
        val state = _uiState.value
        if (!state.isLegacyCreate || !state.friendDialogOpen || state.submitting || state.friendLookupLoading) return
        if (state.initialMembers.size >= Host.MAX_MEMBERS - 1) {
            _uiState.update { it.copy(friendLookupError = "最多添加${Host.MAX_MEMBERS - 1}位好友") }
            return
        }
        val qq = state.friendQq.trim()
        if (qq.isEmpty() || qq.length > 20 || !qq.all { it.isDigit() }) {
            _uiState.update { it.copy(friendLookupError = "请输入正确的QQ号") }
            return
        }
        val generation = ++friendLookupGeneration
        _uiState.update { it.copy(friendLookupLoading = true, friendLookupError = null) }
        friendLookupJob = viewModelScope.launch {
            try {
                val friend = withContext(Dispatchers.IO) { gateway.findFriend(qq).getOrThrow() }
                if (generation != friendLookupGeneration) return@launch
                val problem = when {
                    friend.id == currentPlayerId -> "不能邀请自己，请添加其他好友"
                    _uiState.value.initialMembers.any { it.id == friend.id } -> "已添加这位好友"
                    else -> null
                }
                _uiState.update {
                    if (problem != null) it.copy(friendLookupError = problem)
                    else it.copy(
                        initialMembers = it.initialMembers + friend,
                        friendQq = "",
                        friendDialogOpen = false,
                        friendLookupError = null,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == friendLookupGeneration) {
                    lgr.warn(error) { "添加好友失败" }
                    _uiState.update { it.copy(friendLookupError = error.message ?: "添加失败，请重试") }
                }
            } finally {
                if (generation == friendLookupGeneration) {
                    _uiState.update { it.copy(friendLookupLoading = false) }
                }
            }
        }
    }

    fun removeFriend(id: ObjectId) {
        if (_uiState.value.submitting) return
        _uiState.update { it.copy(initialMembers = it.initialMembers.filterNot { friend -> friend.id == id }) }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun clearErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearBaseWorldsError() {
        _uiState.update { it.copy(baseWorldsErrorMessage = null) }
    }

    init {
        loadInitial()
    }

    fun updateHostName(name: String) {
        _uiState.update { it.copy(hostName = name) }
    }

    fun updateIntro(intro: String) {
        _uiState.update { it.copy(intro = intro) }
    }

    fun updateDifficulty(difficulty: Int) {
        _uiState.update { it.copy(difficulty = difficulty) }
    }

    fun updateGameMode(gameMode: Int) {
        _uiState.update { it.copy(gameMode = gameMode) }
    }

    fun selectLevelChoice(choice: Int) {
        if (_uiState.value.configuredBaseWorld?.required == true) return
        _uiState.update {
            when (choice) {
                0 -> it.copy(levelChoice = 0, levelType = "minecraft:normal", levelTypeDirty = true, worldSource = HostCreateWorldSource.Generate, selectedBaseWorldId = null, baseWorldSelectionOverridden = it.configuredBaseWorld != null, baseWorldBindingErrorMessage = null)
                1 -> it.copy(levelChoice = 1, levelType = "minecraft:flat", levelTypeDirty = true, worldSource = HostCreateWorldSource.Generate, selectedBaseWorldId = null, baseWorldSelectionOverridden = it.configuredBaseWorld != null, baseWorldBindingErrorMessage = null)
                2 -> it.copy(
                    levelChoice = 2,
                    levelType = skyblockLevelType(it.currentMcVersion),
                    levelTypeDirty = true,
                    worldSource = HostCreateWorldSource.Generate,
                    selectedBaseWorldId = null,
                    baseWorldSelectionOverridden = it.configuredBaseWorld != null,
                    baseWorldBindingErrorMessage = null,
                )
                3 -> it.copy(levelChoice = 3, worldSource = HostCreateWorldSource.Generate, selectedBaseWorldId = null, baseWorldSelectionOverridden = it.configuredBaseWorld != null, baseWorldBindingErrorMessage = null)
                else -> it
            }
        }
    }

    fun beginCustomLevelType() {
        if (_uiState.value.configuredBaseWorld?.required == true) return
        _uiState.update {
            it.copy(
                customLevelTypeText = if (it.levelChoice == 3) it.levelType else it.customLevelTypeText,
                customLevelTypeError = null,
            )
        }
    }

    fun updateCustomLevelTypeText(text: String) {
        _uiState.update { it.copy(customLevelTypeText = text, customLevelTypeError = null) }
    }

    fun applyCustomLevelType(): Boolean {
        if (_uiState.value.configuredBaseWorld?.required == true) return false
        val trimmed = _uiState.value.customLevelTypeText.trim()
        if (trimmed.isBlank()) {
            _uiState.update { it.copy(customLevelTypeError = "请输入地形ID") }
            return false
        }
        _uiState.update {
            it.copy(
                levelChoice = 3,
                levelType = trimmed,
                customLevelTypeText = trimmed,
                customLevelTypeError = null,
                levelTypeDirty = true,
                worldSource = HostCreateWorldSource.Generate,
                selectedBaseWorldId = null,
                baseWorldSelectionOverridden = it.configuredBaseWorld != null,
                baseWorldBindingErrorMessage = null,
            )
        }
        return true
    }

    fun cancelCustomLevelType() {
        _uiState.update { it.copy(customLevelTypeError = null) }
    }

    fun updateWhitelist(whitelist: Boolean) {
        _uiState.update { it.copy(whitelist = whitelist) }
    }

    fun updateAllowCheats(allowCheats: Boolean) {
        _uiState.update { it.copy(allowCheats = allowCheats) }
    }

    fun updateGameRules(gameRules: Map<String, String>) {
        _uiState.update { it.copy(gameRules = gameRules.toMap()) }
    }

    fun openBaseWorldSelection() {
        if (!_uiState.value.isLegacyCreate || _uiState.value.configuredBaseWorld?.required == true) return
        _uiState.update { it.copy(baseWorldSelectionOpen = true, baseWorldsErrorMessage = null) }
        loadBaseWorldChoices()
    }

    fun cancelBaseWorldSelection() {
        val cancelLoad = _uiState.value.worldSource == HostCreateWorldSource.Generate
        if (cancelLoad) {
            baseWorldGeneration++
            baseWorldLoadJob?.cancel()
        }
        _uiState.update {
            it.copy(
                baseWorldSelectionOpen = false,
                baseWorldsLoading = if (cancelLoad) false else it.baseWorldsLoading,
                baseWorldsErrorMessage = null,
            )
        }
    }

    fun commitBaseWorldSelection(worldId: UUID) {
        val state = _uiState.value
        if (!state.isBaseWorldSelectionAllowed() || state.submitting || state.configuredBaseWorld?.required == true) return
        if (state.baseWorlds.none { it.id == worldId }) return
        _uiState.update {
            it.copy(
                worldSource = HostCreateWorldSource.Template,
                selectedBaseWorldId = worldId,
                baseWorldSelectionOpen = false,
                baseWorldSelectionOverridden = state.configuredBaseWorld != null,
                baseWorldsErrorMessage = null,
            )
        }
    }

    fun loadBaseWorldChoices() {
        val state = _uiState.value
        if (!state.isLegacyCreate || (!state.baseWorldSelectionOpen && state.worldSource != HostCreateWorldSource.Template)) return
        val requestGeneration = ++baseWorldGeneration
        val identityAtStart = sessionIdentity
        baseWorldLoadJob?.cancel()
        _uiState.update { it.copy(baseWorldsLoading = true, baseWorldsErrorMessage = null) }
        baseWorldLoadJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { gateway.loadBaseWorlds() }
                if (!isCurrentBaseWorldRequest(requestGeneration, identityAtStart)) return@launch
                result.fold(
                    onSuccess = { worlds -> applyBaseWorldChoices(worlds) },
                    onFailure = { error -> _uiState.update { it.copy(baseWorldsLoading = false, baseWorldsErrorMessage = error.message ?: "加载地图模板失败") } },
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                if (isCurrentBaseWorldRequest(requestGeneration, identityAtStart)) {
                    _uiState.update { it.copy(baseWorldsLoading = false, baseWorldsErrorMessage = error.message ?: "加载地图模板失败") }
                }
            }
        }
    }

    fun refreshBaseWorlds() {
        if (!isBaseWorldSelectionAllowed()) return
        loadBaseWorldChoices()
    }

    fun updateSessionIdentity(identity: String, playerId: ObjectId? = null) {
        currentPlayerId = playerId
        if (sessionIdentity == identity) return
        sessionIdentity = identity
        friendLookupGeneration++
        friendLookupJob?.cancel()
        baseWorldGeneration++
        baseWorldLoadJob?.cancel()
        val shouldRefresh = _uiState.value.isLegacyCreate &&
            _uiState.value.worldSource == HostCreateWorldSource.Template
        _uiState.update {
            it.copy(
                worldSource = HostCreateWorldSource.Generate,
                selectedBaseWorldId = null,
                baseWorlds = emptyList(),
                baseWorldsLoading = false,
                baseWorldsErrorMessage = null,
                baseWorldSelectionOpen = false,
                baseWorldSelectionOverridden = false,
                friendQq = "",
                friendDialogOpen = false,
                friendLookupLoading = false,
                friendLookupError = null,
                initialMembers = emptyList(),
            )
        }
        if (shouldRefresh) refreshBaseWorlds()
    }

    private fun isCurrentBaseWorldRequest(generation: Long, identity: String?): Boolean =
        generation == baseWorldGeneration && identity == sessionIdentity &&
            isBaseWorldSelectionAllowed()

    private fun isBaseWorldSelectionAllowed(): Boolean {
        val state = _uiState.value
        return state.isLegacyCreate && (state.baseWorldSelectionOpen || state.worldSource == HostCreateWorldSource.Template)
    }

    private fun HostCreateUiState.isBaseWorldSelectionAllowed(): Boolean =
        isLegacyCreate && (baseWorldSelectionOpen || worldSource == HostCreateWorldSource.Template)

    private fun applyBaseWorldChoices(worlds: List<BaseWorld>) {
        _uiState.update { state ->
            val selectedStillAvailable = state.selectedBaseWorldId?.let { id -> worlds.any { it.id == id } } == true
            val configuredStillAvailable = state.configuredBaseWorld?.id?.let { id -> worlds.any { it.id == id } } == true
            val shouldApplyConfigured = state.configuredBaseWorld != null &&
                !state.baseWorldSelectionOverridden && configuredStillAvailable
            val selectedId = when {
                selectedStillAvailable -> state.selectedBaseWorldId
                shouldApplyConfigured -> state.configuredBaseWorld?.id
                else -> null
            }
            val unresolvedBinding = state.configuredBaseWorld != null &&
                !state.baseWorldSelectionOverridden &&
                !configuredStillAvailable
            val nextWorldSource = when {
                selectedId != null -> HostCreateWorldSource.Template
                unresolvedBinding -> HostCreateWorldSource.Template
                state.worldSource == HostCreateWorldSource.Template -> HostCreateWorldSource.Generate
                else -> state.worldSource
            }
            state.copy(
                baseWorlds = worlds,
                selectedBaseWorldId = selectedId,
                worldSource = nextWorldSource,
                baseWorldsLoading = false,
                baseWorldsErrorMessage = null,
                baseWorldBindingErrorMessage = if (unresolvedBinding) {
                    if (state.configuredBaseWorld?.required == true) {
                        "此版本指定的地图模板不可用，请联系整合包管理者"
                    } else {
                        "此版本推荐的地图模板不可用，请重新选择地图模板"
                    }
                } else null,
            )
        }
    }

    fun submit() {
        val state = _uiState.value
        if (state.loading || state.submitting || state.friendLookupLoading) return
        val trimmedName = state.hostName.trim()
        if (trimmedName.isEmpty()) {
            _uiState.update {
                it.copy(statusMessage = "请输入房间名称")
            }
            return
        }
        val submission = state.editHostId?.let { hostId ->
            HostCreateSubmission.EditLegacy(
                hostId = hostId,
                options = Host.OptionsDto(
                    name = trimmedName,
                    difficulty = state.difficulty,
                    gameMode = state.gameMode,
                    levelType = state.levelType.takeIf { state.levelTypeDirty },
                    allowCheats = state.allowCheats,
                    whitelist = state.whitelist,
                    gameRules = state.gameRules.toMap(),
                ),
            )
        } ?: createSubmission(state, trimmedName) ?: return

        _uiState.update { it.copy(submitting = true, statusMessage = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    gateway.createOrUpdate(submission).getOrThrow()
                }
                when (submission) {
                    is HostCreateSubmission.CreateLegacy -> eventChannel.send(
                        HostCreateEvent.LegacyCreateSubmitted("房间创建中，服务器准备完成后会通过邮箱通知你")
                    )
                    is HostCreateSubmission.EditLegacy -> eventChannel.send(HostCreateEvent.EditSaved("设置已保存"))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                val prefix = if (submission is HostCreateSubmission.EditLegacy) "保存失败" else "创建失败"
                lgr.warn(cause) { prefix }
                _uiState.update {
                    it.copy(statusMessage = "$prefix: ${cause.message ?: "未知错误"}")
                }
            } finally {
                _uiState.update { it.copy(submitting = false) }
            }
        }
    }

    fun requestHostPackUpdate() {
        val hostId = _uiState.value.editHostId ?: return
        if (_uiState.value.updatingPack) return
        _uiState.update { it.copy(updatingPack = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    gateway.requestHostPackUpdate(hostId).getOrThrow()
                }
                eventChannel.send(HostCreateEvent.HostPackUpdateSubmitted("已提交更新"))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "更新房间整合包失败" }
                _uiState.update {
                    it.copy(errorMessage = cause.message ?: "更新失败")
                }
            } finally {
                _uiState.update { it.copy(updatingPack = false) }
            }
        }
    }

    private fun loadInitial() {
        viewModelScope.launch {
            try {
                val (initial, binding) = withContext(Dispatchers.IO) {
                    val initial = gateway.loadInitial(requestedHostId).getOrThrow()
                    val binding = if (requestedHostId == null &&
                        _uiState.value.hostKind == HostKind.Legacy &&
                        _uiState.value.packSourceId != null &&
                        _uiState.value.selectedVersionName.isNotBlank()
                    ) {
                        gateway.loadVersionBaseWorld(
                            _uiState.value.packSourceId!!,
                            _uiState.value.selectedVersionName,
                        ).getOrThrow()
                    } else null
                    initial to binding
                }
                _uiState.update { state -> applyInitial(state, initial, binding) }
                if (binding != null) loadBaseWorldChoices()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                lgr.warn(cause) { "加载房间创建数据失败" }
                _uiState.update {
                    it.copy(errorMessage = "无法载入房间创建数据: ${cause.message ?: "未知错误"}")
                }
            } finally {
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    private fun applyInitial(
        state: HostCreateUiState,
        initial: HostCreateInitialData,
        binding: Modpack.Version.BaseWorldBinding? = null,
    ): HostCreateUiState {
        val host = initial.host
        val issueByTarget = initial.issues.associateBy(HostCreateLoadIssue::target)
        val base = state.copy(
            errorMessage = issueByTarget[HostCreateLoadTarget.HOST]?.cause?.let {
                "无法加载房间信息: ${it.message ?: "未知错误"}"
            },
        )
        if (host == null) {
            return base.copy(
                configuredBaseWorld = binding,
                selectedBaseWorldId = binding?.id,
                worldSource = binding?.let { HostCreateWorldSource.Template } ?: base.worldSource,
            )
        }
        return base.copy(
            editHost = host,
            title = "编辑房间 · ${host.name}",
            hostName = host.name,
            intro = host.intro,
            selectedModpackId = host.modpack.id.toHexString(),
            hostKind = HostKind.Legacy,
            packSourceId = host.modpack.id.toHexString(),
            packDisplayName = host.modpack.name,
            selectedVersionName = host.packVer,
            difficulty = host.difficulty,
            gameMode = host.gameMode,
            currentMcVersion = host.modpack.mcVer,
            levelType = mappedLevelType(host.levelType, host.modpack.mcVer),
            levelChoice = levelChoice(host.levelType),
            customLevelTypeText = if (levelChoice(host.levelType) == 3) host.levelType else "",
            levelTypeDirty = false,
            whitelist = host.whitelist,
            allowCheats = host.allowCheats,
            gameRules = host.gameRules.toMap(),
        )
    }

    private fun createSubmission(
        state: HostCreateUiState,
        trimmedName: String,
    ): HostCreateSubmission? {
        val sourceId = state.packSourceId
        if (sourceId == null) {
            _uiState.update {
                it.copy(
                    statusMessage = "请从“我的整合包”的菜单发起创建多人房间",
                )
            }
            return null
        }
        return when (state.hostKind) {
            HostKind.Legacy -> {
                if (!ObjectId.isValid(sourceId) || state.selectedVersionName.isBlank()) {
                    _uiState.update { it.copy(statusMessage = "整合包来源或版本无效，请从“我的整合包”的菜单发起创建多人房间") }
                    return null
                }
                if (state.baseWorldBindingErrorMessage != null) {
                    _uiState.update { it.copy(statusMessage = state.baseWorldBindingErrorMessage) }
                    return null
                }
                if (state.worldSource == HostCreateWorldSource.Generate &&
                    state.levelChoice == 3 && state.levelType.isBlank()
                ) {
                    _uiState.update { it.copy(statusMessage = "自定义地形不能为空") }
                    return null
                }
                val selectedBaseWorld = if (state.worldSource == HostCreateWorldSource.Template) {
                    if (state.baseWorldsLoading) {
                        _uiState.update { it.copy(statusMessage = "地图模板正在加载，请稍候") }
                        return null
                    }
                    state.selectedBaseWorldId?.let { id -> state.baseWorlds.firstOrNull { it.id == id } }
                        ?: run {
                            _uiState.update { it.copy(statusMessage = "请选择可用的地图模板") }
                            return null
                        }
                } else {
                    if (state.configuredBaseWorld?.required == true) {
                        _uiState.update { it.copy(statusMessage = "此版本必须使用指定的地图模板") }
                        return null
                    }
                    null
                }
                if (state.configuredBaseWorld?.required == true &&
                    (selectedBaseWorld?.id != state.configuredBaseWorld.id)
                ) {
                    _uiState.update { it.copy(statusMessage = "此版本指定的地图模板不可用") }
                    return null
                }
                if (state.initialMembers.isEmpty()) {
                    _uiState.update { it.copy(statusMessage = "至少邀请1位好友一起玩") }
                    return null
                }
                HostCreateSubmission.CreateLegacy(
                    Host.CreateDto(
                        name = trimmedName,
                        modpackId = ObjectId(sourceId),
                        packVer = state.selectedVersionName.trim(),
                        difficulty = state.difficulty,
                        gameMode = state.gameMode,
                        levelType = (selectedBaseWorld?.levelType ?: state.levelType).trim(),
                        allowCheats = state.allowCheats,
                        whitelist = state.whitelist,
                        gameRules = state.gameRules.toMutableMap(),
                        baseWorldId = selectedBaseWorld?.id,
                        initialMemberIds = state.initialMembers.map(RAccount.Dto::id),
                    )
                )
            }
            HostKind.Host2 -> {
                _uiState.update { it.copy(statusMessage = "该房间类型暂不可用") }
                null
            }
        }
    }
}

private fun levelChoice(levelType: String): Int = when {
    levelType.contains("skyblock", ignoreCase = true) -> 2
    levelType == "minecraft:flat" -> 1
    levelType == "minecraft:normal" || levelType.isBlank() -> 0
    else -> 3
}

private fun mappedLevelType(levelType: String, mcVersion: McVersion): String = when (levelChoice(levelType)) {
    0 -> "minecraft:normal"
    1 -> "minecraft:flat"
    2 -> skyblockLevelType(mcVersion)
    else -> levelType
}

private fun skyblockLevelType(mcVersion: McVersion?): String {
    val minor = mcVersion?.vMinor?.toIntOrNull()
    return if (minor != null && minor <= 16) {
        "skyblockbuilder:custom_skyblock"
    } else {
        "skyblockbuilder:skyblock"
    }
}

private fun parseMcVersion(value: String?): McVersion? {
    val normalized = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    return McVersion.entries.firstOrNull { it.name == normalized }
        ?: McVersion.from(normalized)
}

private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (cause: Throwable) {
    Result.failure(cause)
}
