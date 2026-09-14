package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.client.service.StartPlayResult
import calebxzhou.rdi.client.ui.McPlayArgs
import calebxzhou.rdi.client.ui.screen.HostTarget
import calebxzhou.rdi.client.ui.screen.UnifiedHostBrief
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HostListViewModelTest {
    @Test
    fun `initial load uses legacy sources in order`() = runBlocking {
        val gateway = FakeHostListGateway(
            mine = listOf(brief("000000000000000000000001", "我的房间")),
            unavailable = listOf(brief("000000000000000000000002", "公开房间")),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)

        awaitLoaded(viewModel)

        assertEquals(listOf("legacy-mine", "legacy-page-0"), gateway.calls)
        assertEquals(2, viewModel.uiState.value.hosts.size)
        assertFalse(viewModel.uiState.value.initialLoading)
    }

    @Test
    fun `mock mode creates twenty hosts without gateway calls`() {
        val gateway = FakeHostListGateway()
        val viewModel = HostListViewModel(gateway, useMockData = true)

        assertEquals(20, viewModel.uiState.value.hosts.size)
        assertTrue(gateway.calls.isEmpty())
        assertFalse(viewModel.uiState.value.initialLoading)
    }

    @Test
    fun `unavailable pages advance and stop after an empty page`() = runBlocking {
        val gateway = FakeHostListGateway(
            unavailablePages = mapOf(
                0 to listOf(brief("000000000000000000000003", "第一页")),
                1 to listOf(brief("000000000000000000000004", "第二页")),
                2 to emptyList(),
            ),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)

        viewModel.loadMore()
        awaitCalls(gateway, 3)
        withTimeout(5_000) { viewModel.uiState.filter { !it.loadingMore && it.hosts.size == 2 }.first() }
        viewModel.loadMore()
        awaitCalls(gateway, 4)
        withTimeout(5_000) { viewModel.uiState.filter { !it.loadingMore }.first() }
        viewModel.loadMore()
        withTimeout(5_000) { viewModel.uiState.filter { !it.loadingMore }.first() }

        assertEquals(listOf("legacy-mine", "legacy-page-0", "legacy-page-1", "legacy-page-2"), gateway.calls)
        viewModel.loadMore()
        assertEquals(4, gateway.calls.size)
    }

    @Test
    fun `source failure closes loading and does not block other sources`() = runBlocking {
        val gateway = FakeHostListGateway(
            mineResult = Result.failure(IllegalStateException("网络不可用")),
            unavailable = listOf(brief("000000000000000000000005", "公开房间")),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)

        awaitLoaded(viewModel)

        assertEquals(1, viewModel.uiState.value.hosts.size)
        assertEquals("加载我的房间失败：网络不可用", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.loadingMore)
    }

    @Test
    fun `closing a retryable page error retries the same page`() = runBlocking {
        val gateway = FakeHostListGateway(
            unavailableResults = ArrayDeque(
                listOf(
                    Result.failure(IllegalStateException("暂时不可用")),
                    Result.success(listOf(brief("000000000000000000000008", "恢复的房间"))),
                )
            ),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)
        assertTrue(viewModel.uiState.value.retryLoadOnError)

        viewModel.clearError()
        withTimeout(5_000) {
            viewModel.uiState.filter { it.hosts.any { host -> host.name == "恢复的房间" } }.first()
        }

        assertEquals(listOf("legacy-page-0", "legacy-page-0"), gateway.calls.filter { it == "legacy-page-0" })
        assertFalse(viewModel.uiState.value.retryLoadOnError)
    }

    @Test
    fun `concurrent load more requests only one blocked page`() = runBlocking {
        val pageGate = CompletableDeferred<Result<List<Host.BriefVo>>>()
        val gateway = FakeHostListGateway(pageGate = pageGate)
        val viewModel = HostListViewModel(gateway, useMockData = false)
        gateway.awaitCall("legacy-page-0")

        viewModel.loadMore()
        viewModel.loadMore()
        pageGate.complete(Result.success(emptyList()))
        awaitLoaded(viewModel)

        assertEquals(1, gateway.calls.count { it == "legacy-page-0" })
    }

    @Test
    fun `refresh isolates results from a cancelled generation`() = runBlocking {
        val oldGate = CompletableDeferred<Result<List<Host.BriefVo>>>()
        val gateway = FakeHostListGateway(
            mine = listOf(brief("00000000000000000000000e", "新房间")),
            mineGate = oldGate,
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        gateway.awaitCall("legacy-mine")

        viewModel.refresh()
        withTimeout(5_000) {
            viewModel.uiState.filter { it.hosts.any { host -> host.name == "新房间" } && !it.initialLoading }.first()
        }
        oldGate.complete(Result.failure(IllegalStateException("旧请求失败")))
        kotlinx.coroutines.yield()

        assertEquals(listOf("新房间"), viewModel.uiState.value.hosts.map { it.name })
        assertEquals(null, viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `deferred retry is bound to the dismissed error instance`() = runBlocking {
        val secondSource = CompletableDeferred<Result<List<Host.BriefVo>>>()
        val gateway = FakeHostListGateway(
            mineResult = Result.failure(IllegalStateException("错误A")),
            pageGate = secondSource,
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        gateway.awaitCall("legacy-page-0")
        withTimeout(5_000) {
            viewModel.uiState.filter { it.errorMessage?.contains("错误A") == true && it.loadingMore }.first()
        }

        viewModel.clearError()
        secondSource.complete(Result.failure(IllegalStateException("错误B")))
        withTimeout(5_000) {
            viewModel.uiState.filter {
                it.errorMessage?.contains("错误B") == true && !it.loadingMore
            }.first()
        }
        val callsAfterCurrentLoad = gateway.calls.size
        kotlinx.coroutines.yield()
        assertEquals(callsAfterCurrentLoad, gateway.calls.size)

        viewModel.clearError()
        awaitCalls(gateway, callsAfterCurrentLoad + 1)
        withTimeout(5_000) {
            viewModel.uiState.filter { !it.loadingMore && it.errorMessage?.contains("错误B") == true }.first()
        }
        assertEquals(2, gateway.calls.count { it == "legacy-page-0" })
    }

    @Test
    fun `delete success queues ok message behind a pagination error`() = runBlocking {
        val deleteGate = CompletableDeferred<Result<Unit>>()
        val gateway = FakeHostListGateway(
            unavailableResults = ArrayDeque(
                listOf(
                    Result.success(listOf(brief("000000000000000000000010", "待删除房间"))),
                    Result.failure(IllegalStateException("分页错误")),
                    Result.success(emptyList()),
                )
            ),
            deleteGate = deleteGate,
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)
        val host = viewModel.uiState.value.hosts.single()
        viewModel.deleteHost(host, deleteWorld = false)
        viewModel.loadMore()

        withTimeout(5_000) {
            viewModel.uiState.filter { it.errorMessage?.contains("分页错误") == true }.first()
        }
        deleteGate.complete(Result.success(Unit))
        withTimeout(5_000) {
            viewModel.uiState.filter {
                it.hosts.isEmpty() && it.errorMessage?.contains("分页错误") == true &&
                    it.okMessage == null && it.retryLoadOnError
            }.first()
        }

        viewModel.clearError()
        awaitCalls(gateway, 4)
        withTimeout(5_000) {
            viewModel.uiState.filter { it.okMessage == "已删除" && !it.loadingMore }.first()
        }
        assertEquals(2, gateway.calls.count { it == "legacy-page-1" })
        viewModel.clearOkMessage()
        assertEquals(null, viewModel.uiState.value.okMessage)
    }

    @Test
    fun `delete success removes host and emits event`() = runBlocking {
        val gateway = FakeHostListGateway(mine = listOf(brief("000000000000000000000006", "待删除")))
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)
        val host = viewModel.uiState.value.hosts.single()

        viewModel.deleteHost(host, deleteWorld = true)

        assertEquals("已删除", withTimeout(5_000) { viewModel.uiState.filter { it.okMessage != null }.first() }.okMessage)
        assertTrue(viewModel.uiState.value.hosts.isEmpty())
        assertEquals(host.target to true, gateway.deleted.single())
        assertEquals(HostListEvent.HostDeleted(host.target), viewModel.events.first())
    }

    @Test
    fun `delete failure keeps host and clears deleting state`() = runBlocking {
        val gateway = FakeHostListGateway(
            mine = listOf(brief("000000000000000000000009", "保留房间")),
            deleteResult = Result.failure(IllegalStateException("删除失败")),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)

        viewModel.deleteHost(viewModel.uiState.value.hosts.single(), deleteWorld = false)
        val state = withTimeout(5_000) {
            viewModel.uiState.filter { it.errorMessage != null && it.deletingHost == null }.first()
        }
        assertEquals(1, state.hosts.size)
    }

    @Test
    fun `error and success messages remain mutually exclusive`() = runBlocking {
        val gateway = FakeHostListGateway(mine = listOf(brief("00000000000000000000000f", "消息房间")))
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)
        val host = viewModel.uiState.value.hosts.single()

        viewModel.deleteHost(host, deleteWorld = false)
        withTimeout(5_000) { viewModel.uiState.filter { it.okMessage != null }.first() }
        viewModel.showError("新的错误")
        assertEquals("新的错误", viewModel.uiState.value.errorMessage)
        assertEquals(null, viewModel.uiState.value.okMessage)
        viewModel.clearError()
        assertEquals(null, viewModel.uiState.value.errorMessage)

        viewModel.deleteHost(host, deleteWorld = false)
        withTimeout(5_000) { viewModel.uiState.filter { it.okMessage != null }.first() }
        viewModel.clearOkMessage()
        assertEquals(null, viewModel.uiState.value.okMessage)
    }

    @Test
    fun `start need mod reports error and clears launching host`() = runBlocking {
        val gateway = FakeHostListGateway(
            mine = listOf(brief("000000000000000000000007", "启动测试")),
            startResult = Result.success(StartPlayResult.NeedMod(listOf("jei", "jade"))),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)

        viewModel.startHost(viewModel.uiState.value.hosts.single())
        val state = withTimeout(5_000) {
            viewModel.uiState.filter { it.errorMessage != null && it.launchingHost == null }.first()
        }

        assertEquals("房间缺少必要Mod：jei、jade", state.errorMessage)
        assertEquals(null, state.launchingHost)
    }

    @Test
    fun `start ready remembers host and emits play event`() = runBlocking {
        val gateway = FakeHostListGateway(
            mine = listOf(brief("00000000000000000000000a", "可启动房间")),
            startResult = Result.success(StartPlayResult.Ready(testPlayArgs())),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)
        val host = viewModel.uiState.value.hosts.single()

        viewModel.startHost(host)
        val event = withTimeout(5_000) { viewModel.events.first() }
        assertEquals(HostListEvent.OpenMcPlay(testPlayArgs()), event)
        assertEquals(host.target to host.name, gateway.remembered.single())
        withTimeout(5_000) { viewModel.uiState.filter { it.launchingHost == null }.first() }
    }

    @Test
    fun `start install and installing emit their expected outcomes`(): Unit = runBlocking {
        val installTask = Task2.Leaf("安装") { }
        var selectedExtras: Boolean? = null
        val pendingInstall = StartPlayResult.NeedInstall(
            title = "安装",
            dedupeKey = "install-key",
            createTask = { includeClientExtras ->
                selectedExtras = includeClientExtras
                installTask
            },
        )
        val installGateway = FakeHostListGateway(
            mine = listOf(brief("00000000000000000000000b", "安装房间")),
            startResult = Result.success(pendingInstall),
        )
        val installViewModel = HostListViewModel(installGateway, useMockData = false)
        awaitLoaded(installViewModel)
        installViewModel.startHost(installViewModel.uiState.value.hosts.single())
        assertEquals(
            HostListEvent.NeedInstall(pendingInstall),
            withTimeout(5_000) { installViewModel.events.first() },
        )
        assertNull(selectedExtras)
        assertSame(installTask, pendingInstall.createTask(true))
        assertEquals(true, selectedExtras)

        val installingGateway = FakeHostListGateway(
            mine = listOf(brief("00000000000000000000000c", "下载房间")),
            startResult = Result.success(StartPlayResult.Installing("run-1")),
        )
        val installingViewModel = HostListViewModel(installingGateway, useMockData = false)
        awaitLoaded(installingViewModel)
        installingViewModel.startHost(installingViewModel.uiState.value.hosts.single())
        val installingState = withTimeout(5_000) {
            installingViewModel.uiState.filter { it.errorMessage != null && it.launchingHost == null }.first()
        }
        assertEquals("整合包正在下载，请等待下载完成后再启动", installingState.errorMessage)
        assertEquals(HostListEvent.OpenTaskList("run-1"), withTimeout(5_000) { installingViewModel.events.first() })
    }

    @Test
    fun `start failure clears launching host and reports error`() = runBlocking {
        val gateway = FakeHostListGateway(
            mine = listOf(brief("00000000000000000000000d", "失败房间")),
            startResult = Result.failure(IllegalStateException("启动失败")),
        )
        val viewModel = HostListViewModel(gateway, useMockData = false)
        awaitLoaded(viewModel)
        viewModel.startHost(viewModel.uiState.value.hosts.single())

        val state = withTimeout(5_000) {
            viewModel.uiState.filter { it.errorMessage == "启动失败" && it.launchingHost == null }.first()
        }
        assertFalse(state.retryLoadOnError)
    }

    private suspend fun awaitLoaded(viewModel: HostListViewModel): HostListUiState =
        withTimeout(5_000) { viewModel.uiState.filter { !it.initialLoading }.first() }

    private suspend fun awaitCalls(gateway: FakeHostListGateway, count: Int) {
        withTimeout(5_000) {
            while (gateway.calls.size < count) kotlinx.coroutines.yield()
        }
    }

    private fun brief(id: String, name: String): Host.BriefVo = Host.BriefVo.TEST.copy(
        _id = ObjectId(id),
        name = name,
    )

    private class FakeHostListGateway(
        private val mine: List<Host.BriefVo> = emptyList(),
        private val unavailable: List<Host.BriefVo> = emptyList(),
        private val unavailablePages: Map<Int, List<Host.BriefVo>> = emptyMap(),
        private val unavailableResults: ArrayDeque<Result<List<Host.BriefVo>>>? = null,
        private val mineResult: Result<List<Host.BriefVo>> = Result.success(mine),
        private val mineGate: CompletableDeferred<Result<List<Host.BriefVo>>>? = null,
        private val startResult: Result<StartPlayResult> = Result.failure(IllegalStateException("未配置启动结果")),
        private val deleteResult: Result<Unit> = Result.success(Unit),
        private val pageGate: CompletableDeferred<Result<List<Host.BriefVo>>>? = null,
        private val deleteGate: CompletableDeferred<Result<Unit>>? = null,
    ) : HostListGateway {
        val calls = CopyOnWriteArrayList<String>()
        val deleted = CopyOnWriteArrayList<Pair<HostTarget, Boolean>>()
        val remembered = CopyOnWriteArrayList<Pair<HostTarget, String>>()
        private val callEvents = Channel<String>(Channel.BUFFERED)
        private var mineCalls = 0

        private fun record(call: String) {
            calls += call
            callEvents.trySend(call)
        }

        suspend fun awaitCall(expected: String) {
            withTimeout(5_000) {
                while (callEvents.receive() != expected) {
                    // Ignore the preceding calls made by the initial load.
                }
            }
        }

        override suspend fun loadLegacyMine(): Result<List<Host.BriefVo>> {
            record("legacy-mine")
            mineCalls++
            if (mineCalls == 1) mineGate?.let { return withContext(NonCancellable) { it.await() } }
            return mineResult
        }

        override suspend fun loadLegacyUnavailable(page: Int): Result<List<Host.BriefVo>> {
            record("legacy-page-$page")
            pageGate?.let { return it.await() }
            unavailableResults?.let { return it.removeFirst() }
            return Result.success(unavailablePages[page] ?: if (page == 0) unavailable else emptyList())
        }

        override suspend fun deleteHost(target: HostTarget, deleteWorld: Boolean): Result<Unit> {
            deleted += target to deleteWorld
            return deleteGate?.await() ?: deleteResult
        }

        override suspend fun startHost(host: UnifiedHostBrief): Result<StartPlayResult> = startResult

        override fun rememberLastPlayed(target: HostTarget, hostName: String): Result<Unit> {
            remembered += target to hostName
            return Result.success(Unit)
        }
    }

    private fun testPlayArgs() = McPlayArgs(
        title = "测试房间",
        mcVer = McVersion.V211,
        modLoader = ModLoader.neoforge,
        versionId = "test-version",
        playArg = "test-arg",
    )
}
