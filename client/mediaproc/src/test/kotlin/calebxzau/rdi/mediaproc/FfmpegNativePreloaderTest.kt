package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_OPUS
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import kotlin.test.Test
import kotlin.test.assertNotNull

/** Excluded from `test`; the `nativePreloadTest` task runs it with JavaCPP library finding disabled, as the game does. */
class FfmpegNativePreloaderTest {
    @Test
    fun loadsFfmpegFromPreloadPathWithoutLibraryFinding() {
        FfmpegNativePreloader.ensureLoaded().getOrThrow()
        MediaProcWarmup.warmUp().getOrThrow()

        assertNotNull(avcodec_find_decoder(AV_CODEC_ID_OPUS))
        assertNotNull(avcodec_find_decoder(AV_CODEC_ID_AV1))
    }
}
