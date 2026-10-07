package calebxzau.rdi.mc.zstdcodec

/**
 * Wire layout of the RDI packet reference extension, protocol version 1.
 *
 * Both frames reuse the leading VarInt of the legacy envelope:
 *
 *   Reference frame  VarInt 1, VarInt slot, Int check
 *   Control frame    VarInt -3, Byte opcode
 *     START (1)      VarInt version, VarInt slots, VarInt maxEntryBytes
 *     (2)            STREAM_START of the Zstd stream extension; see [ZstdStreamFormat]
 *
 * A legacy envelope never declares a negative size, so -3 is free. Its compressed form never declares
 * a size below the threshold, so the one-byte marker 1 is free whenever the threshold is at least 2.
 * Below that, the encoder sends one-byte packets raw from START onwards, and the decoder reads 1 as a
 * reference only once START has arrived.
 *
 * A reference replaces one server-to-client packet whose exact bytes both [PacketRefCache] tables
 * hold in [slot]. The check is the low 32 bits of that entry's hash, written big-endian. START marks
 * the point in the frame stream from which both ends record packets into freshly reset tables.
 */
internal object PacketRefFormat {
    const val REFERENCE_MARKER: Int = 1
    const val CONTROL_MARKER: Int = -3
    const val OPCODE_START: Int = 1
    const val VERSION: Int = 1

    const val DEFAULT_SLOTS: Int = 256
    const val MINIMUM_SLOTS: Int = 1
    const val MAXIMUM_SLOTS: Int = 1024

    const val DEFAULT_MAX_ENTRY_BYTES: Int = 1024

    /** Smallest packet both tables record; a reference is never smaller than this many plain bytes. */
    const val MINIMUM_ENTRY_BYTES: Int = 8
    const val MAXIMUM_ENTRY_BYTES: Int = 2048

    private const val CHECK_BYTES = 4
    private val FNV_OFFSET_BASIS = 0xcbf29ce484222325UL.toLong()
    private const val FNV_PRIME = 0x100000001b3L
    private val FMIX_FIRST = 0xff51afd7ed558ccdUL.toLong()
    private val FMIX_SECOND = 0xc4ceb9fe1a85ec53UL.toLong()

    /** FNV-1a 64 of the first [length] bytes, finalized with MurmurHash3's fmix64 so every bit mixes. */
    fun hash(bytes: ByteArray, length: Int = bytes.size): Long {
        var hash = FNV_OFFSET_BASIS
        for (index in 0 until length) {
            hash = (hash xor (bytes[index].toLong() and 0xFF)) * FNV_PRIME
        }
        return fmix64(hash)
    }

    fun check(hash: Long): Int = hash.toInt()

    fun isSlotCountAccepted(slots: Int): Boolean = slots in MINIMUM_SLOTS..MAXIMUM_SLOTS

    fun isMaxEntryBytesAccepted(maxEntryBytes: Int): Boolean = maxEntryBytes in MINIMUM_ENTRY_BYTES..MAXIMUM_ENTRY_BYTES

    /** Reference frame bytes without the outer length prefix. */
    fun referenceFrameBytes(slot: Int): Int =
        ZstdBatchFormat.varIntSize(REFERENCE_MARKER) + ZstdBatchFormat.varIntSize(slot) + CHECK_BYTES

    /** START frame bytes without the outer length prefix. */
    fun startFrameBytes(slots: Int, maxEntryBytes: Int): Int =
        ZstdBatchFormat.varIntSize(CONTROL_MARKER) + 1 + ZstdBatchFormat.varIntSize(VERSION) +
            ZstdBatchFormat.varIntSize(slots) + ZstdBatchFormat.varIntSize(maxEntryBytes)

    /** Frame bytes including the outer length prefix, for an inner frame of [innerBytes]. */
    fun withOuterPrefix(innerBytes: Int): Int = ZstdBatchFormat.varIntSize(innerBytes) + innerBytes

    /** An uncompressed legacy envelope for [recordBytes] bytes: outer prefix, VarInt 0, then the record. */
    fun rawLegacyFrameBytes(recordBytes: Int): Int = withOuterPrefix(1 + recordBytes)

    private fun fmix64(value: Long): Long {
        var mixed = value
        mixed = mixed xor (mixed ushr 33)
        mixed *= FMIX_FIRST
        mixed = mixed xor (mixed ushr 33)
        mixed *= FMIX_SECOND
        mixed = mixed xor (mixed ushr 33)
        return mixed
    }
}
