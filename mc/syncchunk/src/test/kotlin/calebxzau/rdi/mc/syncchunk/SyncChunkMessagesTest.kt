package calebxzau.rdi.mc.syncchunk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncChunkMessagesTest {
    private val key = SyncChunkKey("minecraft:overworld", -3, 8)

    @Test
    fun listShowsOnlyDimensionAndChunkCoordinatesTenPerPage() {
        val keys = List(23) { SyncChunkKey("minecraft:overworld", it - 5, -it) }

        assertEquals(3, SyncChunkMessages.pageCount(keys.size))
        val firstPage = SyncChunkMessages.listPage(keys, 1).lines()
        assertEquals(SyncChunkMessages.PAGE_SIZE + 1, firstPage.size)
        assertEquals("同步区块列表：23/256（第1/3页）", firstPage[0])
        assertEquals("1. minecraft:overworld,-5,0", firstPage[1])
        assertEquals(
            listOf(
                "同步区块列表：23/256（第3/3页）",
                "21. minecraft:overworld,15,-20",
                "22. minecraft:overworld,16,-21",
                "23. minecraft:overworld,17,-22",
            ),
            SyncChunkMessages.listPage(keys, 3).lines(),
        )
    }

    @Test
    fun emptyListHasOnePageAndOutOfRangePagesAreRejected() {
        assertEquals(1, SyncChunkMessages.pageCount(0))
        assertEquals(1, SyncChunkMessages.pageCount(10))
        assertEquals(2, SyncChunkMessages.pageCount(11))
        assertEquals("当前没有同步区块（0/256）", SyncChunkMessages.listPage(emptyList(), 1))
        assertFailsWith<IllegalArgumentException> { SyncChunkMessages.listPage(emptyList(), 2) }
        assertFailsWith<IllegalArgumentException> { SyncChunkMessages.listPage(listOf(key), 0) }
    }

    @Test
    fun repeatedOrAbsentSelectionsSucceedWhileOwnershipAndQuotaFail() {
        assertEquals(SyncChunkMessages.Reply(true, "已加入同步区块：minecraft:overworld,-3,8"), SyncChunkMessages.added(SyncChunkAddResult.Added, key))
        assertTrue(SyncChunkMessages.added(SyncChunkAddResult.AlreadyPresent, key).success)
        assertFalse(SyncChunkMessages.added(SyncChunkAddResult.OccupiedByOther, key).success)
        assertEquals(
            SyncChunkMessages.Reply(false, "同步区块数量已达到全服上限256个"),
            SyncChunkMessages.added(SyncChunkAddResult.QuotaReached, key),
        )

        assertEquals(SyncChunkMessages.Reply(true, "已取消同步区块：minecraft:overworld,-3,8"), SyncChunkMessages.removed(SyncChunkRemoveResult.Removed, key))
        assertTrue(SyncChunkMessages.removed(SyncChunkRemoveResult.AlreadyAbsent, key).success)
        assertFalse(SyncChunkMessages.removed(SyncChunkRemoveResult.OwnedByOther, key).success)
    }

    @Test
    fun showMessageTellsWhetherTheListHasArrived() {
        assertEquals("已开启同步区块显示（绿色框），当前维度3个", SyncChunkMessages.showOn(3))
        assertEquals("已开启同步区块显示（绿色框），暂未收到同步区块名单", SyncChunkMessages.showOn(null))
    }
}
