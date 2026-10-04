package calebxzau.rdi.mc.chunkcache.network

import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel

/**
 * Forge 1.20.1 transport for `chunk-cache-1`. The channel is optional on both sides; peers without it
 * keep receiving full chunks. Handlers run on the game thread, in order with vanilla packets.
 */
object ChunkCacheChannel {
    private val channelId = ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache")
    private var channel: SimpleChannel? = null

    /** [handler] receives every message; each side ignores the directions it does not handle. */
    @JvmStatic
    fun register(handler: (ChunkCachePayload, NetworkEvent.Context) -> Unit) {
        if (channel != null) return
        val version = ChunkCachePayloads.VERSION
        val created = NetworkRegistry.newSimpleChannel(
            channelId,
            { version },
            NetworkRegistry.acceptMissingOr(version),
            NetworkRegistry.acceptMissingOr(version),
        )
        var index = 0
        fun <T : ChunkCachePayload> message(type: Class<T>, direction: NetworkDirection, decoder: (FriendlyByteBuf) -> T) {
            created.messageBuilder(type, index++, direction)
                .encoder { payload, buffer -> payload.write(buffer) }
                .decoder(decoder)
                // Enqueued from the Netty thread in arrival order, like vanilla packets.
                .consumerMainThread { payload, supplier -> handler(payload, supplier.get()) }
                .add()
        }
        message(ChunkCacheContextPayload::class.java, NetworkDirection.PLAY_TO_CLIENT) { ChunkCacheContextPayload(it) }
        message(ChunkCacheReusePayload::class.java, NetworkDirection.PLAY_TO_CLIENT) { ChunkCacheReusePayload(it) }
        message(ChunkCacheRetirePayload::class.java, NetworkDirection.PLAY_TO_CLIENT) { ChunkCacheRetirePayload(it) }
        message(ChunkCacheOfferPayload::class.java, NetworkDirection.PLAY_TO_SERVER) { ChunkCacheOfferPayload(it) }
        message(ChunkCacheCancelPayload::class.java, NetworkDirection.PLAY_TO_SERVER) { ChunkCacheCancelPayload(it) }
        message(ChunkCacheResultPayload::class.java, NetworkDirection.PLAY_TO_SERVER) { ChunkCacheResultPayload(it) }
        channel = created
    }

    @JvmStatic
    fun isRemotePresent(connection: Connection): Boolean = channel?.isRemotePresent(connection) == true

    @JvmStatic
    fun sendToServer(payload: ChunkCachePayload) {
        channel?.sendToServer(payload)
    }

    @JvmStatic
    fun sendToPlayer(player: ServerPlayer, payload: ChunkCachePayload) {
        channel?.send(PacketDistributor.PLAYER.with { player }, payload)
    }

    /** The vanilla packet carrying [payload], so callers can keep it in a chunk send's place. */
    @JvmStatic
    fun toClientPacket(payload: ChunkCachePayload): net.minecraft.network.protocol.Packet<*> =
        checkNotNull(channel) { "Chunk cache channel is not registered" }
            .toVanillaPacket(payload, NetworkDirection.PLAY_TO_CLIENT)
}
