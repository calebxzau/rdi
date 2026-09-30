package calebxzhou.rdi.master.service.host

import calebxzau.rdi.common.logging.Loggers
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.model.HostStatus
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.service.McServerPinger
import calebxzhou.rdi.common.util.objectId
import calebxzhou.rdi.master.model.RGlobalPlayerList
import calebxzhou.rdi.master.service.DockerService
import calebxzhou.rdi.master.service.ModpackService
import calebxzhou.rdi.master.service.PlayerService
import calebxzhou.rdi.master.service.host.HostControlService.clearShutFlag
import calebxzhou.rdi.master.service.host.HostControlService.status
import calebxzhou.rdi.master.service.host.HostControlService.stop
import calebxzhou.rdi.master.service.host.HostControlService.updateShutFlag
import com.mongodb.client.model.Filters.`in`
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bson.types.ObjectId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException as KxCancellationException

object HostPresenceService {
    private val lgr by Loggers
    // GTNH公共房间不参与闲置关停。
    private val IDLE_SHUTDOWN_EXEMPT_HOST_ID = ObjectId("69eaf161cae162f1a7e8459a")

    private val onlinePlayersCache = ConcurrentHashMap<ObjectId, OnlinePlayersCacheEntry>()
    private val onlinePlayersRefreshJobs = ConcurrentHashMap<ObjectId, Job>()
    private var globalPlayerListPollJob: Job? = null
    private var idleMonitorJob: Job? = null

    @Volatile
    internal var lastGlobalPlayerListFingerprint: String = ""

    @Volatile
    internal var lastGlobalPlayerList: RGlobalPlayerList? = null

    private data class OnlinePlayersCacheEntry(
        val playerIds: List<ObjectId>,
        val updatedAt: Long
    )

    fun startGlobalPlayerListPoll() {
        if (globalPlayerListPollJob?.isActive == true) return
        globalPlayerListPollJob = HostService.idleMonitorScope.launch {
            while (isActive) {
                runCatching { pollAndBroadcastGlobalPlayerListIfChanged() }
                    .onFailure { error ->
                        if (error is KxCancellationException) throw error
                        lgr.warn { "轮询全局玩家列表失败: ${error.message}" }
                    }
                delay(1.minutes)
            }
        }
    }

    fun shutdown() {
        stopIdleMonitor()
        globalPlayerListPollJob?.cancel()
        globalPlayerListPollJob = null
        onlinePlayersRefreshJobs.values.forEach(Job::cancel)
        onlinePlayersRefreshJobs.clear()
        onlinePlayersCache.clear()
    }

    internal suspend fun pollAndBroadcastGlobalPlayerListIfChanged() {
        val hosts = collectGlobalPlayerListHosts()
        val fingerprint = hosts.json
        // if (fingerprint == lastGlobalPlayerListFingerprint) return

        lastGlobalPlayerListFingerprint = fingerprint
        val playerList = RGlobalPlayerList(
            generatedAt = System.currentTimeMillis(),
            hosts = hosts
        )
        lastGlobalPlayerList = playerList
        HostRuntimeService.broadcastGlobalPlayerList(playerList)
        lgr.info { "全局玩家列表已变化，广播${hosts.sumOf { it.players.size }}个玩家/${hosts.size}个房间" }
    }

    internal suspend fun collectGlobalPlayerListHosts(): List<RGlobalPlayerList.HostEntry> = coroutineScope {
        val legacy = getPlayables()
            .map { host -> async { host.fetchGlobalPlayerListHostEntry() } }
            .awaitAll()
            .filterNotNull()
        legacy
            .sortedWith(compareBy<RGlobalPlayerList.HostEntry> { it.hostName }.thenBy { it.hostId })
    }

    internal suspend fun Host.fetchGlobalPlayerListHostEntry(): RGlobalPlayerList.HostEntry? {
        if (status != HostStatus.PLAYABLE) return null
        val players = runCatching {
            McServerPinger.ping(port, timeoutMillis = 1_000).players?.sample.orEmpty()
        }.getOrElse { error ->
            if (error is KxCancellationException) throw error
            lgr.warn { "轮询host ${_id} 玩家列表失败: ${error.message}" }
            return null
        }
            .mapNotNull { sample ->
                val playerId = sample.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RGlobalPlayerList.PlayerEntry(
                    playerId = playerId,
                    playerName = sample.name?.takeIf { it.isNotBlank() } ?: playerId
                )
            }
            .distinctBy { it.playerId }
            .sortedWith(compareBy<RGlobalPlayerList.PlayerEntry> { it.playerName }.thenBy { it.playerId })
        if (players.isEmpty()) return null
        val modpackName = runCatching { calebxzhou.rdi.master.service.modpack.ModpackQueryService.getById(modpackId)?.name }
            .getOrElse { error ->
                if (error is KxCancellationException) throw error
                lgr.warn { "读取host ${_id} 整合包名称失败: ${error.message}" }
                null
            } ?: "未知整合包"
        return RGlobalPlayerList.HostEntry(
            hostId = _id.toHexString(),
            hostName = name,
            modpackName = modpackName,
            packVer = packVer,
            players = players
        )
    }

    suspend fun getPlayables(): List<Host> = withContext(Dispatchers.IO) {
        if (DEBUG) {
            return@withContext HostService.dbcl.find(`in`("_id", HostRuntimeService.hostStates.keys().toList())).toList()
        }
        val runningIds = DockerService.listContainers(includeStopped = false)
            .mapNotNull { container ->
                val containerName = container.names?.firstOrNull()?.removePrefix("/") ?: return@mapNotNull null
                runCatching { ObjectId(containerName) }.getOrNull()
            }
            .distinct()

        if (runningIds.isEmpty()) {
            emptyList()
        } else {
            HostService.dbcl.find(`in`("_id", runningIds)).toList()
                .filter { host -> HostRuntimeService.hostStates[host._id]?.session != null }
        }
    }

    suspend fun getIdles(): List<Host> {
        val result = mutableListOf<Host>()
        for (host in getPlayables()) {
            if (host.fetchOnlinePlayersNow().isEmpty()) {
                result += host
            }
        }
        return result
    }

    fun startIdleMonitor() {
        if (idleMonitorJob?.isActive == true) return
        idleMonitorJob = HostService.idleMonitorScope.launch {
            while (isActive) {
                try {
                    runIdleMonitorTick()
                } catch (cancel: KxCancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    lgr.warn { "Idle monitor tick failed: ${t.message + "\n" + t}" }
                }
                delay(1.minutes)
            }
        }
    }

    fun stopIdleMonitor() {
        idleMonitorJob?.cancel()
        idleMonitorJob = null
        HostRuntimeService.hostStates.values.forEach { state ->
            state.shutFlag = 0
            state.shutdownJob?.cancel()
        }
    }

    internal suspend fun runIdleMonitorTick(forceStop: Boolean = false) {
        val runningHosts = try {
            getPlayables()
        } catch (cancel: KxCancellationException) {
            throw cancel
        } catch (t: Throwable) {
            lgr.warn { "Failed to fetch running hosts: ${t.message + "\n" + t}" }
            return
        }
        if (runningHosts.isEmpty()) return

        for (host in runningHosts) {
            if (host._id == IDLE_SHUTDOWN_EXEMPT_HOST_ID) {
                clearShutFlag(host._id)
                continue
            }

            val onlinePlayers = try {
                host.fetchOnlinePlayersNow()
            } catch (cancel: KxCancellationException) {
                throw cancel
            }.mapNotNull {
                if (it == ObjectId("000000000000000000000000")) {
                    RAccount.DEFAULT
                } else {
                    PlayerService.getById(it)
                }
            }
            lgr.info { "${host.name}在线：${onlinePlayers.map { it.name }}" }
            if (onlinePlayers.isEmpty()) {
                if (forceStop) {
                    clearShutFlag(host._id)
                    host.stop("forced idle shutdown")
                    continue
                }

                val current = HostRuntimeService.hostStates[host._id]?.shutFlag ?: 0
                val newFlag = current + 1
                updateShutFlag(host._id, newFlag)
                if (newFlag >= HostService.SHUTDOWN_THRESHOLD) {
                    host.stop("idle for $newFlag consecutive minutes")
                    clearShutFlag(host._id)
                }
            } else {
                clearShutFlag(host._id)
            }
        }
    }

    internal suspend fun Host.fetchOnlinePlayersNow(): List<ObjectId> {
        return fetchOnlinePlayersNow(status)
    }

    internal suspend fun Host.fetchOnlinePlayersNow(knownStatus: HostStatus): List<ObjectId> {
        return try {
            if (knownStatus != HostStatus.PLAYABLE) {
                return emptyList()
            }
            val players = McServerPinger.ping(port, timeoutMillis = 1_000).players
            val playerIds = players?.let { players ->
                lgr.info { "get online players for ${this.name} = $players" }
                players.sample.map { UUID.fromString(it.id).objectId }
            } ?: emptyList()
            onlinePlayersCache[_id] = OnlinePlayersCacheEntry(playerIds, System.currentTimeMillis())
            playerIds
        } catch (cancel: KxCancellationException) {
            throw cancel
        } catch (t: Throwable) {
            lgr.warn { "Failed to ping host ${this._id}: ${t.message}" }
            emptyList()
        }
    }

    internal fun Host.refreshOnlinePlayersInBackground() {
        refreshOnlinePlayersInBackground(status)
    }

    internal fun Host.refreshOnlinePlayersInBackground(knownStatus: HostStatus) {
        val existing = onlinePlayersRefreshJobs[_id]
        if (existing?.isActive == true) return
        onlinePlayersRefreshJobs[_id] = HostService.idleMonitorScope.launch {
            try {
                fetchOnlinePlayersNow(knownStatus)
            } finally {
                onlinePlayersRefreshJobs.remove(_id)
            }
        }
    }

    suspend fun Host.getOnlinePlayers(): List<ObjectId> {
        return getOnlinePlayers(status)
    }

    suspend fun Host.getOnlinePlayers(knownStatus: HostStatus): List<ObjectId> {
        if (knownStatus != HostStatus.PLAYABLE) return emptyList()
        val cached = onlinePlayersCache[_id]
        val now = System.currentTimeMillis()
        if (cached != null && now - cached.updatedAt <= HostService.ONLINE_PLAYERS_CACHE_TTL_MS) {
            return cached.playerIds
        }
        refreshOnlinePlayersInBackground(knownStatus)
        return cached?.playerIds ?: emptyList()
    }

    suspend fun getAllHostsOnlinePlayerIds(): List<ObjectId> = coroutineScope {
        val hosts = getPlayables()
        if (hosts.isEmpty()) return@coroutineScope emptyList()
        hosts.map { host ->
            async { host.getOnlinePlayers() }
        }.awaitAll()
            .flatten()
            .distinct()
    }

    suspend fun stopIdleHosts() {
        runIdleMonitorTick(forceStop = true)
    }
}
