package calebxzau.rdi.server.service.hostworldimport

import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.master.service.PlayerService
import calebxzhou.rdi.master.service.PlayerService.getPlayerNames
import calebxzhou.rdi.master.service.host.HostControlService
import calebxzhou.rdi.master.service.host.HostLifecycleLock
import calebxzhou.rdi.master.service.host.HostMemberService
import calebxzhou.rdi.master.service.host.HostQueryService
import calebxzhou.rdi.master.service.host.HostService
import calebxzhou.rdi.master.service.host.dir
import calebxzhou.rdi.master.service.modpack.ModpackQueryService
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.set
import org.bson.types.ObjectId
import java.nio.file.Path

/** The host, account and lock operations the import needs; tests substitute their own. */
interface ImportHostAccess {
    suspend fun host(hostId: ObjectId): Host?

    suspend fun modpack(modpackId: ObjectId): Modpack?

    fun isStopped(host: Host): Boolean

    fun hostDir(host: Host): Path

    suspend fun accountsExist(ids: List<ObjectId>): Boolean

    suspend fun playerNames(ids: List<ObjectId>): Map<ObjectId, String>

    suspend fun previewMemberAdds(host: Host, ids: List<ObjectId>): List<Pair<ObjectId, String>>

    suspend fun addMember(hostId: ObjectId, playerId: ObjectId): Result<Unit>

    /** Clears the host's game-rule overrides, so the save's own rules apply (D10). */
    suspend fun clearGameRules(hostId: ObjectId)

    suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T
}

object DefaultImportHostAccess : ImportHostAccess {
    override suspend fun host(hostId: ObjectId): Host? = HostQueryService.getById(hostId)

    override suspend fun modpack(modpackId: ObjectId): Modpack? = ModpackQueryService.getById(modpackId)

    override fun isStopped(host: Host): Boolean = with(HostControlService) { host.status } == HostStatus.STOPPED

    override fun hostDir(host: Host): Path = host.dir.toPath()

    override suspend fun accountsExist(ids: List<ObjectId>): Boolean =
        ids.isEmpty() || PlayerService.getByIds(ids).map { it._id }.toSet() == ids.toSet()

    override suspend fun playerNames(ids: List<ObjectId>): Map<ObjectId, String> = with(PlayerService) { ids.getPlayerNames() }

    override suspend fun previewMemberAdds(host: Host, ids: List<ObjectId>): List<Pair<ObjectId, String>> =
        HostMemberService.previewMemberAdds(host, ids)

    override suspend fun addMember(hostId: ObjectId, playerId: ObjectId): Result<Unit> =
        HostMemberService.addMemberById(hostId, playerId)

    override suspend fun clearGameRules(hostId: ObjectId) {
        HostService.dbcl.updateOne(eq("_id", hostId), set(Host::gameRules.name, emptyMap<String, String>()))
    }

    override suspend fun <T> withLifecycleLock(hostId: ObjectId, block: suspend () -> T): T =
        HostLifecycleLock.withLock(hostId) { block() }
}
