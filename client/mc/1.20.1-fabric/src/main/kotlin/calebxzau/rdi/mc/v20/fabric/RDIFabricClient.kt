package calebxzau.rdi.mc.v20.fabric

import calebxzau.rdi.mc.v20.fabric.mcp.FabricMcpGame
import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpServer
import calebxzau.rdi.mc.client.chunkcache.ClientRegionZstd
import calebxzau.rdi.mc.v20.fabric.network.FabricChunkCacheClient
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.v20.fabric.network.FabricRoomNetworking
import calebxzau.rdi.mc.v20.fabric.syncchunk.SyncChunkIntegratedFabric
import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

class RDIFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        val host = RDI.GAME_IP
        val nativeMagic = ZstdCompressionPipeline.verifyNativeLoaded()
        ClientRegionZstd.version()
        FabricRoomNetworking.register()
        StandardMcpServer.start(FabricMcpGame, null)
            .onSuccess { port -> logger.info("AI MCP URL: {}", StandardMcpServer.url(port)) }
            .onFailure { error -> logger.error("Failed to start the AI MCP server", error) }
        FabricChunkCacheClient.register()
        SyncChunkIntegratedFabric.register()
        logger.info("RDI Fabric client initialized for {} (Zstd magic {})", host, nativeMagic)
    }

    companion object {
        val logger = LoggerFactory.getLogger(RDIFabricClient::class.java)
    }
}
