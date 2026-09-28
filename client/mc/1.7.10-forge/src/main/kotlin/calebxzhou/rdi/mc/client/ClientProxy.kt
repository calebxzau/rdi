package calebxzhou.rdi.mc.client

import calebxzhou.rdi.mc.client.network.RClientNetwork.register
import calebxzhou.rdi.mc.client.mcp.McpClientNetwork1710
import calebxzhou.rdi.mc.client.mcp.McpLifecycle1710
import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.event.FMLInitializationEvent
import net.minecraftforge.common.MinecraftForge

class ClientProxy : CommonProxy() {
    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    public override fun init(event: FMLInitializationEvent?) {
        super.init(event)
        register()
        McpClientNetwork1710.register()
        FMLCommonHandler.instance().bus().register(McpLifecycle1710)
        FMLCommonHandler.instance().bus().register(JoinServerSubtitle1710)
        MinecraftForge.EVENT_BUS.register(JoinServerSubtitle1710)
    }
}
