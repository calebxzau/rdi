package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.archive.TarZstArchiveWriter
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.master.service.host.HostQueryService
import calebxzhou.rdi.master.service.host.HostControlService
import calebxzhou.rdi.master.service.host.HostControlService.status
import calebxzhou.rdi.master.service.host.dir
import calebxzhou.rdi.master.service.ModpackService.deleteModpack
import calebxzhou.rdi.master.service.ModpackService.deleteVersion
import calebxzhou.rdi.master.service.ModpackService.installToHost
import com.mongodb.kotlin.client.coroutine.MongoCollection
import com.mongodb.kotlin.client.coroutine.FindFlow
import com.mongodb.client.model.DeleteOptions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.FlowCollector
import org.bson.conversions.Bson
import com.mongodb.client.result.UpdateResult
import com.mongodb.client.model.UpdateOptions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModpackServiceInstallDeleteTest {
    private lateinit var collection: MongoCollection<Modpack>

    @BeforeTest
    fun setup() {
        collection = mockk(relaxed = true)
        ModpackService.testDbcl = collection
        mockkObject(HostQueryService)
        mockkObject(HostControlService)
    }

    @AfterTest
    fun cleanup() {
        unmockkObject(HostQueryService)
        unmockkObject(HostControlService)
        ModpackService.testDbcl = null
        ModpackService.testGameLibsDirOverride = null
    }

    @Test
    fun `delete modpack removes files and mongo document when no host uses it`() = runTest {
        val owner = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(owner._id)
        pack.dir.mkdirs()
        pack.dir.resolve("marker.txt").writeText("test")
        coEvery { HostQueryService.findByModpack(pack._id) } returns emptyList()
        coEvery { collection.deleteOne(any<Bson>(), any<DeleteOptions>()) } returns mockk(relaxed = true)

        ModpackContext(owner, pack, null).deleteModpack()

        assertFalse(pack.dir.exists())
        coVerify(exactly = 1) { collection.deleteOne(any<Bson>(), any<DeleteOptions>()) }
    }

    @Test
    fun `delete modpack rejects any host and includes its name`() = runTest {
        val owner = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(owner._id)
        val host = mockk<Host>()
        coEvery { HostQueryService.findByModpack(pack._id) } returns listOf(host)
        io.mockk.every { host.name } returns "正在运行的房间"

        val error = assertFailsWith<Exception> { ModpackContext(owner, pack, null).deleteModpack() }
        assertTrue(error.message.orEmpty().contains("正在运行的房间"))
        coVerify(exactly = 0) { collection.deleteOne(any<Bson>(), any<DeleteOptions>()) }
    }

    @Test
    fun `stopped hosts also block modpack deletion`() = runTest {
        val owner = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(owner._id)
        val host = mockk<Host>()
        io.mockk.every { host.name } returns "已停止的房间"
        coEvery { HostQueryService.findByModpack(pack._id) } returns listOf(host)

        val error = assertFailsWith<Exception> { ModpackContext(owner, pack, null).deleteModpack() }

        assertTrue(error.message.orEmpty().contains("已停止的房间"))
        coVerify(exactly = 0) { collection.deleteOne(any<Bson>(), any<DeleteOptions>()) }
    }

    @Test
    fun `install extracts server files and filters client assets`() = runTest {
        val pack = ModpackServiceTestFixtures.modpack()
        val disabledMod = ModpackServiceTestFixtures.mod("disabled")
        val version = ModpackServiceTestFixtures.version(pack, "1.0.0", mods = mutableListOf(disabledMod))
        val host = Host(
            name = "test-host",
            ownerId = pack.authorId,
            modpackId = pack._id,
            port = 25565,
            difficulty = 2,
            gameMode = 0,
            levelType = "default",
            disabledMods = listOf(disabledMod)
        )
        val libsRoot = ModpackServiceTestFixtures.tempRoot("install-libs")
        try {
            version.storageDir.mkdirs()
            TarZstArchiveWriter(version.zstdPack).use { writer ->
                writer.addFile("server/config/keep.properties", byteArrayOf(1))
                writer.addFile("server/mods/keep.jar", byteArrayOf(1))
                writer.addFile("server/mods/${CLIENT_ONLY_MARK_PREFIX}client.jar", byteArrayOf(1))
                writer.addFile("server/mods/example-rgp-client.jar", byteArrayOf(1))
                writer.addFile("server/mods/I18nUpdateMod.jar", byteArrayOf(1))
                writer.addFile("server/mods/${disabledMod.fileName}", byteArrayOf(1))
                writer.addFile("server/resourcepacks/image.png", byteArrayOf(1))
                writer.addFile("server/sounds/music.ogg", byteArrayOf(1))
            }
            pack.versions.clear()
            pack.versions += version
            stubPack(pack)
            ModpackService.testGameLibsDirOverride = libsRoot
            pack.installToHost("1.0.0", host) { }
            assertTrue(host.dir.resolve("config/keep.properties").exists())
            assertTrue(host.dir.resolve("mods/keep.jar").exists())
            assertFalse(host.dir.resolve("mods/${CLIENT_ONLY_MARK_PREFIX}client.jar").exists())
            assertFalse(host.dir.resolve("mods/example-rgp-client.jar").exists())
            assertFalse(host.dir.resolve("mods/I18nUpdateMod.jar").exists())
            assertFalse(host.dir.resolve("mods/${disabledMod.fileName}").exists())
            assertFalse(host.dir.resolve("resourcepacks/image.png").exists())
            assertFalse(host.dir.resolve("sounds/music.ogg").exists())
        } finally {
            host.dir.deleteRecursivelyNoSymlink()
            pack.dir.deleteRecursivelyNoSymlink()
            ModpackService.testGameLibsDirOverride = null
            libsRoot.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `v2 install preserves existing root world and missing libs fails`() = runTest {
        val pack = ModpackServiceTestFixtures.modpack()
        val version = ModpackServiceTestFixtures.version(pack, "v2-world")
        val host = Host(
            name = "v2-host",
            ownerId = pack.authorId,
            modpackId = pack._id,
            port = 25567,
            difficulty = 2,
            gameMode = 0,
            levelType = "default"
        )
        val field = Host::class.java.getDeclaredField("version").apply { isAccessible = true }
        field.set(host, 2)
        val libsRoot = ModpackServiceTestFixtures.tempRoot("missing-libs")
        try {
            version.storageDir.mkdirs()
            TarZstArchiveWriter(version.zstdPack).use { writer ->
                writer.addFile("server/world/level.dat", byteArrayOf(2))
                writer.addFile("server/config/allowed.properties", byteArrayOf(1))
            }
            host.dir.resolve("world").mkdirs()
            host.dir.resolve("world/keep.dat").writeText("keep")
            pack.versions.clear()
            pack.versions += version
            stubPack(pack)
            val missingLibs = libsRoot.resolve("not-present")
            ModpackService.testGameLibsDirOverride = missingLibs
            assertFailsWith<Exception> { pack.installToHost("v2-world", host) { } }
            assertTrue(host.dir.resolve("world/keep.dat").exists())
            assertTrue(host.dir.resolve("config/allowed.properties").exists())
            assertFalse(host.dir.resolve("world/level.dat").exists())
        } finally {
            host.dir.deleteRecursivelyNoSymlink()
            pack.dir.deleteRecursivelyNoSymlink()
            libsRoot.deleteRecursivelyNoSymlink()
            ModpackService.testGameLibsDirOverride = null
        }
    }

    @Test
    fun `install rejects non-ready and missing archives`() = runTest {
        val pack = ModpackServiceTestFixtures.modpack()
        val waiting = ModpackServiceTestFixtures.version(pack, "waiting", Modpack.Status.WAIT)
        val missing = ModpackServiceTestFixtures.version(pack, "missing", Modpack.Status.OK)
        val host = Host(name = "test-host", ownerId = pack.authorId, modpackId = pack._id, port = 25566, difficulty = 2, gameMode = 0, levelType = "default")
        try {
            pack.versions.clear()
            pack.versions += waiting
            stubPack(pack)
            assertFailsWith<Exception> { pack.installToHost("waiting", host) { } }
            pack.versions.clear()
            pack.versions += missing
            stubPack(pack)
            assertFailsWith<Exception> { pack.installToHost("missing", host) { } }
        } finally {
            host.dir.deleteRecursivelyNoSymlink()
            pack.dir.deleteRecursivelyNoSymlink()
        }
    }

    @Test
    fun `delete version blocks running hosts and cleans every archive and build dir`() = runTest {
        val owner = ModpackServiceTestFixtures.account()
        val pack = ModpackServiceTestFixtures.modpack(owner._id)
        val version = ModpackServiceTestFixtures.version(pack, "1.0.0")
        pack.versions += version
        stubPack(pack)
        val host = mockk<Host>()
        io.mockk.every { host.status } returns HostStatus.STARTED
        coEvery { HostQueryService.findByModpackVersion(pack._id, version.name) } returns listOf(host)
        assertFailsWith<Exception> { ModpackContext(owner, pack, version).deleteVersion() }

        io.mockk.every { host.status } returns HostStatus.STOPPED
        coEvery { HostQueryService.findByModpackVersion(pack._id, version.name) } returns emptyList()
        val deleteResult = mockk<UpdateResult>()
        every { deleteResult.matchedCount } returns 1L
        every { deleteResult.modifiedCount } returns 1L
        coEvery { collection.updateOne(any<Bson>(), any<Bson>(), any<UpdateOptions>()) } returns deleteResult
        version.storageDir.mkdirs()
        listOf(version.zip, version.zstdPack, version.clientZip, version.clientZstdPack).forEach { it.writeBytes(byteArrayOf(1)) }
        val buildDir = version.tempDir("cleanup").apply { mkdirs(); resolve("tmp").writeText("x") }
        try {
            ModpackContext(owner, pack, version).deleteVersion()
            assertFalse(version.zip.exists())
            assertFalse(version.zstdPack.exists())
            assertFalse(version.clientZip.exists())
            assertFalse(version.clientZstdPack.exists())
            assertFalse(buildDir.exists())
            coVerify(exactly = 1) { collection.updateOne(any<Bson>(), any<Bson>(), any<UpdateOptions>()) }
        } finally {
            pack.dir.deleteRecursivelyNoSymlink()
        }
    }

    private fun stubPack(pack: Modpack) {
        val flow = mockk<FindFlow<Modpack>>()
        every { collection.find(any<Bson>()) } returns flow
        every { flow.projection(any()) } returns flow
        coEvery { flow.collect(any()) } coAnswers {
            firstArg<FlowCollector<Modpack>>().emit(pack)
        }
    }
}
