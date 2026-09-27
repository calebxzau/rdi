package calebxzau.rdi.mediaproc

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FORCE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegAvifDecoderTest {
    @Test
    fun detectsAvifBrand() {
        assertTrue(AvifCodec.isAvif(ftyp("avif")))
        assertTrue(AvifCodec.isAvif(ftyp("mif1", "avis")))
        assertFalse(AvifCodec.isAvif(ftyp("mif1", "heic")))
        assertFalse(AvifCodec.isAvif(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun rejectsTruncatedFtypBox() {
        val truncated = ftyp("mif1", "avif").copyOf(18)
        assertFalse(AvifCodec.isAvif(truncated))

        val declaredBeyondInput = ftyp("avif").apply { this[3] = 0x40 }
        assertFalse(AvifCodec.isAvif(declaredBeyondInput))

        val tooSmall = ftyp("avif").apply { this[3] = 8 }
        assertFalse(AvifCodec.isAvif(tooSmall))
    }

    @Test
    fun detectsAvifWithoutMutatingDirectByteBuffer() {
        val encoded = ftyp("mif1", "avif")
        val input = ByteBuffer.allocateDirect(encoded.size + 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(0x55)
            .put(0x66)
            .put(encoded)
            .flip()
        input.position(2)
        val originalPosition = input.position()
        val originalLimit = input.limit()
        val originalOrder = input.order()

        assertTrue(AvifCodec.isAvif(input))
        assertEquals(originalPosition, input.position())
        assertEquals(originalLimit, input.limit())
        assertEquals(originalOrder, input.order())
    }

    @Test
    fun decodesBundledBackgroundToRgba() {
        assumeWindowsX64()
        val input = javaClass.getResourceAsStream("/assets/bg.avif")!!.use { it.readBytes() }

        val decoded = FfmpegAvifDecoder.decode(input).getOrThrow()

        assertEquals(1334, decoded.width)
        assertEquals(800, decoded.height)
        assertEquals(decoded.width * decoded.height * 4, decoded.pixels.size)
        assertTrue((3 until decoded.pixels.size step 4).all { decoded.pixels[it] == 0xFF.toByte() })
    }

    @Test
    fun rejectsInvalidAvif() {
        assumeWindowsX64()
        assertTrue(FfmpegAvifDecoder.decode(ftyp("avif")).isFailure)
    }

    @Test
    fun encodesOpaquePngAndDecodesOpaqueRgba() {
        assumeWindowsX64()
        val input = png(
            width = 2,
            height = 2,
            pixels = intArrayOf(
                0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
                0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(),
            ),
        )

        val encoded = runBlocking { AvifCodec.encodePng(input).getOrThrow() }
        assertTrue(AvifCodec.isAvif(encoded))
        val decoded = AvifCodec.decode(encoded).getOrThrow()

        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertTrue((3 until decoded.pixels.size step 4).all { decoded.pixels[it] == 0xFF.toByte() })
    }

    @Test
    fun preservesGradientAlphaInSecondAvifStream(): Unit {
        assumeWindowsX64()
        val input = png(
            width = 3,
            height = 1,
            pixels = intArrayOf(
                0x00FF0000,
                0x407F00FF,
                0xC000FF00.toInt(),
            ),
        )

        val encoded = runBlocking { AvifCodec.encodePng(input).getOrThrow() }
        val decoded = AvifCodec.decode(encoded).getOrThrow()

        assertEquals(listOf(0x00, 0x40, 0xC0), (3 until decoded.pixels.size step 4)
            .map { decoded.pixels[it].toInt() and 0xFF })
    }

    @Test
    fun lowQualityPreservesGradientAlphaInSecondAvifStream(): Unit {
        assumeWindowsX64()
        val input = png(
            width = 3,
            height = 1,
            pixels = intArrayOf(
                0x00FF0000,
                0x407F00FF,
                0xC000FF00.toInt(),
            ),
        )

        val encoded = runBlocking {
            AvifCodec.encodePng(input, AvifQuality.LOW).getOrThrow()
        }
        val decoded = AvifCodec.decode(encoded).getOrThrow()

        assertEquals(listOf(0x00, 0x40, 0xC0), (3 until decoded.pixels.size step 4)
            .map { decoded.pixels[it].toInt() and 0xFF })
    }

    @Test
    fun encodeRejectsInvalidPng() {
        assumeWindowsX64()
        val result = runBlocking { AvifCodec.encodePng(byteArrayOf(1, 2, 3)) }
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsTruncatedEncodedAvif(): Unit {
        assumeWindowsX64()
        val input = png(2, 2, intArrayOf(-1, -1, -1, -1))
        val encoded = runBlocking { AvifCodec.encodePng(input).getOrThrow() }

        assertTrue(FfmpegAvifDecoder.decode(encoded.copyOf(encoded.size / 2)).isFailure)
    }

    @Test
    fun decodesRepeatedAndConcurrentInputs(): Unit {
        assumeWindowsX64()
        val inputs = (1..4).map { width ->
            AvifCodec.encodePng(png(width, 2, IntArray(width * 2) { 0x80FF0000.toInt() })).getOrThrow()
        }
        repeat(3) {
            val outputs = runBlocking {
                coroutineScope {
                    List(8) { index ->
                        async(Dispatchers.Default) { FfmpegAvifDecoder.decode(inputs[index % inputs.size]).getOrThrow() }
                    }.awaitAll()
                }
            }
            outputs.forEachIndexed { index, decoded ->
                assertEquals(index % inputs.size + 1, decoded.width)
                assertEquals(2, decoded.height)
                assertTrue((3 until decoded.pixels.size step 4).all { decoded.pixels[it] == 0x80.toByte() })
            }
        }
    }

    @Test
    fun memoryAvioCursorSupportsSizeAndBoundedSeeks(): Unit {
        val cursor = MemoryAvioInput.Cursor(10)

        assertEquals(10L, cursor.seek(0, AVSEEK_SIZE))
        assertEquals(3L, cursor.seek(3, SEEK_SET))
        assertEquals(5L, cursor.seek(2, SEEK_CUR))
        assertEquals(9L, cursor.seek(-1, SEEK_END))
        assertEquals(1, cursor.read(4))
        assertEquals(10L, cursor.position)
        assertEquals(0, cursor.read(4))
        assertEquals(10L, cursor.seek(0, AVSEEK_SIZE or AVSEEK_FORCE))
        assertTrue(runCatching { cursor.seek(1, SEEK_SET) }.isSuccess)
        assertTrue(runCatching { cursor.seek(-1, SEEK_SET) }.isFailure)
        assertTrue(runCatching { cursor.seek(Long.MAX_VALUE, SEEK_CUR) }.isFailure)
    }

    private fun ftyp(majorBrand: String, vararg compatibleBrands: String): ByteArray {
        val size = 16 + compatibleBrands.size * 4
        return ByteArray(size).apply {
            this[3] = size.toByte()
            "ftyp".writeTo(this, 4)
            majorBrand.writeTo(this, 8)
            compatibleBrands.forEachIndexed { index, brand ->
                brand.writeTo(this, 16 + index * 4)
            }
        }
    }

    private fun String.writeTo(target: ByteArray, offset: Int) {
        forEachIndexed { index, char -> target[offset + index] = char.code.toByte() }
    }

    private fun png(width: Int, height: Int, pixels: IntArray): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }
    }

    private fun assumeWindowsX64() {
        val osName = System.getProperty("os.name").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty().lowercase()
        assumeTrue(osName.contains("windows", ignoreCase = true))
        assumeTrue(osArch == "amd64" || osArch == "x86_64")
    }

    private companion object {
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2
    }
}
