package calebxzau.rdi.client.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Rect as SkRect

class LiquidGlassTest {
    private val density = Density(1f)

    @Test
    fun unspecifiedCornerRadiusResolvesToPill() {
        val uniforms = LiquidGlassStyle().resolveUniforms(Size(200f, 80f), density, 0f, Offset.Unspecified)

        assertEquals(Rect(0f, 0f, 200f, 80f), uniforms.shapeRect)
        assertEquals(40f, uniforms.cornerRadius)
        assertEquals(13f, uniforms.bevel)
        assertEquals(0f, uniforms.glowIntensity)
    }

    @Test
    fun pressShrinksShapeAroundPivot() {
        val style = LiquidGlassStyle(pressedScale = 0.9f, pressedGlow = 0.5f)

        val uniforms = style.resolveUniforms(Size(200f, 80f), density, 1f, Offset(50f, 20f))

        assertEquals(5f, uniforms.shapeRect.left, 1e-3f)
        assertEquals(2f, uniforms.shapeRect.top, 1e-3f)
        assertEquals(185f, uniforms.shapeRect.right, 1e-3f)
        assertEquals(74f, uniforms.shapeRect.bottom, 1e-3f)
        assertEquals(36f, uniforms.cornerRadius, 1e-3f)
        assertEquals(Offset(50f, 20f), uniforms.glowCenter)
        assertEquals(0.5f, uniforms.glowIntensity, 1e-6f)
    }

    @Test
    fun springOvershootIsClampedToRestingAndPressedShapes() {
        val style = LiquidGlassStyle(pressedScale = 0.9f)
        val size = Size(200f, 80f)

        val belowRest = style.resolveUniforms(size, density, -0.2f, Offset(50f, 20f))
        val beyondPressed = style.resolveUniforms(size, density, 1.3f, Offset(50f, 20f))

        assertEquals(Rect(0f, 0f, 200f, 80f), belowRest.shapeRect)
        assertEquals(185f, beyondPressed.shapeRect.right, 1e-3f)
    }

    @Test
    fun flatTopIsNotDisplaced() {
        val sample = renderCoordinateGradient(testUniforms())

        val sampled = sample.sampledPosition(100, 40)

        assertEquals(100.5f, sampled.x, 1.5f)
        assertEquals(40.5f, sampled.y, 1.5f)
    }

    @Test
    fun rimRefractsTowardCenter() {
        val sample = renderCoordinateGradient(testUniforms())

        val topRim = sample.sampledPosition(100, 1)
        val leftCap = sample.sampledPosition(1, 40)

        // 斜面20px、折射率1.5时，离边1.5px处朝中心偏移约10px
        assertEquals(100.5f, topRim.x, 1.5f)
        assertTrue(topRim.y > 8f, "顶边应向下（中心）采样，实际y=${topRim.y}")
        assertTrue(leftCap.x > 8f, "左端应向右（中心）采样，实际x=${leftCap.x}")
        assertEquals(40.5f, leftCap.y, 1.5f)
    }

    @Test
    fun displacementFadesOutTowardInnerBevel() {
        val sample = renderCoordinateGradient(testUniforms())

        val nearInnerBevel = sample.sampledPosition(100, 15)

        assertEquals(15.5f, nearInnerBevel.y, 2f)
    }

    @Test
    fun outsideShapeIsTransparent() {
        val sample = renderCoordinateGradient(testUniforms())

        assertEquals(0, sample.alpha(0, 0))
        assertEquals(0, sample.alpha(199, 79))
        assertEquals(255, sample.alpha(100, 40))
    }

    @Test
    fun shaderCoordinatesStayLayerLocalUnderCanvasTranslation() {
        val sample = renderCoordinateGradient(testUniforms(), offsetX = 37, offsetY = 11)

        val center = sample.sampledPosition(100, 40)
        val topRim = sample.sampledPosition(100, 1)

        assertEquals(40.5f, center.y, 1.5f)
        assertTrue(topRim.y > 8f, "平移后顶边仍应朝中心采样，实际y=${topRim.y}")
        assertEquals(0, sample.alpha(0, 0))
    }

    /** 无着色、无高光、无模糊，只保留几何折射。 */
    private fun testUniforms() = LiquidGlassStyle(
        bevel = 20.dp,
        frost = 0.dp,
        specularIntensity = 0f,
        hairline = Color.Transparent,
    ).resolveUniforms(Size(WIDTH.toFloat(), HEIGHT.toFloat()), density, 0f, Offset.Unspecified)

    /** 源图R、G通道分别编码x、y，读回输出即可知道每个像素采样了哪里。 */
    private fun renderCoordinateGradient(
        uniforms: LiquidGlassUniforms,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): RenderedSample {
        val pixels = ByteArray(WIDTH * HEIGHT * 4)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val i = (y * WIDTH + x) * 4
                pixels[i] = (x * 255 / (WIDTH - 1)).toByte()
                pixels[i + 1] = (y * 255 / (HEIGHT - 1)).toByte()
                pixels[i + 2] = 0
                pixels[i + 3] = 255.toByte()
            }
        }
        val source = Image.makeRaster(
            ImageInfo(WIDTH, HEIGHT, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL),
            pixels,
            WIDTH * 4,
        )
        val surface = Surface.makeRasterN32Premul(WIDTH + offsetX * 2, HEIGHT + offsetY * 2)
        surface.canvas.apply {
            translate(offsetX.toFloat(), offsetY.toFloat())
            saveLayer(
                SkRect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()),
                Paint().apply { imageFilter = uniforms.toImageFilter() },
            )
            drawImage(source, 0f, 0f)
            restore()
        }
        val bitmap = Bitmap().apply { allocN32Pixels(surface.width, surface.height) }
        surface.readPixels(bitmap, 0, 0)
        return RenderedSample(bitmap, offsetX, offsetY)
    }

    private class RenderedSample(val bitmap: Bitmap, val offsetX: Int, val offsetY: Int) {
        fun alpha(x: Int, y: Int): Int = (bitmap.getColor(x + offsetX, y + offsetY) ushr 24) and 0xFF

        /** 返回该像素采样到的源坐标（像素中心）。 */
        fun sampledPosition(x: Int, y: Int): Offset {
            val color = bitmap.getColor(x + offsetX, y + offsetY)
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            return Offset(
                red * (WIDTH - 1) / 255f + 0.5f,
                green * (HEIGHT - 1) / 255f + 0.5f,
            )
        }
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 80
    }
}
