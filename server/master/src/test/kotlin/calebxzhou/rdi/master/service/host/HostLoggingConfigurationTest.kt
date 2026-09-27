package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import org.bson.types.ObjectId
import org.w3c.dom.Element
import java.io.InputStream
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class HostLoggingConfigurationTest {
    @Test
    fun `modern config preflight validates file and mount`() = withTempConfig { configFile ->
        configFile.writeText("<Configuration/>")
        val validated = HostContainerService.requireModernLog4j2Config(McVersion.V201, configFile)
        assertEquals(configFile, validated)

        val mount = HostContainerService.modernLog4j2Mount(validated!!)
        assertEquals(configFile.absolutePath, mount.source)
        assertEquals("/opt/rdi/mc-log4j2.xml", mount.target)
        assertEquals(true, mount.readOnly)
    }

    @Test
    fun `modern config preflight rejects missing file`() = withTempConfig { configFile ->
        assertFailsWith<RequestError> {
            HostContainerService.requireModernLog4j2Config(McVersion.V201, configFile)
        }
    }

    @Test
    fun `modern config preflight rejects directory`() = withTempConfig { configFile ->
        configFile.mkdirs()
        assertFailsWith<RequestError> {
            HostContainerService.requireModernLog4j2Config(McVersion.V211, configFile)
        }
    }

    @Test
    fun `legacy config preflight skips missing file`() = withTempConfig { configFile ->
        assertNull(HostContainerService.requireModernLog4j2Config(McVersion.V071, configFile))
    }

    @Test
    fun `bundled logging configuration caps archived files`() {
        val document = loggingResource().use { input ->
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(input)
        }
        val rollingAppender = document.getElementsByTagName("RollingRandomAccessFile").item(0) as Element
        assertEquals("logs/latest.log", rollingAppender.getAttribute("fileName"))
        assertEquals("logs/%d{yyyy-MM-dd}-%i.log.gz", rollingAppender.getAttribute("filePattern"))

        val strategy = rollingAppender.getElementsByTagName("DefaultRolloverStrategy").item(0) as Element
        assertEquals("5", strategy.getAttribute("max"))
        val delete = strategy.getElementsByTagName("Delete").item(0) as Element
        assertEquals("logs", delete.getAttribute("basePath"))
        assertEquals("1", delete.getAttribute("maxDepth"))

        val sorter = delete.getElementsByTagName("SortByModificationTime").item(0) as Element
        assertEquals("true", sorter.getAttribute("recentFirst"))
        assertEquals(0, document.getElementsByTagName("PathSortByModificationTime").length)
        val fileNameCondition = delete.getElementsByTagName("IfFileName").item(0) as Element
        assertEquals("*.log.gz", fileNameCondition.getAttribute("glob"))
        val sizeCondition = fileNameCondition.getElementsByTagName("IfAccumulatedFileSize").item(0) as Element
        assertEquals("100 MB", sizeCondition.getAttribute("exceeds"))
    }

    @Test
    fun `modern arguments enable bundled logging configuration before args file`() {
        val env = HostContainerService.run {
            testHost().containerEnv(
                McVersion.V201,
                McVersion.V201.loaderVersions.getValue(ModLoader.forge),
                testModpack(McVersion.V201, ModLoader.forge),
                null,
            )
        }
        val startParams = env.single { it.startsWith("START_PARAMS=") }
        val configurationArg = "-Dlog4j.configurationFile=/opt/rdi/mc-log4j2.xml"
        assertTrue(startParams.contains(configurationArg))
        assertTrue(startParams.indexOf(configurationArg) < startParams.indexOf("@libraries/"))
    }

    @Test
    fun `legacy arguments do not enable bundled logging configuration`() {
        val env = HostContainerService.run {
            testHost().containerEnv(
                McVersion.V071,
                McVersion.V071.loaderVersions.getValue(ModLoader.forge),
                testModpack(McVersion.V071, ModLoader.forge),
                null,
            )
        }
        assertFalse(env.single { it.startsWith("START_PARAMS=") }
            .contains("-Dlog4j.configurationFile=/opt/rdi/mc-log4j2.xml"))
    }

    private fun loggingResource(): InputStream =
        checkNotNull(javaClass.classLoader.getResourceAsStream("mc_log4j2.xml"))

    private fun withTempConfig(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("rdi-log4j2-test").toFile()
        try {
            block(root.resolve("mc-log4j2.xml"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testHost() = Host(
        name = "test-host",
        ownerId = ObjectId(),
        modpackId = ObjectId(),
        port = 25565,
        difficulty = 2,
        gameMode = 0,
        levelType = "default",
    )

    private fun testModpack(mcVersion: McVersion, loader: ModLoader) = Modpack(
        name = "test-pack",
        authorId = ObjectId(),
        modloader = loader,
        mcVer = mcVersion,
    )
}
