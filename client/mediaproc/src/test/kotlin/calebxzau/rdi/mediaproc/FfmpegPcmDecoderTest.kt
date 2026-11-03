package calebxzau.rdi.mediaproc

import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegPcmDecoderTest {
    private val opusOgg: ByteArray
        get() = resource("/legacy/mono-tone.ogg")

    @Test
    fun detectsVorbisOgg() {
        val codec = javaClass.getResourceAsStream("/assets/empty.ogg")!!.buffered().use {
            OggCodecDetector.detect(it).getOrThrow()
        }

        assertEquals(OggAudioCodec.VORBIS, codec)
    }

    @Test
    fun detectsAndDecodesOpusOgg() {
        val opusOgg = opusOgg

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
        val allAtOnce = decodeAll(opusOgg)
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
        FfmpegPcmDecoder.open(opusOgg.inputStream()).getOrThrow().use { stream ->
            assertTrue(stream.readAll(1024).isFailure)
        }
    }

    @Test
    fun invalidInputReturnsFailure() {
        assertTrue(FfmpegPcmDecoder.open(byteArrayOf(1, 2, 3).inputStream()).isFailure)
    }

    @Test
    fun inputWithoutAudioReturnsFailure() {
        val input = CloseTrackingInputStream(resource("/legacy/alpha-gradient-high.avif").inputStream())

        assertTrue(FfmpegPcmDecoder.open(input).isFailure)
        assertEquals(1, input.closeCount)
    }

    @Test
    fun closesInputWhenInitializationFails() {
        val input = CloseTrackingInputStream(byteArrayOf(1, 2, 3).inputStream())

        assertTrue(FfmpegPcmDecoder.open(input).isFailure)
        assertEquals(1, input.closeCount)
    }

    @Test
    fun closesInputWhenStreamCloses() {
        val input = CloseTrackingInputStream(opusOgg.inputStream())
        val stream = FfmpegPcmDecoder.open(input).getOrThrow()

        stream.close()
        stream.close()

        assertEquals(1, input.closeCount)
    }

    @Test
    fun readAfterCloseFailsWithoutTouchingNativeMemory() {
        val stream = FfmpegPcmDecoder.open(opusOgg.inputStream()).getOrThrow()
        stream.close()

        assertTrue(stream.read(4096).isFailure)
        assertTrue(stream.readAll().isFailure)
    }

    @Test
    fun closeWaitsForAReadOnAnotherThread() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(20) {
                val stream = FfmpegPcmDecoder.open(opusOgg.inputStream()).getOrThrow()
                val start = CountDownLatch(2)
                val reading = workers.submit {
                    start.countDown()
                    start.await()
                    while (true) {
                        val chunk = stream.read(256).getOrElse { return@submit }
                        if (!chunk.hasRemaining()) return@submit
                    }
                }
                val closing = workers.submit {
                    start.countDown()
                    start.await()
                    stream.close()
                }
                reading.get(20, TimeUnit.SECONDS)
                closing.get(20, TimeUnit.SECONDS)
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun decodesConcurrentlyWithTheSameResult() {
        val input = resource("/legacy/dual-tone.ogg")
        val expected = decodeAll(input)
        val workers = Executors.newFixedThreadPool(8)
        try {
            List(16) { workers.submit<ByteArray> { decodeAll(input) } }
                .forEach { assertContentEquals(expected, it.get(30, TimeUnit.SECONDS)) }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun truncatedFileDecodesWhatItHas() {
        // This sample spans many Ogg pages, so half of it still holds whole pages.
        val input = resource("/legacy/silence-11s.ogg")
        val whole = decodeAll(input)

        val partial = decodeAll(input.copyOf(input.size / 2))

        assertTrue(partial.isNotEmpty())
        assertTrue(partial.size < whole.size)
    }

    @Test
    fun damagedFileDoesNotFailTheWholeSound() {
        val input = resource("/legacy/silence-11s.ogg")
        val whole = decodeAll(input)
        for (index in input.size / 2 until input.size / 2 + 64) input[index] = 0x55

        val damaged = decodeAll(input)

        assertTrue(damaged.isNotEmpty())
        assertTrue(damaged.size <= whole.size)
    }

    @Test
    fun readFailureOfTheInputIsReported() {
        val input = CloseTrackingInputStream(FailingInputStream(resource("/legacy/dual-tone.ogg"), failAfter = 2048))

        val result = FfmpegPcmDecoder.open(input).mapCatching { stream ->
            stream.use { it.readAll().getOrThrow() }
        }

        val failure = result.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(generateSequence(failure) { it.cause }.any { it is IOException })
        assertEquals(1, input.closeCount)
    }

    @Test
    fun inputReturningSmallChunksDecodesTheSame() {
        val input = resource("/legacy/dual-tone.ogg")
        val expected = decodeAll(input)

        val decoded = FfmpegPcmDecoder.open(SmallChunkInputStream(input)).getOrThrow().use {
            it.readAll().getOrThrow().toByteArray()
        }

        assertContentEquals(expected, decoded)
        assertFalse(decoded.isEmpty())
    }

    private fun decodeAll(input: ByteArray): ByteArray =
        FfmpegPcmDecoder.open(input.inputStream()).getOrThrow().use {
            it.readAll().getOrThrow().toByteArray()
        }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream(name)!!.use { it.readBytes() }

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

    private class FailingInputStream(bytes: ByteArray, private val failAfter: Int) : InputStream() {
        private val input = bytes.inputStream()
        private var position = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (position >= failAfter) throw IOException("Simulated read failure")
            val count = input.read(target, offset, minOf(length, failAfter - position))
            position += maxOf(count, 0)
            return count
        }
    }

    private class SmallChunkInputStream(bytes: ByteArray) : FilterInputStream(bytes.inputStream()) {
        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            super.read(target, offset, minOf(length, 17))
    }
}
