package calebxzau.rdi.client.service

import calebxzhou.rdi.client.service.LocalModpackInfo
import calebxzhou.rdi.client.service.LocalModpackInstallRequest
import calebxzhou.rdi.client.service.LocalModpackInstallSource
import calebxzhou.rdi.client.service.LocalModpackInstaller
import calebxzhou.rdi.client.service.LocalModpackSourceKind
import calebxzhou.rdi.client.service.LocalModpackStore
import calebxzhou.rdi.client.service.LocalModpackTaskSubmitter
import calebxzhou.rdi.client.service.ModpackArchiveCatalog
import calebxzhou.rdi.client.service.ModpackArchiveReader
import calebxzhou.rdi.client.service.ArchiveCatalogFile
import calebxzhou.rdi.client.service.CurseForgeArchiveRef
import calebxzhou.rdi.client.service.scanLocalOnlyPacks
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Task2
import calebxzhou.rdi.common.model.Task2Context
import calebxzhou.rdi.common.service.runInline
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalModpackInstallerTest {
    private val versions: Path = Files.createTempDirectory("rdi-local-versions")

    private fun installer(id: String, tasks: CapturingTaskSubmitter) =
        LocalModpackInstaller(versions, newId = { id }, taskSubmitter = tasks)

    private fun request(source: LocalModpackInstallSource, name: String = "复制包") = LocalModpackInstallRequest(
        source = source,
        mcVersion = McVersion.V201,
        modLoader = ModLoader.forge,
        name = name,
        comment = null,
        iconUrl = null,
    )

    private fun gameDirectory(): Path = Files.createTempDirectory("rdi-copy-source").resolve("1.20.1-Forge").apply {
        resolve("mods").createDirectories()
        resolve("mods/a.jar").writeText("mod")
        resolve("config").createDirectories()
        resolve("config/a.toml").writeText("x=1")
        resolve("saves/world").createDirectories()
        resolve("saves/world/level.dat").writeText("world")
        resolve("logs").createDirectories()
        resolve("logs/latest.log").writeText("log")
        resolve("1.20.1-Forge.json").writeText("{}")
        resolve("1.20.1-Forge.jar").writeText("jar")
        resolve("hmclversion.cfg").writeText("cfg")
        resolve("options.txt").writeText("fov:0.5\nlang:en_us\n")
    }

    @Test
    fun `directory import copies the pack, keeps player options and leaves out launcher files and saves`(): Unit = runBlocking {
        val tasks = CapturingTaskSubmitter()
        installer("019f2d68-6b7e-7ec8-98d1-0b8483c39875", tasks)
            .install(request(LocalModpackInstallSource.CopyFrom(gameDirectory()))).getOrThrow()
        tasks.run()

        val target = versions.resolve("local_019f2d68-6b7e-7ec8-98d1-0b8483c39875")
        assertTrue(Files.isRegularFile(target.resolve("mods/a.jar")))
        assertTrue(Files.isRegularFile(target.resolve("config/a.toml")))
        assertFalse(Files.exists(target.resolve("saves")))
        assertFalse(Files.exists(target.resolve("logs")))
        assertFalse(Files.exists(target.resolve("1.20.1-Forge.json")))
        assertFalse(Files.exists(target.resolve("1.20.1-Forge.jar")))
        assertFalse(Files.exists(target.resolve("hmclversion.cfg")))
        assertEquals("fov:0.5\nlang:en_us\n", target.resolve("options.txt").readText())
        val info = LocalModpackStore.read(target.toFile()).getOrThrow()
        assertEquals("复制包", info.name)
        assertEquals(LocalModpackSourceKind.Directory, info.source)
        assertFalse(Files.exists(versions.resolve("local_019f2d68-6b7e-7ec8-98d1-0b8483c39875.installing")))
    }

    @Test
    fun `saves are copied when the player asks and a duplicate name gets a suffix`(): Unit = runBlocking {
        val source = gameDirectory()
        val tasks = CapturingTaskSubmitter()
        installer("a1", tasks).install(request(LocalModpackInstallSource.CopyFrom(source))).getOrThrow()
        tasks.run()
        installer("a2", tasks).install(request(LocalModpackInstallSource.CopyFrom(source, includeSaves = true))).getOrThrow()
        tasks.run()

        val second = versions.resolve("local_a2")
        assertTrue(Files.isRegularFile(second.resolve("saves/world/level.dat")))
        assertEquals("复制包 (2)", LocalModpackStore.read(second.toFile()).getOrThrow().name)
    }

    @Test
    fun `empty pack gets RDI default options and is listed as a local pack`(): Unit = runBlocking {
        val tasks = CapturingTaskSubmitter()
        installer("e1", tasks).install(request(LocalModpackInstallSource.Empty, name = "空包")).getOrThrow()
        tasks.run()

        val target = versions.resolve("local_e1")
        assertTrue(Files.isDirectory(target.resolve("mods")))
        val options = target.resolve("options.txt").readText()
        assertTrue("lang:zh_cn" in options)
        assertTrue("darkMojangStudiosBackground:true" in options)
        val listed = scanLocalOnlyPacks(versions.toFile()).single()
        assertEquals("local_e1", listed.versionId)
        assertEquals("空包", listed.name)
        assertEquals(McVersion.V201, listed.mcVersion)
        assertTrue(listed.isLocal)
    }

    @Test
    fun `failed copy leaves no pack behind`(): Unit = runBlocking {
        val source = gameDirectory()
        val tasks = CapturingTaskSubmitter()
        installer("f1", tasks).install(request(LocalModpackInstallSource.CopyFrom(source))).getOrThrow()
        source.toFile().deleteRecursively()

        assertTrue(runCatching { tasks.run() }.isFailure)
        assertFalse(Files.exists(versions.resolve("local_f1")))
        assertFalse(Files.exists(versions.resolve("local_f1.installing")))
    }

    @Test
    fun `unsupported game versions are rejected before a task starts`() {
        val tasks = CapturingTaskSubmitter()
        val result = installer("u1", tasks).install(
            request(LocalModpackInstallSource.Empty).copy(mcVersion = McVersion.V071, modLoader = ModLoader.forge),
        )

        assertTrue(result.isFailure)
        assertEquals(null, tasks.task)
    }

    @Test
    fun `archive overrides are applied in layer order`(): Unit = runBlocking {
        val archive = zip(
            "modrinth.index.json" to
                """{"formatVersion":1,"game":"minecraft","name":"覆盖包","dependencies":{"minecraft":"1.20.1","forge":"47.4.20"},"files":[]}""",
            "overrides/mods/embedded.jar" to "jar",
            "overrides/config/a.toml" to "base",
            "client-overrides/config/a.toml" to "client",
        )
        val preview = ModpackArchiveReader(NoCatalog).inspect(archive).getOrThrow()
        val tasks = CapturingTaskSubmitter()
        installer("z1", tasks).install(
            request(LocalModpackInstallSource.Archive(preview, emptySet()), name = preview.name),
        ).getOrThrow()
        tasks.run()

        val target = versions.resolve("local_z1")
        assertEquals("jar", target.resolve("mods/embedded.jar").readText())
        assertEquals("client", target.resolve("config/a.toml").readText())
        assertEquals(LocalModpackSourceKind.Archive, LocalModpackStore.read(target.toFile()).getOrThrow().source)
    }

    @Test
    fun `game directory file filter keeps pack content only`() {
        fun copied(path: String, includeSaves: Boolean = false) =
            LocalModpackInstaller.shouldCopyGameFile(path, "pack", includeSaves)

        assertTrue(copied("mods/a.jar"))
        assertTrue(copied("kubejs/startup_scripts/a.js"))
        assertTrue(copied("options.txt"))
        assertFalse(copied("pack.json"))
        assertFalse(copied("pack.jar"))
        assertFalse(copied("latest.log"))
        assertFalse(copied("versions/1.20.1/1.20.1.json"))
        assertFalse(copied("crash-reports/a.txt"))
        assertFalse(copied("saves/w/level.dat"))
        assertTrue(copied("saves/w/level.dat", includeSaves = true))
        assertFalse(copied(LocalModpackStore.INFO_FILE_NAME))
    }

    @Test
    fun `store skips broken metadata and names are made unique case-insensitively`() {
        val broken = versions.resolve("local_broken").createDirectories()
        broken.resolve(LocalModpackStore.INFO_FILE_NAME).writeText("{")
        versions.resolve("6512bd43d9caa6e02c990b0a_1.0").createDirectories()
        val ok = versions.resolve("local_ok").createDirectories().toFile()
        LocalModpackStore.write(
            ok,
            LocalModpackInfo("ok", "包", mcVersion = McVersion.V211, modLoader = ModLoader.neoforge, createdAt = 1, source = LocalModpackSourceKind.Empty),
        ).getOrThrow()

        assertEquals(listOf("local_ok"), scanLocalOnlyPacks(versions.toFile()).map { it.versionId })
        assertEquals("ABC (3)", LocalModpackStore.uniqueName(" ABC ", listOf("abc", "Abc (2)")))
        assertEquals("新包", LocalModpackStore.uniqueName("新包", listOf("abc")))
    }

    private fun zip(vararg entries: Pair<String, String>): Path {
        val file = Files.createTempFile("rdi-local-archive", ".mrpack")
        ZipOutputStream(Files.newOutputStream(file)).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}

private class CapturingTaskSubmitter : LocalModpackTaskSubmitter {
    var task: Task2? = null

    override fun submit(task: Task2, dedupeKey: String): String {
        this.task = task
        return "run-id"
    }

    suspend fun run() = checkNotNull(task).runInline(Task2Context { })
}

private object NoCatalog : ModpackArchiveCatalog {
    override suspend fun resolveCurseForgeFiles(
        refs: List<CurseForgeArchiveRef>,
    ): Result<Map<Int, ArchiveCatalogFile>> = Result.success(emptyMap())
}
