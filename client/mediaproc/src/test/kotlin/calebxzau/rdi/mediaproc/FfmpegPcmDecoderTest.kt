package calebxzau.rdi.mediaproc

import org.bytedeco.javacv.FFmpegFrameRecorder
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ShortBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegPcmDecoderTest {
    @Test
    fun detectsVorbisOgg() {
        val codec = javaClass.getResourceAsStream("/assets/empty.ogg")!!.buffered().use {
            OggCodecDetector.detect(it).getOrThrow()
        }

        assertEquals(OggAudioCodec.VORBIS, codec)
    }

    @Test
    fun detectsAndDecodesOpusOgg() {
        val opusOgg = createOpusOgg()

        val codec = BufferedInputStream(opusOgg.inputStream()).use {
            OggCodecDetector.detect(it).getOrThrow()
        }
        assertEquals(OggAudioCodec.OPUS, codec)

        val stream = FfmpegPcmDecoder.open(opusOgg.inputStream()).getOrThrow()
        try {
            assertEquals(48_000, stream.format.sampleRate)
            assertEquals(1, stream.format.channels)
            assertTrue(stream.readAll().getOrThrow().hasRemaining())
        } finally {
            stream.closeResult().getOrThrow()
        }
    }

    @Test
    fun chunkedReadMatchesReadAll() {
        val opusOgg = createOpusOgg()
        val allAtOnce = FfmpegPcmDecoder.open(opusOgg.inputStream()).getOrThrow().use {
            it.readAll().getOrThrow().toByteArray()
        }
        val chunked = FfmpegPcmDecoder.open(opusOgg.inputStream()).getOrThrow().use { stream ->
            val output = ByteArrayOutputStream()
            while (true) {
                val bytes = stream.read(4096).getOrThrow().toByteArray()
                if (bytes.isEmpty()) break
                output.write(bytes)
            }
            output.toByteArray()
        }

        assertContentEquals(allAtOnce, chunked)
    }

    @Test
    fun completeDecodeHonorsPcmByteLimit() {
        val opusOgg = createOpusOgg()
        FfmpegPcmDecoder.open(opusOgg.inputStream()).getOrThrow().use { stream ->
            assertTrue(stream.readAll(1024).isFailure)
        }
    }

    @Test
    fun invalidInputReturnsFailure() {
        assertTrue(FfmpegPcmDecoder.open(byteArrayOf(1, 2, 3).inputStream()).isFailure)
    }

    @Test
    fun closesInputWhenInitializationFails() {
        val input = CloseTrackingInputStream(byteArrayOf(1, 2, 3).inputStream())

        assertTrue(FfmpegPcmDecoder.open(input).isFailure)
        assertEquals(1, input.closeCount)
    }

    @Test
    fun closesInputWhenStreamCloses() {
        val opusOgg = createOpusOgg()
        val input = CloseTrackingInputStream(opusOgg.inputStream())
        val stream = FfmpegPcmDecoder.open(input).getOrThrow()

        stream.close()
        stream.close()

        assertEquals(1, input.closeCount)
    }

    private fun createOpusOgg(): ByteArray {
        val outputFile = Files.createTempFile("mediaproc-opus-", ".ogg").toFile()
        val recorder = FFmpegFrameRecorder(outputFile, 1).apply {
            format = "ogg"
            audioCodecName = "libopus"
            sampleRate = 48_000
            audioChannels = 1
        }
        try {
            recorder.start()
            recorder.recordSamples(48_000, 1, ShortBuffer.wrap(ShortArray(4_800)))
            recorder.stop()
            return outputFile.readBytes()
        } finally {
            runCatching { recorder.release() }
            outputFile.delete()
        }
    }

    private fun java.nio.ByteBuffer.toByteArray(): ByteArray =
        ByteArray(remaining()).also(::get)


    private class CloseTrackingInputStream(input: InputStream) : FilterInputStream(input) {
        var closeCount = 0
            private set

        override fun close() {
            closeCount++
            super.close()
        }
    }
}
