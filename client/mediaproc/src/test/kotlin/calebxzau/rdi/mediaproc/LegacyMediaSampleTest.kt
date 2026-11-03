package calebxzau.rdi.mediaproc

import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.Base64
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun decodesStereoOpusSample() {
        assertOpusMatchesReference("dual-tone", channels = 2)
    }

    @Test
    fun decodesMonoOpusSampleToMono() {
        assertOpusMatchesReference("mono-tone", channels = 1)
    }

    @Test
    fun downmixesSurroundOpusSampleToStereo() {
        assertOpusMatchesReference("surround-tone", channels = 2)
    }

    @Test
    fun transcodesStereoOpusLikeTheReference() {
        assertTranscodeMatchesReference("dual-tone", resource("dual-tone.ogg"))
    }

    @Test
    fun transcodesMonoOpusLikeTheReference() {
        assertTranscodeMatchesReference("mono-tone", resource("mono-tone.ogg"))
    }

    @Test
    fun transcodesSurroundOpusLikeTheReference() {
        assertTranscodeMatchesReference("surround-tone", resource("surround-tone.ogg"))
    }

    @Test
    fun transcodesStereoVorbisLikeTheReference() {
        assertTranscodeMatchesReference("vorbis-tone", resource("vorbis-tone.ogg"))
    }

    @Test
    fun transcodesMonoVorbisLikeTheReference() {
        val input = javaClass.getResourceAsStream("/audio/mono-vorbis-44100.ogg.b64")!!
            .use { Base64.getMimeDecoder().decode(it.readBytes()) }
        assertTranscodeMatchesReference("mono-vorbis", input)
    }

    private fun assertOpusMatchesReference(name: String, channels: Int) {
        val reference = shorts(resource("${name}.pcm"))

        val decoded = decode(resource("${name}.ogg"), channels)

        assertEquals(reference.size, decoded.size)
        // A silent reference would make the error below meaningless.
        assertTrue(rms(reference) { reference[it].toDouble() } > MIN_REFERENCE_RMS)
        val error = rms(reference) { (decoded[it] - reference[it]).toDouble() }
        println("legacy sample ${name}: rmsError=${"%.4f".format(error * 100)}% of full scale")
        assertTrue(error <= MAX_OPUS_RMS_ERROR, "Opus RMS error $error exceeds $MAX_OPUS_RMS_ERROR")
    }

    /**
     * The encoded bytes may change between implementations, so this compares what a player hears:
     * the length, the volume of each channel, and the shape of the waveform.
     */
    private fun assertTranscodeMatchesReference(name: String, input: ByteArray) {
        val reference = channels(shorts(resource("${name}.transcoded.pcm")))
        val tempDir = Files.createTempDirectory("mediaproc-legacy-")
        val encoded = try {
            val result = runBlocking { OggTranscoder.transcodeOgg(input, tempDir).getOrThrow() }
            assertIs<OggTranscodeResult.Encoded>(result).bytes
        } finally {
            Files.deleteIfExists(tempDir)
        }

        val decoded = channels(decode(encoded, channels = 2))

        val frameDifference = abs(decoded[0].size - reference[0].size)
        assertTrue(frameDifference <= MAX_TRANSCODE_FRAME_DIFFERENCE, "${name} length differs by $frameDifference frames")
        for (channel in 0 until 2) {
            val volume = rms(decoded[channel]) { decoded[channel][it].toDouble() } /
                rms(reference[channel]) { reference[channel][it].toDouble() }
            val similarity = bestCorrelation(decoded[channel], reference[channel])
            println(
                "legacy sample ${name}.transcoded[${channel}]: frameDifference=${frameDifference} " +
                    "volume=${"%.4f".format(volume)} similarity=${"%.4f".format(similarity)}"
            )
            assertTrue(abs(volume - 1.0) <= MAX_TRANSCODE_VOLUME_ERROR, "${name} volume ratio is $volume")
            assertTrue(similarity >= MIN_TRANSCODE_SIMILARITY, "${name} similarity is $similarity")
        }
    }

    private fun decode(input: ByteArray, channels: Int): ShortArray =
        FfmpegPcmDecoder.open(input.inputStream()).getOrThrow().use {
            assertEquals(PcmAudioFormat(sampleRate = 48_000, channels = channels), it.format)
            shorts(it.readAll().getOrThrow())
        }

    private fun channels(interleaved: ShortArray): List<ShortArray> =
        (0 until 2).map { channel -> ShortArray(interleaved.size / 2) { interleaved[it * 2 + channel] } }

    /** Highest normalized correlation with [b] over small time shifts, because encoder delay may differ. */
    private fun bestCorrelation(a: ShortArray, b: ShortArray): Double {
        val energyA = sqrt(a.sumOf { it.toDouble() * it })
        val energyB = sqrt(b.sumOf { it.toDouble() * it })
        return (-MAX_TRANSCODE_FRAME_DIFFERENCE..MAX_TRANSCODE_FRAME_DIFFERENCE).maxOf { shift ->
            var sum = 0.0
            for (index in maxOf(0, -shift) until minOf(a.size, b.size - shift)) {
                sum += a[index].toDouble() * b[index + shift]
            }
            sum / (energyA * energyB)
        }
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

        // One Opus frame at 48kHz.
        const val MAX_TRANSCODE_FRAME_DIFFERENCE = 960
        const val MAX_TRANSCODE_VOLUME_ERROR = 0.05
        const val MIN_TRANSCODE_SIMILARITY = 0.95
    }
}
