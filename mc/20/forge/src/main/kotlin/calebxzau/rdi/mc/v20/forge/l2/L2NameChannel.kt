package calebxzau.rdi.mc.v20.forge.l2

import net.minecraft.network.Connection
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.fml.ModList
import net.minecraftforge.network.NetworkRegistry
import net.minecraftforge.network.simple.SimpleChannel

/** Presence means passive name-cache hooks are installed before the login handshake. */
object L2NameChannel {
    const val L2_VERSION = "0.3.3"
    private const val PROTOCOL_VERSION = "1"
    private var channel: SimpleChannel? = null

    @JvmStatic
    fun register() {
        if (channel != null || !isLocalSupported()) return
        channel = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath("rdi", "l2_names"),
            { PROTOCOL_VERSION },
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION),
        )
    }

    @JvmStatic
    fun isLocalSupported(): Boolean = ModList.get().getModContainerById("l2tabs")
        .map { it.modInfo.version.toString() == L2_VERSION }.orElse(false)

    @JvmStatic
    fun isSupported(connection: Connection): Boolean = channel?.isRemotePresent(connection) == true
}
