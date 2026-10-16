package calebxzau.rdi.mc.zstdcodec.v21

/**
 * Server settings of the zlib tail extension, sampled for each new connection.
 * [namespaces] lists the custom payload namespaces whose zlib fields leave inflated; "*" names all of them.
 */
data class ZlibTailSettings21(val enabled: Boolean, val namespaces: Set<String>) {
    fun decide(memory: Boolean, negotiated: Boolean, encoderPresent: Boolean, rdiEncoder: Boolean): ExtensionDecision21 =
        ExtensionDecision21.of(enabled && namespaces.isNotEmpty(), memory, negotiated, encoderPresent, rdiEncoder)

    companion object {
        /** Only mods whose zlib fields were checked to accept any valid zlib stream of the same content. */
        const val DEFAULT_NAMESPACES = "wonder_shop_story_core"

        fun read(property: (String) -> String?, warn: (String) -> Unit): ZlibTailSettings21 {
            val raw = property("rdi.zlibtail.namespaces") ?: DEFAULT_NAMESPACES
            val namespaces = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (namespaces.isEmpty()) warn("rdi.zlibtail.namespaces=${raw} names no namespace; zlib tails stay off")
            return ZlibTailSettings21(property("rdi.zlibtail.enabled")?.trim()?.toBoolean() ?: true, namespaces)
        }
    }
}
