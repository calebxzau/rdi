package calebxzau.rdi.anvilrw.remap

/**
 * Best-effort detection of mapped UUIDs in bytes that are copied unchanged: 16 big-endian binary
 * bytes, or dashed/dashless ASCII text. Input is fed in blocks; a 35-byte overlap between blocks
 * finds values that span two blocks. Detection only, so a rare false positive just adds a report line.
 */
internal class RawUuidScanner(private val matcher: UuidMatcher) {
    private var window = ByteArray(0)
    private var scratch = ByteArray(0)
    var hit = false
        private set

    fun feed(block: ByteArray, length: Int) {
        if (hit || matcher.isEmpty || length == 0) return
        val carried = minOf(window.size, OVERLAP)
        val combined = ByteArray(carried + length)
        System.arraycopy(window, window.size - carried, combined, 0, carried)
        System.arraycopy(block, 0, combined, carried, length)
        hit = scanWindow(combined)
        window = combined
    }

    fun scan(bytes: ByteArray): Boolean {
        feed(bytes, bytes.size)
        return hit
    }

    private fun scanWindow(bytes: ByteArray): Boolean {
        for (offset in 0..bytes.size - BINARY_LENGTH) {
            if (matcher.findBinary(bytes, offset) >= 0) return true
        }
        if (scratch.size < bytes.size) scratch = ByteArray(bytes.size)
        return matcher.replaceAscii(bytes, 0, bytes.size, { scratch }) {} > 0
    }

    private companion object {
        const val OVERLAP = 35
        const val BINARY_LENGTH = 16
    }
}
