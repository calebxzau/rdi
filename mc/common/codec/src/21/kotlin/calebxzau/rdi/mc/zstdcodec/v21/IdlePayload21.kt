package calebxzau.rdi.mc.zstdcodec.v21

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Optional `rdi:idle` report from client to server: whether the player stepped away, so the server can hold
 * back what nobody sees. Either side without the channel keeps connecting; the client only reports to a
 * server that negotiated it.
 */
class IdlePayload21(val away: Boolean) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<IdlePayload21> = TYPE

    companion object {
        const val VERSION = "1"

        @JvmField
        val TYPE = CustomPacketPayload.Type<IdlePayload21>(ResourceLocation.fromNamespaceAndPath("rdi", "idle"))

        @JvmField
        val STREAM_CODEC: StreamCodec<ByteBuf, IdlePayload21> = ByteBufCodecs.BOOL.map(::IdlePayload21, IdlePayload21::away)
    }
}
