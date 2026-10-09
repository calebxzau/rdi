package calebxzau.rdi.mc.client.preview

import org.lwjgl.system.MemoryUtil

/** Copies GPU rows into the top-origin NativeImage layout within one frame budget. */
internal object PreviewReadbackCopy {
    const val MAX_BYTES_PER_CALL: Long = 4L * 1024L * 1024L

    fun copyRows(
        sourceAddress: Long,
        destinationAddress: Long,
        width: Int,
        height: Int,
        firstDestinationRow: Int,
        deadlineNanos: Long,
        nowNanos: () -> Long = System::nanoTime,
        copy: (source: Long, destination: Long, bytes: Long) -> Unit = MemoryUtil::memCopy
    ): Int {
        require(width > 0 && height > 0) { "readback dimensions must be positive" }
        require(firstDestinationRow in 0..height) { "first row is outside readback" }
        val rowBytes = width.toLong() * 4L
        require(rowBytes <= MAX_BYTES_PER_CALL) { "one readback row exceeds the copy budget" }
        val maxRows = (MAX_BYTES_PER_CALL / rowBytes).toInt().coerceAtLeast(1)
        var row = firstDestinationRow
        while (row < height && row - firstDestinationRow < maxRows && nowNanos() < deadlineNanos) {
            val sourceRow = height - 1 - row
            copy(
                sourceAddress + sourceRow * rowBytes,
                destinationAddress + row * rowBytes,
                rowBytes
            )
            row++
        }
        return row
    }
}
