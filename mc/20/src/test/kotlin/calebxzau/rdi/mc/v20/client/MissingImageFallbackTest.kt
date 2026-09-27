package calebxzau.rdi.mc.v20.client

import calebxzau.rdi.mc.v20.client.mixin.mMissingImageResource
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceMetadata
import net.minecraft.server.packs.resources.ResourceProvider
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Optional
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class MissingImageFallbackTest {
    private val emptyProvider = ResourceProvider { Optional.empty() }

    @Test
    fun mixinDefaultMethodAlsoCoversOpenWithoutChangingProbes() {
        val provider = object : mMissingImageResource {
            override fun getResource(location: ResourceLocation): Optional<Resource> = Optional.empty()
        }
        val location = ResourceLocation("rdi_test", "through_open.png")
        val image = provider.open(location).use { ImageIO.read(it) }
        assertEquals(0, image.getRGB(0, 0))
        assertFalse(provider.getResource(location).isPresent)
        assertFailsWith<FileNotFoundException> {
            provider.open(ResourceLocation("rdi_test", "missing.json"))
        }
    }

    @Test
    fun missingPngAndAvifProduceTransparentPixelWithIndependentStreams() {
        for (extension in listOf("png", "avif")) {
            val location = ResourceLocation("rdi_test", "missing.${extension}")
            val resource = MissingImageFallback.getResourceOrThrow(emptyProvider, location)
            val image = resource.open().use { ImageIO.read(it) }

            assertEquals(1, image.width)
            assertEquals(1, image.height)
            assertEquals(0, image.getRGB(0, 0))
            assertSame(ResourceMetadata.EMPTY, resource.metadata())
            resource.open().use { first ->
                first.readAllBytes()
                resource.open().use { second -> assertEquals(0x89, second.read()) }
            }
        }
    }

    @Test
    fun existingResourceIsReturnedWithoutOpeningIt() {
        val source = MissingImageFallback.getResourceOrThrow(
            emptyProvider, ResourceLocation("rdi_test", "source.png")
        ).source()
        val readError = IOException("Existing image is unreadable")
        val existing = Resource(source, { throw readError })
        var lookups = 0
        val provider = ResourceProvider {
            lookups++
            Optional.of(existing)
        }

        val result = MissingImageFallback.getResourceOrThrow(
            provider, ResourceLocation("rdi_test", "existing.png")
        )

        assertSame(existing, result)
        assertEquals(1, lookups)
        assertSame(readError, assertFailsWith<IOException> { result.open() })
    }

    @Test
    fun missingNonImagesKeepTheirFileNotFoundException() {
        for (path in listOf("font/default.json", "image.png.mcmeta", "sound.ogg", "font.ttf", "no_extension")) {
            val location = ResourceLocation("rdi_test", path)
            val error = assertFailsWith<FileNotFoundException> {
                MissingImageFallback.getResourceOrThrow(emptyProvider, location)
            }
            assertEquals(location.toString(), error.message)
        }
    }

    @Test
    fun fallbackDoesNotChangeOptionalExistenceProbes() {
        val location = ResourceLocation("rdi_test", "optional.png")
        assertFalse(emptyProvider.getResource(location).isPresent)
        MissingImageFallback.getResourceOrThrow(emptyProvider, location)
        assertFalse(emptyProvider.getResource(location).isPresent)
    }

    @Test
    fun repeatedReadsRemainTransparent() {
        val location = ResourceLocation("rdi_test", "repeated.png")
        repeat(8) {
            val image = MissingImageFallback.getResourceOrThrow(emptyProvider, location)
                .open().use { ImageIO.read(it) }
            assertEquals(0, image.getRGB(0, 0))
        }
    }
}
