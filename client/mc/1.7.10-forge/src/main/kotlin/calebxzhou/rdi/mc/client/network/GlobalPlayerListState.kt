package calebxzhou.rdi.mc.client.network

import calebxzhou.rdi.mc.common.RGlobalPlayerList
import kotlin.concurrent.Volatile

object GlobalPlayerListState {
    @JvmStatic
    @Volatile
    var current: RGlobalPlayerList = RGlobalPlayerList(0L, mutableListOf<RGlobalPlayerList.HostEntry>())


    @JvmStatic
    fun update(playerList: RGlobalPlayerList) {
        current = playerList
    }
}
