package calebxzau.rdi.mc.v20.protocol

import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation

object PacketExtensions20 {
    val channel = ResourceLocation("rdi", "packet_extensions")
    private const val VERSION = 1
    const val SUPPORTED = ZstdInboundPreparation.BATCH or ZstdInboundPreparation.STREAM or
        ZstdInboundPreparation.REFERENCES

    fun supportsStream(extensions: Int): Boolean = extensions and ZstdInboundPreparation.STREAM != 0

    fun encode(extensions: Int): FriendlyByteBuf =
        FriendlyByteBuf(Unpooled.buffer(2)).apply {
            writeByte(VERSION)
            writeByte(extensions)
        }

    /** A missing, invalid or unsupported offer or response enables no extensions. */
    fun decode(buffer: FriendlyByteBuf): Int {
        if (buffer.readableBytes() != 2 || buffer.readUnsignedByte().toInt() != VERSION) return 0
        val extensions = buffer.readUnsignedByte().toInt()
        return if (extensions and SUPPORTED == extensions) extensions else 0
    }

    fun accepted(offer: Int, response: Int): Int =
        if (response and offer == response) response else 0
}
