package calebxzau.rdi.mc.v20.forge.idle

import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.network.NetworkDirection
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel

/** The client reports whether the player stepped away; the server then holds back what nobody sees. */
data class IdleStateMessage(val away: Boolean)

/**
 * Optional `rdi:idle` channel shared by the Forge 1.20.1 client and server. Either side without it keeps
 * connecting; the client only reports to a server that announced the channel.
 */
object IdleChannel {
    private const val PROTOCOL_VERSION = "1"

    @Volatile
    private var channel: SimpleChannel? = null

    /** Set by the server; runs on the server thread for every report. */
    @Volatile
    var serverHandler: ((ServerPlayer, Boolean) -> Unit)? = null

    @JvmStatic
    @Synchronized
    fun register() {
        if (channel != null) return
        val created = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("rdi", "idle"),
            { PROTOCOL_VERSION },
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
        )
        created.messageBuilder(IdleStateMessage::class.java, 0, NetworkDirection.PLAY_TO_SERVER)
            .encoder { message, buffer -> buffer.writeBoolean(message.away) }
            .decoder { buffer -> IdleStateMessage(buffer.readBoolean()) }
            .consumerMainThread { message, supplier ->
                val context = supplier.get()
                context.sender?.let { player -> serverHandler?.invoke(player, message.away) }
                context.packetHandled = true
            }
            .add()
        channel = created
    }

    @JvmStatic
    fun isSupported(connection: Connection): Boolean = channel?.isRemotePresent(connection) == true

    @JvmStatic
    fun sendToServer(away: Boolean) {
        channel?.sendToServer(IdleStateMessage(away))
    }
}
