package calebxzau.rdi.client.ui.viewmodel

import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModpackVersionInfoViewModelTest {
    @Test
    fun `loads the requested version instead of latest`() = runBlocking {
        val latest = version("2.0", slug = "latest")
        val selected = version("1.0", slug = "selected")
        val gateway = FakeGateway(testPack(listOf(latest, selected)))
        val viewModel = ModpackVersionInfoViewModel(noCallCatalog(), gateway, MODPACK_ID, "1.0")

        val state = viewModel.uiState.filter { !it.loading && it.version != null }.first()

        assertEquals("1.0", state.version?.name)
        assertEquals("selected", state.uiMods.single().mod.slug)
    }

    @Test
    fun `empty version exposes an empty completed mod list`() = runBlocking {
        val gateway = FakeGateway(testPack(listOf(version("1.0").copy(mods = mutableListOf()))))
        val viewModel = ModpackVersionInfoViewModel(noCallCatalog(), gateway, MODPACK_ID, "1.0")

        val state = viewModel.uiState.filter { !it.loading && !it.uiModsLoading }.first()

        assertEquals(emptyList(), state.uiMods)
    }

    @Test
    fun `missing version reports exact version error`() = runBlocking {
        val gateway = FakeGateway(testPack(listOf(version("1.0"))))
        val viewModel = ModpackVersionInfoViewModel(noCallCatalog(), gateway, MODPACK_ID, "9.0")

        val state = viewModel.uiState.filter { !it.loading }.first()

        assertEquals("未找到版本 V9.0", state.errorMessage)
    }

    @Test
    fun `delete targets selected version and emits navigation event`() = runBlocking {
        val gateway = FakeGateway(testPack(listOf(version("1.0"))))
        val viewModel = ModpackVersionInfoViewModel(noCallCatalog(), gateway, MODPACK_ID, "1.0")
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.deleteVersion()

        assertIs<ModpackVersionInfoEvent.VersionDeleted>(viewModel.events.first())
        assertEquals(ModpackInfoMutation.DeleteVersion("1.0"), gateway.mutations.single())
    }

    @Test
    fun `rebuild sets an immediate pending lock and refuses duplicates`() = runBlocking {
        val gateway = FakeGateway(testPack(listOf(version("1.0"))))
        val viewModel = ModpackVersionInfoViewModel(noCallCatalog(), gateway, MODPACK_ID, "1.0")
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.rebuildVersion()
        viewModel.rebuildVersion()

        assertTrue(viewModel.uiState.value.versionActionPending)
        assertIs<ModpackVersionInfoEvent.ShowSnackbar>(viewModel.events.first())
        assertEquals(listOf<ModpackInfoMutation>(ModpackInfoMutation.RebuildVersion("1.0")), gateway.mutations)
    }

    @Test
    fun `rebuild failure clears pending and exposes error`() = runBlocking {
        val gateway = FakeGateway(
            testPack(listOf(version("1.0"))),
            mutationFailure = IllegalStateException("busy"),
        )
        val viewModel = ModpackVersionInfoViewModel(noCallCatalog(), gateway, MODPACK_ID, "1.0")
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.rebuildVersion()

        val state = viewModel.uiState.filter { it.errorMessage != null }.first()
        assertEquals(false, state.versionActionPending)
        assertTrue(state.errorMessage!!.contains("重构失败"))
    }

    @Test
    fun `hydration failure finishes loading and retains fallback mods`() = runBlocking {
        val fallback = version("1.0", slug = "fallback")
        val gateway = FakeGateway(testPack(listOf(fallback)))
        val viewModel = ModpackVersionInfoViewModel(noCallCatalog(), gateway, MODPACK_ID, "1.0")

        val state = viewModel.uiState.filter { !it.loading && !it.uiModsLoading }.first()

        assertEquals("fallback", state.uiMods.single().mod.slug)
    }

    @Test
    fun `rebuild lifecycle waits for busy and terminal transitions`() {
        assertEquals(
            VersionRebuildLifecycle.AwaitingBusy,
            transitionVersionRebuildLifecycle(VersionRebuildLifecycle.AwaitingBusy, Modpack.Status.OK),
        )
        assertEquals(
            VersionRebuildLifecycle.ObservedBusy,
            transitionVersionRebuildLifecycle(VersionRebuildLifecycle.AwaitingBusy, Modpack.Status.WAIT),
        )
        assertEquals(
            VersionRebuildLifecycle.ObservedBusy,
            transitionVersionRebuildLifecycle(VersionRebuildLifecycle.ObservedBusy, Modpack.Status.BUILDING),
        )
        assertEquals(
            VersionRebuildLifecycle.Idle,
            transitionVersionRebuildLifecycle(VersionRebuildLifecycle.ObservedBusy, Modpack.Status.FAIL),
        )
    }

    @Test
    fun `stale monitor generation is rejected`() {
        assertFalse(shouldApplyVersionMonitorResponse(currentReloadToken = 8, responseReloadToken = 7))
        assertEquals(true, shouldApplyVersionMonitorResponse(currentReloadToken = 8, responseReloadToken = 8))
    }

    private class FakeGateway(
        private val detail: Modpack.DetailVo,
        private val installed: Boolean = false,
        private val runId: String = "run-1",
        private val mutationFailure: Throwable? = null,
    ) : ModpackInfoGateway {
        val mutations = mutableListOf<ModpackInfoMutation>()
        var queuedVersion: Modpack.Version? = null

        override suspend fun loadDetail(modpackId: String): Result<Modpack.DetailVo?> = Result.success(detail)

        override suspend fun mutate(modpackId: String, mutation: ModpackInfoMutation): Result<Unit> {
            mutations += mutation
            return mutationFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun isVersionInstalled(pack: Modpack.DetailVo, version: Modpack.Version): Result<Boolean> =
            Result.success(installed)

        override fun queueInstall(
            pack: Modpack.DetailVo,
            version: Modpack.Version,
            includeClientExtras: Boolean,
        ): Result<String> {
            queuedVersion = version
            return Result.success(runId)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun noCallCatalog(): ModCatalog = Proxy.newProxyInstance(
        ModCatalog::class.java.classLoader,
        arrayOf(ModCatalog::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "close" -> Unit
            "toString" -> "ModpackVersionInfoViewModelTestCatalog"
            else -> error("catalog should not be called: ${method.name}")
        }
    } as ModCatalog

    private companion object {
        const val MODPACK_ID = "66a000000000000000000001"

        fun testPack(versions: List<Modpack.Version>) = Modpack.DetailVo(
            _id = ObjectId(MODPACK_ID),
            name = "测试整合包",
            authorId = ObjectId("66a000000000000000000002"),
            modCount = versions.sumOf { it.mods.size },
            modloader = ModLoader.neoforge,
            mcVer = McVersion.V211,
            versions = versions,
        )

        fun version(name: String, slug: String? = "example") = Modpack.Version(
            time = 1L,
            modpackId = ObjectId(MODPACK_ID),
            name = name,
            changelog = "",
            status = Modpack.Status.OK,
            mods = slug?.let {
                mutableListOf(Mod(platform = "mr", projectId = "project-$it", slug = it, fileId = "file", hash = "abc"))
            } ?: mutableListOf(),
        )
    }
}
