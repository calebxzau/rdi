package calebxzau.rdi.mc.v20.server.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketClientSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketHash
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.PersistentPacketServerSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketServerStore
import calebxzau.rdi.mc.zstdcodec.PersistentPacketStore
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.channel.Channel
import net.minecraft.network.FriendlyByteBuf
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.concurrent.Executors

object ServerPersistentPackets20 {
    private val logger = LogManager.getLogger("rdi")
    private const val MAXIMUM_OFFER_BYTES = 3 + PersistentPacketOffer.MAXIMUM_ENTRIES * (1 + PersistentPacketHash.BYTES)

    /** Each kind keeps a few older versions on disk as delta bases for clients that missed an update. */
    private const val KEEP_PER_KIND = 4

    private val diskExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RDI persistent packet store").apply { isDaemon = true }
    }
    private val store by lazy {
        PersistentPacketServerStore(PersistentPacketStore(Path.of("rdi", "pcache"), KEEP_PER_KIND), diskExecutor)
    }

    fun isEnabled(property: (String) -> String? = System::getProperty): Boolean =
        property("rdi.pcache.enabled")?.trim()?.toBoolean() ?: true

    fun accept(channel: Channel, data: FriendlyByteBuf?, name: String) {
        if (data == null) {
            logger.info("Player {} keeps plain join packets; the client does not support persistent packets", name)
            return
        }
        if (data.readableBytes() > MAXIMUM_OFFER_BYTES) {
            logger.warn("Player {} keeps plain join packets; persistent packet answer is too large", name)
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
        ZstdCompressionPipeline.attachPersistentPackets(channel, PersistentPacketServerSession(trimmed, store))
        logger.info("Player {} persistent packets negotiated; the client holds {}", name, trimmed.entries.joinToString { "${it.kind}:${it.hash}" }.ifEmpty { "nothing yet" })
    }
}
