package calebxzau.rdi.mc.v20.client

import calebxzhou.rdi.mc.common.RGlobalPlayerList
import net.minecraft.client.gui.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TabLayoutCacheTest {
    private class MeasuringFont : Font({ error("Glyphs must not be loaded in layout tests") }, false) {
        var calls = 0
        var glyphWidth = 6
        override fun width(text: String): Int {
            calls++
            return text.length * glyphWidth
        }
    }

    @Test
    fun stableFramesReuseLayoutAndFontChangesInvalidateIt() {
        TabLayoutCache.clear()
        val snapshot = snapshot(8)
        val font = MeasuringFont()
        val first = TabLayoutCache.get(snapshot, 400, 240, font)
        val calls = font.calls
        repeat(120) { assertSame(first, TabLayoutCache.get(snapshot, 400, 240, font)) }
        assertEquals(calls, font.calls)

        val resized = TabLayoutCache.get(snapshot, 200, 140, font)
        assertNotSame(first, resized)
        font.glyphWidth = 12
        TabLayoutCache.invalidateFont()
        val reloaded = TabLayoutCache.get(snapshot, 200, 140, font)
        assertNotSame(resized, reloaded)
        assertTrue(reloaded.displayText().all { font.width(it) <= 176 })
        TabLayoutCache.clear()
    }

    @Test
    fun truncationCountsPlayersAndKeepsExistingRowOrder() {
        TabLayoutCache.clear()
        val snapshot = snapshot(12)
        val layout = TabLayoutCache.get(snapshot, 400, 78, MeasuringFont())
        assertEquals(5, layout.rows().size)
        assertEquals("RDI在线玩家", layout.rows()[0].text())
        assertEquals("测试房间 · 测试包 1", layout.rows()[1].text())
        assertEquals("玩家0", layout.rows()[2].text())
        assertEquals("玩家1", layout.rows()[3].text())
        assertEquals("还有10名玩家", layout.rows()[4].text())
        TabLayoutCache.clear()
    }

    @Test
    fun sessionChangesClearRowsAndRejectOldPackets() {
        val first = Any()
        val second = Any()
        val oldGeneration = GlobalPlayerListState.beginSession(first)
        assertTrue(GlobalPlayerListState.update(first, oldGeneration, playerList(3)))
        val current = GlobalPlayerListState.snapshot()
        assertEquals(3, current.playerCount)
        assertSame(current, GlobalPlayerListState.snapshot())
        val generation = GlobalPlayerListState.beginSession(second)
        assertEquals(0, GlobalPlayerListState.snapshot().playerCount)
        assertFalse(GlobalPlayerListState.update(first, oldGeneration, playerList(12)))
        assertTrue(GlobalPlayerListState.update(second, generation, playerList(1)))
        assertEquals(1, GlobalPlayerListState.snapshot().playerCount)
        assertTrue(GlobalPlayerListState.endSession(second))
        assertEquals(0, GlobalPlayerListState.snapshot().playerCount)
    }

    private fun snapshot(count: Int): GlobalPlayerListState.Snapshot {
        val list = playerList(count)
        val rows = TabRowBuilder.buildSnapshot(list)
        return GlobalPlayerListState.Snapshot(list, rows.rows, rows.playerCount)
    }

    private fun playerList(count: Int) = RGlobalPlayerList(1, listOf(
        RGlobalPlayerList.HostEntry("room", "测试房间", "测试包", "1", (0 until count).map {
            RGlobalPlayerList.PlayerEntry("00000000-0000-7000-8000-${it.toString().padStart(12, '0')}", "玩家${it}")
        })
    ))
}
