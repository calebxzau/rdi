package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import calebxzau.rdi.client.ui.RTheme
import calebxzau.rdi.client.ui.comp.NameInitialIcon
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val SCALE = 2f

/** CPU光栅化跑玻璃shader很慢，整个测试类只渲染一次。 */
private val sharedRender by lazy { renderTiles() }

class IconTileCardTest {
    @Test
    fun backdropTileCastsShadowBelowItself() {
        val render = sharedRender
        val tile = render.bounds.getValue("running")
        val belowTile = render.pixel(tile.center.x, tile.bottom + 4f * SCALE)
        // 背景是横向渐变，同一列顶部的像素就是未被投影覆盖的原背景
        val background = render.pixel(tile.center.x, 2f)

        assertTrue(
            belowTile.luminance() < background.luminance() - 6,
            "瓷砖下方应有投影，下方=${belowTile.hex()}，背景=${background.hex()}",
        )
    }

    @Test
    fun selectionFrameShowsOnlyAroundHoveredTile() {
        val render = sharedRender

        val hovered = render.selectionPixel("hovered")
        // 第二张瓷砖左外沿会落在悬停瓷砖的选中框上，用第三张
        val idle = render.selectionPixel("dimmed")

        assertTrue(hovered.isCyan(), "悬停瓷砖外沿应有青色选中框，实际=${hovered.hex()}")
        assertTrue(!idle.isCyan(), "未悬停瓷砖外沿不应有选中框，实际=${idle.hex()}")
    }

    @Test
    fun selectionFrameGlowsOutsideItsStroke() {
        val render = sharedRender
        val tile = render.bounds.getValue("hovered")
        // 描边外缘在8dp处，再往外2dp只剩光晕
        val x = tile.left - 10f * SCALE
        val glow = render.pixel(x, tile.center.y)
        // 背景是横向渐变，同一列顶部的像素就是原背景
        val background = render.pixel(x, 2f)

        assertTrue(
            glow.blue() >= background.blue() + 30,
            "选中框外侧应有青色光晕，光晕处=${glow.hex()}，背景=${background.hex()}",
        )
    }

    @Test
    fun backdropRimFollowsWhatIsBehindIt() {
        val render = sharedRender

        val diff = render.rimPixel("hovered").distance(render.rimPixel("dimmed"))

        assertTrue(diff >= 40, "背景上的玻璃框应折射各自身后的背景，差异=$diff")
    }
}

/** 红到蓝的背景上放三张单独折射背景的瓷砖（带版本角标），第一张处于悬停状态，提示框显示在它下方。结果另存为预览图。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderTiles(): TileRender {
    val bounds = mutableMapOf<String, Rect>()
    val scene = ImageComposeScene(width = 960, height = 560, density = Density(SCALE)) {
        RTheme {
            val hazeState = rememberHazeState()
            CardHintHost(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE53935), Color(0xFF1E88E5))))
                )
                TileRow(hazeState = hazeState, bounds = bounds, modifier = Modifier.fillMaxSize())
            }
        }
    }
    try {
        scene.render(0)
        scene.sendPointerEvent(PointerEventType.Move, bounds.getValue("hovered").center)
        // 帧时间跳跃推进悬停动画，少渲染几帧
        var image: Image = scene.render(0)
        for (millis in listOf(100L, 300L, 600L, 1000L, 1500L)) image = scene.render(millis * 1_000_000L)
        previewDir.mkdirs()
        previewDir.resolve("icon-tile.png").writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        return TileRender(Bitmap.makeFromImage(image), bounds)
    } finally {
        scene.close()
    }
}

/** 测试的工作目录是run/，预览图写到模块自己的build/下。 */
private val previewDir: File
    get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { it.resolve("build.gradle.kts").isFile }
        .resolve("build/tile-preview")

@Composable
private fun TileRow(hazeState: HazeState, bounds: MutableMap<String, Rect>, modifier: Modifier) {
    Row(modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(TILE_GRID_SPACING)) {
        listOf(
            Triple("hovered", "机械动力", null),
            Triple("running", "幻想乡", TILE_ACTIVE_DOT_COLOR),
            Triple("dimmed", "暂不可玩", null),
        ).forEach { (key, title, dot) ->
            Box(
                Modifier
                    .width(120.dp)
                    .onGloballyPositioned { bounds[key] = it.boundsInRoot() }
            ) {
                IconTileCard(
                    hint = CardHint(
                        title = title,
                        detail = "A-TM10",
                        detailVersion = "6.6",
                        actions = listOf(
                            CardHintAction(HintMouseButton.Left, "开玩"),
                            CardHintAction(HintMouseButton.Right, "详情 设置"),
                        ),
                    ),
                    statusDotColor = dot,
                    dimmed = key == "dimmed",
                    glass = TileGlass.Backdrop(hazeState),
                    onPrimaryClick = {},
                ) {
                    NameInitialIcon(title, Modifier.fillMaxSize().clip(TileIconShape))
                    TileVersionBadge("1.2.0-beta", Modifier.align(Alignment.BottomEnd))
                }
            }
        }
    }
}

private class TileRender(val bitmap: Bitmap, val bounds: Map<String, Rect>) {
    fun pixel(x: Float, y: Float): Int = bitmap.getColor(x.toInt(), y.toInt())

    /** 瓷砖左侧外沿中段，选中框描边的位置（外扩5dp，描边宽3dp）。 */
    fun selectionPixel(key: String): Int {
        val tile = bounds.getValue(key)
        return pixel(tile.left - 6.5f * SCALE, tile.center.y)
    }

    /** 左侧玻璃框中段：避开高光最强的上下沿。 */
    fun rimPixel(key: String): Int {
        val tile = bounds.getValue(key)
        return pixel(tile.left + 4.5f * SCALE, tile.center.y)
    }
}

private fun Int.channels() = listOf((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

private fun Int.luminance(): Int = channels().let { (r, g, b) -> (r * 299 + g * 587 + b * 114) / 1000 }

private fun Int.distance(other: Int): Int = channels().zip(other.channels()).sumOf { (a, b) -> abs(a - b) }

private fun Int.isCyan(): Boolean = channels().let { (r, g, b) -> g > 180 && b > 200 && r < 190 }

private fun Int.blue(): Int = this and 0xFF

private fun Int.hex(): String = "#%06X".format(this and 0xFFFFFF)
