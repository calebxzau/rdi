package calebxzau.rdi.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SCENE_WIDTH = 800
private const val SCENE_HEIGHT = 200
private const val ITEM_WIDTH = 100
private val SceneBackground = Color(0xFF303030)

class EdgeAutoScrollTest {
    @Test
    fun glowIsFullInsideZoneAndFadesOutOverApproach() {
        assertEquals(1f, edgeGlowProximity(distance = 10f, zone = 56f, approach = 160f))
        assertEquals(1f, edgeGlowProximity(distance = 56f, zone = 56f, approach = 160f))
        assertEquals(0.5f, edgeGlowProximity(distance = 108f, zone = 56f, approach = 160f), 1e-4f)
        assertEquals(0f, edgeGlowProximity(distance = 160f, zone = 56f, approach = 160f))
        assertEquals(0f, edgeGlowProximity(distance = 400f, zone = 56f, approach = 160f))
    }

    @Test
    fun velocityEasesTowardTargetIndependentOfFrameSplit() {
        val oneStep = approachVelocity(0f, 300f, dt = 0.1f, rate = 10f)
        val twoSteps = approachVelocity(approachVelocity(0f, 300f, 0.05f, 10f), 300f, 0.05f, 10f)

        assertEquals(oneStep, twoSteps, 1e-3f)
        assertTrue(oneStep in 150f..250f, "0.1秒后应完成约63%的加速，实际$oneStep")
        assertEquals(300f, approachVelocity(300f, 300f, dt = 0.016f, rate = 10f))
    }

    @Test
    fun hoveringEndZoneScrollsSlowlyAndGlows() {
        val result = runEdgeScene(pointerX = SCENE_WIDTH - 20f, pressed = false, previewName = "edge-glow.png")

        assertTrue(result.scrolled in 120..400, "停在右边缘应慢速滚动，1秒内实际滚动${result.scrolled}px")
        assertTrue(
            result.bitmap.luminance(SCENE_WIDTH - 4, SCENE_HEIGHT / 2) > result.bitmap.luminance(SCENE_WIDTH / 2, 4) + 20,
            "右边缘应发光",
        )
    }

    @Test
    fun holdingMouseInZoneScrollsMuchFaster() {
        val slow = runEdgeScene(pointerX = SCENE_WIDTH - 20f, pressed = false).scrolled
        val fast = runEdgeScene(pointerX = SCENE_WIDTH - 20f, pressed = true).scrolled

        assertTrue(fast > slow * 2, "按住应明显更快，慢速${slow}px，快速${fast}px")
    }

    @Test
    fun edgeAtScrollStartNeitherScrollsNorGlows() {
        val result = runEdgeScene(pointerX = 20f, pressed = false)

        assertEquals(0, result.scrolled, "已在开头，左边缘不应滚动")
        assertTrue(
            result.bitmap.luminance(4, SCENE_HEIGHT / 2) <= result.bitmap.luminance(SCENE_WIDTH / 2, 4) + 2,
            "已在开头，左边缘不应发光",
        )
    }

    @Test
    fun startZoneBlocksClicksOnlyWhenItCanScroll() {
        assertEquals(1, clicksAtStartEdge(initialItem = 0), "已在开头时边缘区不生效，应点到底下的卡片")
        assertEquals(0, clicksAtStartEdge(initialItem = 5), "能往回滚时边缘区盖住卡片，点击不应穿透")
    }

    /** 在左边缘点一下，返回内容收到的点击次数。 */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun clicksAtStartEdge(initialItem: Int): Int {
        var clicks = 0
        val listState = LazyListState(firstVisibleItemIndex = initialItem)
        val scene = ImageComposeScene(width = SCENE_WIDTH, height = SCENE_HEIGHT, density = Density(1f)) {
            EdgeAutoScrollBox(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                LazyRow(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(200) {
                        Box(Modifier.width(ITEM_WIDTH.dp).fillMaxHeight().clickable { clicks++ })
                    }
                }
            }
        }
        try {
            val position = Offset(20f, SCENE_HEIGHT / 2f)
            scene.render(0)
            scene.render(0)
            scene.sendPointerEvent(PointerEventType.Move, position)
            scene.sendPointerEvent(
                PointerEventType.Press,
                position,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary,
            )
            scene.sendPointerEvent(PointerEventType.Release, position, button = PointerButton.Primary)
            scene.render(16_000_000L)
            return clicks
        } finally {
            scene.close()
        }
    }

    private class EdgeSceneResult(val scrolled: Int, val bitmap: Bitmap)

    /** 鼠标停在[pointerX]处1秒（可按住），返回滚动距离与最后一帧。 */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun runEdgeScene(pointerX: Float, pressed: Boolean, previewName: String? = null): EdgeSceneResult {
        val listState = LazyListState()
        val scene = ImageComposeScene(width = SCENE_WIDTH, height = SCENE_HEIGHT, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(SceneBackground)) {
                EdgeAutoScrollBox(scrollState = listState, modifier = Modifier.fillMaxSize()) {
                    LazyRow(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(200) { index ->
                            Box(
                                Modifier
                                    .width(ITEM_WIDTH.dp)
                                    .fillMaxHeight()
                                    .background(if (index % 2 == 0) Color(0xFF404040) else Color(0xFF383838))
                            )
                        }
                    }
                }
            }
        }
        try {
            val position = Offset(pointerX, SCENE_HEIGHT / 2f)
            // 第一帧布局后才知道能否滚动，第二帧边缘区才出现
            scene.render(0)
            scene.render(0)
            scene.sendPointerEvent(PointerEventType.Move, position)
            if (pressed) {
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    position,
                    buttons = PointerButtons(isPrimaryPressed = true),
                    button = PointerButton.Primary,
                )
            }
            scene.render(0)
            val start = listState.scrolledPx()
            var image = scene.render(16_000_000L)
            for (frame in 2..63) image = scene.render(frame * 16_000_000L)
            previewName?.let { name ->
                previewDir.mkdirs()
                previewDir.resolve(name).writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            }
            return EdgeSceneResult(listState.scrolledPx() - start, Bitmap.makeFromImage(image))
        } finally {
            scene.close()
        }
    }

    /** 测试的工作目录是run/，预览图写到模块自己的build/下。 */
    private val previewDir: File
        get() = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { it.resolve("build.gradle.kts").isFile }
            .resolve("build/tile-preview")

    private fun LazyListState.scrolledPx(): Int = firstVisibleItemIndex * ITEM_WIDTH + firstVisibleItemScrollOffset

    private fun Bitmap.luminance(x: Int, y: Int): Int {
        val color = getColor(x, y)
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
