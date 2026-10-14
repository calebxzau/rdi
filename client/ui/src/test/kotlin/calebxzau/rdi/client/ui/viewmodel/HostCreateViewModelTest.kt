package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.Host2
import calebxzhou.rdi.common.model.Host2PackStatus
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.common.model.BaseWorld
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.PackSource
import calebxzhou.rdi.client.ui.screen.HostKind
import calebxzhou.rdi.model.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostCreateViewModelTest {
    @Test
    fun `batch game rule update replaces map and copies supplied values`() = runBlocking {
        val viewModel = createViewModel(FakeHostCreateGateway())
        awaitLoaded(viewModel)

        val supplied = mutableMapOf(
            "keepInventory" to "true",
            "doDaylightCycle" to "false",
        )
        viewModel.updateGameRules(supplied)
        supplied["keepInventory"] = "false"
        supplied["newRule"] = "unexpected"
        supplied.remove("doDaylightCycle")

        assertEquals(
            mapOf(
                "keepInventory" to "true",
                "doDaylightCycle" to "false",
            ),
            viewModel.uiState.value.gameRules,
        )

        viewModel.updateGameRules(mapOf("keepInventory" to "false"))
        assertEquals(mapOf("keepInventory" to "false"), viewModel.uiState.value.gameRules)
    }

    @Test
    fun `create mode uses route source and does not load candidate packs`() = runBlocking {
        val gateway = FakeHostCreateGateway()
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "2.0.0",
            displayName = "测试整合包",
        )

        val state = awaitLoaded(viewModel)

        assertFalse(state.isEditMode)
        assertEquals("测试整合包", state.selectedPackTitle)
        assertEquals("2.0.0", state.selectedVersionName)
        assertEquals(1, gateway.loadInitialCalls)
        assertEquals("创建新房间", state.title)
    }

    @Test
    fun `create submission preserves exact legacy options`() = runBlocking {
        val gateway = FakeHostCreateGateway(submissionResult = Result.success(Unit))
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "2.0.0",
            displayName = "测试整合包",
        )
        awaitLoaded(viewModel)

        viewModel.updateHostName("  新房间  ")
        viewModel.updateIntro("  和朋友一起玩  ")
        viewModel.updateWhitelist(false)
        viewModel.submit()

        val event = assertIs<HostCreateEvent.LegacyCreateSubmitted>(viewModel.events.first())
        assertTrue(event.message.contains("创建中"))
        val create = assertIs<HostCreateSubmission.CreateLegacy>(gateway.submissions.single()).dto
        assertEquals(ObjectId(PACK_ID), create.modpackId)
        assertEquals("2.0.0", create.packVer)
        assertFalse(create.whitelist)
    }

    @Test
    fun `version baseworld is selected automatically and required terrain cannot change`() = runBlocking {
        val worldId = UUID.randomUUID()
        val gateway = FakeHostCreateGateway(
            binding = Modpack.Version.BaseWorldBinding(worldId, required = true),
            baseWorlds = listOf(testBaseWorld(worldId)),
            submissionResult = Result.success(Unit),
        )
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "2.0.0",
        )
        awaitTemplateLoaded(viewModel)

        assertEquals(worldId, viewModel.uiState.value.selectedBaseWorldId)
        viewModel.selectLevelChoice(1)
        assertEquals(HostCreateWorldSource.Template, viewModel.uiState.value.worldSource)
        viewModel.updateHostName("强制模板房间")
        viewModel.submit()

        val create = assertIs<HostCreateSubmission.CreateLegacy>(gateway.submissions.single()).dto
        assertEquals(worldId, create.baseWorldId)
    }

    @Test
    fun `optional version baseworld keeps a confirmed override after refresh`() = runBlocking {
        val configuredId = UUID.randomUUID()
        val overrideId = UUID.randomUUID()
        val gateway = FakeHostCreateGateway(
            binding = Modpack.Version.BaseWorldBinding(configuredId),
            baseWorlds = listOf(testBaseWorld(configuredId), testBaseWorld(overrideId, "替换模板")),
        )
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "2.0.0",
        )
        awaitTemplateLoaded(viewModel)
        viewModel.commitBaseWorldSelection(overrideId)
        viewModel.refreshBaseWorlds()
        withTimeout(5_000) { viewModel.uiState.filter { !it.baseWorldsLoading }.first() }

        assertEquals(overrideId, viewModel.uiState.value.selectedBaseWorldId)
    }

    @Test
    fun `required unavailable version baseworld blocks submission`() = runBlocking {
        val gateway = FakeHostCreateGateway(
            binding = Modpack.Version.BaseWorldBinding(UUID.randomUUID(), required = true),
            baseWorlds = emptyList(),
        )
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "2.0.0",
        )
        awaitTemplateLoaded(viewModel)
        viewModel.updateHostName("缺失模板房间")
        viewModel.submit()

        assertTrue(viewModel.uiState.value.statusMessage?.contains("地图模板") == true)
        assertTrue(gateway.submissions.isEmpty())
    }

    @Test
    fun `legacy 1 20 create uses modern skyblock terrain`() = runBlocking {
        val gateway = FakeHostCreateGateway(submissionResult = Result.success(Unit))
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "1.20.1",
            legacyMcVersion = McVersion.V201.name,
        )
        awaitLoaded(viewModel)

        viewModel.updateHostName("1.20房间")
        viewModel.selectLevelChoice(2)
        viewModel.submit()

        assertIs<HostCreateEvent.LegacyCreateSubmitted>(viewModel.events.first())
        val create = assertIs<HostCreateSubmission.CreateLegacy>(gateway.submissions.single()).dto
        assertEquals("skyblockbuilder:skyblock", create.levelType)
    }

    /* @Test
    fun `create submission preserves exact modpack2 version source`() = runBlocking {
        val versionId = "019c9c18-778d-7000-8000-000000000003"
        val gateway = FakeHostCreateGateway(submissionResult = Result.success(testHost2Detail()))
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Host2,
            sourceId = versionId,
            displayName = "Modpack2整合包",
        )
        awaitLoaded(viewModel)

        viewModel.updateHostName("Modpack2房间")
        viewModel.submit()

        assertIs<HostCreateEvent.Host2Created>(viewModel.events.first())
        val create = assertIs<HostCreateSubmission.CreateHost2>(gateway.submissions.single()).dto
        assertEquals(PackSource.Modpack2(UUID.fromString(versionId)), create.packSource)
    }

    */

    @Test
    fun `missing source cannot submit and explains the entry point`() = runBlocking {
        val gateway = FakeHostCreateGateway()
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)

        viewModel.updateHostName("新房间")
        viewModel.submit()

        assertEquals("请从“我的整合包”的菜单发起创建多人房间", viewModel.uiState.value.statusMessage)
        assertTrue(gateway.submissions.isEmpty())
    }

    /* @Test
    fun `invalid host2 source cannot submit`() = runBlocking {
        val gateway = FakeHostCreateGateway()
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Host2,
            sourceId = "not-a-uuid",
        )
        awaitLoaded(viewModel)

        viewModel.updateHostName("新房间")
        viewModel.submit()

        assertTrue(viewModel.uiState.value.statusMessage?.contains("来源无效") == true)
        assertTrue(gateway.submissions.isEmpty())
    } */

    @Test
    fun `edit mode loads host and submits only host options`() = runBlocking {
        val gateway = FakeHostCreateGateway(
            initial = HostCreateInitialData(host = testHostDetail(ObjectId(WORLD_ID))),
        )
        val viewModel = createViewModel(gateway, hostId = HOST_ID)
        val state = awaitLoaded(viewModel)

        assertTrue(state.isEditMode)
        assertEquals("编辑中的房间", state.hostName)
        assertEquals("测试整合包", state.selectedPackTitle)
        assertEquals("latest", state.selectedVersionName)

        viewModel.updateHostName("  修改后的名称 ")
        viewModel.updateWhitelist(true)
        viewModel.submit()

        assertIs<HostCreateEvent.EditSaved>(viewModel.events.first())
        val edit = assertIs<HostCreateSubmission.EditLegacy>(gateway.submissions.single())
        assertEquals(ObjectId(HOST_ID), edit.hostId)
        assertEquals("修改后的名称", edit.options.name)
        assertEquals(true, edit.options.whitelist)
        assertNull(edit.options.modpackId)
        assertNull(edit.options.packVer)
    }

    @Test
    fun `duplicate submit is ignored while submission is running`() = runBlocking {
        val gate = CompletableDeferred<Result<Unit>>()
        val gateway = FakeHostCreateGateway(submissionGate = gate)
        val viewModel = createViewModel(
            gateway,
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "2.0.0",
        )
        awaitLoaded(viewModel)

        viewModel.updateHostName("新房间")
        viewModel.submit()
        gateway.submissionStarted.await()
        viewModel.submit()

        assertEquals(1, gateway.submissions.size)
        gate.complete(Result.success(Unit))
        assertIs<HostCreateEvent.LegacyCreateSubmitted>(viewModel.events.first())
    }

    @Test
    fun `status message can be cleared`() = runBlocking {
        val viewModel = createViewModel(FakeHostCreateGateway())
        awaitLoaded(viewModel)

        viewModel.submit()

        assertEquals("请输入房间名称", viewModel.uiState.value.statusMessage)
        viewModel.clearStatusMessage()
        assertNull(viewModel.uiState.value.statusMessage)
    }

    private fun createViewModel(
        gateway: HostCreateGateway,
        hostId: String? = null,
        kind: HostKind = HostKind.Legacy,
        sourceId: String? = null,
        legacyVersionName: String? = null,
        displayName: String? = null,
        legacyMcVersion: String? = null,
    ) = HostCreateViewModel(
        args = HostCreateViewModelArgs(
            hostId = hostId,
            defaultHostName = "默认房间名",
            kind = kind,
            sourceId = sourceId,
            legacyVersionName = legacyVersionName,
            displayName = displayName,
            legacyMcVersion = legacyMcVersion,
        ),
        gateway = gateway,
    )

    private suspend fun awaitLoaded(viewModel: HostCreateViewModel): HostCreateUiState =
        withTimeout(5_000) { viewModel.uiState.filter { !it.loading }.first() }

    private suspend fun awaitTemplateLoaded(viewModel: HostCreateViewModel): HostCreateUiState =
        withTimeout(5_000) { viewModel.uiState.filter { !it.loading && !it.baseWorldsLoading }.first() }

    private class FakeHostCreateGateway(
        private val initial: HostCreateInitialData = HostCreateInitialData(),
        private val submissionResult: Result<Unit> = Result.success(Unit),
        private val submissionGate: CompletableDeferred<Result<Unit>>? = null,
        private val binding: Modpack.Version.BaseWorldBinding? = null,
        private val baseWorlds: List<BaseWorld> = emptyList(),
    ) : HostCreateGateway {
        var loadInitialCalls = 0
        val submissions = mutableListOf<HostCreateSubmission>()
        val submissionStarted = CompletableDeferred<Unit>()

        override suspend fun loadInitial(hostId: ObjectId?): Result<HostCreateInitialData> {
            loadInitialCalls++
            return Result.success(initial)
        }

        override suspend fun loadVersionBaseWorld(
            modpackId: String,
            versionName: String,
        ): Result<Modpack.Version.BaseWorldBinding?> = Result.success(binding)

        override suspend fun loadBaseWorlds(): Result<List<BaseWorld>> = Result.success(baseWorlds)

        override suspend fun createOrUpdate(submission: HostCreateSubmission): Result<Unit> {
            submissions += submission
            submissionStarted.complete(Unit)
            return submissionGate?.await() ?: submissionResult
        }

        override suspend fun requestHostPackUpdate(hostId: ObjectId): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        const val HOST_ID = "66a000000000000000000001"
        const val PACK_ID = "66a000000000000000000002"
        const val WORLD_ID = "66a000000000000000000003"
        val OWNER_ID = ObjectId("66a000000000000000000004")

        fun testBaseWorld(id: UUID, name: String = "默认模板") = BaseWorld(
            id = id,
            ownerId = UUID.randomUUID(),
            name = name,
            levelType = "minecraft:normal",
            generatorSettings = null,
            size = 1L,
        )

        /* fun testHost2Detail() = Host2.DetailVo(
            id = UUID.fromString("019c9c18-778d-7000-8000-000000000003"),
            name = "新房间",
            ownerId = UUID.fromString("019c9c18-778d-7000-8000-000000000004"),
            packSource = PackSource.Modpack2(UUID.fromString("019c9c18-778d-7000-0000-000000000003")),
            packStatus = Host2PackStatus.Busy,
            port = 25565,
            whitelist = true,
            status = HostStatus.STOPPED,
            role = Role.OWNER,
        ) */

        fun testHostDetail(worldId: ObjectId? = null) = Host.DetailVo(
            _id = ObjectId(HOST_ID),
            name = "编辑中的房间",
            ownerId = OWNER_ID,
            modpack = calebxzhou.rdi.common.model.Modpack.BriefVo(
                id = ObjectId(PACK_ID),
                name = "测试整合包",
                mcVer = McVersion.V201,
                modloader = ModLoader.forge,
            ),
            packVer = "latest",
            version = 1,
            worldId = worldId,
            port = 25565,
            difficulty = 1,
            gameMode = 1,
            levelType = "minecraft:flat",
            gameRules = mutableMapOf("keepInventory" to "true"),
            whitelist = false,
            allowCheats = true,
        )
    }
}
