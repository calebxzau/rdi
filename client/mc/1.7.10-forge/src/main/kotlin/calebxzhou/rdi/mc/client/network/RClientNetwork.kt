package calebxzhou.rdi.mc.client.network

import cpw.mods.fml.common.network.NetworkRegistry
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper
import cpw.mods.fml.relauncher.Side
import kotlin.concurrent.Volatile

object RClientNetwork {
    val CHANNEL: SimpleNetworkWrapper = NetworkRegistry.INSTANCE.newSimpleChannel("rdi_gplist")

    @Volatile
    private var registered = false

    @JvmStatic
    fun register() {
        if (registered) {
            return
        }
        registered = true
        CHANNEL.registerMessage(
            RGlobalPlayerListPacket.Handler::class.java,
            RGlobalPlayerListPacket::class.java,
            0,
            Side.CLIENT
        )
    }
}
