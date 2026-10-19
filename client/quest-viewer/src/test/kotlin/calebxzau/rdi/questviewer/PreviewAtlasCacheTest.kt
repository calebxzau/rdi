package calebxzau.rdi.questviewer

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class PreviewAtlasCacheTest {
    @TempDir
    lateinit var root: Path

    private fun assets(width: Int = 128): PreviewAssets {
        val image = BufferedImage(128, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 64) for (x in 0 until 128) image.setRGB(x, y, if (x < 64) 0xFFFF0000.toInt() else 0xFF0000FF.toInt())
        Files.createDirectories(root.resolve("texture_pages"))
        ImageIO.write(image, "png", root.resolve("texture_pages/0.png").toFile())
        return PreviewAssets(root, PreviewManifest(1, 64, listOf(PreviewPage("texture_pages/0.png", width, 64)),
            mapOf("blue" to PreviewItem(0, 64, 0, "blue"))))
    }

    @Test
    fun cachesWholePageAndCropsItemAtNonzeroAtlasCoordinates() = runBlocking {
        val assets = assets()
        val cache = PreviewAtlasCache(assets)
        val page = cache.page(0).getOrThrow()
        assertSame(page, cache.page(0).getOrThrow())
        val target = ImageBitmap(64, 64)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(target), Size(64f, 64f)) {
            drawAtlasItem(page, assets.manifest!!.items.getValue("blue"), 64, 0f, 0f, 64f)
        }
        val pixels = target.toPixelMap()
        assertEquals(Color.Blue, pixels[0, 0])
        assertEquals(Color.Blue, pixels[63, 63])
    }

    @Test
    fun mismatchedImageDimensionsAreObservableAndCachedAsFailure() = runBlocking {
        val cache = PreviewAtlasCache(assets(64))
        assertTrue(cache.page(0).isFailure)
        assertTrue(cache.page(0).isFailure)
        assertEquals(1, cache.failedPages)
    }

    @Test
    fun missingPageFallsBackWithoutThrowingFromImageLoader() = runBlocking {
        val cache = PreviewAtlasCache(PreviewAssets(root,
            PreviewManifest(1, 64, listOf(PreviewPage("missing.png", 64, 64)), emptyMap())))
        assertTrue(cache.page(0).isFailure)
        assertEquals(1, cache.failedPages)
    }
}
