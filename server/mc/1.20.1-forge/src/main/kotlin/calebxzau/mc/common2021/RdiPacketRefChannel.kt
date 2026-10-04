package calebxzau.mc.common2021

import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel

/**
 * Presence-only channel that announces the RDI packet reference extension.
 *
 * Like [RdiBatchChannel] it carries no messages: registering it puts [PROTOCOL_VERSION] into the
 * Forge login channel list. The extension stays off unless both sides announced it, and
 * [NetworkRegistry.acceptMissingOr] keeps peers without the channel connecting in both directions.
 * It is a separate channel because bumping rdi:batch would make older peers refuse the login.
 */
object RdiPacketRefChannel {
    const val PROTOCOL_VERSION: String = "1"

    @Volatile
    private var channel: SimpleChannel? = null

    /** Registers the channel with Forge; must run during mod initialization. */
    @Synchronized
    fun register() {
        if (channel != null) {
            return
        }
        channel = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("rdi", "pktref"),
            { PROTOCOL_VERSION },
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
        )
    }

    /** True when the peer on this connection announced the packet reference channel during login. */
    fun isRemotePresent(connection: Connection): Boolean =
        channel?.isRemotePresent(connection) ?: false
}
