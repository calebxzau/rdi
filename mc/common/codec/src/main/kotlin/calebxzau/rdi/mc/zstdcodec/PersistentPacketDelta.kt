package calebxzau.rdi.mc.zstdcodec

import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx

/**
 * Compresses a packet against an older version of it: the older bytes become the raw content
 * dictionary of one independent Zstd frame, so unchanged regions cost a few bytes each.
 */
internal object PersistentPacketDelta {
    private const val LEVEL = 3
    private const val MINIMUM_WINDOW_LOG = 20

    /** The decoder's default limit; a larger window would need both ends to raise it. */
    private const val MAXIMUM_WINDOW_LOG = 27

    /** Zstd reads a buffer that starts with this little-endian magic as a trained dictionary. */
    private val DICTIONARY_MAGIC = byteArrayOf(0x37, 0xA4.toByte(), 0x30, 0xEC.toByte())

    fun isUsableBase(base: ByteArray): Boolean =
        base.isNotEmpty() && !(base.size >= DICTIONARY_MAGIC.size &&
            DICTIONARY_MAGIC.indices.all { base[it] == DICTIONARY_MAGIC[it] })

    fun encode(base: ByteArray, target: ByteArray): ByteArray {
        require(isUsableBase(base)) { "A persistent packet base must not start with the Zstd dictionary magic" }
        val windowLog = windowLog(base.size.toLong() + target.size)
        return ZstdCompressCtx().use { context ->
            context.setLevel(LEVEL)
                .setChecksum(false)
                .setContentSize(true)
                .setDictID(false)
                .setLong(windowLog)
                .loadDict(base)
            context.compress(target)
        }
    }

    fun decode(base: ByteArray, delta: ByteArray, targetSize: Int): ByteArray {
        require(isUsableBase(base)) { "A persistent packet base must not start with the Zstd dictionary magic" }
        val decoded = ZstdDecompressCtx().use { context ->
            context.loadDict(base)
            context.decompress(delta, targetSize)
        }
        check(decoded.size == targetSize) { "Persistent packet delta restored ${decoded.size} bytes, declared ${targetSize}" }
        return decoded
    }

    /** A window that reaches from the end of the target back to the start of the base. */
    private fun windowLog(totalBytes: Long): Int {
        val bits = 64 - java.lang.Long.numberOfLeadingZeros((totalBytes - 1).coerceAtLeast(1))
        return bits.coerceIn(MINIMUM_WINDOW_LOG, MAXIMUM_WINDOW_LOG)
    }
}
