package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.PacketCaptureRecorder
import com.google.gson.GsonBuilder
import net.minecraft.SharedConstants
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import io.netty.channel.Channel
import net.neoforged.fml.ModList
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** One capture writer per server run; all joined players share its memory and file budgets. */
object RServerPacketCapture {
    private val logger = LogManager.getLogger("rdi.packet-capture")

    @Volatile
    private var recorder: PacketCaptureRecorder? = null

    fun start(server: MinecraftServer) = start(
        server.serverDirectory.resolve("rdi").resolve("packbatch"),
        System.getProperty("rdi.capture.enabled"),
    ) {
        mapOf(
            "minecraft" to SharedConstants.getCurrentVersion().name,
            "loader" to "neoforge",
            "mods" to ModList.get().mods.associate { it.modId to it.version.toString() }.toSortedMap(),
        )
    }

    internal fun start(directory: Path, property: String?, runtimeMetadata: () -> Map<String, Any>) {
        if (!enabled(property)) return
        check(recorder == null) { "Packet capture already started" }
        val started = runCatching {
            Files.createDirectories(directory)
            PacketCaptureRecorder(directory, onFailure = { error ->
                logger.error("Packet capture stopped after a writer failure", error)
            })
        }.getOrElse { error ->
            logger.error("Failed to start packet capture at {}", directory, error)
            return
        }
        recorder = started
        // Run metadata is written once during startup, never on a network event loop.
        runCatching {
            val metadata = linkedMapOf(
                "format" to "RDPC",
                "version" to PacketCaptureRecorder.FORMAT_VERSION,
                "run_id" to started.runId.toString(),
                "started_unix_ms" to started.startedEpochMillis,
                "direction" to "s2c",
                "capture_start" to "RegisterConfigurationTasksEvent",
                "phases" to listOf("configuration", "play"),
            )
            metadata.putAll(runtimeMetadata())
            Files.writeString(
                directory.resolve("${started.runId}.json"),
                GsonBuilder().setPrettyPrinting().create().toJson(metadata),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        }.onFailure { error -> logger.error("Failed to write packet capture run metadata", error) }
        logger.info("Recording Configuration and Play custom payloads and attributes to {} (run {})", directory, started.runId)
    }

    internal fun enabled(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        null, "true" -> true
        "false" -> false
        else -> {
            logger.warn("Invalid rdi.capture.enabled value {}; keeping capture enabled", value)
            true
        }
    }

    /** Execute inline on the event loop so the next configuration task cannot overtake attachment. */
    internal fun onEventLoop(channel: Channel, action: () -> Unit) {
        if (channel.eventLoop().inEventLoop()) action() else channel.eventLoop().execute(action)
    }

    fun onConfigurationStarted(listener: ServerCommonPacketListenerImpl) {
        attach(listener.connection.channel(), listener.owner.id)
    }

    fun onPlayerJoined(player: ServerPlayer) {
        attach(player.connection.connection.channel(), player.uuid)
    }

    private fun attach(channel: Channel, playerId: java.util.UUID) {
        val active = recorder ?: return
        if (channel is io.netty.channel.local.LocalChannel) return
        onEventLoop(channel) {
            if (!channel.isActive || recorder !== active) return@onEventLoop
            if (!PacketRecordPipeline.attachCapture(channel) { active.connection(playerId) }) {
                logger.warn("Packet capture unavailable for player {}: record pipeline is absent", playerId)
            }
        }
    }

    fun stop() {
        val active = recorder ?: return
        recorder = null
        active.close().fold(
            onSuccess = { logger.info("Packet capture run {} closed; dropped records={}", active.runId, active.droppedSamples) },
            onFailure = { error -> logger.error("Packet capture run ${active.runId} did not close cleanly", error) },
        )
    }
}
