package calebxzau.rdi.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val BUTTON_SCALE = 2f
private const val BUTTON_SCENE_HEIGHT = 300

/** CPU光栅化跑玻璃较慢，整个测试类只渲染一次。 */
private val buttonRender by lazy { renderButtons() }

class GlassIconButtonTest {
    @Test
    fun pillAndCircleButtonsAreGlass() {
        val render = buttonRender
        listOf("pill", "circle").forEach { key ->
            val bounds = render.bounds.getValue(key)
            // 左缘内侧4dp：避开图标与文字，只看玻璃本身
            val x = bounds.left + 4f * BUTTON_SCALE
            val glass = render.pixel(x, bounds.center.y)
            val raw = render.rawBackground(x)

            assertTrue(glass.distance(raw) >= 30, "$key 按钮应是带着色的玻璃，按钮=${glass.hex()}，原背景=${raw.hex()}")
        }
    }

    @Test
    fun glassStaysInsideButton() {
        val render = buttonRender
        val bounds = render.bounds.getValue("pill")
        val x = bounds.left - 8f * BUTTON_SCALE
        val outside = render.pixel(x, bounds.center.y)

        assertTrue(
            outside.distance(render.rawBackground(x)) <= 30,
            "按钮外不应有玻璃，按钮外=${outside.hex()}，原背景=${render.rawBackground(x).hex()}",
        )
    }
}

/** 红到蓝的背景上放导航栏与三种玻璃按钮（胶囊、圆形、禁用），另存为预览图便于对比两者外观。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderButtons(): ButtonRender {
    val bounds = mutableMapOf<String, Rect>()
    val scene = ImageComposeScene(width = 1200, height = BUTTON_SCENE_HEIGHT, density = Density(BUTTON_SCALE)) {
        RTheme {
            val hazeState = rememberHazeState()
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                        .background(Brush.horizontalGradient(listOf(Color(0xFFE53935), Color(0xFF1E88E5))))
                )
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    GlassNavBar(
                        hazeState = hazeState,
                        items = listOf(GlassNavItem("\uF0C0", "多人房间"), GlassNavItem("\uF1B2", "整合包")),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Tracked("pill", bounds) {
                            GlassIconButton(icon = "\uDB86\uDDD5", label = "整合广场", hazeState = hazeState) {}
                        }
                        Tracked("circle", bounds) {
                            GlassIconButton(icon = "\uF067", label = "创建房间", showText = false, hazeState = hazeState) {}
                        }
                        Tracked("disabled", bounds) {
                            GlassIconButton(icon = "\uF019", label = "导入rdipack2", enabled = false, hazeState = hazeState) {}
                        }
                    }
                }
            }
        }
    }
    try {
        var image = scene.render(0)
        for (millis in listOf(100L, 300L)) image = scene.render(millis * 1_000_000L)
        buttonPreviewDir.mkdirs()
        buttonPreviewDir.resolve("glass-icon-button.png")
            .writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
        return ButtonRender(Bitmap.makeFromImage(image), bounds)
    } finally {
        scene.close()
    }
}

@Composable
private fun Tracked(key: String, bounds: MutableMap<String, Rect>, content: @Composable () -> Unit) {
    Box(Modifier.onGloballyPositioned { bounds[key] = it.boundsInRoot() }) { content() }
}

/** 测试的工作目录是run/，预览图写到模块自己的build/下。 */
private val buttonPreviewDir: File
    get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { it.resolve("build.gradle.kts").isFile }
        .resolve("build/tile-preview")

private class ButtonRender(val bitmap: Bitmap, val bounds: Map<String, Rect>) {
    fun pixel(x: Float, y: Float): Int = bitmap.getColor(x.toInt(), y.toInt())

    /** 背景是横向渐变，同一列最底部没有控件，就是原背景。 */
    fun rawBackground(x: Float): Int = pixel(x, BUTTON_SCENE_HEIGHT - 2f)
}

private fun Int.channels() = listOf((this shr 16) and 0xFF, (this shr 8) and 0xFF, this and 0xFF)

private fun Int.distance(other: Int): Int = channels().zip(other.channels()).sumOf { (a, b) -> abs(a - b) }

private fun Int.hex(): String = "#%06X".format(this and 0xFFFFFF)
