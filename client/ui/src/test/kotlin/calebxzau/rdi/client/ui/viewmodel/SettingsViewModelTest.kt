package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.common.model.DownloadQuota
import calebxzhou.rdi.client.service.ModpackLocalDir
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.File

class SettingsViewModelTest {
    @Test
    fun `initialization backfills saved settings and memory`() = runBlocking {
        val savedDraft = SettingsDraft(
            preferModMirror = false,
            preferMcMirror = true,
            maxMemoryText = "8192",
            proxyEnabled = true,
            proxySystem = false,
            proxyHost = "10.0.0.2",
            proxyPortText = "7890",
            proxyUsr = "user",
            proxyPwd = "password",
            solidWindow = true,
        )
        val viewModel = SettingsViewModel(
            FakeSettingsGateway(initialData = SettingsInitialData(savedDraft, totalMemoryMb = 32768))
        )

        val state = awaitInitialized(viewModel)

        assertEquals(savedDraft, state.draft)
        assertEquals(32768, state.totalMemoryMb)
        assertNull(state.errorMessage)
    }

    @Test
    fun `invalid settings are rejected before save`() = runBlocking {
        val gateway = FakeSettingsGateway()
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)
        viewModel.updateDraft(viewModel.uiState.value.draft.copy(maxMemoryText = "4096"))

        viewModel.save()

        assertEquals("最大内存必须大于4096MB", viewModel.uiState.value.errorMessage)
        assertTrue(gateway.savedDrafts.isEmpty())
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun `valid settings are saved and announced`() = runBlocking {
        val gateway = FakeSettingsGateway()
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)
        val draft = viewModel.uiState.value.draft.copy(
            maxMemoryText = "8192",
            proxyPortText = "10809",
            solidWindow = true,
        )
        viewModel.updateDraft(draft)

        viewModel.save()

        assertEquals(SettingsEvent.ShowSnackbar("设置已保存"), viewModel.events.first())
        assertEquals(listOf(draft), gateway.savedDrafts)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.saving)
    }

    @Test
    fun `node switching ignores duplicates and emits the result`() = runBlocking {
        val switchGate = CompletableDeferred<Result<NodeSwitchResult>>()
        val gateway = FakeSettingsGateway(switchGate = switchGate)
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)

        viewModel.switchNode(gameBackup = true)
        viewModel.uiState.filter { it.switchingNode }.first()
        gateway.awaitNodeSwitchCount(1)
        viewModel.switchNode(forceMain = true)

        assertEquals(1, gateway.nodeSwitches.size)
        assertEquals(NodeSwitchRequest(gameBackup = true, forceMain = false), gateway.nodeSwitches.single())
        switchGate.complete(Result.success(NodeSwitchResult("备用节点A")))
        assertEquals(SettingsEvent.ShowSnackbar("已临时切换到备用节点A"), viewModel.events.first())
        assertFalse(viewModel.uiState.value.switchingNode)
    }

    @Test
    fun `node switching tells players a running game stays in the world`() = runBlocking {
        val switchGate = CompletableDeferred(Result.success(NodeSwitchResult("备用节点A", movedRunningGame = true)))
        val viewModel = SettingsViewModel(FakeSettingsGateway(switchGate = switchGate))
        awaitInitialized(viewModel)

        viewModel.switchNode(gameBackup = true)

        assertEquals(SettingsEvent.ShowSnackbar("已切换到备用节点A，不用退出地图"), viewModel.events.first())
    }

    @Test
    fun `quota success is displayed and can be refreshed`() = runBlocking {
        val firstQuota = quota(remainingBytes = 700)
        val refreshedQuota = quota(remainingBytes = 500)
        val gateway = FakeSettingsGateway(quotaResults = ArrayDeque(listOf(Result.success(firstQuota), Result.success(refreshedQuota))))
        val viewModel = SettingsViewModel(gateway)

        assertEquals(firstQuota, awaitQuotaLoaded(viewModel).downloadQuota)

        viewModel.refreshDownloadQuota()

        assertEquals(refreshedQuota, awaitQuotaLoaded(viewModel, refreshedQuota).downloadQuota)
        assertEquals(2, gateway.quotaLoadCount)
        assertNull(viewModel.uiState.value.downloadQuotaError)
    }

    @Test
    fun `quota failure is visible and refresh can recover`() = runBlocking {
        val initialQuota = quota(remainingBytes = 800)
        val recoveredQuota = quota(remainingBytes = 400)
        val gateway = FakeSettingsGateway(
            quotaResults = ArrayDeque(
                listOf(
                    Result.success(initialQuota),
                    Result.failure(IllegalStateException("网络不可用")),
                    Result.success(recoveredQuota),
                )
            )
        )
        val viewModel = SettingsViewModel(gateway)
        awaitQuotaLoaded(viewModel, initialQuota)

        viewModel.refreshDownloadQuota()
        withTimeout(5_000) { gateway.awaitQuotaLoadCount(2) }
        val failed = withTimeout(5_000) {
            viewModel.uiState.filter {
                !it.downloadQuotaLoading && it.downloadQuotaError != null
            }.first()
        }
        assertEquals("网络不可用", failed.downloadQuotaError)
        assertEquals(initialQuota, failed.downloadQuota)

        viewModel.refreshDownloadQuota()

        val recovered = awaitQuotaLoaded(viewModel, recoveredQuota)
        assertEquals(recoveredQuota, recovered.downloadQuota)
        assertNull(recovered.downloadQuotaError)
    }

    @Test
    fun `download cache cleanup submits task and announces progress location`() = runBlocking {
        val gateway = FakeSettingsGateway()
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)

        viewModel.clearDownloadCache()
        viewModel.clearDownloadCache()
        assertEquals(2, gateway.clearCount)

        assertEquals(
            SettingsEvent.ShowSnackbar("清除下载缓存任务已提交，可在任务中查看进度"),
            viewModel.events.first()
        )
    }

    @Test
    fun `invalid pack check ignores duplicate starts and reports empty result`() = runBlocking {
        val checkGate = CompletableDeferred<Result<List<ModpackLocalDir>>>()
        val gateway = FakeSettingsGateway(invalidPackCheckGate = checkGate)
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)

        viewModel.checkInvalidModpacks()
        viewModel.uiState.filter { it.invalidModpacksChecking }.first()
        viewModel.checkInvalidModpacks()
        assertEquals(1, gateway.invalidPackCheckCount)

        checkGate.complete(Result.success(emptyList()))
        val completed = viewModel.uiState.filter {
            !it.invalidModpacksChecking && it.invalidModpackSuccessMessage != null
        }.first()
        assertEquals("没有无效整合包", completed.invalidModpackSuccessMessage)
    }

    @Test
    fun `invalid pack check failure clears busy state and exposes alert error`() = runBlocking {
        val gateway = FakeSettingsGateway(
            invalidPackResult = Result.failure(IllegalStateException("网络不可用")),
        )
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)

        viewModel.checkInvalidModpacks()
        val failed = viewModel.uiState.filter {
            !it.invalidModpacksChecking && it.invalidModpackErrorMessage != null
        }.first()
        assertEquals("检查无效整合包失败：网络不可用", failed.invalidModpackErrorMessage)
    }

    @Test
    fun `invalid packs can be dismissed or cleaned after confirmation`() = runBlocking {
        val packs = listOf(localPack("111111111111111111111111_one"), localPack("222222222222222222222222_two"))
        val gateway = FakeSettingsGateway(invalidPackResult = Result.success(packs))
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)

        viewModel.checkInvalidModpacks()
        val pending = viewModel.uiState.filter { it.pendingInvalidModpacks.size == 2 }.first()
        assertEquals(packs, pending.pendingInvalidModpacks)
        viewModel.dismissInvalidModpackConfirmation()
        assertTrue(viewModel.uiState.value.pendingInvalidModpacks.isEmpty())

        viewModel.checkInvalidModpacks()
        viewModel.uiState.filter { it.pendingInvalidModpacks.size == 2 }.first()
        viewModel.confirmInvalidModpackCleanup()
        viewModel.uiState.filter { it.invalidModpacksCleaning }.first()
        val completed = viewModel.uiState.filter {
            !it.invalidModpacksCleaning && it.invalidModpackSuccessMessage != null
        }.first()
        assertEquals("已清理2个无效整合包", completed.invalidModpackSuccessMessage)
        assertEquals(packs, gateway.deletedInvalidPacks)
    }

    @Test
    fun `invalid pack cleanup continues after individual failure`() = runBlocking {
        val packs = listOf(localPack("111111111111111111111111_one"), localPack("222222222222222222222222_two"))
        val gateway = FakeSettingsGateway(
            invalidPackResult = Result.success(packs),
            invalidPackDeleteResults = mapOf(
                packs[0].versionId to Result.failure(IllegalStateException("正在运行")),
            ),
        )
        val viewModel = SettingsViewModel(gateway)
        awaitInitialized(viewModel)

        viewModel.checkInvalidModpacks()
        viewModel.uiState.filter { it.pendingInvalidModpacks.size == 2 }.first()
        viewModel.confirmInvalidModpackCleanup()
        val failed = viewModel.uiState.filter {
            !it.invalidModpacksCleaning && it.invalidModpackErrorMessage != null
        }.first()
        assertTrue(failed.invalidModpackErrorMessage!!.contains("成功1个"))
        assertTrue(failed.invalidModpackErrorMessage!!.contains("失败1个"))
        assertTrue(failed.invalidModpackErrorMessage!!.contains(packs[0].versionId))
        assertEquals(packs, gateway.deletedInvalidPacks)
    }

    private suspend fun awaitInitialized(viewModel: SettingsViewModel): SettingsUiState =
        viewModel.uiState.filter { !it.loading }.first()

    private suspend fun awaitQuotaLoaded(
        viewModel: SettingsViewModel,
        expected: DownloadQuota.Vo? = null,
    ): SettingsUiState = viewModel.uiState.filter {
        !it.downloadQuotaLoading && it.downloadQuota != null && (expected == null || it.downloadQuota == expected)
    }.first()

    private class FakeSettingsGateway(
        private val initialData: SettingsInitialData = SettingsInitialData(
            draft = SettingsDraft(),
            totalMemoryMb = 16384,
        ),
        private val saveResult: Result<Unit> = Result.success(Unit),
        private val switchGate: CompletableDeferred<Result<NodeSwitchResult>>? = null,
        private val quotaResults: ArrayDeque<Result<DownloadQuota.Vo>> = ArrayDeque(
            listOf(Result.success(quota(remainingBytes = 800)))
        ),
        private val invalidPackResult: Result<List<ModpackLocalDir>> = Result.success(emptyList()),
        private val invalidPackCheckGate: CompletableDeferred<Result<List<ModpackLocalDir>>>? = null,
        private val invalidPackDeleteResults: Map<String, Result<Unit>> = emptyMap(),
    ) : SettingsGateway {
        val savedDrafts = mutableListOf<SettingsDraft>()
        val nodeSwitches = mutableListOf<NodeSwitchRequest>()
        private val nodeSwitchCount = kotlinx.coroutines.flow.MutableStateFlow(0)
        var quotaLoadCount = 0
        private val quotaLoads = kotlinx.coroutines.flow.MutableStateFlow(0)
        var clearCount = 0
        val invalidPackChecks = mutableListOf<Unit>()
        val deletedInvalidPacks = mutableListOf<ModpackLocalDir>()
        var invalidPackCheckCount = 0

        override suspend fun loadSettings(): Result<SettingsInitialData> = Result.success(initialData)

        override suspend fun saveSettings(draft: SettingsDraft): Result<Unit> {
            savedDrafts += draft
            return saveResult
        }

        override suspend fun switchNode(request: NodeSwitchRequest): Result<NodeSwitchResult> {
            nodeSwitches += request
            nodeSwitchCount.value = nodeSwitches.size
            return switchGate?.await() ?: Result.success(NodeSwitchResult("测试节点"))
        }

        suspend fun awaitNodeSwitchCount(expected: Int) {
            nodeSwitchCount.filter { it >= expected }.first()
        }

        override suspend fun loadDownloadQuota(): Result<DownloadQuota.Vo> {
            quotaLoadCount++
            quotaLoads.value = quotaLoadCount
            return quotaResults.removeFirstOrNull()
                ?: Result.failure(IllegalStateException("没有测试额度结果"))
        }

        override fun clearDownloadCache(): Result<String> {
            clearCount++
            return Result.success("run-$clearCount")
        }

        override suspend fun checkInvalidModpacks(): Result<List<ModpackLocalDir>> {
            invalidPackChecks += Unit
            invalidPackCheckCount++
            return invalidPackCheckGate?.await() ?: invalidPackResult
        }

        override suspend fun deleteInvalidModpack(packdir: ModpackLocalDir): Result<Unit> {
            deletedInvalidPacks += packdir
            return invalidPackDeleteResults[packdir.versionId] ?: Result.success(Unit)
        }

        suspend fun awaitQuotaLoadCount(expected: Int) {
            quotaLoads.filter { it >= expected }.first()
        }
    }

    private companion object {
        fun localPack(versionId: String) = ModpackLocalDir(File(versionId), versionId.substringAfter('_'), null, 0L)

        fun quota(remainingBytes: Long) = DownloadQuota.Vo(
            limitBytes = 1_000,
            usedBytes = 1_000 - remainingBytes,
            remainingBytes = remainingBytes,
            day = "2026-08-12",
        )
    }
}
