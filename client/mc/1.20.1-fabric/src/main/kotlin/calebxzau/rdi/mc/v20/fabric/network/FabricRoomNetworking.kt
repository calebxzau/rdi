package calebxzau.rdi.mc.v20.fabric.network

import calebxzau.rdi.mc.v20.fabric.RDIFabricClient
import calebxzau.rdi.mc.v20.client.GlobalPlayerListParser
import calebxzau.rdi.mc.v20.client.GlobalPlayerListState
import calebxzau.rdi.mc.v20.protocol.FabricRoomWire20
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.resources.ResourceLocation

object FabricRoomNetworking {
    private val playersChannel = ResourceLocation(FabricRoomWire20.PLAYERS_CHANNEL)

    fun register() {
        ClientPlayConnectionEvents.INIT.register { handler, _ ->
            GlobalPlayerListState.beginSession(handler)
        }
        ClientPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            GlobalPlayerListState.endSession(handler)
        }

        ClientPlayNetworking.registerGlobalReceiver(playersChannel) { client, handler, buf, _ ->
            val generation = GlobalPlayerListState.generationFor(handler) ?: return@registerGlobalReceiver
            try {
                val json = FabricRoomWire20.decodePlayersJson(buf)
                val playerList = GlobalPlayerListParser.parse(json).getOrThrow()
                client.execute {
                    GlobalPlayerListState.update(handler, generation, playerList)
                }
            } catch (error: Exception) {
                RDIFabricClient.logger.error("Rejected invalid RDI room player list", error)
            }
        }
    }

}
