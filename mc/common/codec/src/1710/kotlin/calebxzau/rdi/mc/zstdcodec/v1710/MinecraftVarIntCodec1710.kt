package calebxzau.rdi.mc.zstdcodec.v1710

import calebxzau.rdi.mc.zstdcodec.MinecraftVarIntCodec
import io.netty.buffer.ByteBuf
import io.netty.handler.codec.DecoderException

/** Matches PacketBuffer.readVarIntFromBuffer/writeVarIntToBuffer without allocating a wrapper. */
object MinecraftVarIntCodec1710 : MinecraftVarIntCodec {
    override fun read(buffer: ByteBuf): Int {
        var value = 0
        repeat(5) { index ->
            if (!buffer.isReadable) throw DecoderException("Unterminated VarInt")
            val current = buffer.readUnsignedByte().toInt()
            value = value or ((current and 0x7F) shl (index * 7))
            if (current and 0x80 == 0) return value
        }
        throw DecoderException("VarInt too big")
    }

    override fun write(buffer: ByteBuf, value: Int) {
        var current = value
        while (current and -0x80 != 0) {
            buffer.writeByte((current and 0x7F) or 0x80)
            current = current ushr 7
        }
        buffer.writeByte(current)
    }
}
