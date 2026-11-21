package calebxzau.rdi.mc.v20.client.network

import calebxzhou.rdi.mc.common.RDI
import calebxzau.rdi.mc.zstdcodec.PersistentPacketClientSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketHash
import calebxzau.rdi.mc.zstdcodec.PersistentPacketKind
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.PersistentPacketStore
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import io.netty.channel.Channel
import net.minecraft.network.FriendlyByteBuf
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** Loads the room's join packets before the server can start sending persistent frames. */
object ClientPersistentPackets20 {
    private val logger = LogManager.getLogger("RDI Client Persistent Packets")
    private val kinds = listOf(PersistentPacketKind.RECIPES, PersistentPacketKind.TAGS)

    /** One more than the client offers, so a delta base survives one update of the server's tables. */
    private const val KEEP_PER_KIND = PersistentPacketClientSession.HELD_PER_KIND + 1

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RDI persistent packet cache").apply { isDaemon = true }
    }

    fun prepareReply(channel: Channel, query: FriendlyByteBuf): CompletableFuture<ByteArray?> {
        if (query.readableBytes() != 1) {
            logger.warn("Ignoring a persistent packet query with {} bytes", query.readableBytes())
            return CompletableFuture.completedFuture(null)
        }
        val version = query.getUnsignedByte(query.readerIndex()).toInt()
        if (version != PersistentPacketQuery.VERSION) {
            logger.info("Server persistent packet version {} differs from {}; keeping plain join packets",
                version, PersistentPacketQuery.VERSION)
            return CompletableFuture.completedFuture(PersistentPacketOffer(PersistentPacketQuery.VERSION, emptyList()).encode())
        }
        return CompletableFuture.supplyAsync({
            runCatching { prepare(channel).encode() }.getOrElse { error ->
                logger.error("Failed to prepare persistent packets; keeping plain join packets", error)
                null
            }
        }, executor)
    }

    /** Loads and verifies the newest tables of this host, then attaches the session that restores them. */
    private fun prepare(channel: Channel): PersistentPacketOffer {
        val store = PersistentPacketStore(root(), KEEP_PER_KIND)
        val entries = ArrayList<PersistentPacketOffer.Entry>()
        val loaded = LinkedHashMap<PersistentPacketHash, Pair<Int, ByteArray>>()
        for (kind in kinds) {
            val hashes = store.recent(kind, PersistentPacketClientSession.HELD_PER_KIND).getOrElse { error ->
                logger.error("Failed to list kept {} tables", PersistentPacketKind.name(kind), error)
                emptyList()
            }
            val verified = hashes.mapNotNull { hash ->
                store.load(kind, hash)
                    .onFailure { error -> logger.error("Failed to read kept {} table {}", PersistentPacketKind.name(kind), hash, error) }
                    .getOrNull()
                    ?.let { hash to it }
            }
            // The offer lists the newest first; the session ages its entries oldest first.
            verified.forEach { (hash, _) -> entries += PersistentPacketOffer.Entry(kind, hash) }
            verified.asReversed().forEach { (hash, content) -> loaded[hash] = kind to content }
        }
        val session = PersistentPacketClientSession(loaded) { kind, hash, content ->
            executor.execute {
                store.save(kind, hash, content).onFailure { error ->
                    logger.error("Failed to keep the {} table {}", PersistentPacketKind.name(kind), hash, error)
                }
            }
        }
        ZstdCompressionPipeline.attachPersistentPackets(channel, session)
        logger.info("Offering kept join packets: {}", entries.joinToString { "${PersistentPacketKind.name(it.kind)}:${it.hash}" }.ifEmpty { "none yet" })
        return PersistentPacketOffer(PersistentPacketQuery.VERSION, entries)
    }

    /** The launcher passes rdi.host.id for room joins; other multiplayer joins share one directory. */
    private fun root(): Path = Path.of("rdi", "hosts", RDI.HOST_ID ?: "shared", "pcache")
}
