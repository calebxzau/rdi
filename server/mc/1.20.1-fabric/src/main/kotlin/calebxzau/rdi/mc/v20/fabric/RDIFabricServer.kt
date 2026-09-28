package calebxzau.rdi.mc.v20.fabric

import calebxzhou.rdi.mc.common.RDI
import calebxzhou.rdi.mc.common.RGlobalPlayerList
import calebxzhou.rdi.mc.common.WebSocketClient
import calebxzhou.rdi.mc.common.WsMessage
import calebxzhou.rdi.mc.common.WsMessageHandler
import calebxzhou.rdi.mc.rcmd.chat.RChatMessage
import calebxzau.rdi.mc.v20.protocol.FabricRoomWire20
import calebxzau.rdi.mc.v20.protocol.EncodedPlayerList20
import calebxzau.rdi.mc.v20.server.rcmd.RcmdServerRuntime20
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.v20.server.region.RegionZstdCodec
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class RDIFabricServer : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        val nativeMagic = ZstdCompressionPipeline.verifyNativeLoaded()
        logger.info("RDI Fabric server initialized (Zstd magic {})", nativeMagic)

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            currentSession?.takeIf { it.active.get() }?.let { sendPlayers(handler.player, it.playerList) }
            rcmdAdapter?.onPlayerJoin(handler.player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            rcmdAdapter?.onPlayerDisconnect(handler.player)
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val session = FabricServerSession(server)
            currentSession = session
            val adapter = FabricRcmdServerAdapter(server)
            rcmdAdapter = adapter
            RcmdServerRuntime20.install(adapter)
            WebSocketClient.start(session)
            logger.info("RDI Fabric room connection started for host {}", RDI.HOST_ID)
        }
        ServerTickEvents.END_SERVER_TICK.register {
            rcmdAdapter?.tick()
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            rcmdAdapter?.clear()
            currentSession?.active?.set(false)
            WebSocketClient.pauseReconnect()
        }
        ServerLifecycleEvents.SERVER_STOPPED.register {
            runCatching { RegionZstdCodec.closeAll() }.onFailure { error ->
                logger.error("Failed to close Zstd region compression pool", error)
            }
            WebSocketClient.stop()
            rcmdAdapter?.let { RcmdServerRuntime20.clear(it) }
            rcmdAdapter = null
            currentSession?.playerList = EMPTY_PLAYER_LIST
            currentSession = null
        }
    }

    private class FabricServerSession(private val server: MinecraftServer) : WsMessageHandler {
        val active = AtomicBoolean(true)
        @Volatile
        var playerList: EncodedPlayerList20 = EMPTY_PLAYER_LIST

        override fun onMessage(message: WsMessage<JsonElement>) {
            if (!active.get()) return
            when (message.channel) {
                WsMessage.Channel.Command -> {
                    val command = message.data.asString
                    dispatch { session ->
                        val response = runCatching {
                            server.commands.performPrefixedCommand(server.createCommandSourceStack(), command).toString()
                        }.getOrElse { error ->
                            logger.error("Failed to execute a room command", error)
                            "Command failed: ${error.message ?: "unknown error"}"
                        }
                        if (session.active.get()) {
                            WebSocketClient.sendMessage(message.id, WsMessage.Channel.Response, response)
                        }
                    }
                }

                WsMessage.Channel.PlayerList -> {
                    val parsed = parsePlayerList(message.data) ?: return
                    dispatch { session ->
                        session.playerList = parsed
                        server.playerList.players.forEach { player -> sendPlayers(player, parsed) }
                    }
                }

                WsMessage.Channel.Chat -> {
                    val chatMessage: RChatMessage =
                        WebSocketClient.fromJson(message.data, RChatMessage::class.java)
                    dispatch {
                        RcmdServerRuntime20.receiveRoomChat(chatMessage)
                    }
                }

                else -> Unit
            }
        }

        private inline fun dispatch(crossinline action: (FabricServerSession) -> Unit) {
            try {
                server.execute {
                    if (active.get() && currentSession === this) {
                        action(this)
                    }
                }
            } catch (error: RuntimeException) {
                if (active.get()) logger.warn("Discarding a room message because the Minecraft server is stopping", error)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger("rdi-fabric-server")
        private val gson: Gson = GsonBuilder().create()
        private val playersChannel = ResourceLocation(FabricRoomWire20.PLAYERS_CHANNEL)
        private val EMPTY_PLAYER_LIST = EncodedPlayerList20.encode(
            gson.toJson(RGlobalPlayerList(0L, emptyList()))
        ).getOrThrow()

        @Volatile
        private var currentSession: FabricServerSession? = null

        @Volatile
        private var rcmdAdapter: FabricRcmdServerAdapter? = null

        private fun parsePlayerList(element: JsonElement): EncodedPlayerList20? {
            return try {
                val json = element.toString()
                require(json.length <= FabricRoomWire20.MAX_JSON_LENGTH) { "player-list JSON exceeds character limit" }
                require(json.toByteArray(StandardCharsets.UTF_8).size <= FabricRoomWire20.MAX_PAYLOAD_BYTES) {
                    "player-list JSON exceeds payload limit"
                }
                val value = gson.fromJson(json, RGlobalPlayerList::class.java)
                    ?: throw IllegalArgumentException("player-list payload is null")
                val hosts = value.hosts()
                require(hosts.size <= FabricRoomWire20.MAX_HOSTS) { "player-list contains too many hosts" }
                val playerCount = hosts.sumOf { it.players().size }
                require(playerCount <= FabricRoomWire20.MAX_PLAYERS) { "player-list contains too many players" }
                hosts.forEach { host ->
                    require(!host.hostId().isNullOrBlank()) { "player-list host is missing its id" }
                    require(!host.hostName().isNullOrBlank()) { "player-list host is missing its name" }
                    require(!host.modpackName().isNullOrBlank()) { "player-list host is missing its modpack name" }
                    require(!host.packVer().isNullOrBlank()) { "player-list host is missing its modpack version" }
                    host.players().forEach { player ->
                        require(!player.playerId().isNullOrBlank()) { "player-list player is missing its id" }
                        require(!player.playerName().isNullOrBlank()) { "player-list player is missing its name" }
                    }
                }
                // Serialize and encode once on the WebSocket callback, before publishing to the server thread.
                EncodedPlayerList20.encode(gson.toJson(value)).getOrThrow()
            } catch (error: Exception) {
                logger.warn("Dropping an invalid or oversized master player list", error)
                null
            }
        }

        private fun sendPlayers(player: ServerPlayer, snapshot: EncodedPlayerList20) {
            val payload = snapshot.newPayload()
            try {
                ServerPlayNetworking.send(player, playersChannel, payload)
            } catch (error: Exception) {
                payload.release()
                logger.error("Failed to send the current global player list", error)
            }
        }
    }
}
