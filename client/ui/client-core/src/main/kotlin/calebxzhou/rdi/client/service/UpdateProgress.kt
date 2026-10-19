package calebxzhou.rdi.client.service

import calebxzhou.rdi.common.util.humanFileSize
import calebxzhou.rdi.common.net.DownloadProgress
import calebxzhou.rdi.common.util.humanSpeed

internal fun DownloadProgress.detailText(label: String): String {
    val downloadedBytes = bytesDownloaded.takeIf { it >= 0 } ?: 0L
    val percentValue = when {
        totalBytes > 0 -> downloadedBytes * 100.0 / totalBytes
        fraction >= 0 -> fraction * 100.0
        else -> -1.0
    }
    val percentText = percentValue.takeIf { it >= 0 }?.let { String.format("%.1f%%", it) } ?: "--"
    val downloadedText = downloadedBytes.takeIf { it > 0 }?.humanFileSize ?: "0B"
    val totalText = totalBytes.takeIf { it > 0 }?.humanFileSize ?: "--"
    return "$label $percentText $downloadedText/$totalText ${speedBytesPerSecond.humanSpeed}"
}
