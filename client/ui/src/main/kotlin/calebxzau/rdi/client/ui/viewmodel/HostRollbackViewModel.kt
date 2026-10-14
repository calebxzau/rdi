package calebxzau.rdi.client.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import calebxzau.rdi.client.service.HostRollbackApi
import calebxzau.rdi.client.service.RdiHostRollbackApi
import calebxzau.rdi.common.model.HostRollbackOutcome
import calebxzau.rdi.common.model.HostRollbackPointVo
import calebxzau.rdi.common.model.HostRollbackRequestState
import calebxzau.rdi.common.model.HostRollbackStatusVo
import calebxzhou.rdi.common.exception.RequestError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The one notice shown above the 回档点 list. */
sealed interface HostRollbackBanner {
    /** Waits for the next start; it can be cancelled once the host has stopped. */
    data class Pending(val pointId: String, val canCancel: Boolean) : HostRollbackBanner

    /** The host started writing it and stopped before finishing; the next start continues. */
    data class Unfinished(val pointId: String, val canCancel: Boolean) : HostRollbackBanner

    /** The owner gave up an unfinished rollback; the next start restores the state before it. */
    data object Abandoned : HostRollbackBanner

    /** [undoPointId] is the point taken right before it, while that point is still kept. */
    data class Done(val pointId: String?, val undoPointId: String?) : HostRollbackBanner

    data class Failed(val message: String) : HostRollbackBanner
}

data class HostRollbackDayGroup(val label: String, val points: List<HostRollbackPointVo>)

data class HostRollbackUiState(
    val status: HostRollbackStatusVo? = null,
    val busy: String? = null,
    val error: String? = null,
    /** What the last successful action did. */
    val notice: String? = null,
) {
    val banner: HostRollbackBanner? get() = status?.let(::rollbackBanner)
}

/** The rollback (回档) screen of a host; owner only, 1.20.1 Forge hosts only. */
class HostRollbackViewModel(
    private val hostId: String,
    private val api: HostRollbackApi = RdiHostRollbackApi,
    private val pendingRefreshMillis: Long = PENDING_REFRESH_MILLIS,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HostRollbackUiState())
    val uiState: StateFlow<HostRollbackUiState> = _uiState.asStateFlow()
    private var autoRefresh: Job? = null

    init {
        reload()
    }

    fun reload() = work("正在加载回档点") {
        _uiState.update { it.copy(notice = null) }
        load()
    }

    fun rollback(pointId: String) = work("正在设置回档") {
        val created = api.request(hostId, pointId)
        _uiState.update {
            it.copy(notice = if (created.stoppedHost) "房间正在关闭，下次启动房间时会回档" else "已设置回档，下次启动房间时生效")
        }
        load()
    }

    fun cancel() = work("正在取消回档") {
        api.cancel(hostId)
        _uiState.update { it.copy(notice = "已取消回档") }
        load()
    }

    private suspend fun load() {
        val status = api.status(hostId)
        _uiState.update { it.copy(status = status) }
        scheduleAutoRefresh(status)
    }

    /** While a rollback waits on a running host, the host soon stops or reports the result, so the screen follows it quietly. */
    private fun scheduleAutoRefresh(status: HostRollbackStatusVo) {
        autoRefresh?.cancel()
        if (status.pending == null || !status.hostRunning) return
        autoRefresh = viewModelScope.launch {
            delay(pendingRefreshMillis)
            if (_uiState.value.busy != null) return@launch
            try {
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                calebxzhou.rdi.client.net.lgr.warn(error) { "自动刷新回档状态失败: ${hostId}" }
            }
        }
    }

    private fun work(message: String, block: suspend () -> Unit) {
        if (_uiState.value.busy != null) return
        _uiState.update { it.copy(busy = message, error = null) }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error !is RequestError) calebxzhou.rdi.client.net.lgr.error(error) { "回档操作失败: ${message}" }
                _uiState.update { it.copy(error = error.message ?: "操作失败") }
            } finally {
                _uiState.update { it.copy(busy = null) }
            }
        }
    }

    private companion object {
        const val PENDING_REFRESH_MILLIS = 10_000L
    }
}

internal fun rollbackBanner(status: HostRollbackStatusVo): HostRollbackBanner? {
    status.pending?.let { pending ->
        return when {
            pending.state == HostRollbackRequestState.Requested -> HostRollbackBanner.Pending(pending.pointId, canCancel = !status.hostRunning)
            pending.abandoned -> HostRollbackBanner.Abandoned
            else -> HostRollbackBanner.Unfinished(pending.pointId, canCancel = !status.hostRunning)
        }
    }
    val result = status.lastResult ?: return null
    return when (result.outcome) {
        HostRollbackOutcome.Done -> HostRollbackBanner.Done(
            pointId = result.pointId,
            undoPointId = result.beforePointId?.takeIf { before -> status.points.any { it.id == before } },
        )
        HostRollbackOutcome.Failed -> HostRollbackBanner.Failed(result.message ?: "未知错误")
    }
}

/** Groups newest-first points by local day: 今天, 昨天, then M月d日. */
internal fun groupRollbackPointsByDay(
    points: List<HostRollbackPointVo>,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<HostRollbackDayGroup> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return points.groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .map { (day, dayPoints) -> HostRollbackDayGroup(dayLabel(day, today), dayPoints) }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("M月d日")
private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> DAY_FORMAT.format(day)
}

internal fun rollbackPointTime(createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FORMAT.format(Instant.ofEpochMilli(createdAt).atZone(zone))

internal fun rollbackPointDateTime(createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_TIME_FORMAT.format(Instant.ofEpochMilli(createdAt).atZone(zone))

/** The 回档点 the banner or a dialog refers to, or 已过期的回档点 when it is no longer kept. */
internal fun rollbackPointName(status: HostRollbackStatusVo, pointId: String?): String =
    status.points.firstOrNull { it.id == pointId }?.let { rollbackPointDateTime(it.createdAt) } ?: "已过期的回档点"

internal fun rollbackAgo(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - createdAt) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> "${minutes / (24 * 60)}天前"
    }
}

internal fun rollbackPointChanges(point: HostRollbackPointVo): String = when {
    point.changedChunks == 0 && point.changedPlayers == 0 -> "只有生物或物品变化"
    else -> "区块变化${point.changedChunks}个 · 玩家变化${point.changedPlayers}人"
}

/** Share of the rollback space in use, rounded up so any used space shows at least 1%. */
internal fun rollbackUsagePercent(usedBytes: Long, maxBytes: Long): Int {
    if (maxBytes <= 0 || usedBytes <= 0) return 0
    return ((usedBytes * 100 + maxBytes - 1) / maxBytes).toInt().coerceIn(0, 100)
}
