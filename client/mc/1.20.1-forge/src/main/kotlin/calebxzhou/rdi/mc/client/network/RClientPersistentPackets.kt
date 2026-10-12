package calebxzhou.rdi.mc.client.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketClientSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketHash
import calebxzau.rdi.mc.zstdcodec.PersistentPacketKind
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.PersistentPacketStore
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzhou.rdi.mc.common.RDI
import io.netty.buffer.Unpooled
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Client side of persistent packets: the recipe and tag tables of each host stay on disk, so a later
 * join restores them from the server's short reference or delta instead of downloading them again.
 *
 * The answer is built in the background and sent once the session is attached to the decoder; the
 * server holds the login until then.
 */
object RClientPersistentPackets {
    private val logger = LogManager.getLogger("RDI Client Persistent Packets")
    private val kinds = listOf(PersistentPacketKind.RECIPES, PersistentPacketKind.TAGS)

    /** One more than the client offers, so a delta base survives one update of the server's tables. */
    private const val KEEP_PER_KIND = PersistentPacketClientSession.HELD_PER_KIND + 1

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RDI persistent packet cache").apply { isDaemon = true }
    }

    /** True when [packet] is the persistent packet query; the login listener must then ignore it. */
    @JvmStatic
    fun onQuery(connection: Connection, packet: ClientboundCustomQueryPacket): Boolean {
        val identifier = packet.identifier
        if (identifier.namespace != PersistentPacketQuery.CHANNEL_NAMESPACE || identifier.path != PersistentPacketQuery.CHANNEL_PATH) {
            return false
        }
        val transactionId = packet.transactionId
        val body = ByteArray(packet.data.readableBytes())
        packet.data.getBytes(packet.data.readerIndex(), body)
        val serverVersion = PersistentPacketQuery.decodeQuery(body).getOrElse { error ->
            logger.error("Ignoring a malformed persistent packet query; keeping plain join packets", error)
            answer(connection, transactionId, null)
            return true
        }
        if (serverVersion != PersistentPacketQuery.VERSION) {
            logger.info("Server persistent packet version {} differs from {}; keeping plain join packets", serverVersion, PersistentPacketQuery.VERSION)
            answer(connection, transactionId, PersistentPacketOffer(PersistentPacketQuery.VERSION, emptyList()).encode())
            return true
        }
        executor.execute {
            val offer = runCatching { prepare(connection) }.getOrElse { error ->
                logger.error("Failed to prepare persistent packets; keeping plain join packets", error)
                null
            }
            answer(connection, transactionId, offer?.encode())
        }
        return true
    }

    /** Loads and verifies the newest tables of this host, then attaches the session that restores them. */
    private fun prepare(connection: Connection): PersistentPacketOffer {
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
        ZstdCompressionPipeline.attachPersistentPackets(connection.channel(), session)
        logger.info("Offering kept join packets: {}", entries.joinToString { "${PersistentPacketKind.name(it.kind)}:${it.hash}" }.ifEmpty { "none yet" })
        return PersistentPacketOffer(PersistentPacketQuery.VERSION, entries)
    }

    /** A null body tells the server this client keeps plain join packets. */
    private fun answer(connection: Connection, transactionId: Int, body: ByteArray?) {
        val data = body?.let { FriendlyByteBuf(Unpooled.wrappedBuffer(it)) }
        connection.send(ServerboundCustomQueryPacket(transactionId, data))
    }

    /** The launcher passes rdi.host.id for room joins; other multiplayer joins share one directory. */
    private fun root(): Path = Path.of("rdi", "hosts", RDI.HOST_ID ?: "shared", "pcache")
}
