package calebxzau.rdi.client.ui.viewmodel

import calebxzau.rdi.common.model.BaseWorld
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.client.ui.screen.HostKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BaseWorldHostCreateViewModelTest {
    @Test
    fun `generate is default and does not load templates`() = runBlocking {
        val gateway = FakeGateway()
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)

        viewModel.updateHostName("新房间")
        addTestFriend(viewModel)
        viewModel.submit()
        awaitSubmission(gateway)

        val dto = (gateway.submission as HostCreateSubmission.CreateLegacy).dto
        assertEquals(HostCreateWorldSource.Generate, viewModel.uiState.value.worldSource)
        assertNull(dto.baseWorldId)
        assertEquals(0, gateway.baseWorldLoadCalls)
    }

    @Test
    fun `template selection submits id and template terrain`() = runBlocking {
        val gateway = FakeGateway()
        val world = testWorld(levelType = "minecraft:flat")
        gateway.baseWorldResult = Result.success(listOf(world))
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)

        viewModel.openBaseWorldSelection()
        awaitBaseWorlds(viewModel)
        viewModel.commitBaseWorldSelection(world.id)
        viewModel.updateHostName("模板房间")
        addTestFriend(viewModel)
        viewModel.submit()
        awaitSubmission(gateway)

        val dto = (gateway.submission as HostCreateSubmission.CreateLegacy).dto
        assertEquals(world.id, dto.baseWorldId)
        assertEquals(world.levelType, dto.levelType)
    }

    @Test
    fun `canceling first template selection keeps generated source`() = runBlocking {
        val gateway = FakeGateway().apply { baseWorldResult = Result.success(listOf(world)) }
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.selectLevelChoice(1)
        viewModel.openBaseWorldSelection()
        awaitBaseWorlds(viewModel)
        viewModel.cancelBaseWorldSelection()

        assertEquals(HostCreateWorldSource.Generate, viewModel.uiState.value.worldSource)
        assertNull(viewModel.uiState.value.selectedBaseWorldId)
        assertEquals("minecraft:flat", viewModel.uiState.value.levelType)
    }

    @Test
    fun `custom terrain choice clears committed template before dialog confirmation`() = runBlocking {
        val gateway = FakeGateway().apply { baseWorldResult = Result.success(listOf(world)) }
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.updateCustomLevelTypeText("example:custom")
        assertTrue(viewModel.applyCustomLevelType())
        viewModel.openBaseWorldSelection()
        awaitBaseWorlds(viewModel)
        viewModel.commitBaseWorldSelection(gateway.world.id)

        viewModel.selectLevelChoice(3)
        assertEquals(HostCreateWorldSource.Generate, viewModel.uiState.value.worldSource)
        assertNull(viewModel.uiState.value.selectedBaseWorldId)
        assertEquals(3, viewModel.uiState.value.levelChoice)

        viewModel.cancelCustomLevelType()
        viewModel.updateHostName("自定义房间")
        addTestFriend(viewModel)
        viewModel.submit()
        awaitSubmission(gateway)
        assertNull((gateway.submission as HostCreateSubmission.CreateLegacy).dto.baseWorldId)
        assertEquals("example:custom", (gateway.submission as HostCreateSubmission.CreateLegacy).dto.levelType)
    }

    @Test
    fun `switching back to generate restores terrain and clears template id`() = runBlocking {
        val gateway = FakeGateway()
        gateway.baseWorldResult = Result.success(listOf(gateway.world))
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.selectLevelChoice(1)
        viewModel.openBaseWorldSelection()
        awaitBaseWorlds(viewModel)
        viewModel.commitBaseWorldSelection(gateway.world.id)
        viewModel.selectLevelChoice(1)

        assertEquals("minecraft:flat", viewModel.uiState.value.levelType)
        assertNull(viewModel.uiState.value.selectedBaseWorldId)
        viewModel.updateHostName("普通房间")
        addTestFriend(viewModel)
        viewModel.submit()
        awaitSubmission(gateway)
        assertNull((gateway.submission as HostCreateSubmission.CreateLegacy).dto.baseWorldId)
        assertEquals("minecraft:flat", (gateway.submission as HostCreateSubmission.CreateLegacy).dto.levelType)
    }

    @Test
    fun `opening selector without commit keeps generated submission`() = runBlocking {
        val gateway = FakeGateway()
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.openBaseWorldSelection()
        awaitBaseWorlds(viewModel)
        viewModel.updateHostName("模板房间")
        addTestFriend(viewModel)
        viewModel.submit()

        awaitSubmission(gateway)
        assertEquals(HostCreateWorldSource.Generate, viewModel.uiState.value.worldSource)
        assertNull((gateway.submission as HostCreateSubmission.CreateLegacy).dto.baseWorldId)
    }

    @Test
    fun `failed template load is surfaced and retry can succeed`() = runBlocking {
        val gateway = FakeGateway()
        gateway.baseWorldResult = Result.failure(IllegalStateException("加载失败"))
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.openBaseWorldSelection()
        withTimeout(5_000) { viewModel.uiState.filter { it.baseWorldsErrorMessage != null }.first() }
        assertEquals("加载失败", viewModel.uiState.value.baseWorldsErrorMessage)

        gateway.baseWorldResult = Result.success(listOf(gateway.world))
        viewModel.refreshBaseWorlds()
        awaitBaseWorlds(viewModel)
        assertNull(viewModel.uiState.value.baseWorldsErrorMessage)
        assertEquals(listOf(gateway.world), viewModel.uiState.value.baseWorlds)
    }

    @Test
    fun `stale template response cannot overwrite a newer source`() = runBlocking {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Result<List<BaseWorld>>>()
        gateway.baseWorldGate = gate
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.openBaseWorldSelection()
        withTimeout(5_000) { viewModel.uiState.filter { it.baseWorldsLoading }.first() }
        viewModel.cancelBaseWorldSelection()
        gate.complete(Result.success(listOf(gateway.world)))

        assertEquals(HostCreateWorldSource.Generate, viewModel.uiState.value.worldSource)
        assertFalse(viewModel.uiState.value.baseWorldsLoading)
        assertTrue(viewModel.uiState.value.baseWorlds.isEmpty())
    }

    @Test
    fun `session identity change clears old selection and invalidates response`() = runBlocking {
        val gateway = FakeGateway()
        val gate = CompletableDeferred<Result<List<BaseWorld>>>()
        gateway.baseWorldGate = gate
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.updateSessionIdentity("account-1:server-1")
        viewModel.openBaseWorldSelection()
        withTimeout(5_000) { viewModel.uiState.filter { it.baseWorldsLoading }.first() }
        viewModel.updateSessionIdentity("account-2:server-2")
        assertNull(viewModel.uiState.value.selectedBaseWorldId)
        assertTrue(viewModel.uiState.value.baseWorlds.isEmpty())
        assertFalse(viewModel.uiState.value.baseWorldSelectionOpen)
        gateway.baseWorldGate = CompletableDeferred()
        gate.complete(Result.success(listOf(gateway.world)))
        assertTrue(viewModel.uiState.value.baseWorlds.isEmpty())
    }

    @Test
    fun `refresh removes a selected world that was deleted`() = runBlocking {
        val gateway = FakeGateway()
        gateway.baseWorldResult = Result.success(listOf(gateway.world))
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        viewModel.openBaseWorldSelection()
        awaitBaseWorlds(viewModel)
        viewModel.commitBaseWorldSelection(gateway.world.id)
        gateway.baseWorldResult = Result.success(emptyList())
        viewModel.refreshBaseWorlds()
        awaitBaseWorlds(viewModel)

        assertNull(viewModel.uiState.value.selectedBaseWorldId)
        assertEquals(HostCreateWorldSource.Generate, viewModel.uiState.value.worldSource)
    }

    @Test
    fun `unavailable optional configured template blocks until player overrides it`() = runBlocking {
        val gateway = FakeGateway().apply {
            configuredBinding = Modpack.Version.BaseWorldBinding(UUID.randomUUID(), required = false)
            baseWorldResult = Result.success(emptyList())
        }
        val viewModel = createViewModel(gateway)
        awaitLoaded(viewModel)
        withTimeout(5_000) {
            viewModel.uiState.filter { it.baseWorldBindingErrorMessage != null }.first()
        }

        assertEquals(HostCreateWorldSource.Template, viewModel.uiState.value.worldSource)
        assertNull(viewModel.uiState.value.selectedBaseWorldId)
        assertEquals("此版本推荐的地图模板不可用，请重新选择地图模板", viewModel.uiState.value.baseWorldBindingErrorMessage)

        viewModel.updateHostName("模板房间")
        addTestFriend(viewModel)
        viewModel.submit()
        assertNull(gateway.submission)
        assertEquals(viewModel.uiState.value.baseWorldBindingErrorMessage, viewModel.uiState.value.statusMessage)

        viewModel.selectLevelChoice(1)
        assertEquals(HostCreateWorldSource.Generate, viewModel.uiState.value.worldSource)
        assertNull(viewModel.uiState.value.baseWorldBindingErrorMessage)
        addTestFriend(viewModel)
        viewModel.submit()
        awaitSubmission(gateway)
        assertNull((gateway.submission as HostCreateSubmission.CreateLegacy).dto.baseWorldId)
    }

    @Test
    fun `edit mode never loads template choices`() = runBlocking {
        val gateway = FakeGateway(initial = HostCreateInitialData(host = testHost()))
        val viewModel = HostCreateViewModel(
            HostCreateViewModelArgs(
                hostId = HOST_ID,
                defaultHostName = "默认房间",
                kind = HostKind.Legacy,
            ),
            gateway,
        )
        awaitLoaded(viewModel)
        viewModel.openBaseWorldSelection()

        assertTrue(viewModel.uiState.value.isEditMode)
        assertEquals(0, gateway.baseWorldLoadCalls)
    }

    private fun createViewModel(gateway: FakeGateway) = HostCreateViewModel(
        HostCreateViewModelArgs(
            hostId = null,
            defaultHostName = "默认房间",
            kind = HostKind.Legacy,
            sourceId = PACK_ID,
            legacyVersionName = "latest",
        ),
        gateway,
    )

    private suspend fun addTestFriend(viewModel: HostCreateViewModel) {
        if (!viewModel.uiState.value.isLegacyCreate || viewModel.uiState.value.initialMembers.isNotEmpty()) return
        viewModel.openFriendDialog()
        viewModel.updateFriendQq("12345678")
        viewModel.addFriend()
        withTimeout(5_000) { viewModel.uiState.filter { !it.friendLookupLoading }.first() }
    }

    private suspend fun awaitLoaded(viewModel: HostCreateViewModel) {
        withTimeout(5_000) { viewModel.uiState.filter { !it.loading }.first() }
    }

    private suspend fun awaitBaseWorlds(viewModel: HostCreateViewModel) {
        withTimeout(5_000) {
            viewModel.uiState.filter { !it.baseWorldsLoading && it.baseWorldsErrorMessage == null }.first()
        }
    }

    private suspend fun awaitSubmission(gateway: FakeGateway) {
        withTimeout(5_000) { gateway.submissionReady.await() }
    }

    private class FakeGateway(
        private val initial: HostCreateInitialData = HostCreateInitialData(),
    ) : HostCreateGateway {
        val world = testWorld()
        var configuredBinding: Modpack.Version.BaseWorldBinding? = null
        var baseWorldResult: Result<List<BaseWorld>> = Result.success(emptyList())
        var baseWorldGate: CompletableDeferred<Result<List<BaseWorld>>>? = null
        var baseWorldLoadCalls = 0
        var submission: HostCreateSubmission? = null
        val submissionReady = CompletableDeferred<Unit>()

        override suspend fun findFriend(qq: String): Result<RAccount.Dto> = Result.success(
            RAccount.Dto(ObjectId("66a000000000000000000005"), "好友", RAccount.Cloth()),
        )

        override suspend fun loadInitial(hostId: ObjectId?): Result<HostCreateInitialData> =
            Result.success(initial)

        override suspend fun loadBaseWorlds(): Result<List<BaseWorld>> {
            baseWorldLoadCalls++
            return baseWorldGate?.await() ?: baseWorldResult
        }

        override suspend fun loadVersionBaseWorld(
            modpackId: String,
            versionName: String,
        ): Result<Modpack.Version.BaseWorldBinding?> = Result.success(configuredBinding)

        override suspend fun createOrUpdate(submission: HostCreateSubmission): Result<Unit> {
            this.submission = submission
            submissionReady.complete(Unit)
            return Result.success(Unit)
        }

        override suspend fun requestHostPackUpdate(hostId: ObjectId): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        const val HOST_ID = "66a000000000000000000001"
        const val PACK_ID = "66a000000000000000000002"

        fun testWorld(levelType: String = "minecraft:normal") = BaseWorld(
            id = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            name = "测试模板",
            levelType = levelType,
            generatorSettings = null,
            size = 1024,
        )

        fun testHost() = Host.DetailVo(
            _id = ObjectId(HOST_ID),
            name = "编辑房间",
            ownerId = ObjectId("66a000000000000000000004"),
            modpack = calebxzhou.rdi.common.model.Modpack.BriefVo(
                id = ObjectId(PACK_ID),
                name = "整合包",
                mcVer = calebxzhou.rdi.common.model.McVersion.V201,
                modloader = calebxzhou.rdi.common.model.ModLoader.forge,
            ),
            packVer = "latest",
            version = 1,
            worldId = null,
            port = 25565,
            difficulty = 1,
            gameMode = 0,
            levelType = "minecraft:normal",
            gameRules = mutableMapOf(),
            whitelist = true,
            allowCheats = false,
        )
    }
}
