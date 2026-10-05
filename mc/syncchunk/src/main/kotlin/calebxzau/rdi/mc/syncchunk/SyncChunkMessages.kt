package calebxzau.rdi.mc.syncchunk

/** Player-facing sync chunk texts, shared by every Minecraft version. */
object SyncChunkMessages {
    data class Reply(val success: Boolean, val text: String)

    const val PAGE_SIZE = 10
    const val UNAVAILABLE = "同步区块功能当前不可用，请联系服务器管理员"
    const val PLAYER_ONLY = "此命令只能由玩家执行"

    const val SHOW_OFF = "已关闭同步区块显示"
    const val SHOW_UNSUPPORTED = "当前房间不支持显示同步区块"
    const val HERE_ON = "已开启脚下区块边界显示（黄色框）"
    const val HERE_OFF = "已关闭脚下区块边界显示"

    fun label(key: SyncChunkKey): String = "${key.dimensionId},${key.chunkX},${key.chunkZ}"

    fun added(result: SyncChunkAddResult, key: SyncChunkKey): Reply = when (result) {
        SyncChunkAddResult.Added -> Reply(true, "已加入同步区块：${label(key)}")
        SyncChunkAddResult.AlreadyPresent -> Reply(true, "这个区块已经在同步列表中")
        SyncChunkAddResult.OccupiedByOther -> Reply(false, "这个区块已由其他玩家设为同步区块")
        SyncChunkAddResult.QuotaReached -> Reply(false, "同步区块数量已达到全服上限${SyncChunkState.DEFAULT_MAX_TOTAL}个")
    }

    fun removed(result: SyncChunkRemoveResult, key: SyncChunkKey): Reply = when (result) {
        SyncChunkRemoveResult.Removed -> Reply(true, "已取消同步区块：${label(key)}")
        SyncChunkRemoveResult.AlreadyAbsent -> Reply(true, "这个区块不在同步列表中")
        SyncChunkRemoveResult.OwnedByOther -> Reply(false, "这个区块由其他玩家设置，只有设置者可以取消")
    }

    fun pageCount(entryCount: Int): Int = maxOf(1, (entryCount + PAGE_SIZE - 1) / PAGE_SIZE)

    fun invalidPage(pageCount: Int): String = "页码无效，可查看第1至${pageCount}页"

    /** [page] must be within `1..pageCount(keys.size)`; owners are not listed. */
    fun listPage(keys: List<SyncChunkKey>, page: Int): String {
        val pageCount = pageCount(keys.size)
        require(page in 1..pageCount) { "页码超出范围：${page}/${pageCount}" }
        if (keys.isEmpty()) return "当前没有同步区块（0/${SyncChunkState.DEFAULT_MAX_TOTAL}）"
        val start = (page - 1) * PAGE_SIZE
        return buildList {
            add("同步区块列表：${keys.size}/${SyncChunkState.DEFAULT_MAX_TOTAL}（第${page}/${pageCount}页）")
            keys.subList(start, minOf(start + PAGE_SIZE, keys.size)).forEachIndexed { index, key ->
                add("${start + index + 1}. ${label(key)}")
            }
        }.joinToString("\n")
    }

    /** [count] is null while the room's list has not arrived yet. */
    fun showOn(count: Int?): String =
        if (count == null) "已开启同步区块显示（绿色框），暂未收到同步区块名单"
        else "已开启同步区块显示（绿色框），当前维度${count}个"
}
