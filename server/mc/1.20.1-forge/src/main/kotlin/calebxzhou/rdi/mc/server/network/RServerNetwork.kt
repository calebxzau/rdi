package calebxzhou.rdi.mc.server.network

// import calebxzhou.rdi.mc.chunkcache.RChunkCacheManifestPacket
// import calebxzhou.rdi.mc.chunkcache.RChunkCacheReadyPacket
// import calebxzhou.rdi.mc.chunkcache.RChunkHashPacket
// import calebxzhou.rdi.mc.chunkcache.RChunkRequestPacket
import calebxzhou.rdi.mc.common.RGlobalPlayerList
// import calebxzhou.rdi.mc.server.chunkcache.RdiChunkCacheServer
import com.google.gson.Gson
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.dedicated.DedicatedServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.simple.SimpleChannel
import kotlin.concurrent.Volatile

object RServerNetwork {
    private const val PROTOCOL_VERSION = "3"
    private val GSON = Gson()

    @Volatile
    private var registered = false

    @Volatile
    private var lastPacket: RGlobalPlayerListPacket? = null

    private val CHANNEL: SimpleChannel = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath("rdi", "general"),
        { PROTOCOL_VERSION },
        { anObject: String -> PROTOCOL_VERSION.equals(anObject) },
        { anObject: String -> PROTOCOL_VERSION.equals(anObject) }
    )

    fun register() {
        if (registered) {
            return
        }
        registered = true
        CHANNEL.messageBuilder(
            RGlobalPlayerListPacket::class.java,
            0,
            NetworkDirection.PLAY_TO_CLIENT
        )
            .encoder { obj: RGlobalPlayerListPacket, packet: FriendlyByteBuf ->
                RGlobalPlayerListPacket.encode(
                    obj,
                    packet
                )
            }
            .decoder { obj: FriendlyByteBuf -> RGlobalPlayerListPacket.decode(obj) }
            .consumerMainThread(RGlobalPlayerListPacket::handle)
            .add()
        /* CHANNEL.messageBuilder(
            RChunkCacheManifestPacket::class.java,
            2,
            NetworkDirection.PLAY_TO_SERVER
        )
            .encoder(RChunkCacheManifestPacket::encode)
            .decoder(RChunkCacheManifestPacket::decode)
            .consumerMainThread { packet, context ->
                val ctx = context.get()
                val sender = ctx.sender
                if (sender != null) {
                    RdiChunkCacheServer.acceptManifest(sender, packet)
                }
                ctx.packetHandled = true
            }
            .add() */
        /* CHANNEL.messageBuilder(
            RChunkCacheReadyPacket::class.java,
            3,
            NetworkDirection.PLAY_TO_SERVER
        )
            .encoder(RChunkCacheReadyPacket::encode)
            .decoder(RChunkCacheReadyPacket::decode)
            .consumerMainThread { _, context ->
                val ctx = context.get()
                val sender = ctx.sender
                if (sender != null) {
                    RdiChunkCacheServer.markReady(sender)
                }
                ctx.packetHandled = true
            }
            .add() */
        /* CHANNEL.messageBuilder(
            RChunkHashPacket::class.java,
            4,
            NetworkDirection.PLAY_TO_CLIENT
        )
            .encoder(RChunkHashPacket::encode)
            .decoder(RChunkHashPacket::decode)
            .consumerMainThread { _, context -> context.get().packetHandled = true }
            .add() */
        /* CHANNEL.messageBuilder(
            RChunkRequestPacket::class.java,
            5,
            NetworkDirection.PLAY_TO_SERVER
        )
            .encoder(RChunkRequestPacket::encode)
            .decoder(RChunkRequestPacket::decode)
            .consumerMainThread { packet, context ->
                val ctx = context.get()
                val sender = ctx.sender
                if (sender != null) {
                    RdiChunkCacheServer.resendChunk(sender, packet)
                }
                ctx.packetHandled = true
            }
            .add() */
    }

    fun sendToAll(server: DedicatedServer, playerList: RGlobalPlayerList) {
        val packet = RGlobalPlayerListPacket(GSON.toJson(playerList))
        lastPacket = packet
        for (player in server.getPlayerList().getPlayers()) {
            sendTo(player, packet)
        }
    }

    fun sendLastTo(player: ServerPlayer) {
        val packet = lastPacket
        if (packet != null) {
            sendTo(player, packet)
        }
    }

    private fun sendTo(player: ServerPlayer, packet: Any) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, packet)
    }

    /* fun sendChunkHash(player: ServerPlayer, packet: RChunkHashPacket) {
        CHANNEL.send(PacketDistributor.PLAYER.with { player }, packet)
    } */
}
