package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.client.service.dmMasterUrl
import calebxzhou.rdi.client.net.lgr
import calebxzhou.rdi.client.net.rdiResponse
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.DmHostBrief
import calebxzhou.rdi.common.model.Response
import calebxzhou.rdi.common.net.httpRequest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The player's own rdi7 test rooms; nothing is requested until they are shown. */
data class DmRoomListUiState(
    val shown: Boolean = false,
    val rooms: List<DmHostBrief> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface DmRoomGateway {
    suspend fun listMine(): Result<List<DmHostBrief>>
    suspend fun delete(id: String): Result<Unit>
}

class RdiDmRoomGateway : DmRoomGateway {
    override suspend fun listMine(): Result<List<DmHostBrief>> = dmResultOf {
        request<List<DmHostBrief>>("hosts", HttpMethod.Get).data.orEmpty()
    }

    override suspend fun delete(id: String): Result<Unit> = dmResultOf {
        request<Unit>("hosts/$id", HttpMethod.Delete)
        Unit
    }

    private suspend inline fun <reified T> request(path: String, requestMethod: HttpMethod): Response<T> {
        val ticket = apiTicket()
        val response = try {
            httpRequest {
                url("${dmMasterUrl(server.ip)}/$path")
                method = requestMethod
                bearerAuth(ticket)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (cause: Exception) {
            lgr.warn(cause) { "dm-master request failed: $requestMethod $path" }
            throw RequestError("无法连接测试房间服务")
        }
        val result = response.rdiResponse<T>()
        if (!result.ok) throw RequestError(result.msg)
        return result
    }

    private suspend fun apiTicket(): String {
        val response = server.makeRequest<String>("player/dm-ticket", HttpMethod.Post, mapOf("aud" to "api"))
        if (!response.ok) throw RequestError(response.msg)
        return response.data?.takeIf(String::isNotBlank) ?: throw RequestError("账号服务没有返回票据")
    }
}

class DmRoomListViewModel(private val gateway: DmRoomGateway) : ViewModel() {
    private val _uiState = MutableStateFlow(DmRoomListUiState())
    val uiState: StateFlow<DmRoomListUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun toggle() {
        val show = !_uiState.value.shown
        job?.cancel()
        _uiState.update { it.copy(shown = show, rooms = emptyList(), loading = false) }
        if (show) load()
    }

    /** Coming back to the menu shows whether the rooms are still being hosted. */
    fun onMenuResumed() {
        if (_uiState.value.shown && job?.isActive != true) load()
    }

    fun delete(id: String) {
        job?.cancel()
        _uiState.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            gateway.delete(id).fold(
                onSuccess = {
                    _uiState.update { state ->
                        state.copy(rooms = state.rooms.filterNot { room -> room.id == id }, message = "房间已删除")
                    }
                },
                onFailure = { showFailure("删除房间失败", it) },
            )
            _uiState.update { it.copy(loading = false) }
        }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun load() {
        _uiState.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            gateway.listMine().fold(
                onSuccess = { rooms -> _uiState.update { it.copy(rooms = rooms) } },
                onFailure = { showFailure("加载测试房间失败", it) },
            )
            _uiState.update { it.copy(loading = false) }
        }
    }

    private fun showFailure(prefix: String, cause: Throwable) {
        lgr.warn(cause) { prefix }
        showError("$prefix：${cause.message ?: "未知错误"}")
    }
}

private suspend inline fun <T> dmResultOf(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancel: CancellationException) {
    throw cancel
} catch (cause: Throwable) {
    Result.failure(cause)
}
