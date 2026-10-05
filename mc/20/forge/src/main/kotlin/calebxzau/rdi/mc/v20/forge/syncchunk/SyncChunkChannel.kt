package calebxzau.rdi.mc.v20.forge.syncchunk

import calebxzau.rdi.mc.syncchunk.SyncChunkList
import calebxzau.rdi.mc.syncchunk.SyncChunkListCodec
import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkEvent
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel

/**
 * Forge 1.20.1 transport for the sync chunk list. The channel is optional on both sides, so peers
 * without it still connect and simply receive no list.
 */
object SyncChunkChannel {
    private const val PROTOCOL_VERSION = "1"
    private var channel: SimpleChannel? = null

    /** Carries the decode result, so a malformed list is rejected by the handler, not the connection. */
    class Message(val list: Result<SyncChunkList>)

    /** [handler] runs on the game thread, in order with vanilla packets. */
    @JvmStatic
    fun register(handler: (Result<SyncChunkList>, NetworkEvent.Context) -> Unit) {
        if (channel != null) return
        val created = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("rdi", "sync_chunks"),
            { PROTOCOL_VERSION },
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
        )
        created.messageBuilder(Message::class.java, 0, NetworkDirection.PLAY_TO_CLIENT)
            .encoder { message, buf -> SyncChunkListCodec.write(message.list.getOrThrow(), buf) }
            .decoder { buf -> Message(SyncChunkListCodec.read(buf)) }
            .consumerMainThread { message, supplier -> handler(message.list, supplier.get()) }
            .add()
        channel = created
    }

    @JvmStatic
    fun isRemotePresent(connection: Connection): Boolean = channel?.isRemotePresent(connection) == true

    @JvmStatic
    fun send(player: ServerPlayer, list: SyncChunkList) {
        checkNotNull(channel) { "Sync chunk channel is not registered" }
            .send(PacketDistributor.PLAYER.with { player }, Message(Result.success(list)))
    }
}
