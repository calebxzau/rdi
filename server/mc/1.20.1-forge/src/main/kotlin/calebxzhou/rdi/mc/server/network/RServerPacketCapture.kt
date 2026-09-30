package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.v20.server.network.PacketMetricsPipeline20
import calebxzau.rdi.mc.zstdcodec.PacketCaptureRecorder
import com.google.gson.GsonBuilder
import net.minecraft.SharedConstants
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraftforge.fml.ModList
import org.apache.logging.log4j.LogManager
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** One capture writer per server run; all joined players share its memory and file budgets. */
object RServerPacketCapture {
    private val logger = LogManager.getLogger("rdi.packet-capture")

    @Volatile
    private var recorder: PacketCaptureRecorder? = null

    fun start(server: MinecraftServer) {
        check(recorder == null) { "Packet capture already started" }
        val directory = server.serverDirectory.toPath().resolve("rdi").resolve("packbatch")
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
                "version" to 1,
                "run_id" to started.runId.toString(),
                "started_unix_ms" to started.startedEpochMillis,
                "direction" to "s2c",
                "minecraft" to SharedConstants.getCurrentVersion().name,
                "loader" to "forge",
                "mods" to ModList.get().mods.associate { it.modId to it.version.toString() }.toSortedMap(),
            )
            Files.writeString(
                directory.resolve("${started.runId}.json"),
                GsonBuilder().setPrettyPrinting().create().toJson(metadata),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        }.onFailure { error -> logger.error("Failed to write packet capture run metadata", error) }
        logger.info("Recording all joined players' outbound packet contents to {} (run {})", directory, started.runId)
    }

    fun onPlayerJoined(player: ServerPlayer) {
        val active = recorder ?: return
        val channel = player.connection.connection.channel()
        val capture = active.connection(player.uuid)
        channel.eventLoop().execute {
            if (!channel.isActive) return@execute
            if (!PacketMetricsPipeline20.attachCapture(channel, capture)) {
                logger.warn("Packet capture unavailable for player {}: metrics pipeline is absent", player.gameProfile.name)
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
