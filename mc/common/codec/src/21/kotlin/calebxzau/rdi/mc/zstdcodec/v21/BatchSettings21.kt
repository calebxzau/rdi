package calebxzau.rdi.mc.zstdcodec.v21

/**
 * Server batching settings, sampled once per connection; on unless `-Drdi.batch.enabled=false`.
 * [entityBatching] also batches entity tracking updates; off with `-Drdi.batch.entity=false`.
 */
data class BatchSettings21(val enabled: Boolean, val entityBatching: Boolean = true) {
    fun decide(memory: Boolean, negotiated: Boolean, encoderPresent: Boolean, rdiEncoder: Boolean): ExtensionDecision21 =
        ExtensionDecision21.of(enabled, memory, negotiated, encoderPresent, rdiEncoder)

    companion object {
        fun read(property: (String) -> String?): BatchSettings21 =
            BatchSettings21(
                property("rdi.batch.enabled")?.trim()?.toBoolean() ?: true,
                property("rdi.batch.entity")?.trim()?.toBoolean() ?: true,
            )
    }
}
