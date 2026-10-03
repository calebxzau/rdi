package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Runs only from the `nativePreloadTest` task, which disables JavaCPP library finding as the game does. */
class FfmpegNativePreloaderTest {
    @Test
    fun loadsFfmpegFromPreloadPathWithoutLibraryFinding() {
        assumeTrue(System.getProperty("rdi.test.nativePreload") == "true")

        FfmpegNativePreloader.ensureLoaded().getOrThrow()
        MediaProcWarmup.warmUp().getOrThrow()

        assertNotNull(avcodec_find_decoder(AV_CODEC_ID_OPUS))
        assertNotNull(avcodec_find_decoder(AV_CODEC_ID_AV1))
    }
}
