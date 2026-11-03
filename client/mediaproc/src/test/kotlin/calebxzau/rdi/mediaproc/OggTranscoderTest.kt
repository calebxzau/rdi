package calebxzau.rdi.mediaproc

import kotlinx.coroutines.runBlocking
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OggTranscoderTest {
    @Test
    fun ensureReadyLoadsNativeOpusCodec() {
        assertTrue(OggTranscoder.ensureReady().isSuccess)
    }

    @Test
    fun transcodeProducesStereoOpusAtTargetSampleRate() {
        val input = javaClass.getResourceAsStream("/assets/empty.ogg")!!.use { it.readBytes() }
        val tempDir = Files.createTempDirectory("mediaproc-test-")
        try {
            val result = runBlocking { OggTranscoder.transcodeOgg(input, tempDir).getOrThrow() }
            val output = assertIs<OggTranscodeResult.Encoded>(result).bytes
            assertTrue(output.isNotEmpty())
            assertEquals(24_000, readOpusInputSampleRate(output))
            assertEquals(2, readOpusChannelCount(output))

            val outputFile = Files.createTempFile(tempDir, "result-", ".ogg").toFile()
            outputFile.writeBytes(output)
            val grabber = FFmpegFrameGrabber(outputFile)
            try {
                grabber.start()
                assertEquals("ogg", grabber.format)
                assertEquals("opus", grabber.audioCodecName)
                assertEquals(2, grabber.audioChannels)
                assertTrue(grabber.hasAudio())
            } finally {
                runCatching { grabber.release() }
                outputFile.delete()
            }
            assertFalse(Files.list(tempDir).use { it.findAny().isPresent })
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun transcodesMonoOggToStereoWithNativeOpus() {
        val tempDir = Files.createTempDirectory("mediaproc-test-")
        try {
            val inputFile = Files.createTempFile(tempDir, "mono-", ".ogg").toFile()
            val inputRecorder = FFmpegFrameRecorder(inputFile, 1).apply {
                format = "ogg"
                audioCodecName = "libopus"
                sampleRate = 48_000
            }
            var stopAttempted = false
            try {
                inputRecorder.start()
                inputRecorder.recordSamples(48_000, 1, ShortBuffer.wrap(ShortArray(4_800)))
                stopAttempted = true
                inputRecorder.stop()
            } finally {
                if (!stopAttempted) runCatching { inputRecorder.release() }
            }

            val output = runBlocking {
                OggTranscoder.transcodeOgg(inputFile.readBytes(), tempDir).getOrThrow()
            }
            val encoded = assertIs<OggTranscodeResult.Encoded>(output).bytes
            val outputFile = Files.createTempFile(tempDir, "result-", ".ogg").toFile()
            outputFile.writeBytes(encoded)
            val grabber = FFmpegFrameGrabber(outputFile)
            try {
                grabber.start()
                assertEquals("opus", grabber.audioCodecName)
                assertEquals(2, grabber.audioChannels)
            } finally {
                runCatching { grabber.release() }
            }
        } finally {
            Files.list(tempDir).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun transcodesMonoVorbis44100HzToStereoOpus() {
        val tempDir = Files.createTempDirectory("mediaproc-test-")
        try {
            val input = javaClass.getResourceAsStream("/audio/mono-vorbis-44100.ogg.b64")!!
                .use { Base64.getMimeDecoder().decode(it.readBytes()) }
            val inputFile = Files.createTempFile(tempDir, "vorbis-", ".ogg").toFile()
            inputFile.writeBytes(input)
            val inputGrabber = FFmpegFrameGrabber(inputFile)
            try {
                inputGrabber.start()
                assertEquals("vorbis", inputGrabber.audioCodecName)
                assertEquals(1, inputGrabber.audioChannels)
                assertEquals(44_100, inputGrabber.sampleRate)
            } finally {
                runCatching { inputGrabber.release() }
            }

            val result = runBlocking {
                OggTranscoder.transcodeOgg(input, tempDir).getOrThrow()
            }
            val encoded = assertIs<OggTranscodeResult.Encoded>(result).bytes
            assertEquals(24_000, readOpusInputSampleRate(encoded))
            assertEquals(2, readOpusChannelCount(encoded))
        } finally {
            Files.list(tempDir).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun replacesAudioLongerThanTenSeconds() {
        val tempDir = Files.createTempDirectory("mediaproc-test-")
        try {
            val input = createOgg(
                codecName = "libopus",
                durationSeconds = 11,
                channels = 2,
                sampleRate = 24_000,
            )
            val result = runBlocking {
                OggTranscoder.transcodeOgg(input, tempDir).getOrThrow()
            }
            val tooLong = assertIs<OggTranscodeResult.TooLong>(result)
            assertTrue(tooLong.durationMicros > 10_000_000L)
            assertFalse(Files.list(tempDir).use { it.findAny().isPresent })
        } finally {
            Files.list(tempDir).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun invalidInputReturnsFailureAndCleansTemporaryFiles() {
        val tempDir = Files.createTempDirectory("mediaproc-test-")
        try {
            val result = runBlocking {
                OggTranscoder.transcodeOgg(byteArrayOf(1, 2, 3), tempDir)
            }
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull() is MediaProcUnavailableException)
            assertFalse(Files.list(tempDir).use { it.findAny().isPresent })
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    private fun createOgg(
        codecName: String,
        durationSeconds: Int,
        channels: Int,
        sampleRate: Int,
    ): ByteArray {
        val outputFile = Files.createTempFile("mediaproc-input-", ".ogg").toFile()
        val recorder = FFmpegFrameRecorder(outputFile, channels).apply {
            format = "ogg"
            audioCodecName = codecName
            this.sampleRate = sampleRate
            audioChannels = channels
        }
        var stopAttempted = false
        try {
            recorder.start()
            recorder.recordSamples(
                sampleRate,
                channels,
                ShortBuffer.wrap(ShortArray(sampleRate * durationSeconds * channels))
            )
            stopAttempted = true
            recorder.stop()
            return outputFile.readBytes()
        } finally {
            if (!stopAttempted) runCatching { recorder.release() }
            outputFile.delete()
        }
    }

    private fun readOpusInputSampleRate(bytes: ByteArray): Int {
        val header = findOpusHead(bytes)
        return ByteBuffer.wrap(bytes, header + 12, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getInt()
    }

    private fun readOpusChannelCount(bytes: ByteArray): Int = bytes[findOpusHead(bytes) + 9].toInt()

    private fun findOpusHead(bytes: ByteArray): Int {
        val magic = "OpusHead".encodeToByteArray()
        return bytes.indices.first { index ->
            index + magic.size <= bytes.size &&
                bytes.copyOfRange(index, index + magic.size).contentEquals(magic)
        }
    }
}
