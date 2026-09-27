package calebxzau.rdi.mc.v20.client

import calebxzhou.rdi.mc.common.RGlobalPlayerList
import java.util.Collections
import java.util.UUID

data class TabRows(val rows: List<RTabRow>, val playerCount: Int)

object TabRowBuilder {
    @JvmStatic
    fun build(playerList: RGlobalPlayerList): List<RTabRow> {
        return buildSnapshot(playerList).rows
    }

    @JvmStatic
    fun buildSnapshot(playerList: RGlobalPlayerList): TabRows {
        val rows = ArrayList<RTabRow>()
        rows += RTabRow("RDI在线玩家", null, 0xFFFFD86B.toInt())
        var playerCount = 0
        playerList.hosts().forEach { host ->
            if (host.players().isEmpty()) return@forEach
            val title = if (host.modpackName().isEmpty() && host.packVer().isEmpty()) {
                host.hostName()
            } else {
                "${host.hostName()} · ${host.modpackName()} ${host.packVer()}"
            }
            rows += RTabRow(title, null, 0xFFEFEFEF.toInt())
            host.players().forEach { player ->
                rows += RTabRow(player.playerName(), playerIdToUuid(player.playerId()), 0xFFEFEFEF.toInt())
                playerCount++
            }
        }
        return TabRows(Collections.unmodifiableList(rows), playerCount)
    }

}

fun playerIdToUuid(raw: String?): UUID = runCatching {
    UUID.fromString(raw)
}.getOrElse {
    UUID(0L, raw?.hashCode()?.toLong() ?: 0L)
}
