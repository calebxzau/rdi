package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.ui.GlassTileGrid
import calebxzau.rdi.client.ui.RTheme
import calebxzau.rdi.client.ui.comp.NameInitialIcon
import calebxzau.rdi.client.ui.rememberGlassTileGridState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val GRID_SCALE = 2f
private const val GRID_TILES = 10

/** CPU光栅化跑玻璃shader很慢，整个测试类只渲染一次。 */
private val gridRender by lazy { renderGrid() }

class GlassTileGridSceneTest {
    @Test
    fun sharedGlassRefractsWhatIsBehindEachTile() {
        val render = gridRender
        val left = render.rimPixel(0)
        val right = render.rimPixel(GRID_TILES - 1)

        assertTrue(left.distance(right) >= 40, "左右两端瓷砖的玻璃应透出各自身后的背景，左=${left.hex()}，右=${right.hex()}")
    }

    @Test
    fun sharedGlassLinesUpWithTiles() {
        val render = gridRender
        val tile = render.bounds.getValue(0)
        val outside = render.pixel(tile.left - 3f * GRID_SCALE, tile.center.y)
        val rawBackground = render.pixel(tile.left - 3f * GRID_SCALE, 2f)

        assertTrue(
            outside.distance(rawBackground) <= 30,
            "瓷砖左侧外不应有玻璃，外侧=${outside.hex()}，原背景=${rawBackground.hex()}",
        )
        assertTrue(
            render.rimPixel(0).distance(render.pixel(tile.left + 4.5f * GRID_SCALE, 2f)) >= 30,
            "瓷砖玻璃框应带白色着色，与原背景有明显差别",
        )
    }

    @Test
    fun sharedGlassCastsShadowIntoGaps() {
        val render = gridRender
        val tile = render.bounds.getValue(0)
        val belowTile = render.pixel(tile.center.x, tile.bottom + 4f * GRID_SCALE)
        val background = render.pixel(tile.center.x, 2f)

        assertTrue(
            belowTile.luminance() < background.luminance() - 4,
            "瓷砖下方应有投影，下方=${belowTile.hex()}，背景=${background.hex()}",
        )
    }
}

/** 红到蓝的背景上放一张3行共享玻璃网格，悬停第2张瓷砖；结果另存为预览图。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderGrid(): GridRender {
    val bounds = mutableMapOf<Int, Rect>()
    val scene = ImageComposeScene(width = 1400, height = 1040, density = Density(GRID_SCALE)) {
        RTheme {
            val hazeState = rememberHazeState()
            val grid = rememberGlassTileGridState()
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE53935), Color(0xFF1E88E5))))
                )
                GlassTileGrid(
                    items = List(GRID_TILES) { "房间${it + 1}" },
                    state = grid,
                    hazeState = hazeState,
                    tileSize = iconTileSize(120.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(24.dp),
                    spacing = TILE_GRID_SPACING,
                    style = TileGridGlassStyle,
                ) { index, title ->
                    Box(Modifier.onGloballyPositioned { bounds[index] = it.boundsInRoot() }) {
                        IconTileCard(
                            title = title,
                            hint = CardHint(title = title),
                            glass = TileGlass.Shared(grid, index),
                            dimmed = index >= GRID_TILES - 2,
                            onPrimaryClick = {},
                        ) {
                            NameInitialIcon(title, Modifier.fillMaxSize().clip(TileIconShape))
                        }
                    }
                }
            }
        }
    }
    try {
        scene.render(0)
        scene.sendPointerEvent(PointerEventType.Move, bounds.getValue(1).center)
        var image: Image = scene.render(0)
        for (millis in listOf(100L, 300L, 600L, 1000L, 1500L)) image = scene.render(millis * 1_000_000L)
        gridPreviewDir.mkdirs()
        gridPreviewDir.resolve("glass-grid.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        return GridRender(Bitmap.makeFromImage(image), bounds)
    } finally {
        scene.close()
    }
}

/** 测试的工作目录是run/，预览图写到模块自己的build/下。 */
private val gridPreviewDir: File
    get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { it.resolve("build.gradle.kts").isFile }
        .resolve("build/tile-preview")

private class GridRender(val bitmap: Bitmap, val bounds: Map<Int, Rect>) {
    fun pixel(x: Float, y: Float): Int = bitmap.getColor(x.toInt(), y.toInt())

    /** 左侧玻璃框中段。 */
    fun rimPixel(index: Int): Int {
        val tile = bounds.getValue(index)
        return pixel(tile.left + 4.5f * GRID_SCALE, tile.center.y)
    }
}

private fun Int.channels() = listOf((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

private fun Int.luminance(): Int = channels().let { (r, g, b) -> (r * 299 + g * 587 + b * 114) / 1000 }

private fun Int.distance(other: Int): Int = channels().zip(other.channels()).sumOf { (a, b) -> abs(a - b) }

private fun Int.hex(): String = "#%06X".format(this and 0xFFFFFF)
