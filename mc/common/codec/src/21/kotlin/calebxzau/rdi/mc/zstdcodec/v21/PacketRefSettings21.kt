package calebxzau.rdi.mc.zstdcodec.v21

import calebxzau.rdi.mc.zstdcodec.PacketRefFormat

/** Default-on server references; settings are sampled once for each connection. */
data class PacketRefSettings21(val enabled: Boolean, val slots: Int, val maxEntryBytes: Int) {
    fun decide(memory: Boolean, negotiated: Boolean, encoderPresent: Boolean, rdiEncoder: Boolean): ExtensionDecision21 =
        ExtensionDecision21.of(enabled, memory, negotiated, encoderPresent, rdiEncoder)

    companion object {
        fun read(property: (String) -> String?, warn: (String) -> Unit): PacketRefSettings21 {
            fun integer(key: String, default: Int, range: IntRange): Int {
                val raw = property(key) ?: return default
                val parsed = raw.trim().toIntOrNull()
                    ?: return default.also { warn("Ignoring invalid ${key}=${raw}; using ${default}") }
                return parsed.coerceIn(range).also {
                    if (it != parsed) warn("Clamping ${key}=${parsed} to ${it}")
                }
            }
            return PacketRefSettings21(
                property("rdi.pktref.enabled")?.trim()?.toBoolean() ?: true,
                integer("rdi.pktref.slots", PacketRefFormat.DEFAULT_SLOTS,
                    PacketRefFormat.MINIMUM_SLOTS..PacketRefFormat.MAXIMUM_SLOTS),
                integer("rdi.pktref.maxEntryBytes", PacketRefFormat.DEFAULT_MAX_ENTRY_BYTES,
                    PacketRefFormat.MINIMUM_ENTRY_BYTES..PacketRefFormat.MAXIMUM_ENTRY_BYTES),
            )
        }
    }
}
