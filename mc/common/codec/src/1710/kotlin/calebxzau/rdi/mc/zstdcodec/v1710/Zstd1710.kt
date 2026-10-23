package calebxzau.rdi.mc.zstdcodec.v1710

import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.ZstdPacketLimits
import calebxzau.rdi.mc.zstdcodec.ZstdStreamFormat
import com.github.luben.zstd.Zstd
import org.apache.logging.log4j.LogManager

/** Compression settings shared by the two 1.7.10 adapters. */
object Zstd1710 {
    const val THRESHOLD = 256

    /**
     * 33MiB exceeds compressBound(32MiB), including the stream's 64-byte header slack.
     * A fixed bound avoids loading JNI before the explicitly logged initialization self-test.
     */
    @JvmField
    val LIMITS = ZstdPacketLimits(32 * 1024 * 1024, 33 * 1024 * 1024)

    /** Fails mod initialization if the required native library cannot load. */
    @JvmStatic
    fun verifyNativeLoaded() {
        val logger = LogManager.getLogger("rdi")
        try {
            logger.info("Zstd native owner: classLoader={}", Zstd::class.java.classLoader)
            ZstdCompressionPipeline.verifyNativeLoaded()
            logger.info("Zstd native self-test passed for Minecraft1.7.10")
        } catch (error: Throwable) {
            logger.error("Minecraft1.7.10 Zstd native self-test failed", error)
            throw error
        }
    }

    /** Sampled once for each new connection; changing properties does not toggle active streams. */
    data class StreamSettings(val enabled: Boolean, val windowLog: Int) {
        companion object {
            fun read(property: (String) -> String?, warn: (String) -> Unit): StreamSettings {
                val default = ZstdStreamFormat.DEFAULT_WINDOW_LOG
                val minimum = ZstdStreamFormat.MINIMUM_WINDOW_LOG
                val maximum = ZstdStreamFormat.MAXIMUM_WINDOW_LOG
                val value = property("rdi.zstream.windowLog")
                val parsed = value?.trim()?.toIntOrNull()
                val window = when {
                    value == null -> default
                    parsed == null -> default.also { warn("Ignoring invalid rdi.zstream.windowLog=${value}; using ${default}") }
                    else -> parsed.coerceIn(minimum, maximum).also {
                        if (it != parsed) warn("Clamping rdi.zstream.windowLog=${parsed} to ${it}; the accepted range is ${minimum}-${maximum}")
                    }
                }
                return StreamSettings(property("rdi.zstream.enabled")?.toBoolean() ?: true, window)
            }
        }
    }
}
