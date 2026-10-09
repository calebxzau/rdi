package calebxzau.rdi.mc.zstdcodec.v21

import calebxzau.rdi.mc.zstdcodec.ZstdInboundPreparation
import net.minecraft.network.Connection
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.registration.ChannelAttributes
import org.apache.logging.log4j.LogManager

/** Presence-only, version-negotiated PLAY capabilities. Neither side sends these payloads. */
object RdiExtensionChannels21 {
    val STREAM: ResourceLocation = ResourceLocation.fromNamespaceAndPath("rdi", "zstream")
    val BATCH: ResourceLocation = ResourceLocation.fromNamespaceAndPath("rdi", "batch")
    val REFERENCES: ResourceLocation = ResourceLocation.fromNamespaceAndPath("rdi", "pktref")
    private val logger = LogManager.getLogger("rdi.network-extensions")

    private class Presence(val payloadType: CustomPacketPayload.Type<Presence>) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<Presence> = payloadType
    }

    fun register(event: RegisterPayloadHandlersEvent, client: Boolean) {
        // Both sides support all three capabilities; activation still requires negotiated PLAY channels.
        val ids = supportedChannels(client)
        for (id in ids) {
            val type = CustomPacketPayload.Type<Presence>(id)
            val codec: StreamCodec<RegistryFriendlyByteBuf, Presence> = StreamCodec.unit(Presence(type))
            event.registrar("1").optional().playToClient(type, codec) { _, context ->
                logger.error("Unexpected message on presence-only channel {}", id)
                context.disconnect(Component.literal("网络协议错误，请重新进入房间"))
            }
        }
    }

    internal fun supportedChannels(@Suppress("UNUSED_PARAMETER") client: Boolean): List<ResourceLocation> =
        listOf(STREAM, BATCH, REFERENCES)

    // Deliberately no hasChannel() fallback to unversioned c:register / ad-hoc declarations.
    fun isNegotiated(connection: Connection, id: ResourceLocation): Boolean =
        ChannelAttributes.getPayloadSetup(connection)?.getChannels(ConnectionProtocol.PLAY)?.containsKey(id) == true

    fun inboundExtensions(connection: Connection): Int =
        (if (isNegotiated(connection, STREAM)) ZstdInboundPreparation.STREAM else 0) or
            (if (isNegotiated(connection, BATCH)) ZstdInboundPreparation.BATCH else 0) or
            (if (isNegotiated(connection, REFERENCES)) ZstdInboundPreparation.REFERENCES else 0)
}
