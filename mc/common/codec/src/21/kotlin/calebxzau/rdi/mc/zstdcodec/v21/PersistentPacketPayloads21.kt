package calebxzau.rdi.mc.zstdcodec.v21

import calebxzau.rdi.mc.zstdcodec.PersistentPacketHash
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Configuration-phase messages of persistent packets on 1.21, replacing 1.20's Forge login query.
 * Their bodies use the shared [PersistentPacketQuery] and [PersistentPacketOffer] formats.
 */
object PersistentPacketPayloads21 {
    const val VERSION = "1"

    /** The longest valid offer; a query is a single byte. */
    private const val MAXIMUM_BODY_BYTES = 3 + PersistentPacketOffer.MAXIMUM_ENTRIES * (1 + PersistentPacketHash.BYTES)

    private val BODY: StreamCodec<ByteBuf, ByteArray> = ByteBufCodecs.byteArray(MAXIMUM_BODY_BYTES)

    /** Server to client: which recipe and tag tables do you keep for this host? */
    class Query(val body: ByteArray) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Query> = TYPE

        companion object {
            @JvmField
            val TYPE = CustomPacketPayload.Type<Query>(
                ResourceLocation.fromNamespaceAndPath(PersistentPacketQuery.CHANNEL_NAMESPACE, PersistentPacketQuery.CHANNEL_PATH),
            )

            @JvmField
            val STREAM_CODEC: StreamCodec<ByteBuf, Query> = BODY.map(::Query, Query::body)
        }
    }

    /** Client to server: the encoded [PersistentPacketOffer], sent after the client attached its session. */
    class Answer(val body: ByteArray) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Answer> = TYPE

        companion object {
            @JvmField
            val TYPE = CustomPacketPayload.Type<Answer>(
                ResourceLocation.fromNamespaceAndPath(PersistentPacketQuery.CHANNEL_NAMESPACE, "${PersistentPacketQuery.CHANNEL_PATH}_answer"),
            )

            @JvmField
            val STREAM_CODEC: StreamCodec<ByteBuf, Answer> = BODY.map(::Answer, Answer::body)
        }
    }
}
