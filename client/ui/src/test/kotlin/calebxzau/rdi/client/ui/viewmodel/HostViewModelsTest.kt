package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.client.service.GithubRelease
import calebxzhou.rdi.client.service.GithubReleaseAsset
import calebxzhou.rdi.client.service.GithubRepoRef
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostViewModelsTest {
    @Test
    fun `host info loads host and modpack details`() = runBlocking {
        val gateway = FakeHostGateway(host = testHost(), modpack = testModpack())
        val viewModel = HostInfoViewModel(HOST_ID, gateway)

        val state = viewModel.uiState.filter { !it.loading }.first()

        assertEquals("测试房间", state.host?.name)
        assertEquals("测试整合包", state.modpack?.name)
        assertNull(state.errorMessage)
    }

    @Test
    fun `host info reports detail failure`() = runBlocking {
        val gateway = FakeHostGateway(loadHostFailure = IllegalStateException("offline"))
        val viewModel = HostInfoViewModel(HOST_ID, gateway)

        val state = viewModel.uiState.filter { !it.loading }.first()

        assertNull(state.host)
        assertTrue(state.errorMessage.orEmpty().contains("offline"))
    }

    @Test
    fun `host info delete success emits deleted event`() = runBlocking {
        val gateway = FakeHostGateway(host = testHost(), modpack = testModpack())
        val viewModel = HostInfoViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.deleteHost(deleteWorld = true)

        assertEquals(HostInfoEvent.Deleted, withTimeout(5_000) { viewModel.events.first() })
        assertEquals(listOf(true), gateway.deletedWorldFlags)
        withTimeout(5_000) { viewModel.uiState.filter { !it.deleting }.first() }
    }

    @Test
    fun `host info delete failure keeps host and reports message`() = runBlocking {
        val gateway = FakeHostGateway(
            host = testHost(),
            modpack = testModpack(),
            deleteFailure = IllegalStateException("没有权限"),
        )
        val viewModel = HostInfoViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.deleteHost(deleteWorld = false)

        assertEquals(HostInfoEvent.DeleteFailed("没有权限"), withTimeout(5_000) { viewModel.events.first() })
        val state = withTimeout(5_000) { viewModel.uiState.filter { !it.deleting }.first() }
        assertEquals("测试房间", state.host?.name)
        assertNull(state.errorMessage)
    }

    @Test
    fun `host mods synchronizes disabled mods into immediate host detail`() = runBlocking {
        val disabled = testMod("disabled")
        val gateway = FakeHostGateway(
            host = testHost(disabledMods = listOf(disabled)),
            modpack = testModpack(mutableListOf(testMod("active"), disabled)),
        )
        val viewModel = HostModsViewModel(HOST_ID, gateway)

        val state = viewModel.uiState.filter { !it.loading }.first()

        assertEquals(listOf(disabled), state.disabledMods)
        assertEquals(listOf(disabled), state.host?.disabledMods)
        assertEquals(listOf("active"), state.baseVersionMods.map(Mod::slug))
    }

    @Test
    fun `second mod can be enabled immediately after consecutive disables`() = runBlocking {
        val first = testMod("first")
        val second = testMod("second")
        val gateway = FakeHostGateway(
            host = testHost(),
            modpack = testModpack(mutableListOf(first, second)),
        )
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.changeDisabledMods(listOf(first), disabled = true)
        viewModel.uiState.filter { it.pendingDisabledModKeys.isEmpty() && it.disabledMods == listOf(first) }.first()
        viewModel.changeDisabledMods(listOf(second), disabled = true)
        viewModel.uiState.filter { it.pendingDisabledModKeys.isEmpty() && it.disabledMods.size == 2 }.first()
        viewModel.changeDisabledMods(listOf(second), disabled = false)
        val state = viewModel.uiState.filter {
            it.pendingDisabledModKeys.isEmpty() && it.disabledMods == listOf(first)
        }.first()

        assertEquals(listOf(true, true, false), gateway.disabledChanges.map { it.second })
        assertEquals(listOf(first), state.host?.disabledMods)
    }

    @Test
    fun `extra mod filter rejects existing and duplicate slugs`() {
        val existing = testMod("existing")
        val duplicate = testMod("new")

        val result = filterExtraModsForAdding(
            candidateMods = listOf(existing.copy(projectId = "other"), duplicate, duplicate.copy(fileId = "other-file")),
            existingMods = listOf(existing),
        )

        assertEquals(listOf(duplicate), result.acceptedMods)
        assertEquals(2, result.rejectedMessages.size)
    }

    @Test
    fun `github release failure is exposed in dialog state`() = runBlocking {
        val gateway = FakeHostGateway(
            host = testHost(),
            modpack = testModpack(),
            githubFailure = IllegalStateException("rate limited"),
        )
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()
        viewModel.updateExtraModDraft(viewModel.uiState.value.extraModDraft.copy(githubRepoUrl = "https://github.com/a/b"))

        viewModel.loadGithubReleases()
        val state = viewModel.uiState.filter { !it.extraModLoading && it.extraModError != null }.first()

        assertTrue(state.extraModError.orEmpty().contains("rate limited"))
    }

    @Test
    fun `adding valid manual extra mod emits completion`() = runBlocking {
        val gateway = FakeHostGateway(host = testHost(), modpack = testModpack())
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()
        viewModel.updateExtraModDraft(validManualDraft())

        viewModel.submitManualExtraMod()

        assertIs<HostModsEvent.ShowSnackbar>(viewModel.events.first())
        assertIs<HostModsEvent.ExtraModAdded>(viewModel.events.first())
        assertEquals(listOf("manual"), gateway.addedMods.single().map(Mod::slug))
        assertFalse(viewModel.uiState.value.extraModLoading)
    }

    @Test
    fun `failed extra mod add stays in dialog and exposes error`() = runBlocking {
        val gateway = FakeHostGateway(
            host = testHost(),
            modpack = testModpack(),
            addFailure = IllegalStateException("rejected"),
        )
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()
        viewModel.updateExtraModDraft(validManualDraft())

        viewModel.submitManualExtraMod()
        val state = viewModel.uiState.filter { !it.extraModLoading && it.extraModError != null }.first()

        assertTrue(state.extraModError.orEmpty().contains("rejected"))
    }

    @Test
    fun `removing extra mods updates host and reports success`() = runBlocking {
        val first = testMod("first")
        val second = testMod("second")
        val gateway = FakeHostGateway(host = testHost(extraMods = listOf(first, second)), modpack = testModpack())
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.removeExtraMods(listOf(first))
        assertIs<HostModsEvent.ShowSnackbar>(viewModel.events.first())

        assertEquals(listOf(second), viewModel.uiState.value.host?.extraMods)
        assertEquals(listOf(first.projectId), gateway.removedProjectIds.single())
    }

    @Test
    fun `failed extra mod removal preserves host and exposes error`() = runBlocking {
        val mod = testMod("first")
        val gateway = FakeHostGateway(
            host = testHost(extraMods = listOf(mod)),
            modpack = testModpack(),
            removeFailure = IllegalStateException("busy"),
        )
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.removeExtraMods(listOf(mod))
        val state = viewModel.uiState.filter { !it.removingExtraMods && it.errorMessage != null }.first()

        assertEquals(listOf(mod), state.host?.extraMods)
        assertTrue(state.errorMessage.orEmpty().contains("busy"))
    }

    @Test
    fun `changing extra mod side updates host and reports success`() = runBlocking {
        val mod = testMod("first")
        val gateway = FakeHostGateway(host = testHost(extraMods = listOf(mod)), modpack = testModpack())
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.updateExtraModSide(mod, Mod.Side.CLIENT)
        assertIs<HostModsEvent.ShowSnackbar>(viewModel.events.first())

        assertEquals(listOf(mod.copy(side = Mod.Side.CLIENT)), viewModel.uiState.value.host?.extraMods)
        assertEquals(listOf(mod.projectId to Mod.Side.CLIENT), gateway.sideChanges)
    }

    @Test
    fun `extra mod side cannot be changed to neither side`() = runBlocking {
        val mod = testMod("first")
        val gateway = FakeHostGateway(host = testHost(extraMods = listOf(mod)), modpack = testModpack())
        val viewModel = HostModsViewModel(HOST_ID, gateway)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.updateExtraModSide(mod, Mod.Side.UNKNOWN)
        assertIs<HostModsEvent.ShowSnackbar>(viewModel.events.first())

        assertEquals(listOf(mod), viewModel.uiState.value.host?.extraMods)
        assertTrue(gateway.sideChanges.isEmpty())
    }

    private class FakeHostGateway(
        var host: Host.DetailVo? = null,
        var modpack: Modpack.DetailVo? = null,
        private val loadHostFailure: Throwable? = null,
        private val githubFailure: Throwable? = null,
        private val addFailure: Throwable? = null,
        private val removeFailure: Throwable? = null,
        private val deleteFailure: Throwable? = null,
    ) : HostGateway {
        val deletedWorldFlags = mutableListOf<Boolean>()
        val disabledChanges = mutableListOf<Pair<List<Mod>, Boolean>>()
        val addedMods = mutableListOf<List<Mod>>()
        val removedProjectIds = mutableListOf<List<String>>()
        val sideChanges = mutableListOf<Pair<String, Mod.Side>>()

        override suspend fun loadHost(hostId: ObjectId): Result<Host.DetailVo?> =
            if (loadHostFailure == null) Result.success(host) else Result.failure(loadHostFailure)

        override suspend fun loadModpack(modpackId: ObjectId): Result<Modpack.DetailVo?> =
            Result.success(modpack)

        override suspend fun deleteHost(hostId: ObjectId, deleteWorld: Boolean): Result<Unit> {
            deletedWorldFlags += deleteWorld
            return deleteFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun changeDisabledMods(
            hostId: ObjectId,
            mods: List<Mod>,
            disabled: Boolean,
        ): Result<List<Mod>> {
            disabledChanges += mods to disabled
            val current = host?.disabledMods.orEmpty()
            val updated = if (disabled) {
                (current + mods).distinctBy(Mod::slug)
            } else {
                current.filterNot { existing -> mods.any { it.slug == existing.slug } }
            }
            host = host?.copy(disabledMods = updated)
            return Result.success(updated)
        }

        override suspend fun addExtraMods(hostId: ObjectId, mods: List<Mod>): Result<Unit> {
            addedMods += mods
            return if (addFailure == null) Result.success(Unit) else Result.failure(addFailure)
        }

        override suspend fun removeExtraMods(hostId: ObjectId, projectIds: List<String>): Result<List<Mod>> {
            removedProjectIds += projectIds
            removeFailure?.let { return Result.failure(it) }
            val updated = host?.extraMods.orEmpty().filterNot { it.projectId in projectIds }
            host = host?.copy(extraMods = updated)
            return Result.success(updated)
        }

        override suspend fun updateExtraModSide(
            hostId: ObjectId,
            projectId: String,
            side: Mod.Side,
        ): Result<List<Mod>> {
            sideChanges += projectId to side
            val updated = host?.extraMods.orEmpty().map { if (it.projectId == projectId) it.copy(side = side) else it }
            host = host?.copy(extraMods = updated)
            return Result.success(updated)
        }

        override suspend fun loadGithubReleases(repoUrl: String): Result<Pair<GithubRepoRef, List<GithubRelease>>> =
            githubFailure?.let { Result.failure(it) } ?: Result.failure(UnsupportedOperationException())

        override suspend fun buildGithubExtraMod(
            repo: GithubRepoRef,
            asset: GithubReleaseAsset,
            side: Mod.Side,
            onProgress: (String) -> Unit,
        ): Result<Mod> = Result.failure(UnsupportedOperationException())
    }

    private companion object {
        val HOST_ID = ObjectId("66a000000000000000000001")
        val MODPACK_ID = ObjectId("66a000000000000000000002")
        val OWNER_ID = ObjectId("66a000000000000000000003")

        fun testHost(
            extraMods: List<Mod> = emptyList(),
            disabledMods: List<Mod> = emptyList(),
        ) = Host.DetailVo(
            _id = HOST_ID,
            name = "测试房间",
            ownerId = OWNER_ID,
            modpack = Modpack.BriefVo(
                id = MODPACK_ID,
                name = "测试整合包",
                authorId = OWNER_ID,
                mcVer = McVersion.V211,
                modloader = ModLoader.neoforge,
            ),
            packVer = "1.0",
            version = 1,
            port = 25565,
            difficulty = 2,
            gameMode = 0,
            levelType = "minecraft:normal",
            extraMods = extraMods,
            disabledMods = disabledMods,
        )

        fun testModpack(mods: MutableList<Mod> = mutableListOf()) = Modpack.DetailVo(
            _id = MODPACK_ID,
            name = "测试整合包",
            authorId = OWNER_ID,
            modCount = mods.size,
            modloader = ModLoader.neoforge,
            mcVer = McVersion.V211,
            versions = listOf(
                Modpack.Version(
                    time = 1L,
                    modpackId = MODPACK_ID,
                    name = "1.0",
                    changelog = "",
                    status = Modpack.Status.OK,
                    mods = mods,
                )
            ),
        )

        fun testMod(slug: String) = Mod(
            platform = "mr",
            projectId = "$slug-project",
            slug = slug,
            fileId = "$slug-file",
            hash = "$slug-hash",
        )

        fun validManualDraft() = ExtraModDraft(
            platform = "mr",
            projectId = "manual-project",
            slug = "manual",
            fileId = "manual-file",
            hash = "abc",
            downloadUrls = "https://example.com/manual.jar",
        )
    }
}
