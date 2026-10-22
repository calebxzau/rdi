package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.model.Role
import calebxzau.rdi.server.infra.productionMongoCodecRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.BsonDocument
import org.bson.BsonDocumentWriter
import org.bson.codecs.EncoderContext
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostMemberRemovalTest {
    private val owner = ObjectId()
    private val admin = ObjectId()
    private val member = ObjectId()

    @Test
    fun `two member rooms refuse owner kicks and member or admin quits`() = runBlocking {
        for (role in listOf(Role.MEMBER, Role.ADMIN)) {
            val host = host(Host.Member(owner, Role.OWNER), Host.Member(member, role))
            for (quitting in listOf(false, true)) {
                val context = context(host, if (quitting) member else owner, member)
                var writes = 0
                val error = assertFailsWith<RequestError> {
                    HostMemberService.removeMember(context, quitting, loadCurrent = { context }) { _, _ ->
                        writes++
                        true
                    }
                }
                assertTrue(error.message.orEmpty().contains("至少需要保留2名成员"))
                assertEquals(0, writes)
            }
        }
    }

    @Test
    fun `three member rooms allow owner or admin kicks and member quits`() = runBlocking {
        val host = host(Host.Member(owner, Role.OWNER), Host.Member(admin, Role.ADMIN), Host.Member(member, Role.MEMBER))
        for ((actor, quitting) in listOf(owner to false, admin to false, member to true)) {
            val context = context(host, actor, member)
            var remaining = host.members
            HostMemberService.removeMember(context, quitting, loadCurrent = { context }) { current, target ->
                remaining = current.members.filterNot { it.id == target }
                true
            }
            assertEquals(listOf(owner, admin), remaining.map { it.id })
        }
    }

    @Test
    fun `stale requests use current members and permissions`() = runBlocking {
        val host = host(Host.Member(owner, Role.OWNER), Host.Member(admin, Role.ADMIN), Host.Member(member, Role.MEMBER))
        val reduced = host.copy(members = host.members.filterNot { it.id == admin })
        val demoted = host.copy(members = host.members.map { if (it.id == admin) it.copy(role = Role.MEMBER) else it })
        for ((actor, current) in listOf(owner to reduced, admin to demoted)) {
            val original = context(host, actor, member)
            var written = false
            assertFailsWith<RequestError> {
                HostMemberService.removeMember(original, false, loadCurrent = { context(current, actor, member) }) { _, _ ->
                    written = true
                    true
                }
            }
            assertFalse(written)
        }
    }

    @Test
    fun `owner cannot leave or be kicked and ordinary members cannot kick`() = runBlocking {
        val host = host(Host.Member(owner, Role.OWNER), Host.Member(admin, Role.ADMIN), Host.Member(member, Role.MEMBER))
        for ((actor, target, quitting) in listOf(
            Triple(owner, owner, true), Triple(owner, owner, false), Triple(admin, owner, false),
            Triple(member, admin, false),
        )) {
            val context = context(host, actor, target)
            var written = false
            assertFailsWith<RequestError> {
                HostMemberService.removeMember(context, quitting, loadCurrent = { context }) { _, _ ->
                    written = true
                    true
                }
            }
            assertFalse(written)
        }
    }

    @Test
    fun `concurrent kick and quit leave two members`() = runBlocking {
        withTimeout(5_000) {
            var current = host(Host.Member(owner, Role.OWNER), Host.Member(admin, Role.ADMIN), Host.Member(member, Role.MEMBER))
            val kickContext = context(current, owner, member)
            val quitContext = context(current, admin, admin)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val kick = async {
                HostMemberService.removeMember(kickContext, false, loadCurrent = { context(current, owner, member) }) { _, id ->
                    started.complete(Unit)
                    release.await()
                    current = current.copy(members = current.members.filterNot { it.id == id })
                    true
                }
            }
            started.await()
            val quit = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<RequestError> {
                    HostMemberService.removeMember(quitContext, true, loadCurrent = { context(current, admin, admin) }) { _, id ->
                        current = current.copy(members = current.members.filterNot { it.id == id })
                        true
                    }
                }
            }
            assertFalse(quit.isCompleted)
            release.complete(Unit)
            kick.await()
            quit.await()
            assertEquals(listOf(owner, admin), current.members.map { it.id })
        }
    }

    @Test
    fun `database snapshot conflict is reported instead of succeeding`() = runBlocking {
        val host = host(Host.Member(owner, Role.OWNER), Host.Member(admin, Role.ADMIN), Host.Member(member, Role.MEMBER))
        val context = context(host, member, member)
        val error = assertFailsWith<RequestError> {
            HostMemberService.removeMember(context, true, loadCurrent = { context }) { _, _ -> false }
        }
        assertEquals("房间成员已变化，请重试", error.message)
    }

    @Test
    fun `removal filter matches the persisted member snapshot including roles and owner`() {
        val host = host(Host.Member(owner, Role.OWNER), Host.Member(admin, Role.ADMIN), Host.Member(member, Role.MEMBER))
        val registry = productionMongoCodecRegistry()
        val encoded = BsonDocument()
        registry.get(Host::class.java).encode(
            BsonDocumentWriter(encoded), host, EncoderContext.builder().build(),
        )
        val filter = HostMemberService.memberRemovalFilter(host).toBsonDocument(BsonDocument::class.java, registry)
        val conditions = filter.getArray("\$and").map { it.asDocument() }
        for (field in listOf("_id", "ownerId", "members")) {
            assertEquals(encoded[field], conditions.single { it.containsKey(field) }[field])
        }
    }

    private fun host(vararg members: Host.Member) = Host(
        name = "测试房间", ownerId = owner, modpackId = ObjectId(), port = 25565,
        difficulty = 2, gameMode = 0, levelType = "minecraft:normal", members = members.toList(),
    )

    private fun context(host: Host, actor: ObjectId, target: ObjectId) = HostContext(
        host = host,
        player = RAccount(actor, "测试玩家", "unused", "12345678"),
        member = host.members.first { it.id == actor },
        targetMemberNull = host.members.firstOrNull { it.id == target },
    )
}
