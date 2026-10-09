package calebxzau.rdi.mc.zstdcodec.v21

/** Whether one server-to-client extension starts on a connection, checked in this order. */
enum class ExtensionDecision21 {
    Disabled, MemoryConnection, RemoteAbsent, CompressionOff, ForeignEncoder, Enable;

    companion object {
        fun of(
            enabled: Boolean,
            memory: Boolean,
            negotiated: Boolean,
            encoderPresent: Boolean,
            rdiEncoder: Boolean,
        ): ExtensionDecision21 = when {
            !enabled -> Disabled
            memory -> MemoryConnection
            !negotiated -> RemoteAbsent
            !encoderPresent -> CompressionOff
            !rdiEncoder -> ForeignEncoder
            else -> Enable
        }
    }
}
