package calebxzau.rdi.mediaproc

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avdevice
import org.bytedeco.ffmpeg.global.avfilter
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.javacpp.ClassProperties
import org.bytedeco.javacpp.Loader
import java.util.Properties

/**
 * Loads FFmpeg natives from the extracted runtime directory when the game runs with
 * `org.bytedeco.javacpp.findLibraries=false`.
 *
 * With library finding enabled, JavaCPP probes every candidate file name through the mod class loader,
 * which scans every mod module and takes seconds. With it disabled, JavaCPP only reuses libraries that
 * were already loaded, so they must be loaded here first, from `platform.preloadpath`, in dependency order.
 */
object FfmpegNativePreloader {
    // FFmpegFrameGrabber/FFmpegFrameRecorder.tryLoad order; avfilter is initialized through avdevice.
    private val FFMPEG_CLASSES = listOf(
        avutil::class.java,
        swresample::class.java,
        avcodec::class.java,
        avformat::class.java,
        swscale::class.java,
        avfilter::class.java,
        avdevice::class.java,
    )

    private val result: Result<Unit> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            if (isLibraryFindingEnabled()) return@runCatching
            synchronized(Loader::class.java) {
                preload(Loader.loadProperties())
            }
        }
    }

    /** No-op when JavaCPP may search for libraries itself. Must run before any FFmpeg class is loaded. */
    fun ensureLoaded(): Result<Unit> = result

    private fun preload(platform: Properties) {
        val loaded = ArrayList<String>()
        for (type in FFMPEG_CLASSES) {
            val properties = Loader.loadProperties(type, platform, true)
            // Optional, as in Loader.load: absent system libraries are resolved by Windows when FFmpeg loads.
            for (preload in properties.get("platform.preload") + properties.get("platform.link")) {
                load(type, properties, preload.removeSuffix("!"), loaded, required = false)
            }
            val library = properties.getProperty("platform.library")
                ?: error("${type.name} declares no JNI library")
            load(type, properties, library, loaded, required = true)
        }
    }

    private fun load(
        type: Class<*>,
        properties: ClassProperties,
        library: String,
        loaded: MutableList<String>,
        required: Boolean,
    ) {
        // A null class skips class-path resources and searches only the configured library paths.
        val urls = Loader.findLibrary(null, properties, library, true)
        if (urls.isEmpty()) {
            check(!required) { "FFmpeg native library ${library} is missing from the media runtime directory" }
            return
        }
        val filename = try {
            Loader.loadLibrary(type, urls, library, *loaded.toTypedArray())
        } catch (failure: UnsatisfiedLinkError) {
            if (required) throw failure
            return
        }
        if (filename != null) loaded += filename
    }

    private fun isLibraryFindingEnabled(): Boolean {
        // Same parsing as Loader's static initializer.
        val legacy = System.getProperty("org.bytedeco.javacpp.findlibraries", "true")
        val value = System.getProperty("org.bytedeco.javacpp.findLibraries", legacy).lowercase()
        return value == "true" || value == "t" || value.isEmpty()
    }
}
