package calebxzhou.rdi.master.service.host

import java.nio.file.Files
import java.util.Properties
import java.util.UUID
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.exception.RequestError
import org.bson.types.ObjectId
import calebxzhou.rdi.master.service.host.HostInstallService.writeServerProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HostInstallServiceTest {
    private fun testHost(): Host = Host(
        _id = ObjectId("696312b0e61232912c744968"),
        name = "TestHost",
        ownerId = ObjectId(),
        modpackId = ObjectId(),
        port = 25565,
        difficulty = 1,
        gameMode = 0,
        levelType = "default",
        members = emptyList(),
    )

    @Test
    fun resolveRequiredBaseWorldId_uses_exact_required_binding_only() {
        val modpackId = ObjectId()
        val requiredId = UUID.randomUUID()
        val optionalId = UUID.randomUUID()
        val exact = Modpack.Version(
            time = 1L,
            modpackId = modpackId,
            name = "1.0",
            changelog = "",
            status = Modpack.Status.OK,
            baseWorld = Modpack.Version.BaseWorldBinding(requiredId, required = true),
        )
        val newer = Modpack.Version(
            time = 2L,
            modpackId = modpackId,
            name = "2.0",
            changelog = "",
            status = Modpack.Status.OK,
            baseWorld = Modpack.Version.BaseWorldBinding(optionalId, required = true),
        )
        val optional = exact.copy(
            name = "optional",
            baseWorld = Modpack.Version.BaseWorldBinding(optionalId, required = false),
        )
        val modpack = Modpack(
            _id = modpackId,
            name = "test",
            authorId = ObjectId(),
            modloader = ModLoader.neoforge,
            mcVer = McVersion.V211,
            versions = mutableListOf(exact, newer, optional),
        )

        val exactHost = testHost().copy(packVer = "1.0", baseWorldId = null)
        assertEquals(requiredId, HostLifecycleService.resolveRequiredBaseWorldId(modpack, exactHost))
        assertEquals(
            requiredId,
            HostLifecycleService.resolveRequiredBaseWorldId(
                modpack,
                exactHost.copy(baseWorldId = UUID.randomUUID()),
            ),
        )
        assertEquals(
            requiredId,
            HostLifecycleService.resolveRequiredBaseWorldId(
                modpack,
                exactHost.copy(baseWorldId = requiredId),
            ),
        )
        assertNull(
            HostLifecycleService.resolveRequiredBaseWorldId(
                modpack,
                exactHost.copy(packVer = "optional"),
            )
        )
        val selectedGeneratedId = UUID.randomUUID()
        assertEquals(
            selectedGeneratedId,
            HostLifecycleService.resolveResetBaseWorldId(
                modpack,
                exactHost.copy(packVer = "optional", baseWorldId = selectedGeneratedId),
            ),
        )
        assertFailsWith<RequestError> {
            HostLifecycleService.resolveRequiredBaseWorldId(modpack, exactHost.copy(packVer = "missing"))
        }
    }

    @Test
    fun `v2 install cleanup preserves only exact root world`() {
        val root = Files.createTempDirectory("host-v2-install").toFile()
        try {
            root.resolve("world").mkdirs()
            root.resolve("world").resolve("level.dat").writeText("keep")
            root.resolve("World_backup").mkdirs()
            root.resolve("world_nether").mkdirs()
            root.resolve("server.properties").writeText("remove")

            HostInstallService.cleanForV2Install(root)

            assertTrue(root.resolve("world/level.dat").isFile)
            assertFalse(root.resolve("World_backup").exists())
            assertFalse(root.resolve("world_nether").exists())
            assertFalse(root.resolve("server.properties").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `generated reset moves existing world to recoverable sibling`() {
        val root = Files.createTempDirectory("host-generated-reset").toFile()
        try {
            val world = root.resolve("world").apply { mkdirs() }
            world.resolve("level.dat").writeText("keep")

            val backup = HostLifecycleService.moveWorldAsideForGeneratedReset(world)

            assertFalse(world.exists())
            assertTrue(backup?.resolve("level.dat")?.isFile == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `v2 archive filter is root-only and case-sensitive`() {
        assertTrue(calebxzhou.rdi.master.service.ModpackService.shouldSkipRootWorld("world"))
        assertTrue(calebxzhou.rdi.master.service.ModpackService.shouldSkipRootWorld("world/level.dat"))
        assertFalse(calebxzhou.rdi.master.service.ModpackService.shouldSkipRootWorld("World/level.dat"))
        assertFalse(calebxzhou.rdi.master.service.ModpackService.shouldSkipRootWorld("world_nether/level.dat"))
        assertFalse(calebxzhou.rdi.master.service.ModpackService.shouldSkipRootWorld("abc/world/level.dat"))
    }

    @Test
    fun `managed properties keep packaged default from changing world location`() {
        val server = Properties().apply {
            setProperty("server-port", "25565")
            setProperty("online-mode", "true")
        }
        val defaults = Properties().apply {
            setProperty("level-name", "myworld")
            setProperty("server-port", "9999")
            setProperty("max-players", "8")
        }
        HostInstallService.applyDefaultServerProperties(server, defaults)
        assertTrue(server.getProperty("level-name") == "world")
        assertTrue(server.getProperty("server-port") == "25565")
        assertTrue(server.getProperty("max-players") == HostMemberService.MAX_MEMBERS.toString())
    }

    @Test
    fun `strict deletion removes dangling symlink without following it`() {
        val root = Files.createTempDirectory("host-v2-link").toFile()
        try {
            val link = root.resolve("dangling").toPath()
            Files.createSymbolicLink(link, root.resolve("missing-target").toPath())
            HostInstallService.deleteStrictNoSymlink(link.toFile())
            assertFalse(Files.exists(link))
            assertFalse(Files.isSymbolicLink(link))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `template properties are escaped and override defaults without raw injection`() {
        val host = Host(
            _id = ObjectId(),
            name = "properties-template",
            ownerId = ObjectId(),
            modpackId = ObjectId(),
            port = 25565,
            difficulty = 2,
            gameMode = 0,
            levelType = "custom\nlevel-name=evil",
            baseWorldId = UUID.randomUUID(),
            generatorSettings = "{\\\"seed\\\":1}\ngenerator-settings=evil",
            version = 2,
        )
        val dir = host.dir
        try {
            dir.mkdirs()
            dir.resolve("default-server.properties").writeText("level-type=default\ngenerator-settings=default\nlevel-name=wrong\n")
            host.writeServerProperties()
            val properties = Properties().apply { dir.resolve("server.properties").inputStream().use(::load) }
            assertEquals(host.levelType, properties.getProperty("level-type"))
            assertEquals(host.generatorSettings, properties.getProperty("generator-settings"))
            assertEquals("world", properties.getProperty("level-name"))
            assertEquals(null, properties.getProperty("evil"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `template null and empty generator settings are preserved without defaults`() {
        val base = Host(
            _id = ObjectId(),
            name = "properties-empty",
            ownerId = ObjectId(),
            modpackId = ObjectId(),
            port = 25565,
            difficulty = 2,
            gameMode = 0,
            levelType = "flat",
            baseWorldId = UUID.randomUUID(),
            version = 2,
        )
        val dir = base.dir
        try {
            dir.mkdirs()
            base.writeServerProperties()
            val nullSettings = Properties().apply { dir.resolve("server.properties").inputStream().use(::load) }
            assertEquals("{}", nullSettings.getProperty("generator-settings"))
            dir.deleteRecursively()
            val emptySettings = base.copy(generatorSettings = "")
            emptySettings.dir.mkdirs()
            emptySettings.writeServerProperties()
            val empty = Properties().apply { emptySettings.dir.resolve("server.properties").inputStream().use(::load) }
            assertEquals("", empty.getProperty("generator-settings"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `no-template defaults preserve existing level properties`() {
        val host = Host(
            _id = ObjectId(),
            name = "properties-default",
            ownerId = ObjectId(),
            modpackId = ObjectId(),
            port = 25565,
            difficulty = 2,
            gameMode = 0,
            levelType = "normal",
            version = 2,
        )
        val dir = host.dir
        try {
            dir.mkdirs()
            dir.resolve("default-server.properties").writeText("level-type=flat\ngenerator-settings=default\n")
            host.writeServerProperties()
            val properties = Properties().apply { dir.resolve("server.properties").inputStream().use(::load) }
            assertEquals("flat", properties.getProperty("level-type"))
            assertEquals("default", properties.getProperty("generator-settings"))
            assertEquals("world", properties.getProperty("level-name"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `old host create payload decodes without template id`() {
        val payload = """
            {"name":"room","modpackId":"696312b0e61232912c744968","packVer":"latest","difficulty":2,"gameMode":0,"levelType":"normal","allowCheats":false,"whitelist":false,"gameRules":{}}
        """.trimIndent()
        val decoded = serdesJson.decodeFromString<Host.CreateDto>(payload)
        assertEquals(null, decoded.baseWorldId)
    }
}
