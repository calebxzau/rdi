package calebxzhou.rdi.mc.client

import calebxzau.rdi.mc.zstdcodec.v1710.Zstd1710
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.SidedProxy
import cpw.mods.fml.common.event.FMLInitializationEvent
import cpw.mods.fml.common.event.FMLPostInitializationEvent
import cpw.mods.fml.common.event.FMLPreInitializationEvent
import cpw.mods.fml.common.event.FMLServerStartingEvent
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

@Mod(
    modid = "rdi",
    name = "rdi",
    version = Tags.VERSION,
    acceptedMinecraftVersions = "[1.7.10]",
    acceptableRemoteVersions = "*"
)
class RDIClient {
    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        Zstd1710.verifyNativeLoaded()
        proxy!!.preInit(event)
    }

    @Mod.EventHandler
    fun init(event: FMLInitializationEvent?) {
        proxy!!.init(event)
    }

    @Mod.EventHandler
    fun postInit(event: FMLPostInitializationEvent?) {
        proxy!!.postInit(event)
    }

    @Mod.EventHandler
    fun serverStarting(event: FMLServerStartingEvent?) {
        proxy!!.serverStarting(event)
    }

    companion object {
        @JvmField
        val LOG: Logger? = LogManager.getLogger("rdi")

        @SidedProxy(
            clientSide = "calebxzhou.rdi.mc.client.ClientProxy",
            serverSide = "calebxzhou.rdi.mc.client.CommonProxy"
        )
        var proxy: CommonProxy? = null
    }
}
