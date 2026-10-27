package calebxzhou.rdi.mc.client

import calebxzhou.rdi.mc.client.network.RClientNetwork.register
import cpw.mods.fml.common.event.FMLInitializationEvent

class ClientProxy : CommonProxy() {
    // Override CommonProxy methods here, if you want a different behaviour on the client (e.g. registering renders).
    // Don't forget to call the super methods as well.
    public override fun init(event: FMLInitializationEvent?) {
        super.init(event)
        register()
    }
}
