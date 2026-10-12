package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketClientSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.PersistentPacketServerSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketServerStore
import calebxzau.rdi.mc.zstdcodec.PersistentPacketStore
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.v21.PersistentPacketPayloads21
import io.netty.util.AttributeKey
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener
import net.minecraft.server.network.ConfigurationTask
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * NeoForge side of persistent packets: before the tag tables are synchronised, a configuration task asks
 * the client which recipe and tag tables it kept, so the codec can name those instead of sending them again.
 *
 * On by default; `-Drdi.pcache.enabled=false` stops asking new connections, which then receive plain
 * packets. A client without the `rdi:pcache` channel is never asked and keeps plain packets as well.
 */
object RServerPersistentPackets {
    private val logger = LogManager.getLogger("rdi")
    private val taskType = ConfigurationTask.Type("rdi:pcache")
    private val pendingKey = AttributeKey.valueOf<QueryTask>("rdi.pcache.pending21")
    private const val ANSWER_TIMEOUT_SECONDS = 10L

    /** Each kind keeps a few older versions on disk as delta bases for clients that missed an update. */
    private const val KEEP_PER_KIND = 4

    private val diskExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RDI persistent packet store").apply { isDaemon = true }
    }
    private val store by lazy {
        PersistentPacketServerStore(PersistentPacketStore(Path.of("rdi", "pcache"), KEEP_PER_KIND), diskExecutor)
    }

    internal fun isEnabled(property: (String) -> String?): Boolean =
        property("rdi.pcache.enabled")?.trim()?.toBoolean() ?: true

    /** Whether to ask this connection; the client must have negotiated the query channel. */
    internal fun shouldAsk(enabled: Boolean, memoryConnection: Boolean, clientHasChannel: Boolean): Boolean =
        enabled && !memoryConnection && clientHasChannel

    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar(PersistentPacketPayloads21.VERSION)
            .optional()
            .configurationToClient(PersistentPacketPayloads21.Query.TYPE, PersistentPacketPayloads21.Query.STREAM_CODEC) { _, _ -> }
            .configurationToServer(PersistentPacketPayloads21.Answer.TYPE, PersistentPacketPayloads21.Answer.STREAM_CODEC, ::onAnswer)
    }

    /**
     * The task to queue before NeoForge's registry and tag synchronisation, or null to keep plain packets.
     * Called on the server thread while the configuration tasks are assembled.
     */
    @JvmStatic
    fun task(listener: ServerConfigurationPacketListener): ConfigurationTask? {
        val ask = shouldAsk(
            isEnabled(System::getProperty),
            listener.connection.isMemoryConnection,
            listener.hasChannel(PersistentPacketPayloads21.Query.TYPE),
        )
        return if (ask) QueryTask(listener) else null
    }

    /** Sends the query; the next task starts after the answer, or after a timeout with plain packets. */
    private class QueryTask(private val listener: ServerConfigurationPacketListener) : ICustomConfigurationTask {
        override fun type(): ConfigurationTask.Type = taskType

        override fun run(sender: Consumer<CustomPacketPayload>) {
            val channel = listener.connection.channel()
            channel.attr(pendingKey).set(this)
            CompletableFuture.delayedExecutor(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute {
                listener.mainThreadEventLoop.execute {
                    if (channel.attr(pendingKey).compareAndSet(this, null)) {
                        logger.warn("Player {} did not answer the persistent packet query in {}s; keeping plain join packets",
                            name(listener), ANSWER_TIMEOUT_SECONDS)
                        listener.finishCurrentTask(taskType)
                    }
                }
            }
            sender.accept(PersistentPacketPayloads21.Query(PersistentPacketQuery.encodeQuery()))
        }
    }

    /** Runs on the server thread. */
    private fun onAnswer(payload: PersistentPacketPayloads21.Answer, context: IPayloadContext) {
        val listener = context.listener() as? ServerConfigurationPacketListener ?: return
        val name = name(listener)
        val task = listener.connection.channel().attr(pendingKey).getAndSet(null)
        if (task == null) {
            logger.warn("Ignoring a late or unexpected persistent packet answer from {}", name)
            return
        }
        try {
            accept(listener, payload.body, name)
        } finally {
            listener.finishCurrentTask(taskType)
        }
    }

    private fun accept(listener: ServerConfigurationPacketListener, body: ByteArray, name: String) {
        val offer = PersistentPacketOffer.decode(body).getOrElse { error ->
            logger.error("Player {} keeps plain join packets; the persistent packet answer is malformed", name, error)
            return
        }
        if (offer.version != PersistentPacketQuery.VERSION) {
            logger.info("Player {} keeps plain join packets; client persistent packet version {} differs from {}", name, offer.version, PersistentPacketQuery.VERSION)
            return
        }
        val held = offer.entries.groupBy { it.kind }.mapValues { (_, entries) ->
            entries.take(PersistentPacketClientSession.HELD_PER_KIND).map { it.hash }
        }
        val trimmed = PersistentPacketOffer(offer.version, held.flatMap { (kind, hashes) -> hashes.map { PersistentPacketOffer.Entry(kind, it) } })
        // Attached before this task finishes, so it is in place when the tag table is written.
        ZstdCompressionPipeline.attachPersistentPackets(listener.connection.channel(), PersistentPacketServerSession(trimmed, store))
        logger.info("Player {} persistent packets negotiated; the client holds {}", name, trimmed.entries.joinToString { "${it.kind}:${it.hash}" }.ifEmpty { "nothing yet" })
    }

    private fun name(listener: ServerConfigurationPacketListener): String =
        (listener as? ServerCommonPacketListenerImpl)?.owner?.name ?: "unknown"
}
