package calebxzau.rdi.mediaproc

import org.bytedeco.javacpp.Loader

/**
 * File layout of the bytedeco FFmpeg natives for one platform, identified by its Maven classifier
 * such as `windows-x86_64-gpl`, `linux-x86_64-gpl` or `macosx-arm64-gpl`.
 */
data class FfmpegNativePlatform(val classifier: String) {
    val isWindows: Boolean get() = classifier.startsWith("windows-")
    val isMac: Boolean get() = classifier.startsWith("macosx-")

    /** Directory of the natives inside the classifier jar. */
    val jarPrefix: String get() = "org/bytedeco/ffmpeg/$classifier/"

    fun isNativeJarName(fileName: String): Boolean =
        fileName.startsWith("ffmpeg-") && fileName.endsWith("-$classifier.jar")

    /** JNI library whose presence marks a complete extraction. */
    val avutilJniLibrary: String
        get() = when {
            isWindows -> "jniavutil.dll"
            isMac -> "libjniavutil.dylib"
            else -> "libjniavutil.so"
        }

    val ffmpegExecutable: String get() = if (isWindows) "ffmpeg.exe" else "ffmpeg"

    companion object {
        /** The platform of this JVM, named the way JavaCPP names its classifiers. */
        val current: FfmpegNativePlatform by lazy { FfmpegNativePlatform("${Loader.getPlatform()}-gpl") }
    }
}
