package calebxzau.rdi.bgrenderer

import java.io.InputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundResourceTest {
    @Test
    fun bundledMinecraftResourcesHaveExpectedDimensionsAndLanternMetadata() {
        val expected = mapOf(
            "block/grass_block_top.png" to (16 to 16),
            "block/grass_block_side.png" to (16 to 16),
            "block/dirt.png" to (16 to 16),
            "block/stone.png" to (16 to 16),
            "block/snow.png" to (16 to 16),
            "block/sand.png" to (16 to 16),
            "block/gravel.png" to (16 to 16),
            "block/oak_planks.png" to (16 to 16),
            "block/oak_log.png" to (16 to 16),
            "block/oak_log_top.png" to (16 to 16),
            "block/birch_log.png" to (16 to 16),
            "block/spruce_log.png" to (16 to 16),
            "block/oak_leaves.png" to (16 to 16),
            "block/birch_leaves.png" to (16 to 16),
            "block/spruce_leaves.png" to (16 to 16),
            "block/short_grass.png" to (16 to 16),
            "block/spruce_planks.png" to (16 to 16),
            "block/cobblestone.png" to (16 to 16),
            "block/glowstone.png" to (16 to 16),
            "block/lantern.png" to (16 to 48),
            "environment/sun.png" to (32 to 32),
            "environment/clouds.png" to (256 to 256),
        )
        expected.forEach { (path, dimensions) ->
            val resource = resource("assets/minecraft/textures/$path")
            val image = resource.use(ImageIO::read)
            assertNotNull(image, path)
            assertEquals(dimensions.first, image.width, path)
            assertEquals(dimensions.second, image.height, path)
        }
        BlockMaterial.entries.mapNotNull { it.texturePath }.forEach { path -> resource(path).close() }
        // Grass tops are grayscale and tinted by the material color; the side texture carries its own green fringe.
        val grassTop = resource("assets/minecraft/textures/block/grass_block_top.png").use(ImageIO::read)
        val topPixel = grassTop.getRGB(5, 5)
        assertEquals((topPixel shr 16) and 0xFF, (topPixel shr 8) and 0xFF)
        val grassSide = resource("assets/minecraft/textures/block/grass_block_side.png").use(ImageIO::read)
        val fringe = grassSide.getRGB(0, 0)
        assertTrue(((fringe shr 8) and 0xFF) > ((fringe shr 16) and 0xFF))
        // Cutout materials need real holes in their textures.
        BlockMaterial.entries.filter { it.alphaCutout }.forEach { material ->
            val image = resource(requireNotNull(material.texturePath)).use(ImageIO::read)
            var transparent = 0
            for (y in 0 until image.height) for (x in 0 until image.width) if ((image.getRGB(x, y) ushr 24) < 26) transparent++
            assertTrue(transparent > 0, "$material has no cutout texels")
        }
        val sunImage = resource("assets/minecraft/textures/environment/sun.png").use(ImageIO::read)
        assertNotNull(sunImage)
        var hasBlackRgbTexel = false
        var hasNonBlackRgbTexel = false
        for (y in 0 until sunImage.height) {
            for (x in 0 until sunImage.width) {
                val argb = sunImage.getRGB(x, y)
                assertEquals(255, (argb ushr 24) and 0xFF, "sun alpha at ($x,$y)")
                if ((argb and 0x00FFFFFF) == 0) hasBlackRgbTexel = true else hasNonBlackRgbTexel = true
            }
        }
        assertTrue(hasBlackRgbTexel)
        assertTrue(hasNonBlackRgbTexel)
        val complementary = mapOf(
            "cloud-water.png" to (256 to 256),
            "noise.png" to (128 to 128)
        )
        complementary.forEach { (name, dimensions) ->
            val image = resource("assets/complementary/textures/$name").use(ImageIO::read)
            assertNotNull(image, name)
            assertEquals(dimensions.first, image.width, name)
            assertEquals(dimensions.second, image.height, name)
        }
        assertNull(javaClass.classLoader.getResource("assets/minecraft/textures/block/water_still.png"))
        assertNull(javaClass.classLoader.getResource("assets/minecraft/textures/block/cactus_side.png"))
        assertTrue(resource("assets/minecraft/textures/block/lantern.png.mcmeta").use(InputStream::readBytes).decodeToString().contains("frametime"))
    }

    private fun resource(path: String): InputStream =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "Missing resource $path" }
}
