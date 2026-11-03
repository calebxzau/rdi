package calebxzau.rdi.mediaproc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        withTempDir { tempDir ->
            val output = transcode(input, tempDir)

            assertIsTargetOpus(output)
            assertFalse(Files.list(tempDir).use { it.findAny().isPresent })
        }
    }

    @Test
    fun transcodesMonoOggToStereoWithNativeOpus() {
        withTempDir { tempDir ->
            val output = transcode(resource("/legacy/mono-tone.ogg"), tempDir)

            assertIsTargetOpus(output)
            FfmpegPcmDecoder.open(output.inputStream()).getOrThrow().use {
                assertEquals(2, it.format.channels)
                assertTrue(it.readAll().getOrThrow().hasRemaining())
            }
        }
    }

    @Test
    fun transcodesMonoVorbis44100HzToStereoOpus() {
        val input = javaClass.getResourceAsStream("/audio/mono-vorbis-44100.ogg.b64")!!
            .use { Base64.getMimeDecoder().decode(it.readBytes()) }
        assertEquals(OggAudioCodec.VORBIS, input.inputStream().buffered().use { OggCodecDetector.detect(it).getOrThrow() })
        withTempDir { tempDir ->
            assertIsTargetOpus(transcode(input, tempDir))
        }
    }

    @Test
    fun replacesAudioLongerThanTenSeconds() {
        withTempDir { tempDir ->
            val result = runBlocking {
                OggTranscoder.transcodeOgg(resource("/legacy/silence-11s.ogg"), tempDir).getOrThrow()
            }
            val tooLong = assertIs<OggTranscodeResult.TooLong>(result)
            assertTrue(tooLong.durationMicros > 10_000_000L)
            assertFalse(Files.list(tempDir).use { it.findAny().isPresent })
        }
    }

    @Test
    fun invalidInputReturnsFailureAndCleansTemporaryFiles() {
        withTempDir { tempDir ->
            val result = runBlocking {
                OggTranscoder.transcodeOgg(byteArrayOf(1, 2, 3), tempDir)
            }
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull() is MediaProcUnavailableException)
            assertFalse(Files.list(tempDir).use { it.findAny().isPresent })
        }
    }

    @Test
    fun inputWithoutAudioIsNotReportedAsABrokenEncoder() {
        withTempDir { tempDir ->
            val result = runBlocking {
                OggTranscoder.transcodeOgg(resource("/legacy/alpha-gradient-high.avif"), tempDir)
            }
            assertTrue(result.isFailure)
            assertFalse(result.exceptionOrNull() is MediaProcUnavailableException)
            assertFalse(Files.list(tempDir).use { it.findAny().isPresent })
        }
    }

    @Test
    fun truncatedFileTranscodesWhatItHas() {
        // This sample spans many Ogg pages, and half of it is short enough to be under the time limit.
        val input = resource("/legacy/silence-11s.ogg")
        withTempDir { tempDir ->
            assertIsTargetOpus(transcode(input.copyOf(input.size / 2), tempDir))
        }
    }

    @Test
    fun worksInADirectoryWithANonAsciiName() {
        val parent = Files.createTempDirectory("mediaproc-test-")
        val tempDir = Files.createDirectory(parent.resolve("音频 文件夹"))
        try {
            assertIsTargetOpus(transcode(resource("/legacy/mono-tone.ogg"), tempDir))
        } finally {
            Files.deleteIfExists(tempDir)
            Files.deleteIfExists(parent)
        }
    }

    @Test
    fun transcodesConcurrentlyWithTheSameSound() {
        val input = resource("/legacy/vorbis-tone.ogg")
        withTempDir { tempDir ->
            val expected = decode(transcode(input, tempDir))
            val outputs = runBlocking {
                coroutineScope {
                    List(16) {
                        async(Dispatchers.Default) { OggTranscoder.transcodeOgg(input, tempDir).getOrThrow() }
                    }.awaitAll()
                }
            }
            // The bytes differ, because each Ogg file gets a random serial number. The sound must not.
            outputs.forEach { assertContentEquals(expected, decode(assertIs<OggTranscodeResult.Encoded>(it).bytes)) }
        }
    }

    private fun transcode(input: ByteArray, tempDir: Path): ByteArray {
        val result = runBlocking { OggTranscoder.transcodeOgg(input, tempDir).getOrThrow() }
        return assertIs<OggTranscodeResult.Encoded>(result).bytes
    }

    private fun decode(input: ByteArray): ByteArray =
        FfmpegPcmDecoder.open(input.inputStream()).getOrThrow().use { stream ->
            val buffer = stream.readAll().getOrThrow()
            ByteArray(buffer.remaining()).also(buffer::get)
        }

    private fun assertIsTargetOpus(output: ByteArray) {
        assertTrue(output.isNotEmpty())
        assertEquals(OggAudioCodec.OPUS, output.inputStream().buffered().use { OggCodecDetector.detect(it).getOrThrow() })
        assertEquals(24_000, readOpusInputSampleRate(output))
        assertEquals(2, readOpusChannelCount(output))
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val tempDir = Files.createTempDirectory("mediaproc-test-")
        try {
            block(tempDir)
        } finally {
            Files.list(tempDir).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(tempDir)
        }
    }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream(name)!!.use { it.readBytes() }

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
