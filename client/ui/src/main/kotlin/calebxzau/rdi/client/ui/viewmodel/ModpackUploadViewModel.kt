package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.util.javaExePath
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.packproc.LoadedLocalModpack
import calebxzau.rdi.client.packproc.LoadedServerPackResult
import calebxzau.rdi.client.packproc.ModpackProcessor
import calebxzau.rdi.client.packproc.PackProcessingPaths
import calebxzau.rdi.client.packproc.fileMatchesModContent
import calebxzau.rdi.client.packproc.sameModContent
import calebxzau.rdi.client.packproc.toUploadPayload
import calebxzau.rdi.client.ui.currentJavaMajor
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.ClientDirs
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.createUploadClientModDownloadTask2
import calebxzhou.rdi.client.service.isUploadClientContentAvailable
import calebxzhou.rdi.client.service.mcInstall
import calebxzhou.rdi.client.service.modpackTestEnvironment
import calebxzhou.rdi.client.service.modpackTestModSourceResolver
import calebxzhou.rdi.client.service.modpackTestClientExtraResolver
import calebxzhou.rdi.client.service.createUploadModpackTask2
import calebxzhou.rdi.client.service.hydrateToUiMods
import calebxzhou.rdi.client.service.hydrateToUiModsInBatches
import calebxzhou.rdi.client.service.modpackUploadTaskKey
import calebxzhou.rdi.client.service.toUiMods
import calebxzhou.rdi.client.service.content.ClientContentStore
import calebxzau.rdi.client.service.ClientContentStores
import calebxzhou.rdi.client.service.content.ContentDigestAlgorithm
import calebxzhou.rdi.client.service.content.ContentRequest
import calebxzhou.rdi.client.service.content.commitEmbeddedModSources
import calebxzhou.rdi.client.service.content.commitEmbeddedClientExtraSources
import calebxzhou.rdi.client.service.content.toClientContentRequest
import calebxzhou.rdi.client.ui.comp.ConsoleState
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.IGNORE_MODPACK_TEST
import calebxzhou.rdi.common.exception.ModpackError
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.model.LoadProgress
import calebxzhou.rdi.common.model.MODPACK_INFO_MAX_CHARACTERS
import calebxzhou.rdi.common.model.MODPACK_INFO_MIN_CHARACTERS
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.ModpackUploadPreflightDto
import calebxzhou.rdi.common.model.Task2Entry
import calebxzhou.rdi.common.model.Task2Status
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.service.validateIconUrl
import calebxzhou.rdi.common.service.validateIconUrlAddress
import calebxzhou.rdi.common.model.modpackInfoCharacterCount
import calebxzau.rdi.modpacktest.ModpackTestSession
import calebxzau.rdi.modpacktest.ModpackTestStatus
import calebxzau.rdi.modpacktest.ModpackTestTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarFile

enum class ModpackUploadMode { CREATE, UPDATE }

data class ModpackUploadDraft(
    val name: String = "",
    val versionName: String = "",
    val categories: List<Modpack.Category> = emptyList(),
    val iconUrl: String = "",
    val sourceUrl: String = "",
    val info: String = "",
)

data class ModpackUploadFieldErrors(
    val info: String? = null,
    val iconUrl: String? = null,
    val categories: String? = null,
) {
    val isEmpty: Boolean
        get() = info == null && iconUrl == null && categories == null

    val firstMessage: String?
        get() = info ?: iconUrl ?: categories
}

private fun validateModpackUploadDraft(
    draft: ModpackUploadDraft,
    mode: ModpackUploadMode,
): ModpackUploadFieldErrors {
    if (mode == ModpackUploadMode.UPDATE) return ModpackUploadFieldErrors()

    val normalizedInfo = draft.info.trim()
    val infoLength = normalizedInfo.modpackInfoCharacterCount()
    val infoError = when {
        normalizedInfo.isBlank() -> "简介不能为空"
        infoLength < MODPACK_INFO_MIN_CHARACTERS ->
            "简介至少需要${MODPACK_INFO_MIN_CHARACTERS}个字符"
        infoLength > MODPACK_INFO_MAX_CHARACTERS ->
            "简介最多${MODPACK_INFO_MAX_CHARACTERS}个字符"
        else -> null
    }
    val normalizedIconUrl = draft.iconUrl.trim()
    val iconError = when {
        normalizedIconUrl.isBlank() -> "图标链接不能为空"
        else -> validateIconUrlAddress(normalizedIconUrl).fold(
            onSuccess = { null },
            onFailure = { cause -> cause.message ?: "图标链接无效" },
        )
    }
    val categoriesError = when {
        draft.categories.isEmpty() -> "至少选择1个分类"
        draft.categories.distinct().size > Modpack.MAX_CATEGORY_COUNT ->
            "分类最多选择${Modpack.MAX_CATEGORY_COUNT}个"
        else -> null
    }
    return ModpackUploadFieldErrors(infoError, iconError, categoriesError)
}

data class PendingMissingModDownload(
    val usage: String,
    val mods: List<Mod>,
)

data class PreparedClientPack(
    val pack: LoadedLocalModpack,
    val uiMods: List<UiMod>,
)

data class PreparedServerPack(
    val pack: LoadedServerPackResult,
    val uiMods: List<UiMod>,
)

const val MODPACK_UPLOAD_MCA_WARNING = "可能含有内置地图，请创建地图模板，详见说明书。"

data class ModpackUploadSubmission(
    val pack: LoadedLocalModpack,
    val uiMods: List<UiMod>,
    val draft: ModpackUploadDraft,
    val updateModpackId: ObjectId?,
)

data class ModpackUploadUiState(
    val title: String = "上传整合包",
    val loading: Boolean = false,
    val uploadInitializationReady: Boolean = false,
    val uploadInitializationError: String? = null,
    val iconValidationRunning: Boolean = false,
    val editMode: Boolean = false,
    val loadedModpack: LoadedLocalModpack? = null,
    val draft: ModpackUploadDraft = ModpackUploadDraft(),
    val draftErrors: ModpackUploadFieldErrors = ModpackUploadFieldErrors(),
    val uiMods: List<UiMod> = emptyList(),
    val uiModsLoading: Boolean = false,
    val serverPackName: String? = null,
    val progressText: String? = null,
    val progressFraction: Float? = null,
    val downloadTaskRunId: String? = null,
    val downloadTaskEntry: Task2Entry? = null,
    val uploadMode: ModpackUploadMode = ModpackUploadMode.CREATE,
    val uploadedModpacks: List<Modpack.BriefVo> = emptyList(),
    val uploadedModpacksLoading: Boolean = false,
    val uploadedModpacksLoaded: Boolean = false,
    val selectedUpdateTarget: Modpack.BriefVo? = null,
    val pendingMissingModDownload: PendingMissingModDownload? = null,
    val ignoreModpackTest: Boolean = false,
    val clientTestStatus: ModpackTestStatus = ModpackTestStatus.NOT_RUN,
    val clientTestPassSeconds: String? = null,
    val serverTestStatus: ModpackTestStatus = ModpackTestStatus.NOT_RUN,
    val serverTestPassSeconds: String? = null,
    val mcVersionText: String = "",
    val modloaderText: String = "",
    val warningMessage: String? = null,
    val errorMessage: String? = null,
) {
    val allowUploadWithoutTests: Boolean
        get() = DEBUG || ignoreModpackTest

    val uploadDisabledReason: String?
        get() {
            validateModpackUploadDraft(draft, uploadMode).firstMessage?.let { return it }
            uploadInitializationError?.let { return it }
            if (!uploadInitializationReady) return "正在准备上传"
            if (iconValidationRunning) return "正在检查上传信息"
            if (ignoreModpackTest) return null
            return when {
                loading -> "正在处理整合包"
                uiModsLoading -> "正在补充Mod详细信息"
                downloadTaskRunId != null -> "正在下载测试服务端"
                serverTestStatus == ModpackTestStatus.RUNNING -> "服务端测试进行中"
                clientTestStatus == ModpackTestStatus.RUNNING -> "客户端测试进行中"
                uploadMode == ModpackUploadMode.UPDATE && selectedUpdateTarget == null ->
                    "请选择要更新的已有整合包"
                else -> null
            }
        }

    val canSubmitUpload: Boolean
        get() = uploadDisabledReason == null

    val canSelectServerPack: Boolean
        get() = editMode &&
            !loading &&
            !uiModsLoading &&
            downloadTaskRunId == null &&
            serverTestStatus != ModpackTestStatus.RUNNING &&
            clientTestStatus != ModpackTestStatus.RUNNING
}

sealed interface ModpackUploadEvent {
    data class ClientPackLoaded(val initialTab: Int = 0) : ModpackUploadEvent
    data object OpenUploadModeDialog : ModpackUploadEvent
    data class UploadSubmitted(val runId: String) : ModpackUploadEvent
    data object NavigateBack : ModpackUploadEvent
}

interface ModpackUploadGateway {
    val taskEntries: StateFlow<List<Task2Entry>>
    val ignoreModpackTestInitially: Boolean

    suspend fun ensureAudioReady(): Result<Unit> = Result.success(Unit)

    suspend fun loadUpdateTarget(modpackId: String): Result<Modpack.BriefVo> =
        Result.failure(UnsupportedOperationException("更新目标加载未实现"))

    suspend fun loadUploadedModpacks(): Result<List<Modpack.BriefVo>>

    suspend fun preflightUpload(submission: ModpackUploadSubmission): Result<Unit>

    suspend fun prepareClientPack(
        file: File,
        onProgress: (LoadProgress) -> Unit,
    ): Result<PreparedClientPack>

    fun hydrateMods(mods: List<UiMod>): Flow<List<UiMod>>

    suspend fun prepareServerPack(
        directory: File,
        clientUiMods: List<UiMod>,
        onProgress: (LoadProgress) -> Unit,
    ): Result<PreparedServerPack>

    suspend fun validateRuntime(mcVersion: McVersion): Result<Unit>

    suspend fun findMissingMods(mods: List<Mod>): Result<List<Mod>>

    fun queueMissingModDownload(mods: List<Mod>): Result<String>

    fun queueTestServer(pack: LoadedLocalModpack): Result<String>

    fun queueUpload(submission: ModpackUploadSubmission): Result<String>

    fun enableIgnoreModpackTest(): Result<Unit>

    suspend fun cleanup(): Result<Unit>
}

class RdiModpackUploadGateway(
    private val modCatalog: ModCatalog,
) : ModpackUploadGateway {
    private val workDir = Files.createTempDirectory(
        ClientDirs.packProcDir.toPath(),
        "legacy-upload-",
    ).toFile()
    private var submittedRunId: String? = null
    private val processor = ModpackProcessor(
        PackProcessingPaths(
            workDir = workDir,
        )
    )

    override val taskEntries: StateFlow<List<Task2Entry>>
        get() = ClientTaskManager.entries

    override val ignoreModpackTestInitially: Boolean
        get() = IGNORE_MODPACK_TEST

    override suspend fun ensureAudioReady(): Result<Unit> = resultOf {
        processor.ensureAudioReady()
    }

    override suspend fun loadUpdateTarget(modpackId: String): Result<Modpack.BriefVo> = resultOf {
        val response = server.makeRequest<Modpack.DetailVo>("modpack/$modpackId/detail")
        if (!response.ok) throw RequestError(response.msg)
        val detail = response.data ?: throw RequestError("整合包信息为空")
        if (!detail.canUploadVersion) throw RequestError("没有上传新版本的权限")
        detail.toLocalBriefVo()
    }

    override suspend fun loadUploadedModpacks(): Result<List<Modpack.BriefVo>> = resultOf {
        val response = server.makeRequest<List<Modpack>>("modpack/uploadable")
        if (!response.ok) throw RequestError(response.msg)
        response.data.orEmpty()
            .sortedByDescending { pack -> pack.versions.maxOfOrNull { it.time } ?: 0L }
            .map(Modpack::toLocalBriefVo)
    }

    override suspend fun preflightUpload(submission: ModpackUploadSubmission): Result<Unit> = resultOf {
        val draft = submission.draft
        val response = server.makeRequest<Unit>("modpack/preflight", HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(
                serdesJson.encodeToString(
                    ModpackUploadPreflightDto(
                        modpackId = submission.updateModpackId,
                        name = draft.name,
                        verName = draft.versionName,
                        mcVer = submission.pack.mcVersion,
                        modLoader = submission.pack.modloader,
                        iconUrl = draft.iconUrl.trim().ifBlank { null },
                        sourceUrl = draft.sourceUrl.trim().ifBlank { null },
                        info = draft.info.trim().ifBlank { null },
                        categories = Modpack.normalizeCategories(draft.categories),
                    )
                )
            )
        }
        if (!response.ok) throw RequestError(response.msg)
    }

    override suspend fun prepareClientPack(
        file: File,
        onProgress: (LoadProgress) -> Unit,
    ): Result<PreparedClientPack> = resultOf {
        val pack = processor.loadLocalModpack(
            file = file,
            modCatalog = modCatalog,
            onProgress = onProgress,
        ).getOrThrow()
        commitEmbeddedModSources(pack.embeddedModSources).getOrThrow()
        commitEmbeddedClientExtraSources(pack.embeddedClientExtraSources).getOrThrow()
        val initialUiMods = defaultCurseForgeUnknownMods(pack.mods.toUiMods())
        val processedUiMods = processUiMods(initialUiMods).getOrThrow()
        PreparedClientPack(
            pack = pack.copy(mods = processedUiMods.map(UiMod::toMod)),
            uiMods = processedUiMods,
        )
    }

    override fun hydrateMods(mods: List<UiMod>): Flow<List<UiMod>> =
        mods.hydrateToUiModsInBatches(modCatalog)

    override suspend fun prepareServerPack(
        directory: File,
        clientUiMods: List<UiMod>,
        onProgress: (LoadProgress) -> Unit,
    ): Result<PreparedServerPack> = resultOf {
        if (INVALID_SERVER_ROOT_MARKERS.any { directory.resolve(it).isFile }) {
            throw ModpackError("请选择 正确安装了模组载入器 的服务端")
        }
        val clientMods = clientUiMods.map(UiMod::toMod)
        onProgress(LoadProgress.Phase("正在检查本地Mod"))
        val serverPack = withAvailableClientModSources(clientUiMods, onProgress) { clientModSources ->
            processor.loadServerPack(
                file = directory,
                clientMods = clientMods,
                clientModSources = clientModSources,
                onProgress = onProgress,
            ).getOrThrow()
        }
        commitEmbeddedModSources(serverPack.embeddedModSources).getOrThrow()
        val serverUiMods = serverPack.mods.hydrateToUiMods(modCatalog)
        val mergedUiMods = mergeClientAndServerMods(clientUiMods, serverUiMods)
        PreparedServerPack(serverPack, processUiMods(mergedUiMods).getOrThrow())
    }

    private companion object {
        val INVALID_SERVER_ROOT_MARKERS = listOf("spigot.yml", "arclight.conf", "bukkit.yml")
    }

    private suspend fun <T> withAvailableClientModSources(
        uiMods: List<UiMod>,
        onProgress: (LoadProgress) -> Unit,
        block: suspend (Map<Mod, File>) -> T,
    ): T {
        fun hasValidLocalFile(uiMod: UiMod): Boolean =
            uiMod.file?.let { it.exists() && it.isFile } == true

        val localSources = uiMods.mapNotNull { uiMod ->
            uiMod.file?.takeIf { it.exists() && it.isFile }?.let { file ->
                uiMod.mod to file
            }
        }
        val requests = uiMods.asSequence()
            .filterNot(::hasValidLocalFile)
            .map { uiMod ->
                uiMod to uiMod.mod.toClientContentRequest(
                    targetRelativePath = uiMod.mod.fileName,
                )
            }
            .distinctBy { (_, request) -> request.id }
            .toList()
        if (requests.isEmpty()) {
            onProgress(LoadProgress.Percent("本地Mod检查完成", 1f))
            return block(localSources.toMap())
        }

        return ClientContentStores.shared.useCached(
            requests = requests.map { it.second },
            onProgress = { progress ->
                val completedItems = progress.completedItems ?: 0
                val totalItems = progress.totalItems ?: requests.size
                val fraction = if (totalItems > 0) {
                    completedItems.toFloat().div(totalItems).coerceIn(0f, 1f)
                } else {
                    1f
                }
                val modName = progress.message.takeIf { it.isNotBlank() }
                val text = if (modName != null) {
                    "正在检查本地Mod:${modName}(${completedItems}/${totalItems})"
                } else {
                    "正在检查本地Mod(${completedItems}/${totalItems})"
                }
                onProgress(LoadProgress.Percent(text, fraction))
            },
        ) { paths ->
            val cacheSources = requests.mapNotNull { (uiMod, request) ->
                paths[request.id]?.toFile()?.let { file -> uiMod.mod to file }
            }
            block((localSources + cacheSources).toMap())
        }.getOrThrow()
    }

    override suspend fun validateRuntime(mcVersion: McVersion): Result<Unit> = resultOf {
        val currentMajor = currentJavaMajor()
        if (javaExePath.isNotBlank() && mcVersion.supportsCurrentJava(currentMajor)) return@resultOf
        val javaText = mcVersion.supportedJreVers.joinToString("或") { "Java$it" }
        error("MC${mcVersion.mcVer}需要${javaText}。请使用${javaText}启动RDI后再上传")
    }

    override suspend fun findMissingMods(mods: List<Mod>): Result<List<Mod>> = resultOf {
        buildList {
            mods.forEach { mod ->
                if (!isUploadClientContentAvailable(mod)) add(mod)
            }
        }
    }

    private fun processUiMods(mods: List<UiMod>): Result<List<UiMod>> = runCatching {
        preserveUiMods(
            source = mods,
            updatedMods = processor.processUploadMods(mods.map(UiMod::toMod)),
        )
    }

    override fun queueMissingModDownload(mods: List<Mod>): Result<String> = runCatching {
        ClientTaskManager.submit(createUploadClientModDownloadTask2(mods))
    }

    override fun queueTestServer(pack: LoadedLocalModpack): Result<String> = runCatching {
        ClientTaskManager.submit(mcInstall.downloadTestServerTask2(pack.mcVersion, pack.modloader))
    }

    override fun queueUpload(submission: ModpackUploadSubmission): Result<String> = runCatching {
        val draft = submission.draft
        val processedUiMods = processUiMods(submission.uiMods).getOrThrow()
        val payload = submission.pack.copy(
            packName = draft.name,
            packVersion = draft.versionName,
            mods = processedUiMods.map(UiMod::toMod),
        ).toUploadPayload()
        val task = createUploadModpackTask2(
            processor = processor,
            payload = payload,
            mods = payload.mods,
            modpackName = draft.name,
            versionName = draft.versionName,
            iconUrl = draft.iconUrl.trim().ifBlank { null },
            sourceUrl = draft.sourceUrl.trim().ifBlank { null },
            info = draft.info.trim().ifBlank { null },
            categories = Modpack.normalizeCategories(draft.categories),
            updateModpackId = submission.updateModpackId,
        )
        val runId = ClientTaskManager.submit(
            task = task,
            dedupeKey = modpackUploadTaskKey(
                updateModpackId = submission.updateModpackId,
                modpackName = draft.name,
                versionName = draft.versionName,
            ),
        )
        if (ClientTaskManager.entry(runId)?.task === task) {
            submittedRunId = runId
        }
        runId
    }

    override fun enableIgnoreModpackTest(): Result<Unit> = runCatching {
        System.setProperty("rdi.ignoreModpackTest", "true")
    }

    override suspend fun cleanup(): Result<Unit> = resultOf {
        val runId = submittedRunId
        if (runId == null || ClientTaskManager.entry(runId)?.status?.isTerminal == true) {
            workDir.deleteRecursivelyNoSymlink()
        }
    }
}

class ModpackUploadViewModel(
    private val gateway: ModpackUploadGateway,
    private val modpackId: String? = null,
    private val validateFullIconUrl: suspend (String?) -> Result<Unit> = ::validateIconUrl,
) : ViewModel() {
    constructor(
        gateway: ModpackUploadGateway,
        validateFullIconUrl: suspend (String?) -> Result<Unit>,
    ) : this(gateway, null, validateFullIconUrl)
    private val _uiState = MutableStateFlow(
        ModpackUploadUiState(
            loading = true,
            uploadMode = if (modpackId == null) ModpackUploadMode.CREATE else ModpackUploadMode.UPDATE,
            ignoreModpackTest = gateway.ignoreModpackTestInitially,
        )
    )
    val uiState: StateFlow<ModpackUploadUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<ModpackUploadEvent>(Channel.BUFFERED)
    val events: Flow<ModpackUploadEvent> = eventChannel.receiveAsFlow()

    val clientTestConsoleState = ConsoleState()
    val serverTestConsoleState = ConsoleState()

    private var clientTester: ModpackTestSession? = null
    private var serverTester: ModpackTestSession? = null
    private val testerObservationJobs = mutableListOf<Job>()

    init {
        observeTasks()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    gateway.ensureAudioReady().getOrThrow()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                val message = cause.message ?: "音频处理模块损坏，请更新客户端"
                lgr.warn(cause) { "上传页面音频模块初始化失败" }
                _uiState.update {
                    it.copy(
                        loading = false,
                        uploadInitializationReady = false,
                        uploadInitializationError = message,
                        errorMessage = message,
                    )
                }
                return@launch
            }

            val target = try {
                modpackId?.let { targetId ->
                    withContext(Dispatchers.IO) {
                        gateway.loadUpdateTarget(targetId).getOrThrow()
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                val message = "无法加载要更新的整合包，请返回后重试"
                lgr.warn(cause) { "上传页面更新目标加载失败" }
                _uiState.update {
                    it.copy(
                        loading = false,
                        uploadInitializationReady = false,
                        uploadInitializationError = message,
                        errorMessage = message,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    loading = false,
                    uploadInitializationReady = true,
                    uploadInitializationError = null,
                    uploadMode = if (modpackId == null) {
                        ModpackUploadMode.CREATE
                    } else {
                        ModpackUploadMode.UPDATE
                    },
                    selectedUpdateTarget = target,
                    draft = target?.let { targetBrief ->
                        it.draft.copy(
                            name = targetBrief.name,
                            categories = targetBrief.categories,
                            iconUrl = targetBrief.icon.orEmpty(),
                            info = targetBrief.info.orEmpty(),
                        )
                    } ?: it.draft,
                )
            }
        }
    }

    fun updateDraft(draft: ModpackUploadDraft) {
        _uiState.update {
            it.copy(
                draft = draft,
                draftErrors = validateModpackUploadDraft(draft, it.uploadMode),
                errorMessage = null,
            )
        }
    }

    fun updateVersionName(value: String) {
        updateDraft(_uiState.value.draft.copy(versionName = value.replace(' ', '_')))
    }

    fun loadClientPack(file: File) {
        val currentState = _uiState.value
        if (currentState.uploadInitializationError != null) return
        if (modpackId != null && currentState.selectedUpdateTarget == null) return
        _uiState.update {
            it.copy(
                loading = true,
                progressText = "已选择:${file.name}",
                progressFraction = null,
                errorMessage = null,
                warningMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    gateway.prepareClientPack(file, ::updateProgress).getOrThrow()
                }
                withContext(Dispatchers.IO) {
                    gateway.validateRuntime(prepared.pack.mcVersion).getOrThrow()
                }
                _uiState.update {
                    val target = it.selectedUpdateTarget
                    it.copy(
                        loading = false,
                        editMode = true,
                        loadedModpack = prepared.pack,
                        draftErrors = ModpackUploadFieldErrors(),
                        draft = if (target == null) {
                            ModpackUploadDraft(
                                name = prepared.pack.packName,
                                versionName = prepared.pack.packVersion.replace(' ', '_'),
                            )
                        } else {
                            it.draft.copy(
                                name = target.name,
                                versionName = prepared.pack.packVersion.replace(' ', '_'),
                                categories = target.categories,
                                iconUrl = target.icon.orEmpty(),
                                info = target.info.orEmpty(),
                            )
                        },
                        uiMods = prepared.uiMods,
                        uiModsLoading = prepared.uiMods.isNotEmpty(),
                        serverPackName = null,
                        uploadMode = it.uploadMode,
                        selectedUpdateTarget = target,
                        mcVersionText = prepared.pack.mcVersion.mcVer,
                        modloaderText = prepared.pack.modloader.name,
                        warningMessage = MODPACK_UPLOAD_MCA_WARNING.takeIf {
                            prepared.pack.containsExcludedMcaFiles
                        },
                    )
                }
                eventChannel.send(ModpackUploadEvent.ClientPackLoaded())
                try {
                    gateway.hydrateMods(prepared.uiMods)
                        .flowOn(Dispatchers.IO)
                        .collect { batch ->
                            _uiState.update { state ->
                                state.copy(uiMods = mergeHydratedUiMods(state.uiMods, batch))
                            }
                        }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (cause: Throwable) {
                    lgr.warn(cause) { "加载整合包Mod展示信息失败，将使用基础Mod数据" }
                }
                val finalUiMods = _uiState.value.uiMods
                val finalPack = prepared.pack.copy(mods = finalUiMods.map(UiMod::toMod))
                replaceTesters(finalPack, finalUiMods)
                _uiState.update {
                    it.copy(
                        loadedModpack = finalPack,
                        uiModsLoading = false,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("读取整合包失败", cause)
            } finally {
                _uiState.update { it.copy(uiModsLoading = false) }
                finishLoading()
            }
        }
    }

    fun loadServerPack(directory: File) {
        val state = _uiState.value
        if (state.loadedModpack == null) {
            _uiState.update { it.copy(errorMessage = "请先选择整合包文件") }
            return
        }
        _uiState.update {
            it.copy(
                loading = true,
                progressText = "已选择服务端目录:${directory.name}",
                progressFraction = null,
                errorMessage = null,
            )
        }
        viewModelScope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    gateway.prepareServerPack(directory, state.uiMods, ::updateProgress).getOrThrow()
                }
                applyServerPack(prepared)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("读取服务端目录失败", cause)
            } finally {
                finishLoading()
            }
        }
    }

    fun updateModSide(uiMod: UiMod, newSide: Mod.Side) {
        if (newSide == uiMod.side) return
        _uiState.update {
            it.copy(uiMods = updateModSide(it.uiMods, modStableKey(uiMod.mod), newSide))
        }
        val mods = _uiState.value.uiMods.map(UiMod::toMod)
        clientTester?.onModsChanged(mods)
        serverTester?.onModsChanged(mods)
    }

    fun chooseCreateMode() = Unit

    fun chooseUpdateMode(target: Modpack.BriefVo) = Unit

    fun requestUpdateModeSelection() = Unit

    fun requestUploadModeDialog() = Unit

    fun closeUploadModeDialog() = Unit

    fun startClientTest() {
        val tester = clientTester
        when {
            _uiState.value.uiModsLoading -> _uiState.update { it.copy(errorMessage = "Mod信息仍在载入中") }
            tester == null -> _uiState.update { it.copy(errorMessage = "请先选择整合包文件") }
            tester.isRunning() -> _uiState.update { it.copy(errorMessage = "测试客户端已经在运行中") }
            else -> requireModsDownloadedFor("客户端测试") { startClientTestAfterModsReady(tester) }
        }
    }

    fun stopClientTest() {
        clientTester?.stop()?.onFailure { reportError("停止客户端测试失败", it) }
    }

    fun startServerTest() {
        val tester = serverTester
        when {
            _uiState.value.uiModsLoading -> _uiState.update { it.copy(errorMessage = "Mod信息仍在载入中") }
            tester == null -> _uiState.update { it.copy(errorMessage = "请先选择整合包文件") }
            tester.isRunning() -> _uiState.update { it.copy(errorMessage = "测试服务器已经在运行中") }
            else -> requireModsDownloadedFor("服务端测试") { startServerTestAfterModsReady(tester) }
        }
    }

    fun stopServerTest() {
        serverTester?.stop()?.onFailure { reportError("停止服务端测试失败", it) }
    }

    fun dismissMissingModDownload() {
        _uiState.update { it.copy(pendingMissingModDownload = null) }
    }

    fun clearWarningMessage() {
        _uiState.update { it.copy(warningMessage = null) }
    }

    fun confirmMissingModDownload() {
        val pending = _uiState.value.pendingMissingModDownload ?: return
        if (_uiState.value.downloadTaskRunId != null) {
            _uiState.update { it.copy(errorMessage = "已有下载任务正在运行") }
            return
        }
        gateway.queueMissingModDownload(pending.mods)
            .onSuccess { runId ->
                _uiState.update {
                    it.copy(pendingMissingModDownload = null)
                }
                setDownloadRunId(runId)
            }
            .onFailure { reportError("提交Mod下载任务失败", it) }
    }

    fun downloadTestServer() {
        val pack = _uiState.value.loadedModpack
        if (pack == null) {
            _uiState.update { it.copy(errorMessage = "请先选择整合包文件") }
            return
        }
        if (_uiState.value.downloadTaskRunId != null) {
            _uiState.update { it.copy(errorMessage = "已有下载任务正在运行") }
            return
        }
        gateway.queueTestServer(pack)
            .onSuccess(::setDownloadRunId)
            .onFailure { reportError("提交测试服务端下载失败", it) }
    }

    fun submitUpload() {
        val state = _uiState.value
        if (state.uploadInitializationError != null || !state.uploadInitializationReady) {
            _uiState.update {
                it.copy(errorMessage = state.uploadInitializationError ?: "正在准备上传")
            }
            return
        }
        if (state.iconValidationRunning) return
        val pack = state.loadedModpack
        if (pack == null) {
            _uiState.update { it.copy(errorMessage = "请先选择整合包文件") }
            return
        }
        val name = state.draft.name.trim()
        val versionName = state.draft.versionName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "整合包名称不能为空") }
            return
        }
        if (versionName.isBlank()) {
            _uiState.update { it.copy(errorMessage = "版本号不能为空") }
            return
        }
        val draftErrors = validateModpackUploadDraft(state.draft, state.uploadMode)
        if (!draftErrors.isEmpty) {
            _uiState.update {
                it.copy(
                    draftErrors = draftErrors,
                    errorMessage = draftErrors.firstMessage,
                )
            }
            return
        }
        if (state.serverPackName == null && !state.allowUploadWithoutTests) {
            if (state.clientTestStatus != ModpackTestStatus.PASSED) {
                _uiState.update { it.copy(errorMessage = "请先完成客户端测试并通过") }
                return
            }
            if (state.serverTestStatus != ModpackTestStatus.PASSED) {
                _uiState.update { it.copy(errorMessage = "请先完成服务端测试并通过") }
                return
            }
        }
        if (state.uploadMode == ModpackUploadMode.UPDATE && state.selectedUpdateTarget == null) {
            _uiState.update { it.copy(errorMessage = "请选择要更新的已有整合包") }
            return
        }
        val finalName = if (state.uploadMode == ModpackUploadMode.UPDATE) {
            state.selectedUpdateTarget?.name ?: name
        } else {
            name
        }
        val submission = ModpackUploadSubmission(
            pack = pack,
            uiMods = state.uiMods,
            draft = state.draft.copy(name = finalName, versionName = versionName),
            updateModpackId = state.selectedUpdateTarget?.id
                .takeIf { state.uploadMode == ModpackUploadMode.UPDATE },
        )
        _uiState.update {
            it.copy(iconValidationRunning = true, errorMessage = null)
        }
        viewModelScope.launch {
            var preflightStarted = false
            try {
                if (state.uploadMode == ModpackUploadMode.CREATE) {
                    withContext(Dispatchers.IO) {
                        validateFullIconUrl(submission.draft.iconUrl).getOrThrow()
                    }
                }
                if (!isCurrentUploadSubmission(state, pack, submission)) {
                    _uiState.update { it.copy(errorMessage = "上传信息已更改，请重新提交") }
                    return@launch
                }
                preflightStarted = true
                withContext(Dispatchers.IO) {
                    gateway.preflightUpload(submission).getOrThrow()
                }
                if (!isCurrentUploadSubmission(state, pack, submission)) {
                    _uiState.update { it.copy(errorMessage = "上传信息已更改，请重新提交") }
                    return@launch
                }
                queueUploadSubmission(submission)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                if (preflightStarted) {
                    lgr.warn(cause) { "上传检查失败" }
                    _uiState.update {
                        it.copy(errorMessage = "上传检查失败：${cause.message ?: "无法验证上传信息"}")
                    }
                } else {
                    reportError("验证图标链接失败", cause)
                }
            } finally {
                _uiState.update { it.copy(iconValidationRunning = false) }
            }
        }
    }

    private fun isCurrentUploadSubmission(
        capturedState: ModpackUploadUiState,
        capturedPack: LoadedLocalModpack,
        submission: ModpackUploadSubmission,
    ): Boolean {
        val current = _uiState.value
        return current.uploadMode == capturedState.uploadMode &&
            current.draft == capturedState.draft &&
            current.selectedUpdateTarget?.id == capturedState.selectedUpdateTarget?.id &&
            current.loadedModpack === capturedPack &&
            current.uiMods == capturedState.uiMods &&
            current.selectedUpdateTarget?.id == submission.updateModpackId
    }

    private fun queueUploadSubmission(submission: ModpackUploadSubmission) {
        gateway.queueUpload(submission)
            .onSuccess { runId ->
                if (runId.isNotBlank()) viewModelScope.launch {
                    eventChannel.send(ModpackUploadEvent.UploadSubmitted(runId))
                }
            }
            .onFailure { reportError("提交上传任务失败", it) }
    }

    fun enableIgnoreModpackTest() {
        gateway.enableIgnoreModpackTest()
            .onSuccess { _uiState.update { it.copy(ignoreModpackTest = true) } }
            .onFailure { reportError("启用跳过测试失败", it) }
    }

    fun closeScreen() {
        disposeTesters()
        _uiState.update { it.copy(loading = true, title = "清理缓存中...") }
        viewModelScope.launch {
            gateway.cleanup()
                .onFailure { lgr.warn(it) { "清理整合包处理缓存失败" } }
            eventChannel.send(ModpackUploadEvent.NavigateBack)
        }
    }

    override fun onCleared() {
        disposeTesters()
        eventChannel.close()
        super.onCleared()
    }

    private fun loadUploadedModpacks(openDialogAfterLoad: Boolean) {
        val state = _uiState.value
        if (state.uploadedModpacksLoading) return
        if (state.uploadedModpacksLoaded) {
            if (openDialogAfterLoad) viewModelScope.launch {
                eventChannel.send(ModpackUploadEvent.OpenUploadModeDialog)
            }
            return
        }
        _uiState.update { it.copy(uploadedModpacksLoading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val packs = withContext(Dispatchers.IO) {
                    gateway.loadUploadedModpacks().getOrThrow()
                }
                _uiState.update {
                    it.copy(
                        uploadedModpacks = packs,
                        uploadedModpacksLoaded = true,
                    )
                }
                if (openDialogAfterLoad) {
                    eventChannel.send(ModpackUploadEvent.OpenUploadModeDialog)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("读取可上传整合包失败", cause)
            } finally {
                _uiState.update { it.copy(uploadedModpacksLoading = false) }
            }
        }
    }

    private fun requireModsDownloadedFor(usage: String, onReady: () -> Unit) {
        viewModelScope.launch {
            try {
                val missingMods = withContext(Dispatchers.IO) {
                    gateway.findMissingMods(_uiState.value.uiMods.map(UiMod::toMod)).getOrThrow()
                }
                if (missingMods.isEmpty()) {
                    onReady()
                } else {
                    _uiState.update {
                        it.copy(pendingMissingModDownload = PendingMissingModDownload(usage, missingMods))
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Throwable) {
                reportError("检查缺失Mod失败", cause)
            }
        }
    }

    private fun startClientTestAfterModsReady(tester: ModpackTestSession) {
        clientTestConsoleState.clear()
        _uiState.update { it.copy(errorMessage = null) }
        tester.start(_uiState.value.uiMods.map(UiMod::toMod))
            .onFailure { reportError("启动客户端测试失败", it) }
    }

    private fun startServerTestAfterModsReady(tester: ModpackTestSession) {
        serverTestConsoleState.clear()
        _uiState.update { it.copy(errorMessage = null) }
        tester.start(_uiState.value.uiMods.map(UiMod::toMod))
            .onFailure { reportError("启动服务端测试失败", it) }
    }

    private fun applyServerPack(prepared: PreparedServerPack) {
        val processedUiMods = prepared.uiMods
        val updatedPack = _uiState.value.loadedModpack?.copy(
            mods = processedUiMods.map(UiMod::toMod),
            serverExtraFiles = prepared.pack.serverExtraFiles,
        )
        _uiState.update {
            it.copy(
                uiMods = processedUiMods,
                loadedModpack = updatedPack,
                serverPackName = buildServerPackName(prepared.pack),
                editMode = true,
                warningMessage = if (prepared.pack.containsExcludedMcaFiles) {
                    MODPACK_UPLOAD_MCA_WARNING
                } else {
                    it.warningMessage
                },
            )
        }
        if (updatedPack != null) replaceTesters(updatedPack, processedUiMods)
    }

    private fun replaceTesters(pack: LoadedLocalModpack, uiMods: List<UiMod>) {
        disposeTesters()
        clientTestConsoleState.clear()
        serverTestConsoleState.clear()
        _uiState.update {
            it.copy(
                clientTestStatus = ModpackTestStatus.NOT_RUN,
                clientTestPassSeconds = null,
                serverTestStatus = ModpackTestStatus.NOT_RUN,
                serverTestPassSeconds = null,
            )
        }
        val testPack = pack.copy(mods = uiMods.map(UiMod::toMod))
        clientTester = ModpackTestSession(
            loadedModpack = testPack,
            target = ModpackTestTarget.CLIENT,
            environment = modpackTestEnvironment,
            modSourceResolver = modpackTestModSourceResolver,
            clientExtraResolver = modpackTestClientExtraResolver,
        )
        serverTester = ModpackTestSession(
            loadedModpack = testPack,
            target = ModpackTestTarget.SERVER,
            environment = modpackTestEnvironment,
            modSourceResolver = modpackTestModSourceResolver,
        )
        observeTesters()
    }

    private fun observeTesters() {
        val client = clientTester ?: return
        val server = serverTester ?: return
        testerObservationJobs += viewModelScope.launch {
            client.state.collectLatest { state ->
                _uiState.update {
                    it.copy(
                        clientTestStatus = state.status,
                        clientTestPassSeconds = state.passSeconds,
                        errorMessage = state.errorMessage ?: it.errorMessage,
                    )
                }
            }
        }
        testerObservationJobs += viewModelScope.launch {
            client.logs.collect(clientTestConsoleState::append)
        }
        testerObservationJobs += viewModelScope.launch {
            server.state.collectLatest { state ->
                _uiState.update {
                    it.copy(
                        serverTestStatus = state.status,
                        serverTestPassSeconds = state.passSeconds,
                        errorMessage = state.errorMessage ?: it.errorMessage,
                    )
                }
            }
        }
        testerObservationJobs += viewModelScope.launch {
            server.logs.collect(serverTestConsoleState::append)
        }
    }

    private fun observeTasks() {
        viewModelScope.launch {
            gateway.taskEntries.collectLatest { entries ->
                val runId = _uiState.value.downloadTaskRunId
                val entry = runId?.let { id -> entries.firstOrNull { it.runId == id } }
                updateDownloadTaskEntry(entry)
            }
        }
    }

    private fun setDownloadRunId(runId: String) {
        val entry = gateway.taskEntries.value.firstOrNull { it.runId == runId }
        _uiState.update {
            it.copy(downloadTaskRunId = runId, downloadTaskEntry = entry)
        }
        updateDownloadTaskEntry(entry)
    }

    private fun updateDownloadTaskEntry(entry: Task2Entry?) {
        when (entry?.status) {
            Task2Status.DONE, Task2Status.CANCELLED -> {
                _uiState.update { it.copy(downloadTaskRunId = null, downloadTaskEntry = null) }
            }

            Task2Status.FAILED -> {
                _uiState.update {
                    it.copy(
                        downloadTaskRunId = null,
                        downloadTaskEntry = null,
                        errorMessage = entry.snapshot.errorMessage ?: "下载任务失败",
                    )
                }
            }

            else -> _uiState.update { it.copy(downloadTaskEntry = entry) }
        }
    }

    private fun disposeTesters() {
        testerObservationJobs.forEach { it.cancel() }
        testerObservationJobs.clear()
        clientTester?.close()
        serverTester?.close()
        clientTester = null
        serverTester = null
    }

    private fun updateProgress(progress: LoadProgress) {
        _uiState.update {
            when (progress) {
                is LoadProgress.Phase -> it.copy(progressText = progress.text, progressFraction = null)
                is LoadProgress.Percent -> it.copy(
                    progressText = progress.text,
                    progressFraction = progress.fraction.coerceIn(0f, 1f),
                )
                is LoadProgress.Warn -> it.copy(progressText = progress.text)
            }
        }
    }

    private fun finishLoading() {
        _uiState.update {
            it.copy(loading = false, progressText = null, progressFraction = null)
        }
    }

    private fun reportError(prefix: String, cause: Throwable) {
        lgr.warn(cause) { prefix }
        _uiState.update {
            it.copy(errorMessage = cause.message ?: prefix)
        }
    }
}

private fun buildServerPackName(serverPack: LoadedServerPackResult): String = buildString {
    append("已选择服务端(")
    append(serverPack.mods.size)
    append("个mod")
    if (serverPack.serverExtraFiles.isNotEmpty()) {
        append("+")
        append(serverPack.serverExtraFiles.size)
        append("个额外文件")
    }
    append(")")
}

private fun updateModSide(
    uiMods: List<UiMod>,
    modKey: String,
    newSide: Mod.Side,
): List<UiMod> {
    val targetIndex = uiMods.indexOfFirst { modStableKey(it.mod) == modKey }
    if (targetIndex < 0) return uiMods
    return uiMods.toMutableList().apply {
        this[targetIndex] = this[targetIndex].withSide(newSide)
    }
}

private fun preserveUiMods(source: List<UiMod>, updatedMods: List<Mod>): List<UiMod> {
    val sourceByKey = source.associateBy { modStableKey(it.mod) }
    return updatedMods.map { updatedMod ->
        sourceByKey[modStableKey(updatedMod)]?.let { sourceUiMod ->
            sourceUiMod.copy(
                mod = updatedMod,
                card = sourceUiMod.card?.copy(side = updatedMod.side),
            )
        } ?: UiMod(mod = updatedMod)
    }
}

private fun mergeHydratedUiMods(current: List<UiMod>, hydrated: List<UiMod>): List<UiMod> {
    val currentByKey = current.associateBy { modStableKey(it.mod) }
    return hydrated.map { hydratedMod ->
        currentByKey[modStableKey(hydratedMod.mod)]?.let { currentMod ->
            hydratedMod.copy(
                mod = hydratedMod.mod.copy(side = currentMod.side),
                card = hydratedMod.card?.copy(side = currentMod.side),
                file = currentMod.file ?: hydratedMod.file,
            )
        } ?: hydratedMod
    }
}

private fun defaultCurseForgeUnknownMods(source: List<UiMod>): List<UiMod> = source.map { uiMod ->
    if (uiMod.platform.equals("cf", ignoreCase = true) && uiMod.side == Mod.Side.UNKNOWN) {
        uiMod.withSide(Mod.Side.BOTH)
    } else {
        uiMod
    }
}

private fun modStableKey(mod: Mod): String =
    "${mod.platform}:${mod.projectId}:${mod.fileId}:${mod.hash}"

private fun Modpack.toLocalBriefVo(): Modpack.BriefVo = Modpack.BriefVo(
    id = _id,
    name = name,
    authorId = authorId,
    authorName = "",
    mcVer = mcVer,
    modloader = modloader,
    modCount = versions.maxOfOrNull { it.mods.size } ?: 0,
    fileSize = versions.lastOrNull()?.totalSize ?: 0L,
    lastUpdatedTime = versions.lastOrNull()?.time ?: 0L,
    icon = iconUrl,
    info = info,
    categories = categories,
)

private fun Modpack.DetailVo.toLocalBriefVo(): Modpack.BriefVo = Modpack.BriefVo(
    id = _id,
    name = name,
    authorId = authorId,
    authorName = authorName,
    mcVer = mcVer,
    modloader = modloader,
    modCount = modCount,
    fileSize = versions.lastOrNull()?.totalSize ?: 0L,
    playCount = playCount,
    lastUpdatedTime = versions.maxOfOrNull { it.time } ?: 0L,
    icon = icon,
    info = info,
    categories = categories,
)

private data class ModMergeEntry(
    val uiMod: UiMod,
    val strictKey: String,
    val slugKey: String?,
) {
    val mod: Mod
        get() = uiMod.mod
}

private fun strictModMergeKey(
    mod: Mod,
    installedModIds: Map<String, String?>,
): String = when {
    !installedModIds[modStableKey(mod)].isNullOrBlank() ->
        "modid:${installedModIds.getValue(modStableKey(mod))}"
    mod.slug.isNotBlank() -> "slug:${mod.slug.lowercase()}"
    mod.projectId.isNotBlank() -> "project:${mod.projectId}"
    else -> modStableKey(mod)
}

private fun UiMod.asMergeEntry(installedModIds: Map<String, String?>): ModMergeEntry =
    ModMergeEntry(
        uiMod = this,
        strictKey = strictModMergeKey(mod, installedModIds),
        slugKey = slugModMergeKey(mod),
    )

private fun slugModMergeKey(mod: Mod): String? =
    mod.slug.trim().lowercase().takeIf(String::isNotBlank)?.let { "slug:$it" }

private fun trustedModContentRequest(mod: Mod): ContentRequest? = runCatching {
    val request = mod.toClientContentRequest().copy(allowNetwork = false)
    val digest = request.digests.singleOrNull() ?: return@runCatching null
    val valid = when (digest.algorithm) {
        ContentDigestAlgorithm.MURMUR2 -> digest.normalizedValue.toULongOrNull()
            ?.let { it <= UInt.MAX_VALUE.toULong() } == true
        ContentDigestAlgorithm.SHA1 -> digest.normalizedValue.matches(Regex("[0-9a-f]{40}"))
        ContentDigestAlgorithm.SHA256 -> digest.normalizedValue.matches(Regex("[0-9a-f]{64}"))
    }
    request.takeIf { valid }?.copy(relativePath = cacheReadRelativePath(request))
}.getOrNull()

private fun cacheReadRelativePath(request: ContentRequest): String {
    val requestDigest = MessageDigest.getInstance("SHA-256")
        .digest(request.id.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
    return ".rdi-cache-read/$requestDigest.jar"
}

private fun readInstalledModId(file: File, displayName: String): String? = try {
    ModService.run {
        JarFile(file).use { jar ->
            jar.readModMeta()?.primaryModId?.trim()?.lowercase()?.ifBlank { null }
        }
    }
} catch (cancel: CancellationException) {
    throw cancel
} catch (cause: Throwable) {
    lgr.warn(cause) { "读取Mod元数据失败:${displayName}" }
    null
}

private suspend fun readInstalledModIds(
    mods: List<UiMod>,
    store: ClientContentStore = ClientContentStores.shared,
): Map<String, String?> {
    val requestById = linkedMapOf<String, ContentRequest>()
    val stableKeysByRequestId = linkedMapOf<String, MutableList<String>>()
    mods.forEach { uiMod ->
        val key = modStableKey(uiMod.mod)
        val request = trustedModContentRequest(uiMod.mod) ?: return@forEach
        requestById.putIfAbsent(request.id, request)
        stableKeysByRequestId.getOrPut(request.id) { mutableListOf() }.add(key)
    }
    if (requestById.isEmpty()) return emptyMap()

    val modIdsByRequestId = store.useCached(requestById.values.toList()) { paths ->
        requestById.values.associate { request ->
            request.id to paths[request.id]?.toFile()?.takeIf { it.exists() && it.isFile }?.let { file ->
                readInstalledModId(file, request.displayName)
            }
        }
    }.getOrElse { cause ->
        lgr.debug(cause) { "读取Mod缓存失败" }
        emptyMap()
    }
    return buildMap {
        stableKeysByRequestId.forEach { (requestId, stableKeys) ->
            val modId = modIdsByRequestId[requestId]
            stableKeys.forEach { stableKey -> put(stableKey, modId) }
        }
    }
}

internal suspend fun readInstalledModIdsForTest(
    mods: List<UiMod>,
    store: ClientContentStore,
): Map<String, String?> = readInstalledModIds(mods, store)

private suspend fun mergeClientAndServerMods(
    clientMods: List<UiMod>,
    serverMods: List<UiMod>,
): List<UiMod> {
    val installedModIds = readInstalledModIds(clientMods + serverMods)
    val clientEntries = clientMods.map { it.asMergeEntry(installedModIds) }
    val serverEntries = serverMods.map { it.asMergeEntry(installedModIds) }
    val serverByStrictKey = serverEntries.associateBy { it.strictKey }
    val uniqueClientSlugKeys = clientEntries.mapNotNull { it.slugKey }
        .groupingBy { it }
        .eachCount()
        .filterValues { it == 1 }
        .keys
    val serverByUniqueSlugKey = serverEntries.filter { it.slugKey != null }
        .groupBy { it.slugKey!! }
        .filterValues { it.size == 1 }
        .mapValues { it.value.single() }
    val matchedServerEntries = mutableSetOf<ModMergeEntry>()
    val merged = mutableListOf<UiMod>()

    clientEntries.forEach { clientEntry ->
        val strictMatch = serverByStrictKey[clientEntry.strictKey]
            ?.takeIf { it !in matchedServerEntries }
        val slugMatch = clientEntry.slugKey
            ?.takeIf { it in uniqueClientSlugKeys }
            ?.let { serverByUniqueSlugKey[it] }
            ?.takeIf { it !in matchedServerEntries }
        val serverMatch = strictMatch ?: slugMatch
        if (serverMatch != null) {
            if (strictMatch == null) {
                lgr.info {
                    "按slug合并跨来源mod:" +
                        "${clientEntry.mod.platform}:${clientEntry.mod.projectId} ${clientEntry.mod.slug}+" +
                        "${serverMatch.mod.platform}:${serverMatch.mod.projectId} ${serverMatch.mod.slug}"
                }
            }
            matchedServerEntries += serverMatch
            merged += mergeAsBoth(clientEntry.uiMod, serverMatch.uiMod)
        } else {
            merged += clientEntry.uiMod.withSide(Mod.Side.CLIENT)
        }
    }
    serverEntries.forEach { serverEntry ->
        if (serverEntry !in matchedServerEntries) {
            merged += serverEntry.uiMod.withSide(Mod.Side.SERVER)
        }
    }
    val distinctMerged = buildList {
        val seenKeys = mutableSetOf<String>()
        merged.forEach { uiMod ->
            val key = strictModMergeKey(uiMod.mod, installedModIds)
            if (seenKeys.add(key)) add(uiMod)
        }
    }
    return distinctMerged.sortedBy { it.slug.lowercase() }
}

internal suspend fun mergeAsBoth(clientMod: UiMod, serverMod: UiMod): UiMod {
    val sameContent = sameModContent(
        first = clientMod.mod,
        firstFile = clientMod.file,
        second = serverMod.mod,
        secondFile = serverMod.file,
    )
    val mergedDownloadUrls = if (sameContent) {
        (clientMod.mod.downloadUrls + serverMod.mod.downloadUrls).distinct()
    } else {
        clientMod.mod.downloadUrls
    }
    val mergedFile = clientMod.file ?: serverMod.file?.takeIf {
        fileMatchesModContent(it, clientMod.mod)
    }
    return clientMod.copy(
        mod = clientMod.mod.copy(side = Mod.Side.BOTH, downloadUrls = mergedDownloadUrls),
        card = (clientMod.card ?: serverMod.card)?.copy(side = Mod.Side.BOTH),
        file = mergedFile,
    )
}

private suspend fun <T> resultOf(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (cause: Throwable) {
    Result.failure(cause)
}
