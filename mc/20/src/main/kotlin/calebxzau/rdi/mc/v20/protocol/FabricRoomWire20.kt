package calebxzau.rdi.mc.v20.protocol

import net.minecraft.network.FriendlyByteBuf
import java.nio.charset.StandardCharsets

/** Loader-neutral wire format shared by Fabric 1.20.1 room clients and servers. */
object FabricRoomWire20 {
    const val PLAYERS_CHANNEL = "rdi:global_players"

    const val PLAYERS_PROTOCOL_VERSION = 1

    const val MAX_JSON_LENGTH = 262144
    const val MAX_PAYLOAD_BYTES = 1048576
    const val MAX_HOSTS = 1024
    const val MAX_PLAYERS = 4096

    fun encodePlayersJson(buf: FriendlyByteBuf, json: String) {
        require(json.length <= MAX_JSON_LENGTH) { "RDI player-list JSON is too long" }
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "RDI player-list JSON payload is too large"
        }
        buf.writeVarInt(PLAYERS_PROTOCOL_VERSION)
        buf.writeUtf(json, MAX_JSON_LENGTH)
        require(buf.readableBytes() <= MAX_PAYLOAD_BYTES) { "RDI player-list payload is too large" }
    }

    fun decodePlayersJson(buf: FriendlyByteBuf): String {
        require(buf.readableBytes() <= MAX_PAYLOAD_BYTES) { "RDI player-list payload is too large" }
        val version = buf.readVarInt()
        require(version == PLAYERS_PROTOCOL_VERSION) { "Unsupported RDI player-list version" }
        val json = buf.readUtf(MAX_JSON_LENGTH)
        require(!buf.isReadable) { "RDI player-list payload has trailing bytes" }
        require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "RDI player-list JSON payload is too large"
        }
        return json
    }
}
