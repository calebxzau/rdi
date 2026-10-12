package calebxzau.rdi.mc.zstdcodec

/**
 * Wire layout of the RDI persistent packet extension, protocol version 1.
 *
 * Large join-time packets, such as the recipe and tag tables, rarely change between two joins of the
 * same host. The client keeps their exact bytes on disk and lists their SHA-1 hashes during login; the
 * server then names a packet the client already holds instead of sending it again.
 *
 * Every frame reuses the leading VarInt of the legacy envelope, which never declares a negative size:
 *
 *   FULL (1)   VarInt -4, Byte 1, Byte kind, 20 bytes SHA-1, then one legacy envelope with the packet
 *   HIT (2)    VarInt -4, Byte 2, Byte kind, 20 bytes SHA-1
 *   DELTA (3)  VarInt -4, Byte 3, Byte kind, 20 bytes base SHA-1, 20 bytes SHA-1, VarInt size,
 *              then one Zstd frame that uses the base packet as its raw content dictionary
 *
 * The SHA-1 always names the restored packet, and the client verifies it before the packet moves on.
 * A FULL envelope follows the legacy rules, including the Zstd stream once it runs. A DELTA frame is
 * always independent of the stream. The server sends these frames only after the client answered the
 * login query of [PersistentPacketQuery].
 */
internal object PersistentPacketFormat {
    const val MARKER: Int = -4
    const val VERSION: Int = 1

    const val OP_FULL: Int = 1
    const val OP_HIT: Int = 2
    const val OP_DELTA: Int = 3

    /**
     * Smaller packets leave as plain packets. The bound lies above the largest packet reference entry,
     * so a persistent packet never enters the packet reference tables.
     */
    const val MINIMUM_PACKET_BYTES: Int = 4096

    /** Marker, opcode, kind and hash. */
    val HEADER_BYTES: Int = ZstdBatchFormat.varIntSize(MARKER) + 1 + 1 + PersistentPacketHash.BYTES

    init {
        check(MINIMUM_PACKET_BYTES > PacketRefFormat.MAXIMUM_ENTRY_BYTES)
    }
}

/** Packet kinds that persist across joins; the numbers are part of the wire format. */
object PersistentPacketKind {
    const val RECIPES: Int = 1
    const val TAGS: Int = 2

    /** Not a persistent packet. */
    const val NONE: Int = 0

    private const val MAXIMUM: Int = 15

    fun isValid(kind: Int): Boolean = kind in 1..MAXIMUM

    fun name(kind: Int): String = when (kind) {
        RECIPES -> "recipes"
        TAGS -> "tags"
        else -> "kind${kind}"
    }
}

/**
 * The login query that starts the extension. The server sends it with [CHANNEL] and [TRANSACTION_ID]
 * and a one-byte body holding its [PersistentPacketFormat.VERSION]. A client that knows the extension
 * answers with a [PersistentPacketOffer]; any other client answers without a body.
 */
object PersistentPacketQuery {
    const val CHANNEL_NAMESPACE: String = "rdi"
    const val CHANNEL_PATH: String = "pcache"
    const val CHANNEL: String = "${CHANNEL_NAMESPACE}:${CHANNEL_PATH}"

    /** Far above the loader's own indices, which count from 0. */
    const val TRANSACTION_ID: Int = 0x52444950

    const val VERSION: Int = PersistentPacketFormat.VERSION

    fun encodeQuery(): ByteArray = byteArrayOf(VERSION.toByte())

    /** The server's version, or a failure for a malformed body. */
    fun decodeQuery(body: ByteArray): Result<Int> = runCatching {
        require(body.size == 1) { "Persistent packet query carries ${body.size} bytes, expected 1" }
        body[0].toInt() and 0xFF
    }
}
