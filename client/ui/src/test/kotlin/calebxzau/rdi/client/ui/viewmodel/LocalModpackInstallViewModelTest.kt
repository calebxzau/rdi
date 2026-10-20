package calebxzau.rdi.client.ui.viewmodel

import calebxzau.rdi.client.modcatalog.CatalogContentType
import calebxzhou.rdi.client.database.MinecraftInstallationRecord
import calebxzhou.rdi.client.database.MinecraftInstallationStore
import calebxzhou.rdi.client.service.ArchiveCatalogFile
import calebxzhou.rdi.client.service.ContentResolutionUpdate
import calebxzhou.rdi.client.service.CurseForgeArchiveRef
import calebxzhou.rdi.client.service.LocalModpackInstallSource
import calebxzhou.rdi.client.service.LocalModpackInstaller
import calebxzhou.rdi.client.service.LocalModpackScanner
import calebxzhou.rdi.client.service.LocalModpackTaskSubmitter
import calebxzhou.rdi.client.service.ModpackArchiveCatalog
import calebxzhou.rdi.client.service.ModpackArchiveContentResolutionSource
import calebxzhou.rdi.client.service.ModpackArchiveReader
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalModpackInstallViewModelTest {
    private val tasks = RecordingTaskSubmitter()

    private fun createViewModel() = LocalModpackInstallViewModel(
        scanner = LocalModpackScanner(EmptyInstallationStore, Files.createTempDirectory("rdi-managed")),
        installer = LocalModpackInstaller(Files.createTempDirectory("rdi-versions"), taskSubmitter = tasks),
        archiveReader = ModpackArchiveReader(OneFileCatalog),
        contentResolver = ModpackArchiveContentResolutionSource { _, _ -> flowOf(ContentResolutionUpdate.Complete) },
    )

    @Test
    fun `optional files start unselected and unavailable files need confirmation before installing`(): Unit = runBlocking {
        val viewModel = createViewModel()
        viewModel.openArchive(curseForgeArchive())

        val editor = withTimeout(5_000) {
            viewModel.state.filter { it.editor?.source is LocalModpackInstallSource.Archive }.first().editor!!
        }
        val archive = editor.source as LocalModpackInstallSource.Archive
        assertEquals(setOf("cf:12:34"), archive.preview.optionalFiles.map { it.key }.toSet())
        assertTrue(archive.selectedOptionalFiles.isEmpty())
        assertEquals(1, archive.preview.unavailableFiles.size)

        viewModel.submitInstall()
        assertTrue(viewModel.state.value.editor!!.confirmMissingFiles)
        assertNull(tasks.task)

        viewModel.submitInstall(missingFilesConfirmed = true)
        assertNull(viewModel.state.value.editor)
        assertTrue(tasks.task != null)
    }

    @Test
    fun `a folder without version manifest lets the player choose the game version`(): Unit = runBlocking {
        val folder = Files.createTempDirectory("rdi-bare").resolve("我的包")
        folder.resolve("mods").createDirectories()
        folder.resolve("mods/a.jar").writeText("jar")
        val viewModel = createViewModel()

        viewModel.openDirectory(folder)
        val editor = withTimeout(5_000) { viewModel.state.filter { it.editor != null }.first().editor!! }
        assertTrue(editor.versionSelectable)
        assertEquals("我的包", editor.name)
        assertIs<LocalModpackInstallSource.CopyFrom>(editor.source)

        viewModel.updateRuntime(McVersion.V211, ModLoader.neoforge)
        viewModel.toggleIncludeSaves()
        val updated = viewModel.state.value.editor!!
        assertEquals(McVersion.V211, updated.mcVersion)
        assertEquals(ModLoader.neoforge, updated.modLoader)
        assertTrue((updated.source as LocalModpackInstallSource.CopyFrom).includeSaves)
    }

    @Test
    fun `a folder with nothing to import reports an error`(): Unit = runBlocking {
        val viewModel = createViewModel()

        viewModel.openDirectory(Files.createTempDirectory("rdi-empty-folder"))
        val state = withTimeout(5_000) { viewModel.state.filter { it.archiveError != null }.first() }

        assertNull(state.editor)
        assertTrue(state.archiveError.orEmpty().contains("没有找到"))
    }

    private fun curseForgeArchive(): Path {
        val file = Files.createTempFile("rdi-local-cf", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            output.putNextEntry(ZipEntry("manifest.json"))
            output.write(
                """
                {"name":"测试包","minecraft":{"version":"1.20.1","modLoaders":[{"id":"forge-47.4.20"}]},"files":[
                  {"projectID":12,"fileID":34,"required":false},
                  {"projectID":13,"fileID":35,"required":true}
                ]}
                """.trimIndent().toByteArray(),
            )
            output.closeEntry()
        }
        return file
    }
}

private object OneFileCatalog : ModpackArchiveCatalog {
    override suspend fun resolveCurseForgeFiles(refs: List<CurseForgeArchiveRef>) = Result.success(
        mapOf(
            34 to ArchiveCatalogFile(
                projectId = 12,
                displayName = "可选模组",
                fileName = "optional.jar",
                size = 1,
                sha1 = "1111111111111111111111111111111111111111",
                url = "https://mediafilez.forgecdn.net/files/1/5/optional.jar",
                contentType = CatalogContentType.MOD,
            ),
        ),
    )
}

private object EmptyInstallationStore : MinecraftInstallationStore {
    override suspend fun list(): Result<List<MinecraftInstallationRecord>> = Result.success(emptyList())
    override suspend fun upsert(path: Path, seenAt: Long): Result<Unit> = error("unused")
    override suspend fun delete(path: Path): Result<Unit> = error("unused")
    override suspend fun lastFullScanAt(): Result<Long?> = error("unused")
    override suspend fun setLastFullScanAt(scannedAt: Long): Result<Unit> = error("unused")
    override fun close() = Unit
}

private class RecordingTaskSubmitter : LocalModpackTaskSubmitter {
    var task: Task2? = null

    override fun submit(task: Task2, dedupeKey: String): String {
        this.task = task
        return "run-id"
    }
}
