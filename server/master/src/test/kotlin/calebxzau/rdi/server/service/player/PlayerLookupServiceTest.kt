package calebxzau.rdi.server.service.player

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.master.exception.ParamError
import kotlinx.coroutines.runBlocking
import org.bson.types.ObjectId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayerLookupServiceTest {
    private val msid = UUID.randomUUID()
    private val account = RAccount(ObjectId(), "Steve", "hash", "12345", msid = msid)
    private var now = 0L
    private val service = PlayerLookupService(
        findByQq = { qq -> account.takeIf { qq == it.qq } },
        findByMsids = { ids -> listOf(account).filter { it.msid in ids } },
        clock = { now },
    )

    @Test
    fun byQqReturnsOnlyThePublicProfile() = runBlocking {
        assertEquals(RAccount.Dto(account._id, "Steve", account.cloth), service.byQq(ObjectId(), "12345"))
        assertFailsWith<RequestError> { service.byQq(ObjectId(), "99999") }
        assertFailsWith<ParamError> { service.byQq(ObjectId(), "abc") }
        Unit
    }

    @Test
    fun byQqIsRateLimitedPerCaller() = runBlocking {
        val caller = ObjectId()
        repeat(PlayerLookupService.LOOKUPS_PER_MINUTE) { service.byQq(caller, "12345") }
        assertFailsWith<ParamError> { service.byQq(caller, "12345") }
        service.byQq(ObjectId(), "12345")
        now += 60_001
        service.byQq(caller, "12345")
        Unit
    }

    @Test
    fun msidMatchIsBatchLimited() = runBlocking {
        assertEquals(listOf(calebxzau.rdi.common.model.SavePlayerMsidMatchVo(msid, account._id, "Steve")), service.msidMatch(listOf(msid, UUID.randomUUID())))
        assertEquals(emptyList(), service.msidMatch(emptyList()))
        assertFailsWith<ParamError> { service.msidMatch(List(65) { UUID.randomUUID() }) }
        Unit
    }
}
