package calebxzau.rdi.client.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Rect as SkRect

class GlassTileGridTest {
    private val tileSize = IntSize(110, 132)

    @Test
    fun layoutIsExtrapolatedFromScrolledVisibleTiles() {
        // 3行、列距130、行距144；第2列起可见，第0列在视口左侧之外
        val slots = (6..14).map { index ->
            GlassTileSlot(index, IntOffset(-244 + index / 3 * 130, 16 + index % 3 * 144), tileSize)
        }

        val layout = assertNotNull(resolveGlassTileLayout(slots, rows = 3, fallbackPitch = Offset(1f, 1f)))

        assertEquals(Offset(-244f, 16f), layout.origin)
        assertEquals(Offset(130f, 144f), layout.pitch)
        assertEquals(Size(110f, 132f), layout.tileSize)
    }

    @Test
    fun singleVisibleTileFallsBackToConfiguredPitch() {
        val slots = listOf(GlassTileSlot(4, IntOffset(300, 160), tileSize))

        val layout = assertNotNull(resolveGlassTileLayout(slots, rows = 3, fallbackPitch = Offset(122f, 144f)))

        assertEquals(Offset(122f, 144f), layout.pitch)
        assertEquals(Offset(300f - 122f, 160f - 144f), layout.origin)
    }

    @Test
    fun noVisibleTilesHasNoLayout() {
        assertNull(resolveGlassTileLayout(emptyList(), rows = 3, fallbackPitch = Offset(1f, 1f)))
    }

    @Test
    fun existingTilesAreGlassAndMissingSlotsStayEmpty() {
        val sample = renderSolidRed(testUniforms())

        assertEquals(0xFFFF0000.toInt(), sample.color(35, 40), "0号瓷砖中心应为玻璃（采样背景）")
        assertEquals(0xFFFF0000.toInt(), sample.color(95, 110), "3号瓷砖（第1列第1行）存在")
        assertTrue(sample.alpha(155, 110) < 10, "5号格超出数量，不应画玻璃，alpha=${sample.alpha(155, 110)}")
    }

    @Test
    fun gapBelowTileCarriesShadow() {
        val sample = renderSolidRed(testUniforms())

        val gap = sample.color(35, 74)
        val alpha = (gap ushr 24) and 0xFF

        assertTrue(alpha in 5..80, "瓷砖下方缝隙应有淡投影，alpha=$alpha")
        assertEquals(0, (gap shr 16) and 0xFF, "缝隙里不应透出玻璃采样的背景")
    }

    @Test
    fun pressedTileShrinksAroundItsCenter() {
        val sample = renderSolidRed(testUniforms(pressIndex = 0, pressScale = 0.8f))

        assertTrue(sample.alpha(13, 40) < 128, "按下缩小后，原左边缘内侧应不再是玻璃")
        assertEquals(0xFFFF0000.toInt(), sample.color(35, 40))
        assertEquals(0xFFFF0000.toInt(), sample.color(73, 40), "其他瓷砖不受影响")
    }

    /** 2行、3张瓷砖：0号(0,0)、1号(0,1)、2号(1,0)……无着色、无高光、无模糊，只保留几何与投影。 */
    private fun testUniforms(pressIndex: Int = NO_TILE, pressScale: Float = 1f) = GlassTileGridStyle(
        glass = LiquidGlassStyle(
            bevel = 6.dp,
            frost = 0.dp,
            cornerRadius = 10.dp,
            specularIntensity = 0f,
            hairline = Color.Transparent,
        ),
    ).resolveUniforms(
        layout = GlassTileLayout(origin = Offset(10f, 10f), pitch = Offset(60f, 70f), tileSize = Size(50f, 60f)),
        count = 4,
        rows = 2,
        density = Density(1f),
        hoverIndex = NO_TILE,
        hoverProgress = 0f,
        pressIndex = pressIndex,
        pressScale = pressScale,
    )

    private fun renderSolidRed(uniforms: GlassTileGridUniforms): RenderedSample {
        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        surface.canvas.apply {
            saveLayer(
                SkRect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()),
                Paint().apply { imageFilter = uniforms.toImageFilter() },
            )
            drawRect(SkRect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), Paint().apply { color = 0xFFFF0000.toInt() })
            restore()
        }
        val bitmap = Bitmap().apply { allocN32Pixels(WIDTH, HEIGHT) }
        surface.readPixels(bitmap, 0, 0)
        return RenderedSample(bitmap)
    }

    private class RenderedSample(val bitmap: Bitmap) {
        fun color(x: Int, y: Int): Int = bitmap.getColor(x, y)
        fun alpha(x: Int, y: Int): Int = (color(x, y) ushr 24) and 0xFF
    }

    private companion object {
        const val WIDTH = 200
        const val HEIGHT = 160
    }
}
