package calebxzhou.rdi.client.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameWindowServiceTest {
    @TempDir
    lateinit var root: Path

    private fun version(): Path = Files.createDirectories(root.resolve("mc/versions/中文 pack"))

    private fun TestScope.service(download: suspend (String) -> ByteArray = { error("Unexpected download") }) =
        GameWindowService(launcherDir = { root }, download = download, ioDispatcher = StandardTestDispatcher(testScheduler))

    private fun png(pixel: Int = 0x8000ff00.toInt()): ByteArray {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, pixel)
        return ByteArrayOutputStream().use { output ->
            assertTrue(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    @Test
    fun `valid pack icon is reused unchanged without a download`() = runTest {
        val icon = version().resolve("icon.png")
        val original = png()
        Files.write(icon, original)
        assertEquals(icon, service().prepareIcon(icon.parent, "https://example.test/new.png").getOrThrow())
        assertContentEquals(original, Files.readAllBytes(icon))
    }

    @Test
    fun `no local icon and no URL quietly uses default icon`() = runTest {
        assertNull(service().prepareIcon(version(), null).getOrThrow())
    }

    @Test
    fun `download creates transparent PNG in version directory and sends unquoted absolute path`() = runTest {
        val dir = version()
        val arguments = service { png() }.prepareJvmArgs(dir, "整合包", "朋友的房间", "https://example.test/icon", { throw it }, versionName = "1.2.3")
        assertEquals(listOf("-Drdi.window.title=整合包 1.2.3 · 朋友的房间", "-Drdi.window.icon=${dir.resolve("icon.png")}"), arguments)
        assertEquals(0x8000ff00.toInt(), ImageIO.read(dir.resolve("icon.png").toFile()).getRGB(0, 0))
    }

    @Test
    fun `corrupt icon is archived only after replacement is ready`() = runTest {
        val dir = version()
        val original = "broken image".toByteArray()
        Files.write(dir.resolve("icon.png"), original)
        val arguments = service { png() }.prepareJvmArgs(dir, "整合包", null, "https://example.test/icon", { throw it })
        assertEquals(2, arguments.size)
        val archives = Files.walk(root.resolve("DEL")).use { paths -> paths.filter(Files::isRegularFile).toList() }
        val archived = archives.single()
        assertTrue(archived.endsWith(root.relativize(dir.resolve("icon.png"))))
        assertContentEquals(original, Files.readAllBytes(archived))
        assertNotNull(ImageIO.read(dir.resolve("icon.png").toFile()))
    }

    @Test
    fun `download failure preserves corrupt original and yields launchable title arguments`() = runTest {
        val dir = version()
        val original = "broken image".toByteArray()
        Files.write(dir.resolve("icon.png"), original)
        val errors = mutableListOf<Throwable>()
        val arguments = service { throw IOException("HTTP503") }.prepareJvmArgs(dir, "包", "房间", "https://example.test/icon", errors::add)
        assertEquals(listOf("-Drdi.window.title=包 · 房间"), arguments)
        assertEquals("HTTP503", errors.single().message)
        assertContentEquals(original, Files.readAllBytes(dir.resolve("icon.png")))
        assertFalse(Files.exists(root.resolve("DEL")))
    }

    @Test
    fun `download timeout is nonfatal and bounded to three seconds`() = runTest {
        val errors = mutableListOf<Throwable>()
        val arguments = service { delay(10_000); png() }.prepareJvmArgs(version(), "包", null, "https://example.test/icon", errors::add)
        assertEquals(listOf("-Drdi.window.title=包"), arguments)
        assertEquals(3_000, testScheduler.currentTime)
        assertTrue(errors.single().message!!.contains("超时"))
    }

    @Test
    fun `undecodable download does not publish an icon or prevent launch`() = runTest {
        val dir = version()
        val errors = mutableListOf<Throwable>()
        val arguments = service { byteArrayOf(1, 2, 3) }.prepareJvmArgs(dir, "包", null, "https://example.test/icon", errors::add)
        assertEquals(listOf("-Drdi.window.title=包"), arguments)
        assertEquals(1, errors.size)
        assertFalse(Files.exists(dir.resolve("icon.png")))
    }

    @Test
    fun `directory at icon path is preserved and does not prevent launch`() = runTest {
        val dir = version()
        Files.createDirectory(dir.resolve("icon.png"))
        val errors = mutableListOf<Throwable>()
        val arguments = service().prepareJvmArgs(dir, "包", null, "https://example.test/icon", errors::add)
        assertEquals(listOf("-Drdi.window.title=包"), arguments)
        assertEquals(1, errors.size)
        assertTrue(Files.isDirectory(dir.resolve("icon.png")))
    }

    @Test
    fun `archive failure preserves corrupt icon and still yields title`() = runTest {
        val dir = version()
        val original = "broken image".toByteArray()
        Files.write(dir.resolve("icon.png"), original)
        Files.writeString(root.resolve("DEL"), "blocked")
        val errors = mutableListOf<Throwable>()
        val arguments = service { png() }.prepareJvmArgs(dir, "包", null, "https://example.test/icon", errors::add)
        assertEquals(listOf("-Drdi.window.title=包"), arguments)
        assertEquals(1, errors.size)
        assertContentEquals(original, Files.readAllBytes(dir.resolve("icon.png")))
        assertEquals(listOf("icon.png"), Files.list(dir).use { paths -> paths.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `missing version directory makes write failure nonfatal`() = runTest {
        val errors = mutableListOf<Throwable>()
        val arguments = service { png() }.prepareJvmArgs(root.resolve("missing"), "包", null, "https://example.test/icon", errors::add)
        assertEquals(listOf("-Drdi.window.title=包"), arguments)
        assertEquals(1, errors.size)
    }

    @Test
    fun `corrupt local icon without URL is reported`() = runTest {
        val dir = version()
        Files.writeString(dir.resolve("icon.png"), "broken")
        assertTrue(service().prepareIcon(dir, null).isFailure)
    }

    @Test
    fun `cancellation is propagated instead of becoming an icon warning`() = runTest {
        assertFailsWith<CancellationException> {
            service { throw CancellationException("user cancelled") }.prepareIcon(version(), "https://example.test/icon")
        }
    }

    @Test
    fun `concurrent launches of same version share one published icon`() = runTest {
        var downloads = 0
        val service = service { downloads++; delay(100); png() }
        val dir = version()
        val first = async { service.prepareIcon(dir, "https://example.test/icon").getOrThrow() }
        val second = async { service.prepareIcon(dir, "https://example.test/icon").getOrThrow() }
        assertEquals(first.await(), second.await())
        assertEquals(1, downloads)
        assertFalse(Files.exists(root.resolve("DEL")))
    }

    @Test
    fun `title keeps both names within UTF16 limit without splitting emoji`() {
        assertEquals("整合包 · 房间", gameWindowTitle("整合包", "房间"))
        assertEquals("整合包", gameWindowTitle("整合包", null))
        assertEquals("整合包 1.2.3", gameWindowTitle("整合包", null, "1.2.3"))
        assertEquals("整合包 1.2.3 · 房间", gameWindowTitle("整合包", "房间", "1.2.3"))
        assertEquals("整合包", gameWindowTitle("整合包", null, " "))
        assertEquals("Minecraft", gameWindowTitle("", null))
        assertEquals("包 名", gameWindowTitle("包\u0000名", null))
        for ((pack, host) in listOf("包" to "😀".repeat(70), "😀".repeat(70) to "房间", "😀".repeat(70) to "😀".repeat(70))) {
            val title = gameWindowTitle(pack, host, "😀".repeat(40))
            assertTrue(title.length <= 64)
            assertTrue(title.contains(" · "))
            assertTrue(title.contains("…"))
            for (index in title.indices) {
                if (Character.isHighSurrogate(title[index])) assertTrue(index + 1 < title.length && Character.isLowSurrogate(title[index + 1]))
                if (Character.isLowSurrogate(title[index])) assertTrue(index > 0 && Character.isHighSurrogate(title[index - 1]))
            }
        }
        assertEquals("界".repeat(64), gameWindowTitle("界".repeat(64), null))
    }

    @Test
    fun `only owned window properties are filtered including valueless flags`() {
        for (argument in listOf("-Drdi.window.icon=old.png", "-Drdi.window.title=Old", "-Drdi.window.icon", "-Drdi.window.title")) {
            assertTrue(isGameWindowJvmArg(argument))
        }
        assertFalse(isGameWindowJvmArg("-Drdi.window.title.extra=keep"))
        assertFalse(isGameWindowJvmArg("-Xmx8G"))
    }
}
