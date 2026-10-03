package calebxzau.rdi.mediaproc

/** Loads the FFmpeg runtime used by in-game audio and AVIF textures, so the first use does not block. */
object MediaProcWarmup {
    fun warmUp(): Result<Unit> = runCatching {
        FfmpegPcmDecoder.ensureOpusReady().getOrThrow()
        FfmpegAvifNative.ensureDecodeReady().getOrThrow()
    }
}
