package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import java.security.MessageDigest
import java.util.HexFormat

/** SHA-1 of one packet's exact encoded bytes; both ends name a persistent packet by it. */
class PersistentPacketHash private constructor(private val bytes: ByteArray) {
    fun toHex(): String = HEX.formatHex(bytes)

    internal fun writeTo(target: ByteBuf) {
        target.writeBytes(bytes)
    }

    internal fun writeTo(target: java.io.DataOutput) {
        target.write(bytes)
    }

    override fun equals(other: Any?): Boolean = other is PersistentPacketHash && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = toHex()

    companion object {
        const val BYTES: Int = 20
        private val HEX: HexFormat = HexFormat.of()

        fun of(content: ByteArray): PersistentPacketHash =
            PersistentPacketHash(MessageDigest.getInstance("SHA-1").digest(content))

        /** Reads exactly [BYTES] bytes; a shorter input is a failure. */
        internal fun read(source: ByteBuf): PersistentPacketHash {
            require(source.readableBytes() >= BYTES) { "Persistent packet hash ended after ${source.readableBytes()} bytes" }
            val bytes = ByteArray(BYTES)
            source.readBytes(bytes)
            return PersistentPacketHash(bytes)
        }

        internal fun read(source: java.io.DataInput): PersistentPacketHash {
            val bytes = ByteArray(BYTES)
            source.readFully(bytes)
            return PersistentPacketHash(bytes)
        }

        /** Parses a lowercase or uppercase hex name, such as a store file name. */
        fun parseHex(hex: String): Result<PersistentPacketHash> = runCatching {
            require(hex.length == BYTES * 2) { "Persistent packet hash \"${hex}\" is not ${BYTES * 2} hex digits" }
            PersistentPacketHash(HEX.parseHex(hex))
        }
    }
}
