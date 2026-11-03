package calebxzhou.rdi.master.service.host

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.util.str
import calebxzhou.rdi.master.DB
import calebxzhou.rdi.master.HOSTS_DIR
import calebxzhou.rdi.master.Host2Dir
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.idPathParam
import calebxzhou.rdi.master.net.isClientDisconnect
import calebxzhou.rdi.master.net.pathParamNull
import calebxzhou.rdi.master.service.*
import calebxzhou.rdi.master.service.host.HostQueryService.getById
import calebxzhou.rdi.master.service.host.HostRuntimeService.hostStates
import calebxzhou.rdi.model.Role
import com.github.dockerjava.api.exception.NotFoundException
import com.mongodb.client.model.Indexes
import io.ktor.server.application.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.bson.types.ObjectId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException as KxCancellationException

/*
com.github.dockerjava.api.exception.InternalServerErrorException: Status 500: {"message":"driver failed programming external connectivity on endpoint 6a43ea18847459061c7822b9 (4c4d8b7f258dea97065144416cda3145e7e5b47c8a275b10371cd877f02d7939): Error starting userland proxy: listen tcp4 0.0.0.0:57752: bind: address already in use"}

        at com.github.dockerjava.core.DefaultInvocationBuilder.execute(DefaultInvocationBuilder.java:247)
        at com.github.dockerjava.core.DefaultInvocationBuilder.post(DefaultInvocationBuilder.java:102)
        at com.github.dockerjava.core.exec.StartContainerCmdExec.execute(StartContainerCmdExec.java:31)
        at com.github.dockerjava.core.exec.StartContainerCmdExec.execute(StartContainerCmdExec.java:13)
        at com.github.dockerjava.core.exec.AbstrSyncDockerCmdExec.exec(AbstrSyncDockerCmdExec.java:21)
        at com.github.dockerjava.core.command.AbstrDockerCmd.exec(AbstrDockerCmd.java:33)
        at com.github.dockerjava.core.command.StartContainerCmdImpl.exec(StartContainerCmdImpl.java:42)
        at calebxzhou.rdi.master.service.DockerService.start(DockerService.kt:141)
        at calebxzhou.rdi.master.service.host.HostControlService.start(HostControlService.kt:87)
        at calebxzhou.rdi.master.service.host.HostControlService$start$1.invokeSuspend(HostControlService.kt)
        at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
 */
val Host.dir get() =
    when (realVersion) {
        2 -> Host2Dir.resolve(_id.str)
        1 -> HOSTS_DIR.resolve(_id.str)
        else -> throw RequestError("无效房间版本")
    }

data class HostContext(
    val host: Host,
    val player: RAccount,
    val member: Host.Member,
    val targetMemberNull: Host.Member?
) {
    val targetMember get() = targetMemberNull ?: throw ParamError("玩家${player.name}不是此房间的受邀成员")
    suspend fun getTargetPlayer() =
        PlayerService.getById(targetMember.id) ?: throw ParamError("玩家${player.name}不存在")

    suspend fun fresh(): HostContext {
        val current = HostQueryService.getById(host._id) ?: throw RequestError("无此房间")
        val freshMember = current.members.firstOrNull { it.id == player._id }
            ?: if (player.isDav) Host.Member(player._id, Role.ADMIN)
            else if (!current.whitelist) Host.Member(player._id, Role.GUEST)
            else throw RequestError("不是房间受邀成员")
        val freshTarget = targetMemberNull?.let { target -> current.members.firstOrNull { it.id == target.id } }
        return HostContext(current, player, freshMember, freshTarget)
    }
}

object HostService {
    internal val lgr by Loggers

    internal val dbcl = DB.getCollection<Host>("host")

    internal val skipWorldSizeUpdate = ConcurrentHashMap.newKeySet<ObjectId>()
    internal val staleCleanupJob: Job

    internal const val PORT_START = 50000
    internal const val PORT_END_EXCLUSIVE = 60000
    internal const val SHUTDOWN_THRESHOLD = 10
    internal const val HOSTS_PER_PAGE = 100
    internal const val HOST_WORKDIR_LIMIT_BYTES: Long = 16L * 1024 * 1024 * 1024
    internal const val ONLINE_PLAYERS_CACHE_TTL_MS = 15_000L
    internal const val COMMAND_RESPONSE_TIMEOUT_MS = 10_000L

    internal fun createHostTaskKey(hostId: ObjectId): String =
        "server-host-create:${hostId.toHexString()}"

    internal val idleMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        runBlocking {
            dbcl.createIndex(Indexes.ascending("port"))
        }
        // Start periodic cleanup of stale entries
        staleCleanupJob = idleMonitorScope.launch {
            while (isActive) {
                delay(5.minutes)
                cleanupStaleEntries()
            }
        }
        HostPresenceService.startGlobalPlayerListPoll()
        HostPlayerDataBackupService.start()
    }

    fun shutdown() {
        lgr.info { "Shutting down HostService..." }
        HostPlayerDataBackupService.shutdown()
        HostPresenceService.shutdown()
        staleCleanupJob.cancel()
        idleMonitorScope.cancel()
        // Force close all sessions
        hostStates.values.forEach { state ->
            state.session?.let { session ->
                session.launch {
                    runCatching { session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutdown")) }
                }
            }
            state.shutdownJob?.cancel()
        }
        hostStates.clear()
        skipWorldSizeUpdate.clear()
        lgr.info { "HostService shutdown complete" }
    }

    internal fun cleanupStaleEntries() {
        val staleHosts = mutableSetOf<ObjectId>()
        hostStates.forEach { (hostId, state) ->
            if (state.session == null && state.shutFlag <= 0 && state.shutdownJob?.isActive != true) {
                staleHosts.add(hostId)
            }
        }
        staleHosts.forEach { hostId ->
            hostStates.remove(hostId)
            lgr.debug { "Cleaned up stale HostState for $hostId" }
        }

        // Also cleanup skipWorldSizeUpdate for deleted hosts
        val allHostIds = runBlocking {
            dbcl.find().map { it._id }.toList().toSet()
        }
        val staleWorldSkips = skipWorldSizeUpdate.filter { it !in allHostIds }
        staleWorldSkips.forEach {
            skipWorldSizeUpdate.remove(it)
            lgr.debug { "Cleaned up stale skipWorldSizeUpdate for $it" }
        }
    }


    val HostContext.needMember get() = requireRole(Role.MEMBER)
    val HostContext.needInvitedMember: HostContext
        get() {
            if (member.role == Role.GUEST && host.ownerId != player._id && !player.isDav) {
                throw RequestError("仅房间成员可查看")
            }
            return this
        }
    val HostContext.needAdmin get() = requireRole(Role.ADMIN)
    val HostContext.needOwner get() = requireRole(Role.OWNER)
    fun HostContext.requireRole(level: Role): HostContext {
        member.let {
            var allowed = when (level) {
                Role.MEMBER -> member.role != Role.GUEST
                Role.ADMIN -> member.role.level <= Role.ADMIN.level
                Role.OWNER -> member.role == Role.OWNER
                else -> false
            }
            if(player.isDav){
                allowed=true
            }
            if (!allowed) throw RequestError("无权限")
        }
        return this
    }


    suspend fun ApplicationCall.hostContext(): HostContext {
        val player = player()
        val requesterId = player._id
        val host = getById(idPathParam("hostId")) ?: throw RequestError("无此房间")
        val reqMem = host.members.firstOrNull { it.id == requesterId } ?: run {
            if (player.isDav) {
                Host.Member(id = requesterId, role = Role.ADMIN)
            } else if (!host.whitelist) {
                Host.Member(id = requesterId, role = Role.GUEST)
            } else throw RequestError("不是房间受邀成员")
        }
        val tarMem = pathParamNull("uid2")?.let { rawId ->
            runCatching { ObjectId(rawId) }.getOrNull()
        }?.let { uid2 ->
            host.members.find { it.id == uid2 }
        }
        return HostContext(host, player(), reqMem, tarMem)
    }


    // ---------- Streaming Helper (still needs ApplicationCall for SSE) ----------
    suspend fun HostContext.listenLogs(session: ServerSSESession) {
        val hostId = host._id
        val containerName = hostId.str
        session.heartbeat {
            period = 15.seconds
            event = ServerSentEvent(event = "heartbeat", data = "ping")
        }

        var sentFileTail = false
        var sentContainerTail = false
        try {
            while (currentCoroutineContext().isActive) {
                val hasContainer = DockerService.findContainer(containerName) != null
                val started = hasContainer && DockerService.isStarted(containerName)
                if (!started) {
                    if (!sentFileTail) {
                        try {
                            sendLatestLogTail(session, host.dir)
                        } catch (cancel: KxCancellationException) {
                            throw cancel
                        } catch (t: Throwable) {
                            if (t.isClientDisconnect()) return
                        }
                        sentFileTail = true
                    }
                    sentContainerTail = false
                    delay(2.seconds)
                    continue
                }

                if (!sentContainerTail) {
                    try {
                        DockerService.getLog(containerName, startLine = 0, endLine = 200)
                            .lineSequence()
                            .map { it.trimEnd('\r') }
                            .filter { it.isNotBlank() }
                            .toList()
                            .asReversed()
                            .forEach { session.send(ServerSentEvent(data = it)) }
                    } catch (cancel: KxCancellationException) {
                        throw cancel
                    } catch (t: Throwable) {
                        if (t.isClientDisconnect()) return
                    }
                    sentContainerTail = true
                    sentFileTail = false
                }

                val lines = Channel<String>(capacity = Channel.BUFFERED)
                val subscription = DockerService.listenLog(
                    containerName,
                    onLine = { lines.trySend(it).isSuccess },
                    onError = { err -> lines.close(err) },
                    onFinished = { lines.close() }
                )
                try {
                    for (payload in lines) {
                        payload.lineSequence()
                            .map { it.trimEnd('\r') }
                            .filter { it.isNotEmpty() }
                            .forEach { session.send(ServerSentEvent(data = it)) }
                    }
                } catch (t: KxCancellationException) {
                    throw t
                } catch (t: NotFoundException) {
                    sentContainerTail = false
                } catch (t: Throwable) {
                    if (t.isClientDisconnect()) return
                    throw t
                } finally {
                    runCatching { subscription.close() }
                    lines.cancel()
                }
            }
        } catch (cancel: KxCancellationException) {
            //"已取消载入日志"
        } catch (t: Throwable) {
            if (!t.isClientDisconnect()) {
                runCatching { session.send(ServerSentEvent(event = "error", data = t.message ?: "unknown")) }
            }
        }
    }

    internal suspend fun sendLatestLogTail(
        session: ServerSSESession,
        hostDir: File,
        maxLines: Int = 200
    ) {
        val logFile = hostDir.resolve("logs").resolve("latest.log")
        if (!logFile.exists()) {
            session.send(ServerSentEvent(data = "没有日志"))
            return
        }
        val buffer = ArrayDeque<String>(maxLines)
        logFile.useLines(Charsets.UTF_8) { lines ->
            lines.forEach { line ->
                if (buffer.size == maxLines) {
                    buffer.removeFirst()
                }
                buffer.addLast(line)
            }
        }
        buffer.forEach { line ->
            if (line.isNotBlank()) {
                session.send(ServerSentEvent(data = line))
            }
        }
    }


    internal fun markSkipWorldSizeUpdate(hostId: ObjectId) {
        skipWorldSizeUpdate.add(hostId)
    }

    internal fun skipWorldSizeUpdateOnce(hostId: ObjectId): Boolean = skipWorldSizeUpdate.remove(hostId)


}
