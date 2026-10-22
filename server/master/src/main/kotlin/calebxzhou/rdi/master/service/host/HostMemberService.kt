package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.master.service.PlayerService
import calebxzhou.rdi.master.service.host.HostService.needAdmin
import calebxzhou.rdi.model.Role
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.util.concurrent.ConcurrentHashMap

object HostMemberService {
    private val dbcl get() = HostService.dbcl
    private val memberMutationLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> withMemberMutationLocks(
        vararg keys: String,
        block: suspend () -> T
    ): T {
        val locks = keys.distinct()
            .sorted()
            .map { memberMutationLocks.computeIfAbsent(it) { Mutex() } }

        suspend fun acquire(index: Int): T {
            if (index >= locks.size) return block()
            return locks[index].withLock {
                acquire(index + 1)
            }
        }

        return acquire(0)
    }

    suspend fun HostContext.delMember() {
        removeMember(this, quitting = false)
    }

    /**
     * Runs under the host lifecycle lock and the host member lock, so it cannot interleave with an
     * import commit or another member change. Only the two members' roles and `ownerId` are updated,
     * guarded by the expected owner and recipient, so concurrent member changes are never overwritten.
     */
    suspend fun HostContext.transferOwnership() {
        HostLifecycleLock.withLock(host._id) {
            withMemberMutationLocks("host:${host._id}") { transferOwnershipLocked() }
        }
    }

    private suspend fun HostContext.transferOwnershipLocked() {
        val current = HostQueryService.getById(host._id) ?: throw RequestError("无此房间")
        if (current.ownerId != player._id) throw RequestError("只有房间拥有者可以转移")
        val recipient = targetMember
        if (current.ownerId == recipient.id) throw RequestError("不能转给自己")
        val previousOwner = current.members.find { it.id == current.ownerId }
            ?: throw RequestError("当前拥有者不在成员列表")
        if (current.members.none { it.id == recipient.id }) throw RequestError("目标成员不在主机成员列表中")

        val result = dbcl.updateOne(
            and(
                eq("_id", current._id),
                eq(Host::ownerId.name, previousOwner.id),
                eq("${Host::members.name}.${Host.Member::id.name}", recipient.id),
            ),
            combine(
                set(Host::ownerId.name, recipient.id),
                set("${Host::members.name}.$[prev].${Host.Member::role.name}", Role.ADMIN),
                set("${Host::members.name}.$[rcpt].${Host.Member::role.name}", Role.OWNER),
            ),
            UpdateOptions().arrayFilters(
                listOf(
                    Document("prev.id", previousOwner.id),
                    Document("rcpt.id", recipient.id),
                ),
            ),
        )
        if (result.matchedCount == 0L) throw RequestError("房间成员已变化，请重试")
    }

    /**
     * Adds [playerId] as a member, with the same limits as [addMember]. A player who is already a
     * member is skipped. Failures carry a player-facing reason.
     */
    suspend fun addMemberById(hostId: ObjectId, playerId: ObjectId): Result<Unit> = try {
        withMemberMutationLocks("host:${hostId}", "player:${playerId}") {
            val current = HostQueryService.getById(hostId) ?: throw RequestError("无此房间")
            if (current.members.any { it.id == playerId }) return@withMemberMutationLocks
            memberAddProblem(current, playerId, current.members.size)?.let { throw RequestError(it) }
            dbcl.updateOne(eq("_id", current._id), Updates.push(Host::members.name, Host.Member(playerId, Role.MEMBER)))
        }
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** Why each of [playerIds] could not be added to [host] now, for players that are not members yet. */
    suspend fun previewMemberAdds(host: Host, playerIds: List<ObjectId>): List<Pair<ObjectId, String>> {
        var size = host.members.size
        return playerIds.distinct().filter { id -> host.members.none { it.id == id } }.mapNotNull { id ->
            val problem = memberAddProblem(host, id, size)
            if (problem == null) size++
            problem?.let { id to it }
        }
    }

    private suspend fun memberAddProblem(host: Host, playerId: ObjectId, memberCount: Int): String? {
        if (memberCount >= MAX_MEMBERS) return "该房间最多只能有${MAX_MEMBERS}名成员"
        val joinedCount = dbcl.countDocuments(eq("${Host::members.name}.${Host.Member::id.name}", playerId))
        if (joinedCount >= MAX_JOINED_HOSTS) return "该玩家加入的房间已达上限"
        return null
    }

    const val MAX_MEMBERS = Host.MAX_MEMBERS
    private const val MAX_JOINED_HOSTS = 10

    /** Keep admission and the initial insert under the same player locks as later invitations. */
    internal suspend fun <T> withInitialMembers(
        ownerId: ObjectId,
        requestedIds: List<ObjectId>,
        accountExists: suspend (ObjectId) -> Boolean = { PlayerService.getById(it) != null },
        joinedHostCount: suspend (ObjectId) -> Long = {
            dbcl.countDocuments(eq("${Host::members.name}.${Host.Member::id.name}", it))
        },
        persist: suspend (List<Host.Member>) -> T,
    ): T {
        val ids = requestedIds.distinct()
        if (ids.isEmpty()) throw RequestError("至少邀请1位好友一起玩")
        if (ownerId in ids) throw RequestError("不能邀请自己，请添加其他好友")
        if (ids.size >= MAX_MEMBERS) throw RequestError("该房间最多只能有${MAX_MEMBERS}名成员")
        return withMemberMutationLocks(*(ids + ownerId).map { "player:${it}" }.toTypedArray()) {
            for (id in ids) {
                if (!accountExists(id)) throw RequestError("受邀玩家账号不存在，请移除后重新添加")
                if (joinedHostCount(id) >= MAX_JOINED_HOSTS) {
                    throw RequestError("受邀玩家加入的房间已达上限，请调整好友名单")
                }
            }
            persist(listOf(Host.Member(ownerId, Role.OWNER)) + ids.map { Host.Member(it, Role.MEMBER) })
        }
    }

    suspend fun HostContext.addMember(qq: String) {
        val target = PlayerService.getByQQ(qq) ?: throw RequestError("无此账号")
        withMemberMutationLocks("host:${host._id}", "player:${target._id}") {
            val current = HostQueryService.getById(host._id) ?: throw RequestError("无此房间")
            if (current.members.any { it.id == target._id }) {
                throw RequestError("该用户已是成员")
            }
            memberAddProblem(current, target._id, current.members.size)?.let { throw RequestError(it) }
            dbcl.updateOne(
                eq("_id", current._id),
                Updates.push(Host::members.name, Host.Member(target._id, Role.MEMBER))
            )
        }
    }

    suspend fun HostContext.setRole(role: Role) {
        if (targetMember.role == role) {
            throw RequestError("角色未更改")
        }
        if (targetMember.role == Role.OWNER) {
            throw RequestError("无法更改拥有者角色")
        }
        if (role == Role.OWNER) {
            throw RequestError("请使用转移拥有者来指定新的拥有者")
        }
        dbcl.updateOne(
            eq("_id", host._id),
            combine(
                set("${Host::members.name}.$[elem].${Host.Member::role.name}", role),
            ),
            UpdateOptions().arrayFilters(
                listOf(
                    Document("elem.id", targetMember.id),
                )
            )
        )
    }

    suspend fun HostContext.quit() {
        removeMember(this, quitting = true)
    }

    /** Fresh permissions and the minimum size are checked before an atomic snapshot-guarded pull. */
    internal suspend fun removeMember(
        context: HostContext,
        quitting: Boolean,
        loadCurrent: suspend () -> HostContext = { context.fresh() },
        persist: suspend (Host, ObjectId) -> Boolean = { current, targetId ->
            dbcl.updateOne(
                memberRemovalFilter(current),
                Updates.pull(Host::members.name, eq(Host.Member::id.name, targetId)),
            ).matchedCount == 1L
        },
    ) {
        HostLifecycleLock.withLock(context.host._id) {
            withMemberMutationLocks("host:${context.host._id}") {
                val current = loadCurrent()
                val targetId = if (quitting) {
                    if (current.host.members.none { it.id == current.player._id }) {
                        throw RequestError("你不是此房间成员")
                    }
                    if (current.host.ownerId == current.player._id) {
                        throw RequestError("拥有者无法退出房间")
                    }
                    current.player._id
                } else {
                    current.needAdmin
                    val target = current.targetMember
                    if (target.id == current.host.ownerId || target.role == Role.OWNER) {
                        throw RequestError("无法踢出房间拥有者")
                    }
                    if (target.role.level <= Role.ADMIN.level && current.member.role != Role.OWNER) {
                        throw RequestError("无法踢出管理员")
                    }
                    target.id
                }
                val remaining = current.host.members.map { it.id }.toSet() - targetId
                if (remaining.size < 2) {
                    val action = if (quitting) "退出房间" else "踢出成员"
                    throw RequestError("房间至少需要保留2名成员，无法${action}")
                }
                if (!persist(current.host, targetId)) {
                    throw RequestError("房间成员已变化，请重试")
                }
            }
        }
    }

    internal fun memberRemovalFilter(current: Host): Bson = and(
        eq("_id", current._id),
        eq(Host::ownerId.name, current.ownerId),
        eq(Host::members.name, current.members.map {
            Document(Host.Member::id.name, it.id).append(Host.Member::role.name, it.role.name)
        }),
    )
}
