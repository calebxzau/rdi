package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.ui.screen.HostTarget
import calebxzhou.rdi.client.ui.screen.UnifiedHostBrief
import calebxzhou.rdi.client.ui.screen.selectShownHosts
import calebxzhou.rdi.common.model.Host
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostListResumeTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `first resume shares initial load and returning discovers a created room`() = runTest(dispatcher) {
        val gateway = Gateway()
        val viewModel = HostListViewModel(gateway, useMockData = false)
        viewModel.onMenuResumed()
        advanceUntilIdle()
        assertEquals(1, gateway.mineCalls)
        assertTrue(viewModel.uiState.value.hosts.isEmpty())

        gateway.mineResult = Result.success(listOf(brief(1)))
        viewModel.onMenuResumed()
        advanceUntilIdle()

        assertEquals(2, gateway.mineCalls)
        assertEquals(listOf("房间1"), viewModel.uiState.value.hosts.map { it.name })
        assertFalse(viewModel.uiState.value.showOtherHosts)
        assertTrue(gateway.pages.isEmpty())
    }

    @Test
    fun `resume replaces stale playable snapshot and retains other pages and visibility`() = runTest(dispatcher) {
        val removed = brief(1)
        val updated = brief(2)
        val other = brief(3, playable = false)
        val newlyPlayable = brief(4, playable = false)
        val gateway = Gateway(listOf(removed, updated), listOf(other, newlyPlayable))
        val viewModel = HostListViewModel(gateway, useMockData = false, showOtherHosts = true)
        viewModel.onMenuResumed()
        advanceUntilIdle()
        val before = viewModel.uiState.value.hosts

        gateway.mineResult = Result.success(listOf(updated.copy(name = "新名字"), newlyPlayable.copy(playable = true)))
        val gate = CompletableDeferred<Unit>()
        gateway.mineGate = gate
        viewModel.onMenuResumed()
        advanceUntilIdle()
        assertEquals(before, viewModel.uiState.value.hosts)
        assertFalse(viewModel.uiState.value.initialLoading)
        gate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.showOtherHosts)
        assertEquals(setOf("新名字", "房间3", "房间4"), state.hosts.map { it.name }.toSet())
        assertTrue(state.hosts.single { it.name == "房间4" }.playable)
        assertEquals(listOf(0), gateway.pages)
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0, 1), gateway.pages)
    }

    @Test
    fun `failed refresh retains cards and retries after dismissing error`() = runTest(dispatcher) {
        val gateway = Gateway(listOf(brief(1)))
        val viewModel = HostListViewModel(gateway, useMockData = false)
        viewModel.onMenuResumed()
        advanceUntilIdle()
        val before = viewModel.uiState.value.hosts

        gateway.mineResult = Result.failure(IllegalStateException("断网"))
        viewModel.onMenuResumed()
        advanceUntilIdle()
        assertEquals(before, viewModel.uiState.value.hosts)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.retryLoadOnError)
        assertFalse(viewModel.uiState.value.loadingMore)

        gateway.mineResult = Result.success(listOf(brief(2)))
        viewModel.clearError()
        advanceUntilIdle()
        assertEquals(listOf("房间2"), viewModel.uiState.value.hosts.map { it.name })
        assertFalse(viewModel.uiState.value.retryLoadOnError)
    }

    @Test
    fun `repeated resumes during a request coalesce and fetch a newer snapshot afterwards`() = runTest(dispatcher) {
        val gateway = Gateway(listOf(brief(1)))
        val viewModel = HostListViewModel(gateway, useMockData = false)
        viewModel.onMenuResumed()
        advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        gateway.mineGate = gate
        viewModel.onMenuResumed()
        advanceUntilIdle()
        gateway.mineResult = Result.success(listOf(brief(2)))
        repeat(3) { viewModel.onMenuResumed() }
        assertEquals(2, gateway.mineCalls)
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(3, gateway.mineCalls)
        assertEquals(listOf("房间2"), viewModel.uiState.value.hosts.map { it.name })
    }

    @Test
    fun `show other hosts during queued refresh still loads their first page`() = runTest(dispatcher) {
        val gateway = Gateway(listOf(brief(1)), listOf(brief(2, playable = false)))
        val gate = CompletableDeferred<Unit>()
        gateway.mineGate = gate
        val viewModel = HostListViewModel(gateway, useMockData = false)
        viewModel.onMenuResumed()
        advanceUntilIdle()
        viewModel.onMenuResumed()
        viewModel.toggleOtherHosts()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, gateway.mineCalls)
        assertEquals(listOf(0, 1), gateway.pages)
        assertEquals(2, viewModel.uiState.value.hosts.size)
        assertTrue(viewModel.uiState.value.showOtherHosts)
    }

    @Test
    fun `a cancelled background refresh cannot overwrite a newer generation`() = runTest(dispatcher) {
        val gateway = Gateway(listOf(brief(1)))
        val viewModel = HostListViewModel(gateway, useMockData = false)
        viewModel.onMenuResumed()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        gateway.mineGate = gate
        viewModel.onMenuResumed()
        advanceUntilIdle()

        gateway.mineResult = Result.success(listOf(brief(2)))
        viewModel.refresh()
        advanceUntilIdle()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("房间2"), viewModel.uiState.value.hosts.map { it.name })
    }

    @Test
    fun `ordinary account and dav can both hide loaded other hosts without mutating source`() {
        val playable = UnifiedHostBrief.fromLegacy(brief(1))
        val other = UnifiedHostBrief.fromLegacy(brief(2, playable = false))
        val hosts = listOf(other, playable)
        for (isDav in listOf(false, true)) {
            val shown = selectShownHosts(hosts, showOtherHosts = true, isDav = isDav)
            assertEquals(listOf(playable.target, other.target), shown.map { it.target })
            assertEquals(isDav, shown.last().playable)
            assertEquals(listOf(playable.target), selectShownHosts(hosts, false, isDav).map { it.target })
            assertEquals(2, selectShownHosts(hosts, true, isDav).size)
        }
        assertFalse(other.playable)
    }

    private fun brief(id: Int, playable: Boolean = true): Host.BriefVo = Host.BriefVo.TEST.copy(
        _id = ObjectId(id.toString(16).padStart(24, '0')),
        name = "房间${id}",
        playable = playable,
    )

    private class Gateway(
        mine: List<Host.BriefVo> = emptyList(),
        private val other: List<Host.BriefVo> = emptyList(),
    ) : HostListGateway {
        var mineResult: Result<List<Host.BriefVo>> = Result.success(mine)
        var mineGate: CompletableDeferred<Unit>? = null
        var mineCalls = 0
        val pages = mutableListOf<Int>()

        override suspend fun loadLegacyMine(): Result<List<Host.BriefVo>> {
            mineCalls++
            val snapshot = mineResult
            val gate = mineGate
            mineGate = null
            if (gate != null) withContext(NonCancellable) { gate.await() }
            return snapshot
        }

        override suspend fun loadLegacyUnavailable(page: Int): Result<List<Host.BriefVo>> {
            pages += page
            return Result.success(if (page == 0) other else emptyList())
        }

        override suspend fun startHost(host: UnifiedHostBrief): Result<StartPlayResult> = error("Unexpected start")

        override fun rememberLastPlayed(target: HostTarget, hostName: String): Result<Unit> = error("Unexpected play")
    }
}
