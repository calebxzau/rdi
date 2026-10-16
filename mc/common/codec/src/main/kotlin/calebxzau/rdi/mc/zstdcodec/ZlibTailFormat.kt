package calebxzau.rdi.mc.zstdcodec

/**
 * Wire layout of the RDI zlib tail extension, protocol version 1.
 *
 * Some mods deflate a large field themselves before the packet reaches the codec, for example
 * `[VarInt rawLength][VarInt zlibLength][zlib bytes]` holding a JSON snapshot. Zstd cannot compress
 * those bytes again, and the Zstd stream cannot see that one snapshot repeats most of the previous one.
 * The server therefore sends such a packet with the field inflated, and the client deflates it again
 * before the packet moves on, so the mod reads the same content from a valid zlib stream:
 *
 *   original   [prefix][VarInt rawLength][VarInt zlibLength][zlib bytes][suffix]
 *   unwrapped  [prefix][VarInt rawLength][rawLength inflated bytes][suffix]
 *
 * The frame reuses the leading VarInt of the legacy envelope, which never declares a negative size:
 *
 *   VarInt -5, Byte version, VarInt tailOffset, then one legacy envelope with the unwrapped packet
 *
 * tailOffset is the size of prefix. The envelope follows the legacy rules, including the Zstd stream
 * once it runs. The restored zlib bytes may differ from the original ones, so neither end records these
 * packets in its packet reference table. The server sends these frames only after the client negotiated
 * the extension and only for the custom payload namespaces it was configured with.
 */
internal object ZlibTailFormat {
    const val MARKER: Int = -5
    const val VERSION: Int = 1

    /** Smaller packets are never inspected; their zlib field could not save a meaningful amount. */
    const val MINIMUM_PACKET_BYTES: Int = 64

    /** The rawLength VarInt starts within this many leading bytes; the packet id and channel come first. */
    const val SEARCH_BYTES: Int = 128

    /** Bounds the inflated field, so a restored zlib field stays far below common mod payload limits. */
    const val MAXIMUM_RAW_BYTES: Int = 512 * 1024

    const val MAXIMUM_ZLIB_BYTES: Int = 900_000
}
