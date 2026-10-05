package calebxzhou.rdi.mc.client.texture

import calebxzau.rdi.mediaproc.AvifCodec
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EffekAvifTextureConverterTest {
    @Test
    fun passesPngThroughWithoutChangingItsBuffer() {
        val png = samplePng()
        val padded = png + byteArrayOf(1, 2, 3)
        val before = padded.copyOf()

        assertSame(padded, EffekAvifTextureConverter.toPngIfAvif(padded, png.size))
        assertContentEquals(before, padded)
    }

    @Test
    fun ignoresAvifHeaderOutsideTheEffectiveLength() {
        val bytes = avifHeader()

        assertSame(bytes, EffekAvifTextureConverter.toPngIfAvif(bytes, 8))
        assertSame(bytes, EffekAvifTextureConverter.toPngIfAvif(bytes, 0))
    }

    @Test
    fun rejectsInvalidLengthsBeforeNativeLoading() {
        assertFailsWith<IllegalArgumentException> {
            EffekAvifTextureConverter.toPngIfAvif(byteArrayOf(1), -1)
        }
        assertFailsWith<IllegalArgumentException> {
            EffekAvifTextureConverter.toPngIfAvif(byteArrayOf(1), 2)
        }
    }

    @Test
    fun convertsAvifToPngPreservingDecodedRgba() {
        val avif = AvifCodec.encodePng(samplePng()).getOrThrow()
        val png = assertConversion(avif)
        val image = ImageIO.read(ByteArrayInputStream(png))
        try {
            assertEquals(listOf(0, 64, 192, 255), (0 until 4).map { pixel ->
                image.getRGB(pixel % 2, pixel / 2) ushr 24
            })
        } finally {
            image.flush()
        }
    }

    @Test
    fun convertsOnlyTheEffectiveAvifBytesAndPreservesTheInput() {
        val avif = AvifCodec.encodePng(samplePng()).getOrThrow()
        val padded = avif + ByteArray(64) { 0x7F }
        val before = padded.copyOf()
        val png = EffekAvifTextureConverter.toPngIfAvif(padded, avif.size)

        assertContentEquals(EffekAvifTextureConverter.toPngIfAvif(avif, avif.size), png)
        assertContentEquals(before, padded)
    }

    @Test
    fun reportsCorruptAvifWithItsCause() {
        val bytes = avifHeader()
        val error = assertFailsWith<IOException> {
            EffekAvifTextureConverter.toPngIfAvif(bytes, bytes.size)
        }
        assertTrue(error.cause != null)
    }

    @Test
    fun convertsExternalEffekTextureFixtureWhenProvided() {
        val fixture = System.getenv("RDI_EFFEK_AVIF_FIXTURE")
        assumeTrue(!fixture.isNullOrBlank(), "Set RDI_EFFEK_AVIF_FIXTURE to verify a real mod texture")
        val avif = Files.readAllBytes(Path.of(checkNotNull(fixture)))
        val png = assertConversion(avif)
        println("Effekseer fixture: ${avif.size} AVIF bytes -> ${png.size} PNG bytes; RGBA verified")
    }

    private fun assertConversion(avif: ByteArray): ByteArray {
        assertTrue(AvifCodec.isAvif(avif))
        val decoded = AvifCodec.decode(avif).getOrThrow()
        val png = EffekAvifTextureConverter.toPngIfAvif(avif, avif.size)
        assertContentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 13, 10, 26, 10), png.copyOf(8))
        val image = ImageIO.read(ByteArrayInputStream(png))
        try {
            assertEquals(decoded.width, image.width)
            assertEquals(decoded.height, image.height)
            val actual = ByteArray(decoded.pixels.size)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    val offset = (y * image.width + x) * 4
                    actual[offset] = (argb ushr 16).toByte()
                    actual[offset + 1] = (argb ushr 8).toByte()
                    actual[offset + 2] = argb.toByte()
                    actual[offset + 3] = (argb ushr 24).toByte()
                }
            }
            assertContentEquals(decoded.pixels, actual)
        } finally {
            image.flush()
        }
        return png
    }

    private fun samplePng(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        return try {
            image.setRGB(0, 0, 2, 2, intArrayOf(0x00FF0000, 0x4000FF00, 0xC00000FF.toInt(), 0xFFFFFFFF.toInt()), 0, 2)
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "png", output))
                output.toByteArray()
            }
        } finally {
            image.flush()
        }
    }

    private fun avifHeader(): ByteArray =
        byteArrayOf(0, 0, 0, 20) + "ftypavif".encodeToByteArray() + ByteArray(4) + "avif".encodeToByteArray()
}
