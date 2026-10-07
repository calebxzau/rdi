package calebxzau.rdi.server.service.player

import calebxzau.rdi.common.model.SavePlayerMsidMatchVo
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.master.exception.ParamError
import org.bson.types.ObjectId
import java.util.UUID

/**
 * Account lookups used when a save is imported into a host. Only public profile fields are returned;
 * QQ numbers, password hashes and Microsoft IDs never leave the server.
 */
class PlayerLookupService(
    private val findByQq: suspend (String) -> RAccount?,
    private val findByMsids: suspend (Collection<UUID>) -> List<RAccount>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lookupsByCaller = HashMap<ObjectId, ArrayDeque<Long>>()

    /** QQ → public profile. Rate-limited per caller, since it allows enumerating accounts by QQ. */
    suspend fun byQq(caller: ObjectId, qq: String): RAccount.Dto {
        checkRateLimit(caller)
        if (qq.isBlank() || qq.length > MAX_QQ_LENGTH || !qq.all { it.isDigit() }) throw ParamError("QQ号格式不正确")
        return findByQq(qq)?.dto ?: throw RequestError("无此账号")
    }

    /** The accounts whose bound Microsoft profile is one of [uuids]; unmatched UUIDs are left out. */
    suspend fun msidMatch(uuids: List<UUID>): List<SavePlayerMsidMatchVo> {
        if (uuids.size > MAX_MSID_BATCH) throw ParamError("一次最多查询${MAX_MSID_BATCH}个玩家")
        if (uuids.isEmpty()) return emptyList()
        return findByMsids(uuids.distinct()).mapNotNull { account ->
            account.msid?.let { SavePlayerMsidMatchVo(it, account._id, account.name) }
        }
    }

    private fun checkRateLimit(caller: ObjectId) {
        val now = clock()
        synchronized(lookupsByCaller) {
            val timestamps = lookupsByCaller.getOrPut(caller) { ArrayDeque() }
            while (timestamps.firstOrNull()?.let { it <= now - WINDOW_MILLIS } == true) timestamps.removeFirst()
            if (timestamps.size >= LOOKUPS_PER_MINUTE) throw ParamError("查询过于频繁，请稍后再试")
            timestamps.addLast(now)
        }
    }

    companion object {
        const val LOOKUPS_PER_MINUTE = 30
        const val MAX_MSID_BATCH = 64
        private const val MAX_QQ_LENGTH = 20
        private const val WINDOW_MILLIS = 60_000L
    }
}
