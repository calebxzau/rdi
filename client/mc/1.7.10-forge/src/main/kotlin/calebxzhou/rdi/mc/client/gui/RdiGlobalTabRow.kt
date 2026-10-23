package calebxzhou.rdi.mc.client.gui

import calebxzhou.rdi.mc.common.RGlobalPlayerList

class RdiGlobalTabRow(
    @JvmField val text: String,
    @JvmField val color: Int,
    @JvmField val player: RGlobalPlayerList.PlayerEntry?,
) {
    constructor(text: String, color: Int) : this(text, color, null)
}
