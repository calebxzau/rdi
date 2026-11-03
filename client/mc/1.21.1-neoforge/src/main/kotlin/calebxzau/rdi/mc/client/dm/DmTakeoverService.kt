@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import calebxzau.rdi.mc.client.syncchunk.SyncChunkRequestError
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows
import net.minecraft.network.chat.Component
import net.minecraft.world.level.storage.LevelStorageSource
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Takes over a DM room after its host went offline: the best placed participant (chosen by
 * the master) downloads the latest checkpoint and opens it as a singleplayer world, which then
 * hosts the room through the normal [DmHostService] path; everyone else reconnects. A room nobody
 * hosts any more can also be taken over from the join screen, see [beginManual].
 */
object DmTakeoverService {
    private val logger = LoggerFactory.getLogger(DmTakeoverService::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    /** Long enough for a host that saves and quits to upload its final checkpoint first. */
    private const val OFFLINE_WAIT_MILLIS = 60_000L
    /**
     * The gateway reports a crashed host after dropping its guests, and a host that silently lost its
     * network only after 45s, against about 30s for the guest's own timeout; a failed report is retried 10s later.
     */
    internal const val ONLINE_GRACE_MILLIS = 30_000L
    private const val POLL_MILLIS = 1_000L
    private const val NEW_HOST_POLL_MILLIS = 2_000L
    private const val NEW_HOST_WAIT_MILLIS = 5 * 60_000L
    private const val RENEW_INTERVAL_MILLIS = 10_000L
    private const val HOSTING_START_MILLIS = 5 * 60_000L
    private const val MAX_CLAIM_FAILURES = 10
    private const val LEAVE_WORLD_MILLIS = 5 * 60_000L

    enum class Step { WaitForHost, Claim, Reconnect, Abort }

    /**
     * After the disconnect: only an offline host is worth taking over. An "Online" answer may
     * just mean the host's offline report has not arrived yet, so it only counts after [ONLINE_GRACE_MILLIS].
     */
    internal fun afterDisconnect(host: DmHostInfo, elapsedMillis: Long): Step = when (host.state) {
        "Online" -> if (elapsedMillis < ONLINE_GRACE_MILLIS) Step.WaitForHost else Step.Abort
        "Offline" -> Step.Claim
        else -> Step.WaitForHost
    }

    /** The join screen offers a manual takeover only while the master reports the host offline. */
    internal fun canStartManually(host: DmHostInfo): Boolean = host.state == "Offline"

    /** After losing the election: reconnect once the new host is up, or claim again if the winner gave up. */
    internal fun whileOthersTakeOver(host: DmHostInfo): Step = when {
        host.state == "Online" && host.gamePort != null -> Step.Reconnect
        host.state == "Offline" && host.takeover == null -> Step.Claim
        else -> Step.WaitForHost
    }

    /** [parent] is set for a manual takeover: its screen is already shown and returns there. */
    private class Flow(val generation: Long, val participant: DmParticipantSession.Participant, val parent: Screen? = null) {
        @Volatile var attempt: Attempt? = null
        @Volatile var finished = false
    }

    private enum class HostingResult { Ready, Failed, ClaimRefused }

    /** One granted claim; a player that loses it can be elected again with a new one. */
    private class Attempt(val claimId: UUID) {
        val hosting = CompletableFuture<HostingResult>()
        val leftWorld = CompletableFuture<Unit>()
        /** From here on only the gateway decides: the master also drops the claim once hosting starts. */
        @Volatile var opening = false
        @Volatile var claimLost = false
    }

    private val generation = AtomicLong()
    @Volatile private var flow: Flow? = null
    @Volatile var status: String = ""
        private set

    /** True when the takeover screen should offer "back" rather than "cancel". */
    val isFinished: Boolean get() = flow?.finished ?: true

    /** True while this player hosts [hostId] because it won an election, not because it chose to. */
    fun isAutomatic(hostId: UUID): Boolean = flow?.let { it.parent == null && it.participant.hostId == hostId } ?: false

    /** The claim the gateway must see for this player to host [hostId], if this player was elected. */
    fun claimFor(hostId: UUID): UUID? = flow?.takeIf { it.participant.hostId == hostId }?.attempt?.claimId

    fun begin(participant: DmParticipantSession.Participant) {
        cancel()
        val current = Flow(generation.incrementAndGet(), participant)
        flow = current
        status = "正在检查房主状态…"
        logger.info("Lost the connection to DM host {}; checking whether it went offline", participant.hostId)
        executor.submit { run(current) }
    }

    /** Takes over a room nobody hosts, chosen on the join screen; client thread only. */
    fun beginManual(hostId: UUID, config: DmConfig, parent: Screen) {
        cancel()
        val candidateId = UUID.fromString(kotlin.uuid.Uuid.generateV7().toString())
        val current = Flow(generation.incrementAndGet(), DmParticipantSession.Participant(hostId, config, candidateId), parent)
        flow = current
        status = "正在申请接管房间…"
        logger.info("Taking over offline DM host {} from the join screen", hostId)
        Minecraft.getInstance().setScreen(DmTakeoverScreen(parent))
        executor.submit { run(current) }
    }

    /** Stops the takeover and gives up the claim, if this player holds one. */
    fun cancel() {
        val current = flow ?: return
        flow = null
        generation.incrementAndGet()
        current.attempt?.let { attempt ->
            attempt.hosting.complete(HostingResult.Failed)
            attempt.leftWorld.complete(Unit)
            executor.submit {
                DmHttpClient(current.participant.config).releaseTakeover(current.participant.hostId, attempt.claimId)
                    .onFailure { error -> logger.warn("Failed to release DM takeover claim", error) }
            }
        }
    }

    fun onHostingReady(hostId: UUID) = completeHosting(hostId, HostingResult.Ready)

    fun onHostingFailed(hostId: UUID) = completeHosting(hostId, HostingResult.Failed)

    /** The gateway refused this player's claim, so another player may be hosting or about to. */
    fun onClaimRefused(hostId: UUID) = completeHosting(hostId, HostingResult.ClaimRefused)

    private fun completeHosting(hostId: UUID, result: HostingResult) {
        flow?.takeIf { it.participant.hostId == hostId }?.attempt?.hosting?.complete(result)
    }

    private fun isCurrent(current: Flow): Boolean = flow === current && generation.get() == current.generation

    private fun run(current: Flow) {
        val client = DmHttpClient(current.participant.config)
        try {
            if (current.parent == null) {
                if (!waitForOffline(current, client)) return
                showScreen(current)
            }
            claimLoop(current, client)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            logger.error("DM takeover failed", error)
            finish(current, "接管失败：${error.message ?: "未知错误"}")
        }
    }

    private fun waitForOffline(current: Flow, client: DmHttpClient): Boolean {
        // The disconnect may still be tearing the old level down here; whether the player moved on
        // to another world is checked on the client thread before the takeover screen opens.
        val started = System.currentTimeMillis()
        val deadline = started + OFFLINE_WAIT_MILLIS
        while (isCurrent(current) && System.currentTimeMillis() < deadline) {
            client.getHost(current.participant.hostId).onSuccess { host ->
                when (afterDisconnect(host, System.currentTimeMillis() - started)) {
                    Step.Claim -> return true
                    Step.Abort -> {
                        logger.info("DM host {} is still online; not taking over", current.participant.hostId)
                        abandon(current)
                        return false
                    }
                    else -> {}
                }
            }.onFailure { error -> logger.warn("Cannot query DM host after disconnect", error) }
            Thread.sleep(POLL_MILLIS)
        }
        if (isCurrent(current)) logger.info("DM host {} was not reported offline in time; not taking over", current.participant.hostId)
        abandon(current)
        return false
    }

    private fun claimLoop(current: Flow, client: DmHttpClient) {
        val participant = current.participant
        var failures = 0
        while (isCurrent(current)) {
            val claim = client.claimTakeover(participant.hostId, participant.candidateId).getOrElse { error ->
                failures++
                logger.warn("DM takeover claim failed ({}/{})", failures, MAX_CLAIM_FAILURES, error)
                if (failures >= MAX_CLAIM_FAILURES) {
                    finish(current, "无法接管房间：${error.message ?: "网络错误"}")
                    return
                }
                status = "正在联系服务器…"
                Thread.sleep(NEW_HOST_POLL_MILLIS)
                null
            } ?: continue
            failures = 0
            when (claim) {
                is DmTakeoverClaim.Pending -> {
                    status = "房主已离线，正在选择新房主…"
                    Thread.sleep(claim.retryAfterMs + 100)
                }
                is DmTakeoverClaim.Claimed -> {
                    status = "其他玩家正在接管房间，稍后自动重连"
                    if (!waitForNewHost(current, client)) return
                }
                DmTakeoverClaim.HostOnline -> {
                    reconnect(current)
                    return
                }
                is DmTakeoverClaim.Granted -> {
                    if (!becomeHost(current, client, claim.claimId)) return
                    status = "接管资格已失效，正在重新申请…"
                }
                DmTakeoverClaim.Incompatible -> {
                    finish(current, "你的Mod和房间不一致，不能接管房间。${modDifference(client, participant.hostId)}")
                    return
                }
            }
        }
    }

    /** Returns true when this player should claim again because the chosen player gave up. */
    private fun waitForNewHost(current: Flow, client: DmHttpClient): Boolean {
        val deadline = System.currentTimeMillis() + NEW_HOST_WAIT_MILLIS
        while (isCurrent(current) && System.currentTimeMillis() < deadline) {
            Thread.sleep(NEW_HOST_POLL_MILLIS)
            val host = client.getHost(current.participant.hostId)
                .onFailure { error -> logger.warn("Cannot query DM host while waiting for the new host", error) }
                .getOrNull() ?: continue
            when (whileOthersTakeOver(host)) {
                Step.Reconnect -> {
                    reconnect(current)
                    return false
                }
                Step.Claim -> return true
                else -> {}
            }
        }
        finish(current, "等待新房主超时，请稍后从「加入DM房间」重新进入")
        return false
    }

    /** Returns true when this player lost the claim and should apply again. */
    private fun becomeHost(current: Flow, client: DmHttpClient, claimId: UUID): Boolean {
        val hostId = current.participant.hostId
        val attempt = Attempt(claimId)
        current.attempt = attempt
        val renewer = executor.submit { renewLoop(current, client, attempt) }
        try {
            status = "正在下载最新世界…"
            val cache = DmWorldCache(Minecraft.getInstance().gameDirectory.toPath(), hostId)
            val download = cache.downloadAndReplace(client) { !isCurrent(current) || attempt.claimLost }.getOrElse { error ->
                if (isCurrent(current) && attempt.claimLost) return onClaimLost(current, hostId)
                throw error
            }
            DmWorldCache.removeLevelPlayer(cache.world).getOrThrow()
            DmWorldAssociationStore.write(cache.world, DmWorldAssociation(hostId, DmCheckpointMark(download?.cycleId))).getOrThrow()
            if (!isCurrent(current)) return false
            status = "正在打开世界…"
            onClient(current) {
                // A claim lost up to here stops the takeover; a later loss is settled by the gateway.
                attempt.opening = true
                if (attempt.claimLost) {
                    attempt.hosting.complete(HostingResult.ClaimRefused)
                    return@onClient
                }
                val minecraft = Minecraft.getInstance()
                WorldOpenFlows(minecraft, LevelStorageSource.createDefault(cache.directory))
                    .openWorld(DmWorldCache.WORLD_NAME) { attempt.hosting.complete(HostingResult.Failed) }
            }
            val hosting = try {
                attempt.hosting.get(HOSTING_START_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                HostingResult.Failed
            }
            if (!isCurrent(current)) return false
            when (hosting) {
                HostingResult.Ready -> {
                    logger.info("Took over DM host {}", hostId)
                    current.attempt = null
                    flow = null
                }
                HostingResult.Failed -> {
                    release(current, client, claimId)
                    finish(current, "无法开始托管房间")
                }
                HostingResult.ClaimRefused -> {
                    leaveWorld(current, attempt)
                    return isCurrent(current) && onClaimLost(current, hostId)
                }
            }
            return false
        } catch (error: Throwable) {
            release(current, client, claimId)
            throw error
        } finally {
            renewer.cancel(true)
        }
    }

    private fun onClaimLost(current: Flow, hostId: UUID): Boolean {
        logger.warn("Lost the takeover claim for DM host {}; applying again", hostId)
        current.attempt = null
        return true
    }

    /** Saves and closes a world this player may no longer host, then returns to the takeover screen. */
    private fun leaveWorld(current: Flow, attempt: Attempt) {
        status = "接管资格已失效，正在退出世界…"
        onClient(current) {
            val minecraft = Minecraft.getInstance()
            minecraft.level?.let { level ->
                level.disconnect()
                minecraft.disconnect(GenericMessageScreen(Component.translatable("menu.savingLevel")))
            }
            minecraft.setScreen(DmTakeoverScreen(current.parent ?: TitleScreen()))
            attempt.leftWorld.complete(Unit)
        }
        try {
            attempt.leftWorld.get(LEAVE_WORLD_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // Only reachable when the player quit first and dropped the queued task; they chose to leave.
            logger.warn("Timed out leaving the world of DM host {}", current.participant.hostId)
            abandon(current)
        }
    }

    private fun renewLoop(current: Flow, client: DmHttpClient, attempt: Attempt) {
        while (!Thread.currentThread().isInterrupted && !attempt.hosting.isDone) {
            try {
                Thread.sleep(RENEW_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
            client.renewTakeover(current.participant.hostId, attempt.claimId).onFailure { error ->
                if (error is SyncChunkRequestError && error.reason == "ClaimLost") {
                    if (!attempt.opening) attempt.claimLost = true
                    return
                }
                logger.warn("Failed to renew DM takeover claim", error)
            }
        }
    }

    /** Which mods differ, or an empty string when the room's fingerprint cannot be read. */
    private fun modDifference(client: DmHttpClient, hostId: UUID): String {
        val room = client.getHostMods(hostId).getOrElse { error ->
            logger.warn("Failed to read the mods of DM host {}", hostId, error)
            null
        } ?: return ""
        val local = DmModFingerprints.local(null)
        return DmModFingerprint.describe(DmModFingerprint.diff(room.fingerprint, local), room.fingerprint, local)
    }

    private fun release(current: Flow, client: DmHttpClient, claimId: UUID) {
        current.attempt = null
        client.releaseTakeover(current.participant.hostId, claimId)
            .onFailure { error -> logger.warn("Failed to release DM takeover claim", error) }
    }

    private fun showScreen(current: Flow) {
        onClient(current) {
            val minecraft = Minecraft.getInstance()
            // While the disconnect screen is up the old level may not be cleared yet.
            if (minecraft.screen !is DisconnectedScreen && (minecraft.level != null || minecraft.connection != null)) {
                logger.info("Player entered another world; not taking over DM host {}", current.participant.hostId)
                abandon(current)
                return@onClient
            }
            minecraft.setScreen(DmTakeoverScreen(minecraft.screen ?: TitleScreen()))
        }
    }

    private fun reconnect(current: Flow) {
        onClient(current) {
            flow = null
            Minecraft.getInstance().setScreen(DmJoinScreen(current.parent ?: TitleScreen(), current.participant.hostId, autoJoin = true))
        }
    }

    private fun finish(current: Flow, message: String) {
        if (!isCurrent(current)) return
        status = message
        current.finished = true
    }

    private fun abandon(current: Flow) {
        if (isCurrent(current)) flow = null
    }

    private fun onClient(current: Flow, action: () -> Unit) {
        Minecraft.getInstance().execute { if (isCurrent(current)) action() }
    }
}
