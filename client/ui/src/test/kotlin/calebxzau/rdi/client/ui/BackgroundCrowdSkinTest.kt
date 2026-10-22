package calebxzau.rdi.client.ui

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackgroundCrowdSkinTest {
    private fun bitmap(width: Int, height: Int, slim: Boolean = false) = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).let { image ->
        for (y in 0 until height) for (x in 0 until width) {
            val sx = x * 64 / width
            val sy = y * 64 / width
            val transparent = slim && ((sx == 54 && sy in 20..31) || (sx == 46 && sy in 52..63))
            image.setRGB(x, y, if (transparent) 0 else 0xff336699.toInt())
        }
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        decodeImageBitmap(bytes).getOrThrow()
    }

    @Test
    fun normalizesHdPixelsAndDetectsSlimArms() {
        val skin = bitmap(128, 128, slim = true).toBackgroundCrowdSkin()
        assertEquals(64, skin.width)
        assertEquals(64, skin.height)
        assertEquals(64 * 64 * 4, skin.rgba.size)
        assertEquals(listOf(0x33, 0x66, 0x99, 0xff), skin.rgba.take(4).map { it.toInt() and 255 })
        assertTrue(skin.slim)
    }

    @Test
    fun preservesLegacyAspectRatioAndClassicArms() {
        val skin = bitmap(128, 64).toBackgroundCrowdSkin()
        assertEquals(64, skin.width)
        assertEquals(32, skin.height)
        assertFalse(skin.slim)
    }

    @Test
    fun rejectsUnrelatedImageDimensions() {
        assertFailsWith<IllegalArgumentException> { bitmap(64, 48).toBackgroundCrowdSkin() }
        assertFailsWith<IllegalArgumentException> { bitmap(32, 32).toBackgroundCrowdSkin() }
    }
}
