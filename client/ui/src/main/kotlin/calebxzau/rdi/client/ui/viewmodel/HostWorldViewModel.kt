package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.client.service.HostWorldDownloadApi
import calebxzau.rdi.client.service.HostWorldDownloadTask
import calebxzau.rdi.client.service.RdiHostWorldDownloadApi
import calebxzau.rdi.common.model.HostWorldDownloadStatusVo
import calebxzhou.rdi.client.net.loggedAccount
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.ClientTaskManager
import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.service.startPlay
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2Status
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.model.Role
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.bson.types.ObjectId
import java.io.File
import java.nio.file.Path

data class HostWorldUiState(
    val host: Host.DetailVo? = null,
    val isOwner: Boolean = false,
    /** A member who is not a guest; only they may download the save. */
    val isMember: Boolean = false,
    val download: HostWorldDownloadStatusVo? = null,
    val busy: String? = null,
    val error: String? = null,
    /** What the last successful action did. */
    val notice: String? = null,
    /** The background task downloading this host's save, while it runs or after it was started here. */
    val downloadRunId: String? = null,
    /** The `saves` folder the save is downloaded into. */
    val savesDir: Path? = null,
) {
    /** Save import, rollback and reset are owner (or dav) only on the server. */
    val canImportSave: Boolean get() = isOwner

    /** 回档 is supported on 1.20.1 Forge hosts for now. */
    val canRollback: Boolean
        get() {
            val modpack = host?.modpack ?: return false
            return isOwner && modpack.mcVer == McVersion.V201 && modpack.modloader == ModLoader.forge
        }

    val canResetWorld: Boolean get() = isOwner && host?.version == 2

    val canDownload: Boolean get() = isMember && host?.version == 2
}

/** Server calls, local files and background tasks of the host save screen; tests substitute their own. */
interface HostWorldGateway {
    suspend fun hostDetail(hostId: String): Host.DetailVo

    suspend fun resetWorld(hostId: String)

    suspend fun downloadStatus(hostId: String): HostWorldDownloadStatusVo

    /** The `saves` folder of the host's modpack; throws a [RequestError] when it is not installed. */
    suspend fun savesDir(host: Host.DetailVo): Path

    /** Starts the download task, or returns the one already running for [hostId]. */
    fun submitDownload(hostId: String, hostName: String, savesDir: Path): String

    /** The download task still running for [hostId], if any. */
    fun runningDownload(hostId: String): String?

    /** The status of task [runId]; null once it is removed from the task list. */
    fun taskStatus(runId: String): Flow<Task2Status?>
}

class RdiHostWorldGateway(
    private val downloads: HostWorldDownloadApi = RdiHostWorldDownloadApi,
) : HostWorldGateway {
    override suspend fun hostDetail(hostId: String): Host.DetailVo {
        val response = server.makeRequest<Host.DetailVo>("host/${hostId}/detail")
        if (!response.ok) throw RequestError(response.msg, errorCode = response.errorCode)
        return response.data ?: throw RequestError("无法加载房间信息")
    }

    override suspend fun resetWorld(hostId: String) {
        val response = server.makeRequest<Unit>("host/${hostId}/reset-world", HttpMethod.Post)
        if (!response.ok) throw RequestError(response.msg, errorCode = response.errorCode)
    }

    override suspend fun downloadStatus(hostId: String) = downloads.status(hostId)

    override suspend fun savesDir(host: Host.DetailVo): Path = when (val result = host.startPlay(startHost = false)) {
        is StartPlayResult.Ready ->
            File(result.args.versionDir ?: throw RequestError("找不到房间整合包目录")).toPath().resolve("saves")
        is StartPlayResult.Installing -> throw RequestError("房间整合包正在安装，请等待安装完成后再下载存档")
        else -> throw RequestError("请先进入房间游玩一次，安装好房间整合包后再下载存档")
    }

    override fun submitDownload(hostId: String, hostName: String, savesDir: Path): String =
        ClientTaskManager.submit(HostWorldDownloadTask(downloads).task(hostId, hostName, savesDir), dedupeKey(hostId))

    override fun runningDownload(hostId: String): String? =
        ClientTaskManager.entries.value.firstOrNull { it.dedupeKey == dedupeKey(hostId) && !it.status.isTerminal }?.runId

    override fun taskStatus(runId: String): Flow<Task2Status?> =
        ClientTaskManager.entries.map { entries -> entries.firstOrNull { it.runId == runId }?.status }

    private fun dedupeKey(hostId: String) = "host-world-download:${hostId}"
}

/** The 房间存档管理 screen: which save actions this player has, downloading and resetting the save. */
class HostWorldViewModel(
    private val hostId: String,
    private val gateway: HostWorldGateway = RdiHostWorldGateway(),
    private val selfId: () -> ObjectId = { loggedAccount._id },
    /** dav counts as the owner of every host. */
    private val selfIsDav: () -> Boolean = { loggedAccount.isDav },
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostWorldUiState())
    val uiState: StateFlow<HostWorldUiState> = _uiState.asStateFlow()

    init {
        gateway.runningDownload(hostId)?.let { runId ->
            _uiState.update { it.copy(downloadRunId = runId) }
            follow(runId)
        }
        reload()
    }

    fun reload() = work("正在加载房间") { load() }

    fun resetWorld() = work("正在重置存档") {
        if (!_uiState.value.canResetWorld) throw RequestError("只有房主可以重置存档")
        gateway.resetWorld(hostId)
        _uiState.update { it.copy(notice = "存档已重置，下次启动房间时会生成新存档") }
        load()
    }

    /**
     * Checks what can be checked here, then hands the download to a background task, so leaving the
     * screen does not stop it. The task packs the save on the server (or reuses the packed one),
     * downloads it and puts it into the modpack's `saves` folder as a new singleplayer save.
     */
    fun downloadSave() = work("正在准备下载") {
        val state = _uiState.value
        val host = state.host ?: throw RequestError("房间信息未加载")
        if (!state.canDownload) throw RequestError("只有房间成员可以下载存档")
        val status = gateway.downloadStatus(hostId)
        _uiState.update { it.copy(download = status) }
        if (!status.reusable) {
            status.blockedReason?.let { throw RequestError(it) }
            if (status.remaining <= 0) throw RequestError("今天的${status.limit}次下载已经用完，明天再来")
        }
        val savesDir = gateway.savesDir(host)
        val runId = gateway.submitDownload(hostId, host.name, savesDir)
        _uiState.update {
            it.copy(downloadRunId = runId, savesDir = savesDir, notice = "已开始下载，可以在任务列表查看进度")
        }
        follow(runId)
    }

    /** Refreshes the day's downloads once the task ends. */
    private fun follow(runId: String) {
        viewModelScope.launch {
            val status = gateway.taskStatus(runId).first { it == null || it.isTerminal }
            if (_uiState.value.downloadRunId != runId) return@launch
            _uiState.update {
                when (status) {
                    Task2Status.DONE -> it.copy(notice = "存档已下载到整合包的单人存档里")
                    Task2Status.FAILED -> it.copy(notice = null, error = "存档下载失败，可以在任务列表查看原因")
                    else -> it.copy(notice = null)
                }
            }
            runCatching { load() }.onFailure { exception ->
                calebxzhou.rdi.client.net.lgr.warn(exception) { "刷新存档下载次数失败: ${hostId}" }
            }
        }
    }

    private suspend fun load() {
        val host = gateway.hostDetail(hostId)
        val self = selfId()
        val dav = selfIsDav()
        val isMember = dav || host.members.any { it.id == self && it.role != Role.GUEST }
        val download = if (isMember && host.version == 2) gateway.downloadStatus(hostId) else null
        _uiState.update { it.copy(host = host, isOwner = dav || host.ownerId == self, isMember = isMember, download = download) }
    }

    private fun work(message: String, block: suspend () -> Unit) {
        if (_uiState.value.busy != null) return
        _uiState.update { it.copy(busy = message, error = null, notice = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error !is RequestError) calebxzhou.rdi.client.net.lgr.error(error) { "存档管理操作失败: ${message}" }
                _uiState.update { it.copy(error = error.message ?: "操作失败") }
            } finally {
                _uiState.update { it.copy(busy = null) }
            }
        }
    }
}
