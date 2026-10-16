package calebxzau.rdi.mediaproc

import kotlinx.coroutines.sync.Semaphore
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarFile

object MediaProcRuntimeClasspath {
    fun resolve(
        classpath: String = System.getProperty("java.class.path"),
        platform: FfmpegNativePlatform = FfmpegNativePlatform.current,
    ): Result<List<File>> = runCatching {
        val codeSources = listOf(
            FfmpegPcmDecoder::class.java,
            FFmpegFrameGrabber::class.java,
            Loader::class.java,
            avcodec::class.java,
            Semaphore::class.java
        ).map { type -> File(type.protectionDomain.codeSource.location.toURI()) }
        val nativeJar = findNativeJar(classpath, codeSources, platform)
            ?: error("FFmpeg ${platform.classifier} runtime is unavailable")

        (codeSources + nativeJar).onEach { file ->
            check(file.exists()) { "Media runtime does not exist: ${file.absolutePath}" }
        }.distinctBy { it.absolutePath }
    }

    internal fun findNativeJar(
        classpath: String,
        codeSources: List<File>,
        platform: FfmpegNativePlatform = FfmpegNativePlatform.current,
    ): File? {
        fun isNativeJar(file: File): Boolean = file.isFile && platform.isNativeJarName(file.name)
        val runtimeEntries = classpath.split(File.pathSeparatorChar).map(::File)
        val directEntries = runtimeEntries + codeSources.mapNotNull(File::getParentFile).flatMap { directory ->
            directory.listFiles()?.asList().orEmpty()
        }
        return directEntries.firstOrNull(::isNativeJar)
            ?: runtimeEntries.asSequence()
                .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                .flatMap { it.manifestClasspathEntries().asSequence() }
                .firstOrNull(::isNativeJar)
    }

    fun nativeJar(files: List<File>, platform: FfmpegNativePlatform = FfmpegNativePlatform.current): File =
        files.firstOrNull { it.isFile && platform.isNativeJarName(it.name) }
            ?: error("FFmpeg ${platform.classifier} runtime is unavailable")

    private fun File.manifestClasspathEntries(): List<File> = JarFile(this).use { jar ->
        jar.manifest?.mainAttributes
            ?.getValue(Attributes.Name.CLASS_PATH)
            ?.split(Regex("\\s+"))
            ?.map { location -> File(toURI().resolve(location)) }
            .orEmpty()
    }
}
