package calebxzhou.rdi.master.service.host

import calebxzau.rdi.server.service.hostworldimport.HostWorldImportGuard
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.DL_MOD_DIR
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.model.isDav
import calebxzhou.rdi.common.model.normalizedSlug
import calebxzhou.rdi.common.util.str
import calebxzhou.rdi.master.model.WsMessage
import calebxzhou.rdi.master.service.DockerService
import calebxzhou.rdi.master.service.ModpackService
import calebxzhou.rdi.master.service.libsDir
import calebxzhou.rdi.master.service.modpack.ModpackQueryService.getVersion
import calebxzhou.rdi.master.service.host.HostContainerService.isDisabledMod
import calebxzhou.rdi.master.service.host.HostContainerService.isServerInstalledMod
import calebxzhou.rdi.master.service.host.HostContainerService.makeContainer
import calebxzhou.rdi.master.service.host.HostContainerService.requireModernLog4j2Config
import calebxzhou.rdi.master.service.host.HostInstallService.deleteTransientStartupDirs
import calebxzhou.rdi.master.service.host.HostInstallService.refreshWorldSizeAfterStop
import calebxzhou.rdi.master.service.host.HostInstallService.writeServerProperties
import calebxzhou.rdi.master.service.host.HostRuntimeService.listenCrashOnStart
import calebxzhou.rdi.model.Role
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException as KxCancellationException

internal fun resolveHostStatus(
    containerStatus: HostStatus,
    debug: Boolean,
    hasSession: Boolean,
): HostStatus = if (hasSession && (debug || containerStatus == HostStatus.STARTED)) {
    HostStatus.PLAYABLE
} else {
    containerStatus
}

internal fun hasEnabledKotlinForForgeJar(modsDir: File): Boolean =
    modsDir.isDirectory && modsDir.listFiles().orEmpty().any { file ->
        isMatchingKotlinForForgeJar(file)
    }

private fun isMatchingKotlinForForgeJar(file: File): Boolean =
    file.isFile &&
        file.extension.equals("jar", ignoreCase = true) &&
        (file.name.contains("kotlin-for-forge", ignoreCase = true) ||
            file.name.contains("kotlinforforge", ignoreCase = true))

internal fun hasEnabledKotlinForForge(
    host: Host,
    version: Modpack.Version,
    hostModsDir: File = host.dir.resolve("mods"),
    modCacheDir: File = DL_MOD_DIR,
): Boolean {
    if (hasEnabledKotlinForForgeJar(hostModsDir)) return true

    val hasCachedServerMod = { mod: Mod ->
        isServerInstalledMod(mod) &&
            mod.candidateFiles(modCacheDir).any(::isMatchingKotlinForForgeJar)
    }
    return version.mods.any { !host.isDisabledMod(it) && hasCachedServerMod(it) } ||
        host.extraMods.any(hasCachedServerMod)
}

object HostControlService {
    private val lgr by Loggers

    fun updateShutFlag(hostId: ObjectId, value: Int) {
        if (value <= 0) {
            clearShutFlag(hostId)
        } else {
            val state = HostRuntimeService.hostStates.computeIfAbsent(hostId) { HostRuntimeService.HostState() }
            state.shutFlag = value
            lgr.info { "upd shut flag $hostId $value" }
        }
    }

    fun clearShutFlag(hostId: ObjectId) {
        HostRuntimeService.hostStates[hostId]?.let { state ->
            state.shutFlag = 0
            state.shutdownJob?.cancel()
            if (state.session == null && state.shutdownJob?.isActive != true) {
                HostRuntimeService.hostStates.remove(hostId, state)
            }
        }
        lgr.debug { "保持在线 $hostId" }
    }

    suspend fun Host.stop(reason: String) = HostLifecycleLock.withLock(_id) {
        runCatching {
            DockerService.stop(_id.str)
            lgr.info { "Stopped host $name ($reason)" }
            refreshWorldSizeAfterStop(waitForStop = false)
        }.onFailure {
            lgr.warn { "Failed to stop host ${name + "\n" + it}: ${it.message}" }
        }
        clearShutFlag(_id)
    }

    suspend fun HostContext.start() {
        HostLifecycleLock.withLock(host._id) { fresh().startLocked() }
    }

    private suspend fun HostContext.startLocked() {
        val current = host
        HostWorldImportGuard.check(current._id)
        val isMember = member.role != Role.GUEST
        if (current.whitelist && !isMember && !player.isDav) {
            throw RequestError("私有房间仅成员可启动")
        }
        if (DockerService.isStarted(current._id.str)) {
            clearShutFlag(current._id)
            return
        }
        val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(current.modpackId) ?: throw RequestError("无此整合包")
        val version = modpack.getVersion(current.packVer) ?: throw RequestError("无此版本")
        requireModernLog4j2Config(modpack.mcVer)
        current.requireRequiredStartupMods(modpack, version)
        if (modpack.modloader == ModLoader.Fabric) {
            FabricServerRuntimeFiles.requireAvailable(
                modpack.libsDir.absoluteFile,
                modpack.mcVer,
                modpack.modloader,
            )
        }
        if (modpack.modloader in setOf(ModLoader.forge, ModLoader.neoforge) &&
            !hasEnabledKotlinForForge(current, version) && modpack.mcVer.isModern
        ) {
            throw RequestError("请先为此房间安装kotlinforforge模组才能启动")
        }
        DockerService.deleteContainer(current._id.str)
        current.writeServerProperties()
        current.deleteTransientStartupDirs()
        current.makeContainer(current.worldId, modpack, version)
        DockerService.start(current._id.str)
        current.listenCrashOnStart()
        clearShutFlag(current._id)
    }

    suspend fun HostContext.graceStop() {
        HostLifecycleLock.withLock(host._id) { graceStopLocked() }
    }

    private suspend fun HostContext.graceStopLocked() {
        sendCommand("stop")
        clearShutFlag(host._id)
        host.refreshWorldSizeAfterStop(waitForStop = true)
    }

    suspend fun HostContext.forceStop() {
        HostLifecycleLock.withLock(host._id) { forceStopLocked() }
    }

    private fun HostContext.forceStopLocked() {
        clearShutFlag(host._id)
        DockerService.forceStop(host._id.str)
        host.refreshWorldSizeAfterStop(waitForStop = false)
    }

    suspend fun HostContext.restart() {
        HostLifecycleLock.withLock(host._id) { restartLocked() }
    }

    private suspend fun HostContext.restartLocked() {
        HostWorldImportGuard.check(host._id)
        val modpack = calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(host.modpackId)
            ?: throw RequestError("无此整合包")
        if (modpack.modloader == ModLoader.Fabric) {
            FabricServerRuntimeFiles.requireAvailable(modpack.libsDir.absoluteFile, modpack.mcVer, modpack.modloader)
            sendCommand("stop")
            clearShutFlag(host._id)
            try {
                withTimeout(120_000L) {
                    while (true) {
                        val stoppedRoom = DockerService.findContainer(host._id.str)
                            ?: throw RequestError("无法确认房间已停止，请稍后重新启动")
                        if (stoppedRoom.state.equals("exited", ignoreCase = true)) break
                        delay(500L)
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw RequestError("房间仍在保存或停止中，请稍后重新启动", timeout)
            }
            host.refreshWorldSizeAfterStop(waitForStop = false)
            fresh().startLocked()
            return
        }
        sendCommand("stop")
        clearShutFlag(host._id)
        DockerService.restart(host._id.str)
    }

    suspend fun HostContext.sendCommand(command: String, waitForResponse: Boolean = false): String {
        val hostId = host._id
        val normalized = command.trimEnd()
        if (normalized.isBlank()) throw RequestError("命令不能为空")

        val pendingResponse = if (waitForResponse) CompletableDeferred<String>() else null
        val target = HostRuntimeService.commandTarget(hostId, pendingResponse)

        val message = WsMessage(
            target.requestId,
            channel = WsMessage.Channel.Command,
            data = normalized
        )

        try {
            target.session.send(Frame.Text(message.json))
            if (!waitForResponse) return "OK"
            return withTimeout(HostService.COMMAND_RESPONSE_TIMEOUT_MS.milliseconds) {
                pendingResponse!!.await()
            }
        } catch (timeout: TimeoutCancellationException) {
            throw RequestError("命令已发送，但${HostService.COMMAND_RESPONSE_TIMEOUT_MS / 1000}秒内未收到返回")
        } catch (cancel: KxCancellationException) {
            HostRuntimeService.markSessionDisconnected(hostId, target.session)
            throw cancel
        } catch (requestError: RequestError) {
            throw requestError
        } catch (t: Throwable) {
            HostRuntimeService.markSessionDisconnected(hostId, target.session)
            lgr.warn { "发送命令到 $hostId 失败: ${t.message + "\n" + t}" }
            throw RequestError("发送命令失败: ${t.message ?: "未知错误"}")
        } finally {
            HostRuntimeService.removePendingCommand(hostId, target.requestId)
        }
    }

    val Host.status: HostStatus
        get() {
            val hasSession = HostRuntimeService.hasSession(_id)
            if (DEBUG && hasSession) return HostStatus.PLAYABLE
            val containerStatus = DockerService.getContainerStatus(_id.str)
            return resolveHostStatus(
                containerStatus = containerStatus,
                debug = DEBUG,
                hasSession = hasSession,
            )
        }

    internal fun Iterable<Host>.statusSnapshot(): Map<ObjectId, HostStatus> {
        val hosts = toList()
        val sessionIds = if (DEBUG) {
            hosts.filter { HostRuntimeService.hasSession(it._id) }.map { it._id }.toSet()
        } else {
            emptySet()
        }
        val containerStatuses = if (DEBUG && sessionIds.size == hosts.size) {
            emptyMap()
        } else {
            DockerService.getContainerStatusSnapshot()
        }
        val missingStatus = if (containerStatuses == null) HostStatus.UNKNOWN else HostStatus.STOPPED
        return hosts.associate { host ->
            val hasSession = host._id in sessionIds || HostRuntimeService.hasSession(host._id)
            host._id to resolveHostStatus(
                containerStatus = containerStatuses?.get(host._id.str) ?: missingStatus,
                debug = DEBUG,
                hasSession = hasSession,
            )
        }
    }

    private fun Host.requireRequiredStartupMods(modpack: Modpack, version: Modpack.Version) {
        if (modpack.mcVer != McVersion.V201 && modpack.mcVer != McVersion.V211) return
        val installedSlugs = (version.mods
            .filter(::isServerInstalledMod)
            .filterNot { isDisabledMod(it) } + extraMods.filter(::isServerInstalledMod))
            .map { it.normalizedSlug }
            .toSet()
        val missingMods = REQUIRED_MODERN_HOST_MODS.filter { it.slug !in installedSlugs }
        if (missingMods.isEmpty()) return
        throw RequestError(
            "MC${modpack.mcVer.mcVer}房间必须安装${missingMods.joinToString("、") { it.name }}后才能启动"
        )
    }

    private data class RequiredStartupMod(
        val slug: String,
        val name: String
    )

    //client 已经mount了 kotlin lib classpath 保留备用
    private val REQUIRED_MODERN_HOST_MODS = emptyList<RequiredStartupMod>() /*listOf(
        //RequiredStartupMod("ftb-chunks", "FTB Chunks"),
        RequiredStartupMod("kotlin-for-forge", "Kotlin for Forge")
    )*/

    val Host.playable get() = status == HostStatus.PLAYABLE
}
