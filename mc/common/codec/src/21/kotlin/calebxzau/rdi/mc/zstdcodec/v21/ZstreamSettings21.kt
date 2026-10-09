package calebxzau.rdi.mc.zstdcodec.v21

import calebxzau.rdi.mc.zstdcodec.ZstdStreamFormat

/** Server settings are sampled for each new connection; there is no active-session hot toggle. */
data class ZstreamSettings21(val enabled: Boolean, val windowLog: Int) {
    fun decide(memory: Boolean, negotiated: Boolean, encoderPresent: Boolean, rdiEncoder: Boolean): ExtensionDecision21 =
        ExtensionDecision21.of(enabled, memory, negotiated, encoderPresent, rdiEncoder)

    companion object {
        fun read(property: (String) -> String?, warn: (String) -> Unit): ZstreamSettings21 {
            val default = ZstdStreamFormat.DEFAULT_WINDOW_LOG
            val minimum = ZstdStreamFormat.MINIMUM_WINDOW_LOG
            val maximum = ZstdStreamFormat.MAXIMUM_WINDOW_LOG
            val value = property("rdi.zstream.windowLog")
            val parsed = value?.trim()?.toIntOrNull()
            val window = when {
                value == null -> default
                parsed == null -> default.also { warn("Ignoring invalid rdi.zstream.windowLog=${value}; using ${default}") }
                else -> parsed.coerceIn(minimum, maximum).also {
                    if (it != parsed) {
                        warn("Clamping rdi.zstream.windowLog=${parsed} to ${it}; the accepted range is ${minimum}-${maximum}")
                    }
                }
            }
            return ZstreamSettings21(property("rdi.zstream.enabled")?.toBoolean() ?: true, window)
        }
    }
}
