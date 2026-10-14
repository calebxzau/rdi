package calebxzau.rdi.client.ui.viewmodel

import calebxzau.rdi.common.model.HostWorldDownloadStatusVo
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.Task2Status
import calebxzhou.rdi.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostWorldViewModelTest {
    private val owner = ObjectId()
    private val member = ObjectId()
    private val guest = ObjectId()
    private val temp: Path = Files.createTempDirectory("host-world-vm")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        temp.toFile().deleteRecursively()
    }

    @Test
    fun ownerOfA1201ForgeHostGetsEverySaveAction() {
        val state = viewModel(FakeGateway(host())).uiState.value
        assertTrue(state.canImportSave)
        assertTrue(state.canRollback)
        assertTrue(state.canResetWorld)
        assertTrue(state.canDownload)
    }

    @Test
    fun membersOnlyDownloadTheSaveAndGuestsGetNothing() {
        val memberState = viewModel(FakeGateway(host()), self = member).uiState.value
        assertFalse(memberState.canImportSave)
        assertFalse(memberState.canRollback)
        assertFalse(memberState.canResetWorld)
        assertTrue(memberState.canDownload)
        assertEquals(2, memberState.download?.remaining)

        val gateway = FakeGateway(host())
        val guestState = viewModel(gateway, self = guest).uiState.value
        assertFalse(guestState.canDownload)
        assertNull(guestState.download, "guests do not ask for the download status")
        assertEquals(0, gateway.statusCalls)
    }

    @Test
    fun rollbackNeeds1201ForgeAndResetAndDownloadNeedANewHost() {
        val neoForge = viewModel(FakeGateway(host(mcVer = McVersion.V211, loader = ModLoader.neoforge))).uiState.value
        assertFalse(neoForge.canRollback)
        assertTrue(neoForge.canResetWorld)
        assertTrue(neoForge.canDownload)

        val oldHost = viewModel(FakeGateway(host(version = 1))).uiState.value
        assertTrue(oldHost.canRollback)
        assertFalse(oldHost.canResetWorld)
        assertFalse(oldHost.canDownload)
    }

    @Test
    fun resetSuccessShowsANotice() {
        val gateway = FakeGateway(host())
        val vm = viewModel(gateway)
        vm.resetWorld()
        assertEquals(1, gateway.resets)
        assertEquals("存档已重置，下次启动房间时会生成新存档", vm.uiState.value.notice)
        assertNull(vm.uiState.value.error)
        assertNull(vm.uiState.value.busy)
    }

    @Test
    fun resetFailureIsShownAndOnlyOwnersCanReset() {
        val failing = FakeGateway(host(), resetError = RequestError("房间正在运行"))
        val vm = viewModel(failing)
        vm.resetWorld()
        assertEquals("房间正在运行", vm.uiState.value.error)
        assertNull(vm.uiState.value.notice)

        val memberGateway = FakeGateway(host())
        val memberVm = viewModel(memberGateway, self = member)
        memberVm.resetWorld()
        assertEquals(0, memberGateway.resets)
        assertEquals("只有房主可以重置存档", memberVm.uiState.value.error)
    }

    @Test
    fun downloadStartsABackgroundTaskAndRefreshesWhenItEnds(): Unit = runBlocking {
        val gateway = FakeGateway(host(), saves = temp.resolve("saves"))
        val vm = viewModel(gateway, self = member)

        vm.downloadSave()

        val state = vm.uiState.value
        assertNull(state.error)
        assertEquals(listOf(Triple("host", "房间", temp.resolve("saves"))), gateway.submitted)
        assertEquals("run-1", state.downloadRunId)
        assertEquals(temp.resolve("saves"), state.savesDir)
        assertEquals("已开始下载，可以在任务列表查看进度", state.notice)
        assertEquals(2, gateway.statusCalls, "checked once more before starting")

        gateway.task.value = Task2Status.DONE
        withTimeout(10_000) { vm.uiState.first { it.notice == "存档已下载到整合包的单人存档里" } }
        assertEquals(3, gateway.statusCalls, "the day's downloads are reloaded after the task")
    }

    @Test
    fun failedTaskPointsToTheTaskList(): Unit = runBlocking {
        val gateway = FakeGateway(host(), saves = temp.resolve("saves"))
        val vm = viewModel(gateway, self = member)
        vm.downloadSave()
        gateway.task.value = Task2Status.FAILED
        withTimeout(10_000) { vm.uiState.first { it.error == "存档下载失败，可以在任务列表查看原因" } }
    }

    @Test
    fun downloadNeedsTheModpackInstalledAndAllowedDownloads(): Unit = runBlocking {
        val notInstalled = FakeGateway(host(), saves = null)
        val vm = viewModel(notInstalled, self = member)
        vm.downloadSave()
        assertEquals("请先进入房间游玩一次，安装好房间整合包后再下载存档", vm.uiState.value.error)
        assertEquals(emptyList(), notInstalled.submitted)

        val usedUp = FakeGateway(host(), saves = temp.resolve("saves"), remaining = 0)
        val usedUpVm = viewModel(usedUp, self = member)
        usedUpVm.downloadSave()
        assertEquals("今天的3次下载已经用完，明天再来", usedUpVm.uiState.value.error)
        assertEquals(emptyList(), usedUp.submitted)

        val blocked = FakeGateway(host(), saves = temp.resolve("saves"), blockedReason = "请先停止房间再下载存档")
        val blockedVm = viewModel(blocked, self = member)
        blockedVm.downloadSave()
        assertEquals("请先停止房间再下载存档", blockedVm.uiState.value.error)
        assertEquals(emptyList(), blocked.submitted)
    }

    @Test
    fun reopeningTheScreenFindsTheRunningDownload() {
        val gateway = FakeGateway(host(), running = "run-7")
        val vm = viewModel(gateway, self = member)
        assertEquals("run-7", vm.uiState.value.downloadRunId)
    }

    private fun viewModel(gateway: FakeGateway, self: ObjectId = owner) = HostWorldViewModel("host", gateway) { self }

    private fun host(mcVer: McVersion = McVersion.V201, loader: ModLoader = ModLoader.forge, version: Int = 2) = Host.DetailVo(
        _id = ObjectId(),
        name = "房间",
        ownerId = owner,
        modpack = Modpack.BriefVo(id = ObjectId(), name = "整合包", mcVer = mcVer, modloader = loader),
        packVer = "latest",
        version = version,
        worldId = null,
        port = 25565,
        difficulty = 1,
        gameMode = 0,
        levelType = "minecraft:normal",
        gameRules = mutableMapOf(),
        whitelist = false,
        allowCheats = false,
        members = listOf(Host.Member(owner, Role.OWNER), Host.Member(member, Role.MEMBER), Host.Member(guest, Role.GUEST)),
    )

    private inner class FakeGateway(
        private val host: Host.DetailVo,
        private val resetError: Exception? = null,
        private val saves: Path? = null,
        private val remaining: Int = 2,
        private val blockedReason: String? = null,
        private val running: String? = null,
    ) : HostWorldGateway {
        var resets = 0
        var statusCalls = 0
        val submitted = mutableListOf<Triple<String, String, Path>>()
        val task = MutableStateFlow<Task2Status?>(Task2Status.RUNNING)

        override suspend fun hostDetail(hostId: String): Host.DetailVo = host

        override suspend fun resetWorld(hostId: String) {
            resetError?.let { throw it }
            resets++
        }

        override suspend fun downloadStatus(hostId: String): HostWorldDownloadStatusVo {
            statusCalls++
            return HostWorldDownloadStatusVo(limit = 3, remaining = remaining, today = emptyList(), reusable = false, blockedReason = blockedReason)
        }

        override suspend fun savesDir(host: Host.DetailVo): Path =
            saves ?: throw RequestError("请先进入房间游玩一次，安装好房间整合包后再下载存档")

        override fun submitDownload(hostId: String, hostName: String, savesDir: Path): String {
            submitted += Triple(hostId, hostName, savesDir)
            return "run-1"
        }

        override fun runningDownload(hostId: String): String? = running

        override fun taskStatus(runId: String): Flow<Task2Status?> = task
    }
}
