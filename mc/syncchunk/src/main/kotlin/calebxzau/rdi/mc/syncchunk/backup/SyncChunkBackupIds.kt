package calebxzau.rdi.mc.syncchunk.backup

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object SyncChunkBackupIds {
    private val random = SecureRandom()

    fun newUuidV7(nowMillis: Long = System.currentTimeMillis()): UUID {
        val bytes = ByteArray(16).also(random::nextBytes)
        for (index in 0..5) bytes[index] = (nowMillis ushr (40 - index * 8)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (index in 0..7) most = (most shl 8) or (bytes[index].toLong() and 0xff)
        for (index in 8..15) least = (least shl 8) or (bytes[index].toLong() and 0xff)
        return UUID(most, least)
    }

    fun sha1Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes))

    fun hex(digest: ByteArray): String = buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private const val HEX = "0123456789abcdef"
}
