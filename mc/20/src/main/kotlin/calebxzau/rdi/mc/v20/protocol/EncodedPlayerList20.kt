package calebxzau.rdi.mc.v20.protocol

import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf

/** One immutable wire snapshot, with independently owned bytes for each recipient. */
class EncodedPlayerList20 private constructor(private val bytes: ByteArray) {
    fun newPayload(): FriendlyByteBuf = FriendlyByteBuf(Unpooled.wrappedBuffer(bytes.copyOf()))

    companion object {
        fun encode(json: String): Result<EncodedPlayerList20> = runCatching {
            val buffer = FriendlyByteBuf(Unpooled.buffer())
            try {
                FabricRoomWire20.encodePlayersJson(buffer, json)
                val bytes = ByteArray(buffer.readableBytes())
                buffer.readBytes(bytes)
                EncodedPlayerList20(bytes)
            } finally {
                buffer.release()
            }
        }
    }
}
