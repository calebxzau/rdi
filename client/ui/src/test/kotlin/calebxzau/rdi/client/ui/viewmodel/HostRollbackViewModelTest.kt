package calebxzau.rdi.client.ui.viewmodel

import calebxzau.rdi.client.service.HostRollbackApi
import calebxzau.rdi.common.model.HostRollbackCreateVo
import calebxzau.rdi.common.model.HostRollbackOutcome
import calebxzau.rdi.common.model.HostRollbackPendingVo
import calebxzau.rdi.common.model.HostRollbackPointKind
import calebxzau.rdi.common.model.HostRollbackPointVo
import calebxzau.rdi.common.model.HostRollbackRequestState
import calebxzau.rdi.common.model.HostRollbackResultVo
import calebxzau.rdi.common.model.HostRollbackStatusVo
import calebxzhou.rdi.common.exception.RequestError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class HostRollbackViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val older = point("older", 1_000_000L)
    private val newer = point("newer", 2_000_000L)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun pendingRollbackCanBeCancelledOnlyOnceTheHostStopped() {
        val pending = HostRollbackPendingVo("older", 1L, HostRollbackRequestState.Requested, abandoned = false)
        assertEquals(HostRollbackBanner.Pending("older", canCancel = false), rollbackBanner(status(pending = pending, running = true)))
        assertEquals(HostRollbackBanner.Pending("older", canCancel = true), rollbackBanner(status(pending = pending)))

        val applying = pending.copy(state = HostRollbackRequestState.Applying)
        assertEquals(HostRollbackBanner.Unfinished("older", canCancel = true), rollbackBanner(status(pending = applying)))
        assertEquals(HostRollbackBanner.Abandoned, rollbackBanner(status(pending = applying.copy(abandoned = true))))
    }

    @Test
    fun pendingRollbackHidesTheLastResult() {
        val pending = HostRollbackPendingVo("older", 1L, HostRollbackRequestState.Requested, abandoned = false)
        val banner = rollbackBanner(status(pending = pending, result = result(HostRollbackOutcome.Failed, before = null)))
        assertEquals(HostRollbackBanner.Pending("older", canCancel = true), banner)
    }

    @Test
    fun undoIsOfferedWhileTheBeforePointIsKept() {
        assertEquals(HostRollbackBanner.Done("older", undoPointId = "newer"), rollbackBanner(status(result = result(HostRollbackOutcome.Done, before = "newer"))))
        assertEquals(HostRollbackBanner.Done("older", undoPointId = null), rollbackBanner(status(result = result(HostRollbackOutcome.Done, before = "expired"))))
        assertEquals(HostRollbackBanner.Failed("坏了"), rollbackBanner(status(result = result(HostRollbackOutcome.Failed, before = null, message = "坏了"))))
        assertNull(rollbackBanner(status()))
    }

    @Test
    fun pointsAreGroupedByLocalDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
        val points = listOf(point("a", at(13, 9)), point("b", at(13, 1)), point("c", at(12, 23)), point("d", at(10, 8)))

        val groups = groupRollbackPointsByDay(points, now = at(13, 12), zone = zone)
        assertEquals(listOf("今天", "昨天", "10月10日"), groups.map { it.label })
        assertEquals(listOf("a", "b"), groups[0].points.map { it.id })
    }

    @Test
    fun labelsUseCompactChineseText() {
        assertEquals("25分钟前", rollbackAgo(0, now = 25 * 60_000L))
        assertEquals("3小时前", rollbackAgo(0, now = 3 * 3_600_000L))
        assertEquals(0, rollbackUsagePercent(0, 1024))
        assertEquals(1, rollbackUsagePercent(1, 1024), "any used space shows at least 1%")
        assertEquals(50, rollbackUsagePercent(512, 1024))
        assertEquals(100, rollbackUsagePercent(2048, 1024))
        assertEquals("只有生物或物品变化", rollbackPointChanges(older.copy(changedChunks = 0, changedPlayers = 0)))
        assertEquals("区块变化2个 · 玩家变化1人", rollbackPointChanges(older))
    }

    @Test
    fun rollbackTellsWhetherTheHostIsBeingStoppedAndShowsTheNewRequest() = runTest(dispatcher) {
        val api = FakeApi(status(running = true))
        val vm = HostRollbackViewModel("host", api)
        runCurrent()

        api.next = status(pending = HostRollbackPendingVo("older", 1L, HostRollbackRequestState.Requested, false), running = true)
        vm.rollback("older")
        runCurrent()

        assertEquals(listOf("older"), api.requested)
        assertEquals("房间正在关闭，下次启动房间时会回档", vm.uiState.value.notice)
        assertEquals(HostRollbackBanner.Pending("older", canCancel = false), vm.uiState.value.banner)
        assertNull(vm.uiState.value.busy)
        // Lets the follow-up refresh end instead of polling forever.
        api.next = status()
    }

    @Test
    fun aPendingRollbackOnARunningHostIsFollowedUntilTheHostStops() = runTest(dispatcher) {
        val pending = HostRollbackPendingVo("older", 1L, HostRollbackRequestState.Requested, false)
        val api = FakeApi(status(pending = pending, running = true))
        val vm = HostRollbackViewModel("host", api, pendingRefreshMillis = 1_000L)
        runCurrent()
        assertEquals(1, api.statusCalls)

        api.next = status(pending = pending)
        advanceTimeBy(1_001L)
        assertEquals(2, api.statusCalls)
        assertEquals(HostRollbackBanner.Pending("older", canCancel = true), vm.uiState.value.banner)

        advanceTimeBy(5_000L)
        assertEquals(2, api.statusCalls, "a stopped host is not polled")
    }

    @Test
    fun serverRefusalIsShown() = runTest(dispatcher) {
        val api = FakeApi(status())
        api.failRequest = RequestError("回档点不存在或已过期")
        val vm = HostRollbackViewModel("host", api)
        runCurrent()
        vm.rollback("gone")
        runCurrent()
        assertEquals("回档点不存在或已过期", vm.uiState.value.error)
        assertNull(vm.uiState.value.notice)
    }

    private fun point(id: String, createdAt: Long) =
        HostRollbackPointVo(id, HostRollbackPointKind.Auto, createdAt, chunkCount = 10, playerCount = 2, changedChunks = 2, changedPlayers = 1)

    private fun result(outcome: HostRollbackOutcome, before: String?, message: String? = null) =
        HostRollbackResultVo("older", before, outcome, message, finishedAt = 3L)

    private fun status(
        pending: HostRollbackPendingVo? = null,
        result: HostRollbackResultVo? = null,
        running: Boolean = false,
    ) = HostRollbackStatusVo(listOf(newer, older), pending, result, usedBytes = 1L, maxBytes = 2L, hostRunning = running)

    private class FakeApi(var next: HostRollbackStatusVo) : HostRollbackApi {
        var statusCalls = 0
        val requested = mutableListOf<String>()
        var failRequest: RequestError? = null

        override suspend fun status(hostId: String): HostRollbackStatusVo {
            statusCalls++
            return next
        }

        override suspend fun request(hostId: String, pointId: String): HostRollbackCreateVo {
            failRequest?.let { throw it }
            requested += pointId
            return HostRollbackCreateVo(stoppedHost = next.hostRunning)
        }

        override suspend fun cancel(hostId: String) = Unit
    }
}
