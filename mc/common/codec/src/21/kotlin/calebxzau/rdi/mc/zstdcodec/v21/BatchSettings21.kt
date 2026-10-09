package calebxzau.rdi.mc.zstdcodec.v21

/** Server batching setting, sampled once per connection; off unless `-Drdi.batch.enabled=true`. */
data class BatchSettings21(val enabled: Boolean) {
    fun decide(memory: Boolean, negotiated: Boolean, encoderPresent: Boolean, rdiEncoder: Boolean): ExtensionDecision21 =
        ExtensionDecision21.of(enabled, memory, negotiated, encoderPresent, rdiEncoder)

    companion object {
        fun read(property: (String) -> String?): BatchSettings21 =
            BatchSettings21(property("rdi.batch.enabled")?.trim()?.toBoolean() ?: true)
    }
}
