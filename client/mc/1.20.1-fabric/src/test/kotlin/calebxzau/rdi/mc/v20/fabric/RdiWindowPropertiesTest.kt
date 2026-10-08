package calebxzau.rdi.mc.v20.fabric

import calebxzhou.rdi.mc.common.RDI
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RdiWindowPropertiesTest {
    @TempDir
    lateinit var directory: Path

    private val savedProperties = mutableMapOf<String, String?>()
    private val titles = mutableListOf<String>()
    private val icons = mutableListOf<Int>()
    private val errors = mutableListOf<Pair<String, Throwable>>()

    @BeforeTest
    fun prepareProperties() {
        for (key in listOf("rdi.play", "rdi.window.icon", "rdi.window.title")) {
            savedProperties[key] = System.getProperty(key)
            System.clearProperty(key)
        }
        val play = "http://localhost\nlocalhost\n测试房间\n25565\n00000000-0000-0000-0000-000000000001\nTestPlayer"
        System.setProperty("rdi.play", Base64.getEncoder().encodeToString(play.toByteArray(Charsets.UTF_8)))
    }

    @AfterTest
    fun restoreProperties() {
        for ((key, value) in savedProperties) {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @Test
    fun `absent properties preserve defaults without errors`() {
        applyProperties()
        assertTrue(titles.isEmpty())
        assertTrue(icons.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `valid title preserves whitespace and accepts exactly 64 characters`() {
        for (title in listOf(" 我的RDI房间 ", " ", "界".repeat(64))) {
            System.setProperty("rdi.window.title", title)
            applyProperties()
            assertEquals(title, titles.last())
        }
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `empty and oversized titles log errors without changing title`() {
        for (title in listOf("", "界".repeat(65))) {
            System.setProperty("rdi.window.title", title)
            applyProperties()
        }
        assertTrue(titles.isEmpty())
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.first.contains("rdi.window.title") })
    }

    @Test
    fun `transparent PNG is decoded with alpha and both settings are applied`() {
        val pixel = 0x7f12ab34
        System.setProperty("rdi.window.icon", writePng(pixel).toString())
        System.setProperty("rdi.window.title", "测试房间")
        applyProperties()
        assertEquals(listOf(pixel), icons)
        assertEquals(listOf("测试房间"), titles)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `undecodable and truncated images log errors and still apply title`() {
        val textFile = directory.resolve("not-an-image.png")
        Files.writeString(textFile, "not an image")
        val truncatedPng = directory.resolve("truncated.png")
        Files.write(truncatedPng, byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        System.setProperty("rdi.window.title", "有效标题")
        for (path in listOf(textFile, truncatedPng)) {
            System.setProperty("rdi.window.icon", path.toString())
            applyProperties()
        }
        assertTrue(icons.isEmpty())
        assertEquals(listOf("有效标题", "有效标题"), titles)
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.first.contains("rdi.window.icon") })
    }

    @Test
    fun `missing empty and invalid paths log errors and still apply title`() {
        System.setProperty("rdi.window.title", "有效标题")
        for (path in listOf(directory.resolve("missing.png").toString(), "", "bad\u0000path")) {
            System.setProperty("rdi.window.icon", path)
            applyProperties()
        }
        assertTrue(icons.isEmpty())
        assertEquals(3, errors.size)
        assertEquals(List(3) { "有效标题" }, titles)
    }

    @Test
    fun `invalid title does not prevent a valid icon`() {
        System.setProperty("rdi.window.icon", writePng(0xffaabbcc.toInt()).toString())
        System.setProperty("rdi.window.title", "")
        applyProperties()
        assertEquals(listOf(0xffaabbcc.toInt()), icons)
        assertTrue(titles.isEmpty())
        assertEquals(1, errors.size)
        assertTrue(errors.single().first.contains("rdi.window.title"))
    }

    private fun writePng(pixel: Int): Path {
        val path = directory.resolve("图标 with spaces.png")
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, pixel)
        assertTrue(ImageIO.write(image, "png", path.toFile()))
        return path
    }

    private fun applyProperties() {
        RDI.applyWindowProperties(
            { image -> icons.add(image.getRGB(0, 0)) },
            { title -> titles.add(title) },
            { message, error -> errors.add(message to assertNotNull(error)) }
        )
    }
}
