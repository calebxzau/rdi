package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.model.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostInitialMembersTest {
    private val owner = ObjectId()
    private val friend = ObjectId()

    @Test
    fun `missing friends self and invalid accounts never persist a room`() = runBlocking {
        for (ids in listOf(emptyList(), listOf(owner), listOf(owner, friend), listOf(friend))) {
            var persisted = false
            assertFailsWith<RequestError> {
                HostMemberService.withInitialMembers(
                    owner, ids, accountExists = { false }, joinedHostCount = { 0L },
                ) { persisted = true }
            }
            assertFalse(persisted)
        }
    }

    @Test
    fun `deduplicated friends and owner are persisted together with fixed roles`() = runBlocking {
        var writes = 0
        val members = HostMemberService.withInitialMembers(
            owner, listOf(friend, friend), accountExists = { true }, joinedHostCount = { 9L },
        ) {
            writes++
            it
        }
        assertEquals(1, writes)
        assertEquals(listOf(Host.Member(owner, Role.OWNER), Host.Member(friend, Role.MEMBER)), members)
    }

    @Test
    fun `room capacity includes owner and joined room limit is enforced`() = runBlocking {
        val maxFriends = List(Host.MAX_MEMBERS - 1) { ObjectId() }
        val members = HostMemberService.withInitialMembers(
            owner, maxFriends, accountExists = { true }, joinedHostCount = { 0L },
        ) { it }
        assertEquals(Host.MAX_MEMBERS, members.size)
        for (ids in listOf(maxFriends + friend, listOf(friend))) {
            var persisted = false
            assertFailsWith<RequestError> {
                HostMemberService.withInitialMembers(
                    owner, ids, accountExists = { true }, joinedHostCount = { 10L },
                ) { persisted = true }
            }
            assertFalse(persisted)
        }
    }

    @Test
    fun `simultaneous creations cannot exceed a friends joined room limit`() = runBlocking {
        withTimeout(5_000) {
            var count = 9L
            val inserting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first = async {
                HostMemberService.withInitialMembers(
                    owner, listOf(friend), accountExists = { true }, joinedHostCount = { count },
                ) {
                    inserting.complete(Unit)
                    release.await()
                    count++
                }
            }
            inserting.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<RequestError> {
                    HostMemberService.withInitialMembers(
                        ObjectId(), listOf(friend), accountExists = { true }, joinedHostCount = { count },
                    ) { count++ }
                }
            }
            assertFalse(second.isCompleted)
            release.complete(Unit)
            first.await()
            second.await()
            assertEquals(10L, count)
        }
    }

    @Test
    fun `failed persistence propagates and releases player locks`() = runBlocking {
        withTimeout(5_000) {
            assertFailsWith<IllegalStateException> {
                HostMemberService.withInitialMembers(
                    owner, listOf(friend), accountExists = { true }, joinedHostCount = { 0L },
                ) { error("write failed") }
            }
            val retried = HostMemberService.withInitialMembers(
                owner, listOf(friend), accountExists = { true }, joinedHostCount = { 0L },
            ) { true }
            assertTrue(retried)
        }
    }

    @Test
    fun `creation request round trips friend ids and older requests decode to empty`() {
        val dto = Host.CreateDto(
            name = "测试房间", modpackId = ObjectId(), packVer = "1.0", difficulty = 2,
            gameMode = 0, levelType = "minecraft:normal", allowCheats = false,
            whitelist = true, gameRules = mutableMapOf(), initialMemberIds = listOf(friend),
        )
        val encoded = serdesJson.encodeToString(dto)
        assertEquals(listOf(friend), serdesJson.decodeFromString<Host.CreateDto>(encoded).initialMemberIds)
        val oldRequest = serdesJson.encodeToString(dto.copy(initialMemberIds = emptyList()))
        val withoutIds = kotlinx.serialization.json.JsonObject(
            (serdesJson.parseToJsonElement(oldRequest) as kotlinx.serialization.json.JsonObject)
                .filterKeys { it != "initialMemberIds" },
        )
        assertTrue(serdesJson.decodeFromString<Host.CreateDto>(withoutIds.toString()).initialMemberIds.isEmpty())
    }
}
