package calebxzau.rdi.mc.v20.client

import calebxzhou.rdi.mc.common.RGlobalPlayerList
import calebxzau.rdi.mc.v20.protocol.FabricRoomWire20
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

object GlobalPlayerListParser {
    private val gson = Gson()

    fun parse(json: String): Result<RGlobalPlayerList> = runCatching {
        require(json.length <= FabricRoomWire20.MAX_JSON_LENGTH) { "RDI player-list JSON is too long" }
        require(json.toByteArray(StandardCharsets.UTF_8).size <= FabricRoomWire20.MAX_PAYLOAD_BYTES) {
            "RDI player-list payload is too large"
        }

        val root = JsonParser.parseString(json)
        require(root.isJsonObject) { "RDI player list must be a JSON object" }
        val rawHosts = root.asJsonObject.get("hosts")
        require(rawHosts != null && rawHosts.isJsonArray) { "RDI player list has no hosts array" }
        require(rawHosts.asJsonArray.size() <= FabricRoomWire20.MAX_HOSTS) { "RDI player list has too many hosts" }
        var playerCount = 0
        rawHosts.asJsonArray.forEach { rawHost ->
            require(rawHost.isJsonObject) { "RDI player list contains a null or invalid host" }
            val host = rawHost.asJsonObject
            require(host.stringValue("hostId").isNotBlank()) { "RDI player list contains a host without an id" }
            require(host.stringValue("hostName").isNotBlank()) { "RDI player list contains a host without a name" }
            host.stringValue("modpackName")
            host.stringValue("packVer")
            val players = host.get("players")
            require(players != null && players.isJsonArray) { "RDI player list host has no players array" }
            playerCount += players.asJsonArray.size()
            require(playerCount <= FabricRoomWire20.MAX_PLAYERS) { "RDI player list has too many players" }
            players.asJsonArray.forEach { rawPlayer ->
                require(rawPlayer.isJsonObject) { "RDI player list contains a null or invalid player" }
                val player = rawPlayer.asJsonObject
                require(player.stringValue("playerId").isNotBlank()) { "RDI player list contains a player without an id" }
                require(player.stringValue("playerName").isNotBlank()) { "RDI player list contains a player without a name" }
            }
        }

        val list = requireNotNull(gson.fromJson(root, RGlobalPlayerList::class.java)) {
            "RDI player list was null"
        }
        val hosts = requireNotNull(list.hosts()) { "RDI player list has no hosts" }
        hosts.forEach { host ->
            requireNotNull(host) { "RDI player list contains a null host" }
            require(!host.hostId().isNullOrBlank()) { "RDI player list contains a host without an id" }
            require(!host.hostName().isNullOrBlank()) { "RDI player list contains a host without a name" }
            requireNotNull(host.modpackName()) { "RDI player list contains a host without a modpack name" }
            requireNotNull(host.packVer()) { "RDI player list contains a host without a modpack version" }
            val players = requireNotNull(host.players()) { "RDI player list host has no players" }
            players.forEach { player ->
                requireNotNull(player) { "RDI player list contains a null player" }
                require(!player.playerId().isNullOrBlank()) { "RDI player list contains a player without an id" }
                require(!player.playerName().isNullOrBlank()) { "RDI player list contains a player without a name" }
            }
        }
        list
    }

    private fun com.google.gson.JsonObject.stringValue(name: String): String {
        val element = get(name)
        require(element != null && !element.isJsonNull && element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            "RDI player list contains an invalid $name"
        }
        return element.asString
    }

}
