package calebxzau.rdi.mc.server.chunkcache

import java.security.SecureRandom
import java.util.UUID

/** Session epochs: UUIDv7, so they sort by creation time in logs. */
internal object ChunkCacheEpochs {
    private val random = SecureRandom()

    fun newUuidV7(): UUID {
        val bytes = ByteArray(16).also(random::nextBytes)
        val millis = System.currentTimeMillis()
        for (index in 0..5) bytes[index] = (millis ushr (40 - index * 8)).toByte()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        var most = 0L
        var least = 0L
        for (index in 0..7) most = (most shl 8) or (bytes[index].toLong() and 0xff)
        for (index in 8..15) least = (least shl 8) or (bytes[index].toLong() and 0xff)
        return UUID(most, least)
    }
}
