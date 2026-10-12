package calebxzhou.rdi.mc.server.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketClientSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.PersistentPacketServerSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketServerStore
import calebxzau.rdi.mc.zstdcodec.PersistentPacketStore
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.buffer.Unpooled
import io.netty.util.AttributeKey
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.event.entity.player.PlayerNegotiationEvent
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Forge side of persistent packets: during login negotiation the server asks the client which recipe
 * and tag tables it kept, so the codec can name those instead of sending them again.
 *
 * On by default; `-Drdi.pcache.enabled=false` stops asking new connections, which then receive plain
 * packets. A client without the extension answers without a body and keeps plain packets as well.
 */
object RServerPersistentPackets {
    private val logger = LogManager.getLogger("rdi")
    private val queryChannel = ResourceLocation.fromNamespaceAndPath(PersistentPacketQuery.CHANNEL_NAMESPACE, PersistentPacketQuery.CHANNEL_PATH)
    private val pendingKey = AttributeKey.valueOf<CompletableFuture<Void>>("rdi.pcache.pending")
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

    /** Sends the query; login proceeds after the answer, or after a timeout with plain packets. */
    fun onNegotiation(event: PlayerNegotiationEvent) {
        val connection = event.connection
        if (!isEnabled(System::getProperty) || connection.isMemoryConnection) return
        val name = event.profile?.name
        val pending = CompletableFuture<Void>()
        connection.channel().attr(pendingKey).set(pending)
        CompletableFuture.delayedExecutor(ANSWER_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute {
            if (pending.complete(null)) {
                connection.channel().attr(pendingKey).compareAndSet(pending, null)
                logger.warn("Player {} did not answer the persistent packet query in {}s; keeping plain join packets", name, ANSWER_TIMEOUT_SECONDS)
            }
        }
        event.enqueueWork(pending)
        val body = FriendlyByteBuf(Unpooled.wrappedBuffer(PersistentPacketQuery.encodeQuery()))
        connection.send(ClientboundCustomQueryPacket(PersistentPacketQuery.TRANSACTION_ID, queryChannel, body))
    }

    /** True when [packet] answered the query; the login listener must then ignore it. */
    @JvmStatic
    fun onQueryAnswer(connection: Connection, packet: ServerboundCustomQueryPacket, name: String): Boolean {
        if (packet.transactionId != PersistentPacketQuery.TRANSACTION_ID) return false
        val pending = connection.channel().attr(pendingKey).getAndSet(null)
        if (pending == null) {
            logger.warn("Ignoring a late or unexpected persistent packet answer from {}", name)
            return true
        }
        try {
            accept(connection, packet, name)
        } finally {
            pending.complete(null)
        }
        return true
    }

    private fun accept(connection: Connection, packet: ServerboundCustomQueryPacket, name: String) {
        val data = packet.data
        if (data == null) {
            logger.info("Player {} keeps plain join packets; the client does not support persistent packets", name)
            return
        }
        val body = ByteArray(data.readableBytes())
        data.readBytes(body)
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
        ZstdCompressionPipeline.attachPersistentPackets(connection.channel(), PersistentPacketServerSession(trimmed, store))
        logger.info("Player {} persistent packets negotiated; the client holds {}", name, trimmed.entries.joinToString { "${it.kind}:${it.hash}" }.ifEmpty { "nothing yet" })
    }
}
