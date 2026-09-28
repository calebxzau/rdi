package calebxzhou.rdi.mc.client.mcp

import calebxzhou.rdi.mc.client.RDIClient
import calebxzhou.rdi.mc.client.mcp.standard.StandardMcpServer
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.network.FMLNetworkEvent

object McpLifecycle1710 {
    @SubscribeEvent
    fun onClientConnect(event: FMLNetworkEvent.ClientConnectedToServerEvent) {
        /*Search1710.refreshResourceIndex()
        StandardMcpServer.start(McpGameImpl1710)
            .onFailure { RDIClient.LOG?.warn("Failed to start standard MCP", it) }*/
    }

    @SubscribeEvent
    fun onClientDisconnect(event: FMLNetworkEvent.ClientDisconnectionFromServerEvent) {
        // StandardMcpServer.stop()
    }
}
