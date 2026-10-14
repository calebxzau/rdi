package calebxzhou.rdi.master.service.host

import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.master.service.ModpackService
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.toBriefVo
import calebxzhou.rdi.master.service.host.HostControlService.status
import calebxzhou.rdi.master.service.host.HostControlService.statusSnapshot
import calebxzhou.rdi.master.service.host.HostPresenceService.getOnlinePlayers
import calebxzhou.rdi.model.Role
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Sorts
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId

object HostQueryService {
    private val dbcl get() = HostService.dbcl

    internal data class HostListingCandidate(
        val host: Host,
        val status: HostStatus,
        val isMember: Boolean,
        val role: Role?,
        val playable: Boolean,
    )

    suspend fun getById(id: ObjectId): Host? = dbcl.find(eq("_id", id)).firstOrNull()

    suspend fun getByPort(port: Int): Host? = dbcl.find(eq("port", port)).firstOrNull()

    suspend fun getByOwner(uid: ObjectId): List<Host> =
        dbcl.find(eq("ownerId", uid)).toList()

    suspend fun findByWorld(worldId: ObjectId): Host? =
        dbcl.find(eq("worldId", worldId)).firstOrNull()

    suspend fun findByModpack(modpackId: ObjectId): List<Host> {
        return dbcl.find(eq("modpackId", modpackId)).toList()
    }

    suspend fun findByModpackVersion(modpackId: ObjectId, verName: String): List<Host> {
        return dbcl.find(
            and(
                eq("modpackId", modpackId),
                eq("packVer", verName)
            )
        ).toList()
    }

    suspend fun findByOwnerAndModpack(uid: ObjectId, modpackId: ObjectId): Host? =
        dbcl.find(
            and(
                eq("ownerId", uid),
                eq("modpackId", modpackId)
            )
        ).firstOrNull()

    suspend fun RAccount.ownHosts() = getByOwner(_id)

    suspend fun RAccount.getBriefHost(id: ObjectId): Host.BriefVo? =
        getById(id)?.let { host ->
            host.toListingCandidate(_id, host.status).toBriefVo()
        }

    suspend fun RAccount.listAllHosts(
        page: Int = 0,
        myOnly: Boolean,
        pageSize: Int = HostService.HOSTS_PER_PAGE
    ): List<Host.BriefVo> {
        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(1, 100)
        val hosts = dbcl.find()
            .sort(Sorts.descending("_id"))
            .toList()

        if (hosts.isEmpty()) return emptyList()

        val requesterId = _id
        val statuses = hosts.statusSnapshot()
        val candidates = hosts.map { host ->
            host.toListingCandidate(requesterId, statuses.getValue(host._id))
        }
        val visibleHosts = selectLegacyHosts(candidates, myOnly, safePage, safeSize)

        return coroutineScope {
            visibleHosts.map { candidate ->
                async {
                    candidate.toBriefVo()
                }
            }.awaitAll()
        }
    }

    internal fun Host.isPlayableFor(requesterId: ObjectId, status: HostStatus): Boolean {
        val isMember = ownerId == requesterId || members.any { it.id == requesterId }
        return isMember || status == HostStatus.PLAYABLE && !whitelist
    }

    private fun Host.toListingCandidate(requesterId: ObjectId, status: HostStatus): HostListingCandidate {
        val isMember = ownerId == requesterId || members.any { it.id == requesterId }
        val role = if (ownerId == requesterId) {
            Role.OWNER
        } else {
            members.firstOrNull { it.id == requesterId }?.role
        }
        return HostListingCandidate(
            host = this,
            status = status,
            isMember = isMember,
            role = role,
            playable = isPlayableFor(requesterId, status),
        )
    }

    internal fun selectLegacyHosts(
        candidates: List<HostListingCandidate>,
        myOnly: Boolean,
        page: Int,
        pageSize: Int,
    ): List<HostListingCandidate> {
        val filtered = candidates.filter { it.playable == myOnly }
        if (myOnly) return filtered

        val safePage = page.coerceAtLeast(0)
        val safeSize = pageSize.coerceIn(1, 100)
        return filtered.drop(safePage * safeSize).take(safeSize)
    }

    private suspend fun HostListingCandidate.toBriefVo(): Host.BriefVo {
        val host = host
        val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(host.modpackId)
        val onlinePlayers = host.getOnlinePlayers(status)
        return Host.BriefVo(
            _id = host._id,
            intro = host.intro,
            name = host.name,
            ownerId = host.ownerId,
            modpackName = modpack?.name ?: "未知整合包",
            iconUrl = modpack?.iconUrl,
            packVer = host.packVer,
            version = host.realVersion,
            port = host.port,
            playable = playable,
            isMember = isMember,
            role = role,
            onlinePlayerIds = onlinePlayers,
            status = status,
        )
    }

    suspend fun Host.toDetailVo(): Host.DetailVo {
        val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(modpackId)
        val modpackVo = modpack?.toBriefVo()
            ?: Modpack.BriefVo(id = modpackId, name = "未知整合包")
        val onlinePlayers = runCatching { getOnlinePlayers() }.getOrElse { emptyList() }
        return Host.DetailVo(
            _id = _id,
            name = name,
            intro = intro,
            iconUrl = modpackVo.icon,
            ownerId = ownerId,
            modpack = modpackVo,
            packVer = packVer,
            version = realVersion,
            worldId = worldId,
            port = port,
            difficulty = difficulty,
            gameMode = gameMode,
            levelType = levelType,
            gameRules = gameRules,
            whitelist = whitelist,
            allowCheats = allowCheats,
            members = members,
            extraMods = extraMods,
            disabledMods = disabledMods,
            onlinePlayerIds = onlinePlayers
        )
    }
}
