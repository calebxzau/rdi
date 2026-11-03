package calebxzau.rdi.mediaproc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Files encoded by an earlier FFmpeg build must keep decoding to the same result, because players have
 * already uploaded such files. The samples are frozen: see `legacy/README.md` before changing them.
 */
class LegacyMediaSampleTest {
    @Test
    fun decodesHighQualityAvifSample() {
        assertAvifMatchesReference("alpha-gradient-high")
    }

    @Test
    fun decodesLowQualityAvifSample() {
        assertAvifMatchesReference("alpha-gradient-low")
    }

    @Test
    fun decodesOpusSample() {
        val reference = shorts(resource("dual-tone.pcm"))
        val input = resource("dual-tone.ogg")

        val stream = FfmpegPcmDecoder.open(input.inputStream()).getOrThrow()
        val decoded = stream.use {
            assertEquals(PcmAudioFormat(sampleRate = 48_000, channels = 2), it.format)
            shorts(it.readAll().getOrThrow())
        }

        assertEquals(reference.size, decoded.size)
        // A silent reference would make the error below meaningless.
        assertTrue(rms(reference) { reference[it].toDouble() } > MIN_REFERENCE_RMS)
        val error = rms(reference) { (decoded[it] - reference[it]).toDouble() }
        println("legacy sample dual-tone: rmsError=${"%.4f".format(error * 100)}% of full scale")
        assertTrue(error <= MAX_OPUS_RMS_ERROR, "Opus RMS error $error exceeds $MAX_OPUS_RMS_ERROR")
    }

    private fun assertAvifMatchesReference(name: String) {
        val reference = resource("${name}.rgba")

        val decoded = AvifCodec.decode(resource("${name}.avif")).getOrThrow()

        assertEquals(SAMPLE_SIZE, decoded.width)
        assertEquals(SAMPLE_SIZE, decoded.height)
        assertEquals(reference.size, decoded.pixels.size)
        var maxError = 0
        for (index in reference.indices) {
            val error = abs((decoded.pixels[index].toInt() and 0xFF) - (reference[index].toInt() and 0xFF))
            maxError = max(maxError, error)
        }
        println("legacy sample ${name}: maxChannelError=${maxError}/255")
        assertTrue(maxError <= MAX_AVIF_CHANNEL_ERROR, "AVIF channel error $maxError exceeds $MAX_AVIF_CHANNEL_ERROR")
    }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/legacy/${name}")!!.use { it.readBytes() }

    private fun shorts(bytes: ByteArray): ShortArray = shorts(ByteBuffer.wrap(bytes))

    private fun shorts(buffer: ByteBuffer): ShortArray {
        val samples = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(samples.remaining()).also(samples::get)
    }

    /** Root mean square of [sampleAt] over every index of [samples], as a fraction of full scale. */
    private fun rms(samples: ShortArray, sampleAt: (Int) -> Double): Double {
        val sum = samples.indices.sumOf { index -> sampleAt(index).let { it * it } }
        return sqrt(sum / samples.size) / FULL_SCALE
    }

    private companion object {
        const val SAMPLE_SIZE = 64
        const val FULL_SCALE = 32_768.0
        const val MIN_REFERENCE_RMS = 0.1

        // SIMD paths may round differently between x86 and ARM, so an exact match is not required.
        const val MAX_AVIF_CHANNEL_ERROR = 2
        const val MAX_OPUS_RMS_ERROR = 0.01
    }
}
