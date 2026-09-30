package calebxzau.rdi.mc.zstdcodec

/**
 * Wire layout of the RDI batch block.
 *
 * A batch block replaces one or more legacy per-packet envelopes inside a single outer Minecraft
 * frame, so one Zstd payload carries the exact bytes of several encoded packets:
 *
 *   VarInt  marker       -1, a value a legacy envelope can never declare
 *   VarInt  formatVersion
 *   Byte    flags        bit0 = payload is stored raw instead of as a Zstd frame
 *   VarInt  payloadBytes uncompressed size of the record area
 *   VarInt  recordCount
 *   Byte[]  payload      Zstd frame, or raw bytes when FLAG_RAW_PAYLOAD is set
 *
 * The record area repeats a VarInt length followed by that many bytes of encoded packet content
 * recordCount times. A record is exactly what the vanilla packet encoder produced for one packet,
 * so a receiving decoder hands each record to the vanilla packet decoder as if it arrived alone.
 */
internal object ZstdBatchFormat {
    /** Distinguishes a batch block from a legacy envelope, whose declared size is never negative. */
    const val MARKER: Int = -1

    const val VERSION: Int = 1

    const val FLAG_RAW_PAYLOAD: Int = 0x01

    const val MAXIMUM_RECORDS: Int = 1 shl 16

    /** Marker, version, flags, payload size and record count, each at its widest VarInt. */
    const val MAXIMUM_HEADER_BYTES: Int = 5 + 5 + 1 + 5 + 5

    fun varIntSize(value: Int): Int = when {
        value and -0x80 == 0 -> 1
        value and -0x4000 == 0 -> 2
        value and -0x200000 == 0 -> 3
        value and -0x10000000 == 0 -> 4
        else -> 5
    }
}
