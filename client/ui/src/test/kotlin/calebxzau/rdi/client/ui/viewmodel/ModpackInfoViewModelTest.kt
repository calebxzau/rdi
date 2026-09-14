package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModpackInfoViewModelTest {
    @Test
    fun `invalid edit is rejected before reaching gateway`() = runBlocking {
        val gateway = FakeModpackInfoGateway(detail = testPack())
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.beginEdit()
        viewModel.updateEditDraft(viewModel.uiState.value.editDraft.copy(iconUrl = "not-a-url"))
        viewModel.saveEdit()

        val state = viewModel.uiState.filter { it.errorMessage != null && !it.savingEdit }.first()
        assertFalse(state.savingEdit)
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun `invalid name is rejected before reaching gateway`() = runBlocking {
        val gateway = FakeModpackInfoGateway(detail = testPack())
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.beginEdit()
        viewModel.updateEditDraft(viewModel.uiState.value.editDraft.copy(name = "!"))
        viewModel.saveEdit()

        viewModel.uiState.filter { it.errorMessage != null && !it.savingEdit }.first()
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun `outer whitespace in name is rejected before reaching gateway`() = runBlocking {
        val gateway = FakeModpackInfoGateway(detail = testPack())
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.beginEdit()
        viewModel.updateEditDraft(viewModel.uiState.value.editDraft.copy(name = " New Name"))
        viewModel.saveEdit()

        val state = viewModel.uiState.value
        assertTrue(state.errorMessage!!.contains("整合包名称"))
        assertTrue(gateway.mutations.isEmpty())
    }

    @Test
    fun `saving edit updates metadata and reports completion`() = runBlocking {
        val gateway = FakeModpackInfoGateway(detail = testPack())
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.beginEdit()
        viewModel.updateEditDraft(viewModel.uiState.value.editDraft.copy(name = "新 名称"))
        viewModel.saveEdit()

        val event = viewModel.events.first()
        val mutation = assertIs<ModpackInfoMutation.UpdateOptions>(gateway.mutations.single())
        assertEquals("新 名称", mutation.options.name)
        assertIs<ModpackInfoEvent.EditSaved>(event)
        viewModel.uiState.filter { !it.loading && gateway.loadCount >= 2 }.first()
    }

    @Test
    fun `deleting pack emits navigation event`() = runBlocking {
        val gateway = FakeModpackInfoGateway(detail = testPack())
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.deletePack()

        assertIs<ModpackInfoEvent.PackDeleted>(viewModel.events.first())
        assertEquals(ModpackInfoMutation.DeletePack, gateway.mutations.single())
    }

    @Test
    fun `installed version requests redownload confirmation for selected version`() = runBlocking {
        val selected = testVersion("1.0")
        val other = testVersion("2.0")
        val gateway = FakeModpackInfoGateway(testPack(listOf(other, selected)), installed = true)
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.requestDownload(selected)

        assertEquals("1.0", assertIs<ModpackInfoEvent.ConfirmRedownload>(viewModel.events.first()).versionName)
        assertEquals("1.0", gateway.checkedVersion?.name)
    }

    @Test
    fun `not installed version requests download method for selected version`() = runBlocking {
        val selected = testVersion("1.0")
        val other = testVersion("2.0")
        val gateway = FakeModpackInfoGateway(testPack(listOf(other, selected)))
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.requestDownload(selected.copy(name = selected.name))

        assertEquals("1.0", assertIs<ModpackInfoEvent.SelectDownloadMethod>(viewModel.events.first()).versionName)
        assertEquals("1.0", gateway.checkedVersion?.name)
    }

    @Test
    fun `install queues exact selected version`(): Unit = runBlocking {
        val selected = testVersion("1.0")
        val other = testVersion("2.0")
        val gateway = FakeModpackInfoGateway(testPack(listOf(other, selected)), runId = "run-7")
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.installVersion(selected.name)

        val event = withTimeout(5_000) {
            assertIs<ModpackInfoEvent.InstallQueued>(viewModel.events.first())
        }
        assertEquals("1.0", gateway.queuedVersion?.name)
        assertEquals(false, gateway.queuedIncludeClientExtras)
        assertEquals("run-7", event.runId)
    }

    @Test
    fun `install forwards client extras selection`(): Unit = runBlocking {
        val selected = testVersion("1.0")
        val gateway = FakeModpackInfoGateway(testPack(listOf(selected)))
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.installVersion(selected.name, includeClientExtras = false)
        withTimeout(5_000) { assertIs<ModpackInfoEvent.InstallQueued>(viewModel.events.first()) }
        assertEquals("1.0", gateway.queuedVersion?.name)
        assertEquals(false, gateway.queuedIncludeClientExtras)

        viewModel.installVersion(selected.name, includeClientExtras = true)
        withTimeout(5_000) { assertIs<ModpackInfoEvent.InstallQueued>(viewModel.events.first()) }
        assertEquals("1.0", gateway.queuedVersion?.name)
        assertEquals(true, gateway.queuedIncludeClientExtras)
    }

    @Test
    fun `non OK version cannot be queued`() = runBlocking {
        val selected = testVersion("1.0", status = Modpack.Status.FAIL)
        val gateway = FakeModpackInfoGateway(testPack(listOf(selected)))
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.installVersion(selected.name)

        val state = viewModel.uiState.filter { it.errorMessage != null }.first()
        assertTrue(state.errorMessage!!.contains("当前版本不可下载"))
        assertNull(gateway.queuedVersion)
    }

    @Test
    fun `missing version does not check installation`() = runBlocking {
        val gateway = FakeModpackInfoGateway(testPack())
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.requestDownload(testVersion("9.0"))

        viewModel.uiState.filter { it.errorMessage != null }.first()
        assertTrue(gateway.checkCalls.isEmpty())
    }

    @Test
    fun `non OK version does not check installation`() = runBlocking {
        val version = testVersion("1.0", status = Modpack.Status.FAIL)
        val gateway = FakeModpackInfoGateway(testPack(listOf(version)))
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.requestDownload(version)

        viewModel.uiState.filter { it.errorMessage != null }.first()
        assertTrue(gateway.checkCalls.isEmpty())
    }

    @Test
    fun `overlapping download checks only emit the newest version`() = runBlocking {
        val first = testVersion("1.0")
        val second = testVersion("2.0")
        val firstResult = CompletableDeferred<Boolean>()
        val secondResult = CompletableDeferred<Boolean>()
        val gateway = FakeModpackInfoGateway(
            detail = testPack(listOf(first, second)),
            controlledChecks = mapOf("1.0" to firstResult, "2.0" to secondResult),
            ignoreCheckCancellation = true,
        )
        val viewModel = ModpackInfoViewModel(MODPACK_ID, gateway)
        awaitLoaded(viewModel)

        viewModel.requestDownload(first)
        assertEquals("1.0", gateway.checkStarted.receive())
        viewModel.requestDownload(second)
        assertEquals("2.0", gateway.checkStarted.receive())

        firstResult.complete(true)
        secondResult.complete(false)

        val event = assertIs<ModpackInfoEvent.SelectDownloadMethod>(viewModel.events.first())
        assertEquals("2.0", event.versionName)
    }

    private suspend fun awaitLoaded(viewModel: ModpackInfoViewModel): ModpackInfoUiState =
        viewModel.uiState.filter { !it.loading }.first()

    private class FakeModpackInfoGateway(
        var detail: Modpack.DetailVo?,
        private val installed: Boolean = false,
        private val runId: String = "run-1",
        private val controlledChecks: Map<String, CompletableDeferred<Boolean>> = emptyMap(),
        private val ignoreCheckCancellation: Boolean = false,
    ) : ModpackInfoGateway {
        val mutations = mutableListOf<ModpackInfoMutation>()
        var loadCount = 0
        var checkedVersion: Modpack.Version? = null
        var queuedVersion: Modpack.Version? = null
        var queuedIncludeClientExtras: Boolean? = null
        val checkCalls = mutableListOf<String>()
        val checkStarted = Channel<String>(Channel.UNLIMITED)

        override suspend fun loadDetail(modpackId: String): Result<Modpack.DetailVo?> {
            loadCount++
            return Result.success(detail)
        }

        override suspend fun mutate(modpackId: String, mutation: ModpackInfoMutation): Result<Unit> {
            mutations += mutation
            return Result.success(Unit)
        }

        override suspend fun isVersionInstalled(
            pack: Modpack.DetailVo,
            version: Modpack.Version,
        ): Result<Boolean> {
            checkedVersion = version
            checkCalls += version.name
            checkStarted.send(version.name)
            val result = controlledChecks[version.name]?.let { deferred ->
                if (ignoreCheckCancellation) {
                    withContext(NonCancellable) { deferred.await() }
                } else {
                    deferred.await()
                }
            } ?: installed
            return Result.success(result)
        }

        override fun queueInstall(
            pack: Modpack.DetailVo,
            version: Modpack.Version,
            includeClientExtras: Boolean,
        ): Result<String> {
            queuedVersion = version
            queuedIncludeClientExtras = includeClientExtras
            return Result.success(runId)
        }

    }

    private companion object {
        const val MODPACK_ID = "66a000000000000000000001"

        fun testPack(versions: List<Modpack.Version> = listOf(testVersion("1.0"))) = Modpack.DetailVo(
            _id = ObjectId(MODPACK_ID),
            name = "测试整合包",
            authorId = ObjectId("66a000000000000000000002"),
            modCount = 0,
            modloader = ModLoader.neoforge,
            mcVer = McVersion.V211,
            versions = versions,
        )

        fun testVersion(name: String, status: Modpack.Status = Modpack.Status.OK) = Modpack.Version(
            time = 1L,
            modpackId = ObjectId(MODPACK_ID),
            name = name,
            changelog = "",
            status = status,
            mods = mutableListOf(),
        )

    }
}
