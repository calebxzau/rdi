package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bson.types.ObjectId

private val lgr by Loggers

/** 主菜单刷新在线玩家的间隔。 */
private const val ONLINE_PLAYERS_POLL_INTERVAL_MS = 30_000L

interface OnlinePlayersGateway {
    /** 所有房间里正在游戏的玩家，已去重。 */
    suspend fun loadOnlinePlayerIds(): Result<List<ObjectId>>
}

class RdiOnlinePlayersGateway : OnlinePlayersGateway {
    override suspend fun loadOnlinePlayerIds(): Result<List<ObjectId>> = try {
        val response = server.makeRequest<List<ObjectId>>("host/online-player-ids")
        if (!response.ok) throw RequestError(response.msg)
        Result.success(response.data.orEmpty())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (cause: Throwable) {
        Result.failure(cause)
    }
}

class OnlinePlayersViewModel(
    private val gateway: OnlinePlayersGateway,
) : ViewModel() {
    private val _onlinePlayerIds = MutableStateFlow<List<ObjectId>>(emptyList())
    val onlinePlayerIds: StateFlow<List<ObjectId>> = _onlinePlayerIds.asStateFlow()

    /** 一直轮询到调用方取消；请求失败时记日志并保留上一次的列表。 */
    suspend fun pollOnlinePlayers() {
        while (true) {
            gateway.loadOnlinePlayerIds()
                .onSuccess { _onlinePlayerIds.value = it.distinct() }
                .onFailure { lgr.warn(it) { "获取在线玩家失败" } }
            delay(ONLINE_PLAYERS_POLL_INTERVAL_MS)
        }
    }
}
