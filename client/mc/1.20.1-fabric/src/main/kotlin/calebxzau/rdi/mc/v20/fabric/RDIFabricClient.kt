package calebxzau.rdi.mc.v20.fabric

import calebxzhou.rdi.mc.rcmd.RcmdClientCommands
import calebxzhou.rdi.mc.common.RDI
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.v20.fabric.network.FabricRoomNetworking
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

class RDIFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        RcmdClientCommands.setFirmSectionDisplayEnabled(false)
        val host = RDI.GAME_IP
        val nativeMagic = ZstdCompressionPipeline.verifyNativeLoaded()
        FabricRoomNetworking.register()
        logger.info("RDI Fabric client initialized for {} (Zstd magic {})", host, nativeMagic)
    }

    companion object {
        val logger = LoggerFactory.getLogger(RDIFabricClient::class.java)
    }
}
