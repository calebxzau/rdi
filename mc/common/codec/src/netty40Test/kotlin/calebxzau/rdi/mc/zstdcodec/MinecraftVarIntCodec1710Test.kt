package calebxzau.rdi.mc.zstdcodec

import calebxzau.rdi.mc.zstdcodec.v1710.MinecraftVarIntCodec1710
import calebxzau.rdi.mc.zstdcodec.v1710.Zstd1710
import com.github.luben.zstd.Zstd
import io.netty.buffer.Unpooled
import io.netty.handler.codec.DecoderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Wire boundary vectors and settings without Minecraft static initialization. */
class MinecraftVarIntCodec1710Test {
    @Test
    fun `VarInt boundary values have vanilla lengths`() {
        val vectors = listOf(0 to 1, 127 to 1, 128 to 2, 16383 to 2, 16384 to 3, 2097151 to 3, 2097152 to 4, Int.MAX_VALUE to 5, -1 to 5)
        for ((value, length) in vectors) {
            val buffer = Unpooled.buffer()
            try {
                MinecraftVarIntCodec1710.write(buffer, value)
                assertEquals(length, buffer.readableBytes())
                assertEquals(value, MinecraftVarIntCodec1710.read(buffer))
                assertFalse(buffer.isReadable)
            } finally {
                buffer.release()
            }
        }
    }

    @Test
    fun `unterminated and overlong VarInts fail`() {
        for (bytes in listOf(byteArrayOf(0x80.toByte()), ByteArray(6) { 0x80.toByte() })) {
            val buffer = Unpooled.wrappedBuffer(bytes)
            try {
                assertFailsWith<DecoderException> { MinecraftVarIntCodec1710.read(buffer) }
            } finally {
                buffer.release()
            }
        }
    }

    @Test
    fun `stream settings default clamp warn and disable without global properties`() {
        val warnings = ArrayList<String>()
        assertEquals(Zstd1710.StreamSettings(true, 25), Zstd1710.StreamSettings.read({ null }, warnings::add))
        for ((value, window) in listOf("bad" to 25, "19" to 20, "26" to 25, " 22 " to 22)) {
            val settings = Zstd1710.StreamSettings.read({ if (it.endsWith("enabled")) "false" else value }, warnings::add)
            assertEquals(Zstd1710.StreamSettings(false, window), settings)
        }
        assertEquals(3, warnings.size)
        assertTrue(Zstd1710.LIMITS.maximumCompressedBytes >= Zstd.compressBound(Zstd1710.LIMITS.maximumUncompressedBytes.toLong()) + 64)
    }
}
