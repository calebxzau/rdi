package calebxzau.rdi.mediaproc

import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.PointerPointer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** libopus only takes frames of an exact length, so the writer must regroup whatever it is given. */
class OggOpusWriterTest {
    @Test
    fun inputShorterThanOneFrameIsNotLost(@TempDir tempDir: Path) {
        val file = tempDir.resolve("short.ogg").toFile()

        write(file, listOf(100))

        assertTrue(decodedFrames(file) > 0)
    }

    @Test
    fun inputOfExactlyOneFrameIsWritten(@TempDir tempDir: Path) {
        val file = tempDir.resolve("one-frame.ogg").toFile()

        write(file, listOf(FRAME_SAMPLES))

        assertLength(FRAME_SAMPLES, decodedFrames(file))
    }

    @Test
    fun chunkSizesDoNotChangeTheLengthOrVolume(@TempDir tempDir: Path) {
        val chunks = listOf(1, 7, FRAME_SAMPLES - 1, FRAME_SAMPLES, FRAME_SAMPLES + 1, 3_000, 2, 9_000, 1)
        val regular = tempDir.resolve("regular.ogg").toFile()
        val irregular = tempDir.resolve("irregular.ogg").toFile()
        val total = chunks.sum()

        write(regular, listOf(total))
        write(irregular, chunks)

        assertLength(total, decodedFrames(irregular))
        val expected = rms(decode(regular))
        val actual = rms(decode(irregular))
        assertTrue(abs(actual / expected - 1.0) < 0.02, "Volume changed from $expected to $actual")
    }

    @Test
    fun closingWithoutFinishingReleasesTheFile(@TempDir tempDir: Path) {
        val file = tempDir.resolve("abandoned.ogg").toFile()
        FfmpegNativePreloader.ensureLoaded().getOrThrow()

        OggOpusWriter(file, SAMPLE_RATE, CHANNELS, BITRATE).close()

        assertTrue(file.delete())
    }

    private fun write(file: File, chunks: List<Int>) {
        FfmpegNativePreloader.ensureLoaded().getOrThrow()
        OggOpusWriter(file, SAMPLE_RATE, CHANNELS, BITRATE).use { writer ->
            var position = 0
            for (chunk in chunks) {
                writeTone(writer, position, chunk)
                position += chunk
            }
            writer.finish()
        }
    }

    private fun writeTone(writer: OggOpusWriter, start: Int, samples: Int) {
        val bytes = ByteBuffer.allocate(samples * CHANNELS * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        for (sample in start until start + samples) {
            val value = (sin(2 * PI * 440.0 * sample / SAMPLE_RATE) * 0.4 * Short.MAX_VALUE).toInt().toShort()
            repeat(CHANNELS) { bytes.putShort(value) }
        }
        BytePointer(*bytes.array()).use { pointer ->
            PointerPointer<BytePointer>(1L).use { planes ->
                planes.put(0L, pointer)
                writer.write(planes, samples)
            }
        }
    }

    private fun decode(file: File): ShortArray =
        FfmpegPcmDecoder.open(file.inputStream()).getOrThrow().use { stream ->
            val samples = stream.readAll().getOrThrow().asShortBuffer()
            ShortArray(samples.remaining()).also(samples::get)
        }

    private fun decodedFrames(file: File): Int = decode(file).size / CHANNELS

    /** Decoding is always at 48kHz, so a sample written at 24kHz comes back as two. */
    private fun assertLength(writtenSamples: Int, decodedFrames: Int) {
        val expected = writtenSamples * 2
        assertTrue(abs(decodedFrames - expected) <= 960, "Expected about $expected frames, got $decodedFrames")
    }

    private fun rms(samples: ShortArray): Double =
        sqrt(samples.sumOf { it.toDouble() * it } / samples.size)

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val CHANNELS = 2
        const val BITRATE = 48_000
        const val FRAME_SAMPLES = 480
    }
}
