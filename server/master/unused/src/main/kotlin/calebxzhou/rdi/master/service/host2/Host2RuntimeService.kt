package calebxzhou.rdi.master.service.host2

import calebxzhou.rdi.common.exception.RequestError
import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.ModLoader
import calebxzhou.rdi.common.model.supportsForgeguard
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.service.McServerPinger
import calebxzhou.rdi.common.util.ioScope
import calebxzhou.rdi.common.util.objectId
import calebxzhou.rdi.master.GAME_LIBS_DIR
import calebxzhou.rdi.master.HOST2_DIR
import calebxzhou.rdi.master.model.WsMessage
import calebxzhou.rdi.master.model.RChatMessage
import calebxzhou.rdi.master.model.RGlobalPlayerList
import calebxzhou.rdi.master.service.ChatService
import calebxzhou.rdi.master.service.DockerService
import calebxzhou.rdi.master.service.MailService
import calebxzhou.rdi.master.service.host.HostPresenceService
import calebxzhou.rdi.master.service.host.HostContainerService.FORGEGUARD_CONTAINER_PATH
import calebxzhou.rdi.master.service.host.HostContainerService.forgeguardMount
import calebxzhou.rdi.master.service.host.HostRuntimeService
import com.github.dockerjava.api.model.Mount
import com.github.dockerjava.api.model.MountType
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds

object Host2RuntimeService {
    private val lgr by Loggers
    private val states = ConcurrentHashMap<UUID, State>()
    private val intentionalStops = ConcurrentHashMap.newKeySet<UUID>()
    private val missingKffNotices = ConcurrentHashMap.newKeySet<Pair<UUID, Long>>()

    private data class State(
        var session: DefaultWebSocketServerSession? = null,
        val nextCommandId: AtomicInteger = AtomicInteger(),
        val pendingCommands: ConcurrentHashMap<Int, CompletableDeferred<String>> = ConcurrentHashMap(),
        var host: Host2Record? = null,
        var onlinePlayerIds: List<UUID> = emptyList(),
        var disconnectJob: Job? = null
    )

    internal fun resolveHost2Status(containerStatus: HostStatus, hasSession: Boolean): HostStatus =
        if (containerStatus == HostStatus.STARTED && hasSession) HostStatus.PLAYABLE else containerStatus

    fun statusSnapshot(hostIds: Collection<UUID>): Map<UUID, HostStatus> {
        val ids = hostIds.toSet()
        if (ids.isEmpty()) return emptyMap()
        val containerStatuses = DockerService.getContainerStatusSnapshot()
        val missingStatus = if (containerStatuses == null) HostStatus.UNKNOWN else HostStatus.STOPPED
        return ids.associateWith { id ->
            resolveHost2Status(
                containerStatus = containerStatuses?.get(id.toString()) ?: missingStatus,
                hasSession = states[id]?.session != null,
            )
        }
    }

    fun status(hostId: UUID): HostStatus {
        return resolveHost2Status(
            containerStatus = DockerService.getContainerStatus(hostId.toString()),
            hasSession = states[hostId]?.session != null,
        )
    }

    fun start(context: Host2StartContext) {
        val host = context.host
        ensureQuota(host.id)
        if (DockerService.isStarted(host.id.toString())) throw RequestError("新版房间已经启动")
        intentionalStops.remove(host.id)
        makeContainer(context)
        DockerService.start(host.id.toString())
        monitorStartup(host)
    }

    suspend fun stop(hostId: UUID) {
        if (status(hostId) == HostStatus.STOPPED) return
        intentionalStops += hostId
        runCatching { command(hostId, "stop") }
        repeat(STOP_WAIT_SECONDS * 5) {
            if (DockerService.getContainerStatus(hostId.toString()) == HostStatus.STOPPED) return
            delay(200.milliseconds)
        }
        runCatching { DockerService.forceStop(hostId.toString()) }
            .getOrElse { error ->
                if (DockerService.getContainerStatus(hostId.toString()) != HostStatus.STOPPED) throw error
            }
    }

    suspend fun restart(context: Host2StartContext) {
        val host = context.host
        stop(host.id)
        for (attempt in 0 until 30) {
            if (DockerService.getContainerStatus(host.id.toString()) == HostStatus.STOPPED) break
            delay(200.milliseconds)
        }
        DockerService.deleteContainer(host.id.toString())
        start(context)
    }

    suspend fun command(hostId: UUID, command: String): String {
        val normalized = command.trim()
        if (normalized.isBlank()) throw RequestError("命令不能为空")
        val state = states[hostId] ?: throw RequestError("新版房间未处于游玩状态")
        val session = state.session ?: throw RequestError("新版房间未处于游玩状态")
        val requestId = state.nextCommandId.getAndIncrement()
        val response = CompletableDeferred<String>()
        state.pendingCommands[requestId] = response
        return try {
            session.send(Frame.Text(WsMessage(requestId, WsMessage.Channel.Command, normalized).json))
            withTimeout(COMMAND_TIMEOUT_MS.milliseconds) { response.await() }
        } catch (_: TimeoutCancellationException) {
            throw RequestError("命令已发送，但未收到返回")
        } finally {
            state.pendingCommands.remove(requestId)
        }
    }

    fun register(host: Host2Record, session: DefaultWebSocketServerSession) {
        val hostId = host.id
        val state = states.computeIfAbsent(hostId) { State() }
        state.disconnectJob?.cancel()
        state.disconnectJob = null
        state.host = host
        state.session?.takeIf { it !== session }?.let { oldSession ->
            oldSession.launch {
                runCatching { oldSession.close(CloseReason(CloseReason.Codes.NORMAL, "新的连接建立")) }
            }
        }
        state.session = session
        HostPresenceService.lastGlobalPlayerList?.let { HostRuntimeService.sendGlobalPlayerListToSession(session, it) }
    }

    suspend fun handleMessage(hostId: UUID, text: String) {
        val message = runCatching { serdesJson.decodeFromString<WsMessage<JsonElement>>(text) }
            .getOrElse {
                lgr.warn { "Host2 $hostId gameplay消息解析失败: ${it.message}" }
                return
            }
        when (message.channel) {
            WsMessage.Channel.Response, WsMessage.Channel.Command -> {
                val output = runCatching { message.data.jsonPrimitive.content }.getOrElse { message.data.toString() }
                states[hostId]?.pendingCommands?.remove(message.id)?.complete(output.ifBlank { "OK" })
            }
            WsMessage.Channel.Chat -> {
                val chat = runCatching { serdesJson.decodeFromJsonElement<RChatMessage>(message.data) }
                    .getOrElse {
                        lgr.warn { "Host2 $hostId 聊天消息解析失败: ${it.message}" }
                        return
                    }
                ioScope.launch {
                    runCatching { ChatService.recordGameChat(chat) }
                        .onFailure { lgr.warn { "Host2 $hostId 保存聊天失败: ${it.message}" } }
                }
                if (chat.global) {
                    val sourced = chat.copy(sourceHostId = hostId.toString())
                    HostRuntimeService.broadcastChatMessage(message.id, sourced)
                }
            }
            WsMessage.Channel.PlayerList -> Unit
        }
    }

    fun unregister(hostId: UUID, session: DefaultWebSocketServerSession) {
        states[hostId]?.let { state ->
            if (state.session === session) {
                state.session = null
                state.pendingCommands.values.forEach { it.completeExceptionally(RequestError("新版房间连接已断开")) }
                state.pendingCommands.clear()
                state.disconnectJob?.cancel()
                state.disconnectJob = ioScope.launch {
                    delay(DISCONNECT_GRACE_SECONDS * 1_000L)
                    if (states[hostId]?.session == null) {
                        runCatching { stop(hostId) }
                            .onFailure { lgr.warn { "Host2 $hostId gameplay断开后停止失败: ${it.message}" } }
                        states.remove(hostId, state)
                    }
                }
            }
        }
    }

    suspend fun globalEntries(): List<RGlobalPlayerList.HostEntry> = states.mapNotNull hostLoop@ { (id, state) ->
        val host = state.host ?: return@hostLoop null
        if (state.session == null || status(id) != HostStatus.PLAYABLE) return@hostLoop null
        val players = runCatching { McServerPinger.ping(host.port, timeoutMillis = 1_000).players?.sample.orEmpty() }
            .getOrElse {
                lgr.warn { "Host2 $id 全局玩家列表ping失败: ${it.message}" }
                return@hostLoop null
            }
            .mapNotNull playerLoop@ { sample ->
                val playerId = sample.id?.takeIf(String::isNotBlank) ?: return@playerLoop null
                RGlobalPlayerList.PlayerEntry(playerId, sample.name?.takeIf(String::isNotBlank) ?: playerId)
            }
            .distinctBy(RGlobalPlayerList.PlayerEntry::playerId)
        state.onlinePlayerIds = players.mapNotNull { runCatching { UUID.fromString(it.playerId) }.getOrNull() }
        if (players.isEmpty()) null else RGlobalPlayerList.HostEntry(id.toString(), host.name, "", "", players)
    }

    fun onlinePlayerIds(hostId: UUID): List<UUID> = states[hostId]?.onlinePlayerIds.orEmpty()

    fun broadcastGlobalPlayerList(playerList: RGlobalPlayerList) {
        states.values.mapNotNull { it.session }
            .forEach { HostRuntimeService.sendGlobalPlayerListToSession(it, playerList) }
    }

    fun broadcastChatMessage(id: Int, chat: RChatMessage) {
        val message = WsMessage(id, WsMessage.Channel.Chat, chat)
        states.forEach { (hostId, state) ->
            if (hostId.toString() == chat.sourceHostId) return@forEach
            state.session?.let { session ->
                session.launch {
                    runCatching { session.send(Frame.Text(message.json)) }
                        .onFailure { lgr.warn { "广播聊天到Host2 ${hostId}失败: ${it.message}" } }
                }
            }
        }
    }

    fun forceRemove(hostId: UUID) {
        intentionalStops += hostId
        DockerService.deleteContainer(hostId.toString())
        states.remove(hostId)?.let { state ->
            state.disconnectJob?.cancel()
            state.pendingCommands.values.forEach { it.completeExceptionally(RequestError("新版房间已删除")) }
            state.session?.let { session ->
                session.launch {
                    runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "新版房间已删除")) }
                }
            }
        }
        ioScope.launch {
            delay(5_000)
            intentionalStops.remove(hostId)
        }
    }

    private fun makeContainer(context: Host2StartContext) {
        val host = context.host
        val mcVersion = context.mcVersion
        val modLoader = context.modLoader
        val id = host.id.toString()
        val hostDir = HOST2_DIR.resolve(id)
        if (!hostDir.isDirectory) throw RequestError("新版房间目录不存在")
        val loaderVersion = mcVersion.loaderVersions[modLoader] ?: throw RequestError("不支持的ModLoader")
        val sharedRoot = GAME_LIBS_DIR.resolve("${mcVersion.mcVer}-${modLoader}")
        val sharedLibraries = sharedRoot.resolve("libraries")
        if (!sharedLibraries.isDirectory) throw RequestError("新版房间运行库缺失")
        if (notifyMissingKotlinForForge(host)) {
            throw RequestError("房间需要且只能启用1个KotlinForForge文件")
        }
        val args = when (mcVersion) {
            McVersion.V201, McVersion.V211 -> listOf(loaderVersion.serverArgsPath(true))
            McVersion.V071 -> {
                val launcher = hostDir.resolve("lwjgl3ify-forgePatches.jar")
                val java9Args = hostDir.resolve("java9args.txt")
                if (!launcher.isFile || !java9Args.isFile) {
                    throw RequestError("1.7.10 server pack缺少LWJGL3ify运行文件")
                }
                findLegacyForgeJar(sharedRoot)
                listOf("-Dfml.readTimeout=180", "@${java9Args.name}", "-jar", launcher.name)
            }
        }
        DockerService.deleteContainer(id)
        patchServerProperties(hostDir, host)
        val mounts = mutableListOf(
            Mount().withType(MountType.BIND).withSource(hostDir.absolutePath).withTarget("/opt/server"),
            Mount().withType(MountType.BIND).withSource(sharedLibraries.absolutePath)
                .withTarget("/opt/server/libraries"),
        )
        if (mcVersion.supportsForgeguard(modLoader)) {
            mounts += forgeguardMount()
        }
        if (mcVersion == McVersion.V071) {
            sharedRoot.listFiles()?.filter { it.isFile && it.extension.equals("jar", true) }?.forEach { jar ->
                mounts += Mount().withType(MountType.BIND).withSource(jar.absolutePath).withTarget("/opt/server/${jar.name}")
            }
        }
        DockerService.createContainer(
            port = host.port,
            containerName = id,
            cpu = if (mcVersion == McVersion.V071) 2 else 4,
            memory = 8L * 1024 * 1024 * 1024,
            memorySwap = 16L * 1024 * 1024 * 1024,
            mounts = mounts,
            image = "rdi:j${mcVersion.jreSupport}",
            env = listOf(
                "HOST_ID=$id",
                "GAME_PORT=${host.port}",
                "ALL_OP=false",
                "START_PARAMS=${buildJvmArgs(mcVersion, modLoader).joinToString(" ")} ${args.joinToString(" ")} ${if (mcVersion == McVersion.V071) "nogui" else "--nogui"}"
            )
        )
    }

    private fun buildJvmArgs(mcVersion: McVersion, modLoader: ModLoader) = buildList {
        add("-Xmx8G")
        if (mcVersion.supportsForgeguard(modLoader)) add("-javaagent:$FORGEGUARD_CONTAINER_PATH")
        add("-XX:+UseCompactObjectHeaders")
        if (mcVersion == McVersion.V071) add("-Dfml.queryResult=confirm")
    }

    private fun patchServerProperties(hostDir: File, host: Host2Record) {
        val file = hostDir.resolve("server.properties")
        val properties = Properties()
        if (file.exists()) file.inputStream().use { properties.load(it) }
        properties.setProperty("server-port", host.port.toString())
        properties.setProperty("level-name", "world")
        properties.setProperty("online-mode", "false")
        properties.setProperty("server-ip", "")
        properties.setProperty("white-list", host.whitelist.toString())
        properties.setProperty("enforce-whitelist", host.whitelist.toString())
        properties.setProperty("enable-rcon", "false")
        properties.setProperty("enable-status", "true")
        properties.setProperty("hide-online-players", "false")
        file.outputStream().use { properties.store(it, null) }
        hostDir.resolve("eula.txt").writeText("eula=true\n")
    }

    private fun findLegacyForgeJar(hostDir: File): File =
        hostDir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("forge-") && it.name.endsWith("-universal.jar")
        } ?: throw RequestError("server pack缺少Forge universal文件")

    private fun hasKotlinForForge(hostDir: File): Boolean {
        val files = hostDir.resolve("mods").listFiles().orEmpty().filter { file ->
            file.isFile && !file.name.endsWith(".disabled", true) &&
                (file.name.contains("kotlinforforge", true) || file.name.contains("kotlin-for-forge", true))
        }
        return files.size == 1
    }

    fun notifyMissingKotlinForForge(host: Host2Record): Boolean {
        if (hasKotlinForForge(HOST2_DIR.resolve(host.id.toString()))) return false
        val notice = host.id to host.activeContentRevision
        if (missingKffNotices.add(notice)) {
            ioScope.launch {
                runCatching {
                    MailService.sendSystemMail(
                        host.ownerId.objectId,
                        "新版房间缺少KotlinForForge",
                        "${host.name}当前内容版本缺少KotlinForForge，暂时不能启动。",
                    )
                }.onFailure { lgr.warn { "Host2 ${host.id}发送KotlinForForge提醒失败: ${it.message}" } }
            }
        }
        return true
    }

    private fun ensureQuota(hostId: UUID) {
        val size = sizeBytes(hostId)
        if (size > HOST2_QUOTA_BYTES) throw RequestError("新版房间目录超过8GiB，请删除不必要文件后再启动")
    }

    fun sizeBytes(hostId: UUID): Long = HOST2_DIR.resolve(hostId.toString()).walkTopDown()
            .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .sumOf(File::length)

    private fun monitorStartup(host: Host2Record) {
        val triggered = AtomicBoolean(false)
        val listener = arrayOfNulls<java.io.Closeable>(1)
        val timeout = ioScope.launch {
            delay(5.minutes)
            listener[0]?.close()
        }
        listener[0] = DockerService.listenLog(
            host.id.toString(),
            onLine = { line ->
                if (STARTUP_FAILURE_MARKERS.any(line::contains) && triggered.compareAndSet(false, true)) {
                    timeout.cancel()
                    listener[0]?.close()
                    ioScope.launch {
                        runCatching { DockerService.forceStop(host.id.toString()) }
                        runCatching {
                            MailService.sendSystemMail(
                                host.ownerId.objectId,
                                "新版房间启动失败",
                                "${host.name}启动失败，请在新版房间详情中查看log。"
                            )
                        }
                    }
                }
            },
            onError = { timeout.cancel() },
            onFinished = {
                timeout.cancel()
                if (triggered.compareAndSet(false, true) &&
                    host.id !in intentionalStops &&
                    DockerService.getContainerStatus(host.id.toString()) == HostStatus.STOPPED
                ) {
                    ioScope.launch {
                        runCatching {
                            MailService.sendSystemMail(
                                host.ownerId.objectId,
                                "新版房间启动失败",
                                "${host.name}启动后意外停止，请在新版房间详情中查看log。"
                            )
                        }.onFailure { lgr.warn { "Host2 ${host.id}发送启动失败邮件失败: ${it.message}" } }
                    }
                }
            }
        )
    }
}

const val HOST2_QUOTA_BYTES = 8L * 1024 * 1024 * 1024
private const val COMMAND_TIMEOUT_MS = 10_000L
private const val STOP_WAIT_SECONDS = 30
private const val DISCONNECT_GRACE_SECONDS = 30L
private val STARTUP_FAILURE_MARKERS = listOf(
    "Preparing crash report",
    "Failed to start the minecraft server",
    "Minecraft Crash Report",
    "Missing or unsupported mandatory dependencies"
)
