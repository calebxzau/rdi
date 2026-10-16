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
 *
 * Warm-up messages use a second optional channel, so the indices of the first stay as older peers know them;
 * a peer has warm-up exactly when it has both channels.
 */
object ChunkCacheChannel {
    private val channelId = ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache")
    private val warmupChannelId = ResourceLocation.fromNamespaceAndPath("rdi", "chunk_cache_v2")
    private var channel: SimpleChannel? = null
    private var warmupChannel: SimpleChannel? = null

    /** [handler] receives every message; each side ignores the directions it does not handle. */
    @JvmStatic
    fun register(handler: (ChunkCachePayload, NetworkEvent.Context) -> Unit) {
        if (channel != null) return
        val created = newChannel(channelId, ChunkCachePayloads.VERSION)
        val messages = Messages(created, handler)
        messages.add(ChunkCacheContextPayload::class.java, NetworkDirection.PLAY_TO_CLIENT) { ChunkCacheContextPayload(it) }
        messages.add(ChunkCacheReusePayload::class.java, NetworkDirection.PLAY_TO_CLIENT) { ChunkCacheReusePayload(it) }
        messages.add(ChunkCacheRetirePayload::class.java, NetworkDirection.PLAY_TO_CLIENT) { ChunkCacheRetirePayload(it) }
        messages.add(ChunkCacheOfferPayload::class.java, NetworkDirection.PLAY_TO_SERVER) { ChunkCacheOfferPayload(it) }
        messages.add(ChunkCacheCancelPayload::class.java, NetworkDirection.PLAY_TO_SERVER) { ChunkCacheCancelPayload(it) }
        messages.add(ChunkCacheResultPayload::class.java, NetworkDirection.PLAY_TO_SERVER) { ChunkCacheResultPayload(it) }
        val warmup = newChannel(warmupChannelId, WARMUP_VERSION)
        val warmupMessages = Messages(warmup, handler)
        warmupMessages.add(ChunkCacheWarmupPayload::class.java, NetworkDirection.PLAY_TO_CLIENT) { ChunkCacheWarmupPayload(it) }
        warmupMessages.add(ChunkCacheAbsentPayload::class.java, NetworkDirection.PLAY_TO_SERVER) { ChunkCacheAbsentPayload(it) }
        channel = created
        warmupChannel = warmup
    }

    private fun newChannel(id: ResourceLocation, version: String): SimpleChannel = NetworkRegistry.newSimpleChannel(
        id,
        { version },
        NetworkRegistry.acceptMissingOr(version),
        NetworkRegistry.acceptMissingOr(version),
    )

    private class Messages(val channel: SimpleChannel, val handler: (ChunkCachePayload, NetworkEvent.Context) -> Unit) {
        private var index = 0

        fun <T : ChunkCachePayload> add(type: Class<T>, direction: NetworkDirection, decoder: (FriendlyByteBuf) -> T) {
            channel.messageBuilder(type, index++, direction)
                .encoder { payload, buffer -> payload.write(buffer) }
                .decoder(decoder)
                // Enqueued from the Netty thread in arrival order, like vanilla packets.
                .consumerMainThread { payload, supplier -> handler(payload, supplier.get()) }
                .add()
        }
    }

    @JvmStatic
    fun isRemotePresent(connection: Connection): Boolean = channel?.isRemotePresent(connection) == true

    /** The peer also has the warm-up messages, so it may receive a larger capacity and send absent reports. */
    @JvmStatic
    fun isWarmupRemotePresent(connection: Connection): Boolean =
        isRemotePresent(connection) && warmupChannel?.isRemotePresent(connection) == true

    @JvmStatic
    fun sendToServer(payload: ChunkCachePayload) {
        channelFor(payload)?.sendToServer(payload)
    }

    @JvmStatic
    fun sendToPlayer(player: ServerPlayer, payload: ChunkCachePayload) {
        channelFor(payload)?.send(PacketDistributor.PLAYER.with { player }, payload)
    }

    /** The vanilla packet carrying [payload], so callers can keep it in a chunk send's place. */
    @JvmStatic
    fun toClientPacket(payload: ChunkCachePayload): net.minecraft.network.protocol.Packet<*> =
        checkNotNull(channelFor(payload)) { "Chunk cache channel is not registered" }
            .toVanillaPacket(payload, NetworkDirection.PLAY_TO_CLIENT)

    private fun channelFor(payload: ChunkCachePayload): SimpleChannel? = when (payload) {
        is ChunkCacheWarmupPayload, is ChunkCacheAbsentPayload -> warmupChannel
        else -> channel
    }

    const val WARMUP_VERSION = "chunk-cache-2"
}
