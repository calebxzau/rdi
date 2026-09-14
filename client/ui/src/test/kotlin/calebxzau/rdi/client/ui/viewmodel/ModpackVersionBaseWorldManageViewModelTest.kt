package calebxzau.rdi.client.ui.viewmodel

import calebxzau.rdi.common.model.BaseWorld
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModpackVersionBaseWorldManageViewModelTest {
    @Test
    fun `initial binding is copied into draft`() = runBlocking {
        val worldId = UUID.randomUUID()
        val gateway = FakeGateway(version = version(Modpack.Version.BaseWorldBinding(worldId, true)), worlds = listOf(world(worldId)))
        val viewModel = ModpackVersionBaseWorldManageViewModel(gateway, MODPACK_ID, VERSION)

        val state = viewModel.uiState.filter { !it.loading }.first()

        assertEquals(worldId, state.draftId)
        assertTrue(state.draftRequired)
        assertTrue(state.canSave)
    }

    @Test
    fun `selection and required flag preserve invariants`() = runBlocking {
        val worldId = UUID.randomUUID()
        val gateway = FakeGateway(version = version(), worlds = listOf(world(worldId)))
        val viewModel = ModpackVersionBaseWorldManageViewModel(gateway, MODPACK_ID, VERSION)
        viewModel.uiState.filter { !it.loading }.first()

        viewModel.setRequired(true)
        assertFalse(viewModel.uiState.value.draftRequired)
        viewModel.select(worldId)
        viewModel.setRequired(true)
        assertEquals(worldId, viewModel.uiState.value.draftId)
        assertTrue(viewModel.uiState.value.draftRequired)
        viewModel.select(worldId)
        assertEquals(null, viewModel.uiState.value.draftId)
        assertFalse(viewModel.uiState.value.draftRequired)
    }

    @Test
    fun `save sends exact binding and emits one success event`() = runBlocking {
        val worldId = UUID.randomUUID()
        val gateway = FakeGateway(version = version(), worlds = listOf(world(worldId)))
        val viewModel = ModpackVersionBaseWorldManageViewModel(gateway, MODPACK_ID, VERSION)
        viewModel.uiState.filter { !it.loading }.first()
        viewModel.select(worldId)
        viewModel.setRequired(true)

        viewModel.save()
        viewModel.uiState.filter { !it.saving }.first()

        assertEquals(
            ModpackInfoMutation.UpdateVersionBaseWorld(
                VERSION,
                Modpack.Version.BaseWorldBinding(worldId, true),
            ),
            gateway.mutations.single(),
        )
        assertEquals(ModpackVersionBaseWorldManageEvent.Saved, viewModel.events.first())
    }

    @Test
    fun `missing configured world cannot save until cleared`() = runBlocking {
        val missingId = UUID.randomUUID()
        val gateway = FakeGateway(version = version(Modpack.Version.BaseWorldBinding(missingId, true)))
        val viewModel = ModpackVersionBaseWorldManageViewModel(gateway, MODPACK_ID, VERSION)

        val state = viewModel.uiState.filter { !it.loading }.first()
        assertTrue(state.selectedWorldUnavailable)
        assertFalse(state.canSave)

        viewModel.select(missingId)
        assertTrue(viewModel.uiState.value.canSave)
        viewModel.save()
        viewModel.uiState.filter { !it.saving }.first()
        assertEquals(
            ModpackInfoMutation.UpdateVersionBaseWorld(VERSION, null),
            gateway.mutations.single(),
        )
    }

    @Test
    fun `stale first reload cannot overwrite the newer reload`() = runBlocking {
        val firstLoad = CompletableDeferred<Result<Modpack.DetailVo?>>()
        val secondVersion = version()
        val gateway = DeferredLoadGateway(firstLoad, secondVersion)
        val viewModel = ModpackVersionBaseWorldManageViewModel(gateway, MODPACK_ID, VERSION)
        gateway.firstLoadStarted.await()

        viewModel.reload()
        gateway.secondLoadStarted.await()
        gateway.completeSecondLoad()
        viewModel.uiState.filter { !it.loading && it.dataLoaded }.first()

        firstLoad.complete(Result.success(Modpack.DetailVo(
            _id = ObjectId(MODPACK_ID),
            name = "旧整合包",
            authorId = ObjectId(AUTHOR_ID),
            modCount = 0,
            modloader = ModLoader.neoforge,
            mcVer = McVersion.V211,
            versions = listOf(version(binding = null)),
        )))

        assertEquals(secondVersion, viewModel.uiState.value.version)
    }

    @Test
    fun `failed save clears saving retains draft and exposes error`() = runBlocking {
        val worldId = UUID.randomUUID()
        val gateway = FakeGateway(
            version = version(),
            worlds = listOf(world(worldId)),
            mutationFailure = IllegalStateException("busy"),
        )
        val viewModel = ModpackVersionBaseWorldManageViewModel(gateway, MODPACK_ID, VERSION)
        viewModel.uiState.filter { !it.loading }.first()
        viewModel.select(worldId)
        viewModel.setRequired(true)

        viewModel.save()
        viewModel.uiState.filter { it.saving }.first()
        val state = viewModel.uiState.filter { !it.saving && it.errorMessage != null }.first()

        assertEquals(worldId, state.draftId)
        assertTrue(state.draftRequired)
        assertTrue(state.errorMessage!!.contains("更新地图模板失败"))
    }

    private class FakeGateway(
        private val version: Modpack.Version,
        private val worlds: List<BaseWorld> = emptyList(),
        private val mutationFailure: Throwable? = null,
    ) : ModpackInfoGateway {
        val mutations = mutableListOf<ModpackInfoMutation>()

        override suspend fun loadDetail(modpackId: String): Result<Modpack.DetailVo?> =
            Result.success(Modpack.DetailVo(
                _id = ObjectId(MODPACK_ID),
                name = "测试整合包",
                authorId = ObjectId(AUTHOR_ID),
                modCount = 0,
                modloader = ModLoader.neoforge,
                mcVer = McVersion.V211,
                versions = listOf(version),
            ))

        override suspend fun loadReadyBaseWorlds(): Result<List<BaseWorld>> = Result.success(worlds)

        override suspend fun mutate(modpackId: String, mutation: ModpackInfoMutation): Result<Unit> {
            mutations += mutation
            return mutationFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun isVersionInstalled(pack: Modpack.DetailVo, version: Modpack.Version): Result<Boolean> = Result.success(false)

        override fun queueInstall(
            pack: Modpack.DetailVo,
            version: Modpack.Version,
            includeClientExtras: Boolean,
        ): Result<String> = Result.success("run")
    }

    private class DeferredLoadGateway(
        private val firstLoad: CompletableDeferred<Result<Modpack.DetailVo?>>,
        private val secondVersion: Modpack.Version,
    ) : ModpackInfoGateway {
        val firstLoadStarted = CompletableDeferred<Unit>()
        val secondLoadStarted = CompletableDeferred<Unit>()
        private var callCount = 0

        override suspend fun loadDetail(modpackId: String): Result<Modpack.DetailVo?> {
            callCount++
            return if (callCount == 1) {
                firstLoadStarted.complete(Unit)
                firstLoad.await()
            } else {
                secondLoadStarted.complete(Unit)
                Result.success(Modpack.DetailVo(
                    _id = ObjectId(MODPACK_ID),
                    name = "测试整合包",
                    authorId = ObjectId(AUTHOR_ID),
                    modCount = 0,
                    modloader = ModLoader.neoforge,
                    mcVer = McVersion.V211,
                    versions = listOf(secondVersion),
                ))
            }
        }

        override suspend fun loadReadyBaseWorlds(): Result<List<BaseWorld>> = Result.success(emptyList())

        override suspend fun mutate(modpackId: String, mutation: ModpackInfoMutation): Result<Unit> = Result.success(Unit)

        override suspend fun isVersionInstalled(pack: Modpack.DetailVo, version: Modpack.Version): Result<Boolean> = Result.success(false)

        override fun queueInstall(
            pack: Modpack.DetailVo,
            version: Modpack.Version,
            includeClientExtras: Boolean,
        ): Result<String> = Result.success("run")

        fun completeSecondLoad() = Unit
    }

    private companion object {
        const val MODPACK_ID = "66a000000000000000000001"
        const val AUTHOR_ID = "66a000000000000000000002"
        const val VERSION = "1.0"

        fun version(binding: Modpack.Version.BaseWorldBinding? = null) = Modpack.Version(
            time = 1L,
            modpackId = ObjectId(MODPACK_ID),
            name = VERSION,
            changelog = "",
            status = Modpack.Status.OK,
            mods = mutableListOf<Mod>(),
            baseWorld = binding,
        )

        fun world(id: UUID) = BaseWorld(id, UUID.randomUUID(), "模板", "minecraft:normal", null, 1L)
    }
}
