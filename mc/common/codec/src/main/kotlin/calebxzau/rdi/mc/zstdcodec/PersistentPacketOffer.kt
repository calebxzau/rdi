package calebxzau.rdi.mc.zstdcodec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * The client's answer to [PersistentPacketQuery]: the packets it holds, newest first within a kind.
 *
 *   Byte version, UShort count, then count times (Byte kind, 20 bytes SHA-1)
 */
data class PersistentPacketOffer(val version: Int, val entries: List<Entry>) {
    data class Entry(val kind: Int, val hash: PersistentPacketHash)

    init {
        require(entries.size <= MAXIMUM_ENTRIES) { "A persistent packet offer holds at most ${MAXIMUM_ENTRIES} entries" }
    }

    /** The offered hashes of one kind, newest first. */
    fun hashes(kind: Int): List<PersistentPacketHash> = entries.filter { it.kind == kind }.map { it.hash }

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream(3 + entries.size * (1 + PersistentPacketHash.BYTES))
        DataOutputStream(bytes).use { output ->
            output.writeByte(version)
            output.writeShort(entries.size)
            entries.forEach { entry ->
                output.writeByte(entry.kind)
                entry.hash.writeTo(output)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        const val MAXIMUM_ENTRIES: Int = 64

        fun decode(body: ByteArray): Result<PersistentPacketOffer> = runCatching {
            DataInputStream(ByteArrayInputStream(body)).use { input ->
                val version = input.readUnsignedByte()
                val count = input.readUnsignedShort()
                require(count <= MAXIMUM_ENTRIES) { "Persistent packet offer declares ${count} entries" }
                val entries = List(count) {
                    val kind = input.readUnsignedByte()
                    require(PersistentPacketKind.isValid(kind)) { "Persistent packet offer names invalid kind ${kind}" }
                    Entry(kind, PersistentPacketHash.read(input))
                }
                require(input.available() == 0) { "Persistent packet offer carries ${input.available()} trailing bytes" }
                PersistentPacketOffer(version, entries)
            }
        }
    }
}
