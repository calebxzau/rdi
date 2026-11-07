package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.client.database.ModpackLaunchOptionsRecord
import calebxzhou.rdi.client.database.ModpackLaunchOptionsStore
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModpackOptionViewModelTest {
    @Test
    fun `missing record uses the default jdwp parameter`() = runBlocking {
        val store = MemoryModpackLaunchOptionsStore()
        val viewModel = createViewModel(store)

        val state = awaitLoaded(viewModel)

        assertEquals(DEFAULT_JDWP_PARAM, state.draft.jdwpParam)
        assertFalse(state.dirty)
    }

    @Test
    fun `enabling jdwp saves the default parameter`() = runBlocking {
        val store = MemoryModpackLaunchOptionsStore()
        val viewModel = createViewModel(store)
        val loadedState = awaitLoaded(viewModel)

        viewModel.updateDraft(loadedState.draft.copy(jdwpEnabled = true))
        viewModel.save()
        awaitSaved(viewModel)

        assertEquals(DEFAULT_JDWP_PARAM, store.record?.jdwpParam)
        assertEquals(true, store.record?.jdwpEnabled)
    }

    @Test
    fun `custom jvm params and forgeguard option are saved`() = runBlocking {
        val store = MemoryModpackLaunchOptionsStore()
        val viewModel = createViewModel(store)
        val loadedState = awaitLoaded(viewModel)

        viewModel.updateDraft(
            loadedState.draft.copy(
                customJvmParams = "-XX:+UnlockDiagnosticVMOptions\n-Dexample=true",
                forgeguardDisabled = true,
            )
        )
        viewModel.save()
        awaitSaved(viewModel)

        assertEquals(
            "-XX:+UnlockDiagnosticVMOptions\n-Dexample=true",
            store.record?.customJvmParams,
        )
        assertTrue(store.record?.forgeguardDisabled == true)
    }

    @Test
    fun `reset defaults removes overrides`() = runBlocking {
        val store = MemoryModpackLaunchOptionsStore(
            ModpackLaunchOptionsRecord(
                versionId = VERSION_ID,
                javaPath = "C:/jdk/bin/java.exe",
                maxMemoryMb = 8192,
                jdwpEnabled = true,
                jdwpParam = "transport=dt_socket,server=n,suspend=n,address=*:5005",
            )
        )
        val viewModel = createViewModel(store)
        awaitLoaded(viewModel)

        viewModel.resetDefaults()
        viewModel.save()
        awaitSaved(viewModel)

        assertNull(store.record)
    }

    private fun createViewModel(store: MemoryModpackLaunchOptionsStore): ModpackOptionViewModel =
        ModpackOptionViewModel(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
            versionId = VERSION_ID,
            store = store,
            runtime = TestModpackOptionRuntime,
        )

    private suspend fun awaitLoaded(viewModel: ModpackOptionViewModel): ModpackOptionUiState =
        viewModel.uiState.filter { !it.loading }.first()

    private suspend fun awaitSaved(viewModel: ModpackOptionViewModel): ModpackOptionUiState =
        viewModel.uiState.filter { !it.saving }.first()

    private class MemoryModpackLaunchOptionsStore(
        var record: ModpackLaunchOptionsRecord? = null,
    ) : ModpackLaunchOptionsStore {
        override suspend fun find(versionId: String): Result<ModpackLaunchOptionsRecord?> =
            Result.success(record?.takeIf { it.versionId == versionId })

        override suspend fun upsert(record: ModpackLaunchOptionsRecord): Result<Unit> {
            this.record = record
            return Result.success(Unit)
        }

        override suspend fun delete(versionId: String): Result<Unit> {
            if (record?.versionId == versionId) record = null
            return Result.success(Unit)
        }

        override fun close() = Unit
    }

    private object TestModpackOptionRuntime : ModpackOptionRuntime {
        override fun getTotalPhysicalMemoryMb(): Int = 16384

        override fun validateJdkPath(rawPath: String): Result<String> = Result.success(rawPath)

        override fun validateMaxMemory(maxMemoryText: String, totalMemoryMb: Int): Result<Int> =
            Result.success(maxMemoryText.trim().toInt())
    }

    private companion object {
        const val VERSION_ID = "pack_1.0"
    }
}
