@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.dm

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.DisconnectedScreen
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * A player connected to someone else's DM room. While connected it reports how well this
 * machine could host the room; when the connection drops unexpectedly it starts a takeover.
 */
object DmParticipantSession {
    private val logger = LoggerFactory.getLogger(DmParticipantSession::class.java)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()
    private const val REPORT_INTERVAL_MILLIS = 5 * 60 * 1000L

    data class Participant(val hostId: UUID, val config: DmConfig, val candidateId: UUID)

    private data class Expected(val hostId: UUID, val config: DmConfig, val address: String, var joinTicket: String?)

    private var expected: Expected? = null
    private var active: Participant? = null
    @Volatile private var helloTicket: String? = null
    private var reporter: Future<*>? = null
    @Volatile private var startupMillis: Long? = null

    /** Called on the client thread right before connecting to a DM room at [address]. */
    fun expect(hostId: UUID, config: DmConfig, address: String, joinTicket: String) {
        expected = Expected(hostId, config, address, joinTicket)
    }

    /**
     * A connection to [address] is starting. Any other server must never see a ticket: it would
     * fail on the extra bytes, and a ticket works only once anyway.
     */
    @JvmStatic
    fun onConnecting(address: String?) {
        val joining = expected?.takeIf { address != null && it.address == address }
        helloTicket = joining?.joinTicket
        joining?.joinTicket = null
    }

    /** The join ticket for the hello packet being written, handed out once. */
    @JvmStatic
    fun takeHelloTicket(): String? {
        val ticket = helloTicket
        helloTicket = null
        return ticket
    }

    /** The DM room about to be joined at [address], if any; its chunk cache is kept apart from other worlds. */
    fun expectedHostId(address: String?): UUID? = expected?.takeIf { address != null && it.address == address }?.hostId

    /** Game start to the first title screen; a proxy for how long this machine takes to load the modpack's world. */
    fun recordStartup() {
        if (startupMillis != null) return
        startupMillis = runCatching { System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().startTime }
            .getOrElse { error ->
                logger.warn("Cannot measure game startup time", error)
                null
            }?.takeIf { it > 0 }
        // Taken before any server connection replaces the synced registries with that server's.
        runCatching { DmModFingerprints.registries() }
            .onFailure { error -> logger.error("Cannot read the registry fingerprint", error) }
    }

    fun onLoggingIn(minecraft: Minecraft) {
        val joining = expected
        expected = null
        stopReporting()
        active = null
        // A failed DM connection leaves the expectation behind, so only the same address counts.
        if (joining == null || minecraft.isLocalServer || minecraft.currentServer?.ip != joining.address) return
        val participant = Participant(joining.hostId, joining.config, UUID.fromString(kotlin.uuid.Uuid.generateV7().toString()))
        active = participant
        reporter = executor.submit { reportLoop(participant) }
    }

    fun onLoggingOut(minecraft: Minecraft) {
        val participant = active ?: return
        active = null
        stopReporting()
        // The disconnect screen is set after this event. execute() would run right away on this
        // thread, so queue the check; the disconnect runs queued tasks once that screen is shown.
        minecraft.tell {
            if (minecraft.screen is DisconnectedScreen) DmTakeoverService.begin(participant)
        }
    }

    fun stop() {
        expected = null
        helloTicket = null
        active = null
        stopReporting()
    }

    private fun stopReporting() {
        reporter?.cancel(true)
        reporter = null
    }

    private fun reportLoop(participant: Participant) {
        val client = DmHttpClient(participant.config)
        val downloadBps = client.measureDownloadBps().getOrElse { error ->
            logger.warn("DM download speed test failed", error)
            null
        }
        val uploadBps = client.measureUploadBps().getOrElse { error ->
            logger.warn("DM upload speed test failed", error)
            null
        }
        while (!Thread.currentThread().isInterrupted) {
            val rttMs = client.measureRttMillis().getOrElse { error ->
                logger.warn("DM latency measurement failed", error)
                null
            }
            val runtime = Runtime.getRuntime()
            val metrics = DmCandidateMetrics(
                startupMillis, runtime.maxMemory(), runtime.availableProcessors(), rttMs, downloadBps, uploadBps,
                DmModFingerprints.registries().overall,
            )
            client.reportCandidate(participant.hostId, participant.candidateId, metrics)
                .onSuccess { logger.info("Reported DM takeover metrics for {}: {}", participant.hostId, metrics) }
                .onFailure { error -> logger.warn("Failed to report DM takeover metrics for {}", participant.hostId, error) }
            try {
                TimeUnit.MILLISECONDS.sleep(REPORT_INTERVAL_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
