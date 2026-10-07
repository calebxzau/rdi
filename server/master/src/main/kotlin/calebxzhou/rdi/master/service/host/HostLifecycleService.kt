package calebxzhou.rdi.master.service.host

import calebxzau.rdi.server.service.hostworldimport.HostWorldImportGuard
import calebxzhou.rdi.common.util.deleteRecursivelyNoSymlink
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.Host.Companion.getDifficultyText
import calebxzhou.rdi.common.model.Host.Companion.getGameModeText
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.util.str
import calebxzhou.rdi.common.util.validateName
import calebxzhou.rdi.common.util.ioScope
import calebxzhou.rdi.master.service.DockerService
import calebxzhou.rdi.master.service.MailService
import calebxzhou.rdi.master.service.ModpackService
import calebxzhou.rdi.master.service.WorldService
import calebxzhou.rdi.master.service.host.HostControlService.clearShutFlag
import calebxzhou.rdi.master.service.host.HostControlService.playable
import calebxzhou.rdi.master.service.host.HostControlService.sendCommand
import calebxzhou.rdi.master.service.host.HostControlService.status
import calebxzhou.rdi.master.service.host.HostContainerService.requireModernLog4j2Config
import calebxzhou.rdi.master.service.host.HostInstallService.startCreateHost
import calebxzhou.rdi.master.service.host.HostInstallService.writeServerProperties
import calebxzhou.rdi.master.service.host.HostQueryService.getById
import calebxzhou.rdi.master.service.host.HostService.needAdmin
import calebxzhou.rdi.master.service.host.HostService.needOwner
import calebxzhou.rdi.master.service.host.HostRuntimeService.hostStates
import calebxzhou.rdi.master.service.modpack.ModpackVersionMutationLock
import calebxzau.rdi.server.service.baseworld.BaseWorldService
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Updates.combine
import com.mongodb.client.model.Updates.set
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.launch
import org.bson.conversions.Bson
import java.nio.file.Files
import java.nio.file.LinkOption

object HostLifecycleService {
    private val dbcl get() = HostService.dbcl

    internal fun resolveRequiredBaseWorldId(modpack: Modpack, host: Host): java.util.UUID? {
        val version = modpack.versions.firstOrNull { it.name == host.packVer }
            ?: throw RequestError("无此版本")
        return version.baseWorld?.takeIf { it.required }?.id
    }

    internal fun resolveResetBaseWorldId(modpack: Modpack, host: Host): java.util.UUID? =
        resolveRequiredBaseWorldId(modpack, host) ?: host.baseWorldId

    suspend fun HostContext.delete(payload: Host.DeleteDto = Host.DeleteDto()) {
        HostLifecycleLock.withLock(host._id) { fresh().needOwner.deleteLocked(payload) }
    }

    private suspend fun HostContext.deleteLocked(payload: Host.DeleteDto) {
        val current = host
        HostWorldImportGuard.check(current._id)
        if (current.status != HostStatus.STOPPED) {
            throw RequestError("请先去后台停止房间后 再删除")
        }
        if (current.realVersion == 2 && current.worldId != null) {
            throw RequestError("v2房间存档数据无效")
        }
        val worldIdToDelete = current.worldId.takeIf { current.realVersion == 1 && payload.deleteWorld }
        DockerService.deleteContainer(current._id.str)

        val source = current.dir
        val isolationRoot = source.parentFile.resolve(".deleting").apply { mkdirs() }
        val isolated = isolationRoot.resolve(current._id.str)
        if (Files.exists(isolated.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw RequestError("房间删除目录已存在，请联系管理员处理")
        }
        if (source.exists()) {
            Files.move(source.toPath(), isolated.toPath())
        }
        try {
            val result = dbcl.deleteOne(eq("_id", current._id))
            if (result.deletedCount != 1L) throw RequestError("房间记录删除失败")
        } catch (error: Throwable) {
            if (Files.exists(isolated.toPath(), LinkOption.NOFOLLOW_LINKS) && !source.exists()) {
                runCatching { Files.move(isolated.toPath(), source.toPath()) }
            }
            throw error
        }

        hostStates.remove(current._id)?.let { state ->
            state.shutdownJob?.cancel()
            state.session?.launch {
                runCatching { state.session?.close(CloseReason(CloseReason.Codes.NORMAL, "Host deleted")) }
            }
        }
        HostService.skipWorldSizeUpdate.remove(current._id)
        runCatching { HostWorldImportGuard.onHostDeleted(current._id) }
            .onFailure { error -> HostService.lgr.error(error) { "清理已删除房间的存档导入任务失败: ${current._id}" } }
        worldIdToDelete?.let { WorldService.delete(player._id, it) }
        if (Files.exists(isolated.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            ioScope.launch {
                runCatching { isolated.deleteRecursivelyNoSymlink() }
                    .onFailure { error -> HostService.lgr.error(error) { "清理已删除房间目录失败: $isolated" } }
            }
        }
    }

    suspend fun HostContext.resetWorld(baseWorldService: BaseWorldService) {
        HostLifecycleLock.withLock(host._id) { fresh().needOwner.resetWorldLocked(baseWorldService) }
    }

    private suspend fun HostContext.resetWorldLocked(baseWorldService: BaseWorldService) {
        val current = host
        HostWorldImportGuard.check(current._id)
        if (current.realVersion != 2) throw RequestError("仅v2房间支持重置世界")
        if (current.worldId != null) throw RequestError("v2房间存档数据无效")
        if (current.status != HostStatus.STOPPED) throw RequestError("请先去后台停止房间后 再重置存档")
        val worldPath = current.dir.resolve("world")
        var lease: BaseWorldService.SnapshotLease? = null
        var snapshot: java.io.File? = null
        try {
            lease = ModpackVersionMutationLock.withLock(current.modpackId, current.packVer) {
                val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(current.modpackId)
                    ?: throw RequestError("无此整合包")
                val selectedBaseWorldId = resolveResetBaseWorldId(modpack, current)
                    ?: return@withLock null
                baseWorldService.acquireSnapshotLease(selectedBaseWorldId).getOrThrow()
            }
            val acquiredLease = lease ?: run {
                if (worldPath.exists() || Files.isSymbolicLink(worldPath.toPath())) {
                    worldPath.deleteRecursivelyNoSymlink()
                }
                return
            }
            if (acquiredLease.generated) {
                moveWorldAsideForGeneratedReset(worldPath)
                return
            }
            snapshot = baseWorldService.snapshotForHost(acquiredLease.world.id).getOrThrow()
            baseWorldService.replaceWorldFromSnapshot(snapshot, worldPath).getOrThrow()
        } finally {
            snapshot?.let { file ->
                runCatching { Files.deleteIfExists(file.toPath()) }
                    .onFailure { error -> HostService.lgr.error(error) { "清理地图模板快照失败: ${file.absolutePath}" } }
            }
            lease?.let { acquired ->
                runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { acquired.release() } }
                    .onFailure { error -> HostService.lgr.error(error) { "释放地图模板快照租约失败: ${acquired.world.id}" } }
            }
        }
    }

    internal fun moveWorldAsideForGeneratedReset(worldPath: java.io.File): java.io.File? {
        if (!Files.exists(worldPath.toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(worldPath.toPath())) {
            return null
        }
        val backup = worldPath.resolveSibling(".${worldPath.name}.generated-backup-${java.util.UUID.randomUUID()}")
        Files.move(worldPath.toPath(), backup.toPath())
        return backup
    }

    suspend fun HostContext.changeVersion(packVer: String?) {
        HostLifecycleLock.withLock(host._id) { fresh().needAdmin.changeVersionLocked(packVer) }
    }

    private suspend fun HostContext.changeVersionLocked(packVer: String?) {
        HostWorldImportGuard.check(host._id)
        if (host.status != HostStatus.STOPPED) {
            throw RequestError("请先停止主机")
        }
        if (host.realVersion == 2 && host.worldId != null) {
            throw RequestError("v2房间存档数据无效")
        }
        val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(host.modpackId) ?: throw RequestError("无此整合包")
        val modpackVer = modpack.versions.find { it.name == packVer } ?: modpack.versions.lastOrNull()
        ?: throw RequestError("无可用版本")
        requireModernLog4j2Config(modpack.mcVer)
        val resolvedVer = modpackVer.name
        val hostIdStr = host._id.str
        val mailId = MailService.sendSystemMail(
            player._id,
            "房间整合包切换中",
            "你的房间《${host.name}》正在切换到整合包版本 $resolvedVer ，请稍等几分钟..."
        )._id

        DockerService.deleteContainer(hostIdStr)
        clearShutFlag(host._id)
        val installHost = host.copy(packVer = resolvedVer)
        startCreateHost(
            host = installHost,
            modpack = modpack,
            version = modpackVer,
            mailId = mailId,
            runningTitle = "房间整合包切换中",
            successTitle = "房间整合包版本切换完成",
            successContent = "好了",
            failureTitle = "房间整合包版本切换失败",
            persistPackVersion = true
        )
    }

    suspend fun HostContext.changeOptions(payload: Host.OptionsDto) {
        HostLifecycleLock.withLock(host._id) { fresh().needAdmin.changeOptionsLocked(payload) }
    }

    private suspend fun HostContext.changeOptionsLocked(payload: Host.OptionsDto) {
        // An import replaces the world and clears game-rule overrides; any version change is blocked too,
        // including the modpackId + packVer form that skips changeVersionLocked.
        if (payload.gameRules != null || payload.packVer != null) HostWorldImportGuard.check(host._id)
        if (payload.packVer != null && payload.modpackId == null) {
            changeVersionLocked(payload.packVer)
        }
        val updates = mutableListOf<Bson>()
        payload.gameRules?.let { rules ->
            updates += set(Host::gameRules.name, rules)
            if (host.playable) {
                rules.forEach { (k, v) -> sendCommand("gamerule $k $v") }
            }
        }
        payload.name?.let {
            it.validateName()
            updates += set(Host::name.name, it)
        }
        // A version-only change is installed asynchronously by changeVersionLocked;
        // do not publish the target version before installation succeeds.
        if (payload.packVer != null && payload.modpackId != null) {
            updates += set(Host::packVer.name, payload.packVer)
        }
        payload.difficulty?.let {
            updates += set(Host::difficulty.name, it)
            if (host.playable) {
                val modeStr = getDifficultyText(it)
                sendCommand("difficulty ${modeStr}")
            }
        }
        payload.gameMode?.let {
            updates += set(Host::gameMode.name, it)
            if (host.playable) {
                val modeStr = getGameModeText(it)
                sendCommand("defaultgamemode ${modeStr}")
                sendCommand("gamemode ${modeStr} @a")
            }
        }
        payload.levelType?.takeIf { it.isNotBlank() }?.let { updates += set(Host::levelType.name, it) }
        payload.whitelist?.let { updates += set(Host::whitelist.name, it) }
        payload.allowCheats?.let {
            updates += set(Host::allowCheats.name, it)
            if (host.playable) {
                sendCommand("${if (it) "op" else "deop"} @a")
            }
        }
        if (updates.isNotEmpty()) {
            val update = if (updates.size == 1) updates.first() else combine(updates)
            dbcl.updateOne(eq("_id", host._id), update)
        }
        getById(host._id)?.writeServerProperties()
    }
}
