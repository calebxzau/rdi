package calebxzau.mc.common2021

import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel

/**
 * Presence-only channel that announces the RDI Zstd batch block capability.
 *
 * The channel carries no messages. Registering it puts [PROTOCOL_VERSION] into the Forge login
 * channel list, and that list is the negotiation: a peer that also registered the channel can decode
 * batch blocks, a peer that did not must keep receiving the legacy per-packet envelope.
 *
 * [NetworkRegistry.acceptMissingOr] makes the handshake tolerant of peers without this channel. Forge
 * validates every registered channel against the version the other side reported, and reports the
 * missing case as the ABSENT sentinel, so a build that only knows the older RDI protocol still
 * connects in both directions.
 */
object RdiBatchChannel {
    const val PROTOCOL_VERSION: String = "1"

    @Volatile
    private var channel: SimpleChannel? = null

    /**
     * Registers the channel with Forge.
     *
     * Must run during mod initialization, before any login handshake builds the channel list.
     */
    @Synchronized
    fun register() {
        if (channel != null) {
            return
        }
        channel = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("rdi", "batch"),
            { PROTOCOL_VERSION },
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
        )
    }

    /** True when the peer on this connection announced the batch channel during login. */
    fun isRemotePresent(connection: Connection): Boolean =
        channel?.isRemotePresent(connection) ?: false
}
