package calebxzhou.rdi.mc.client.network

import calebxzau.rdi.mc.zstdcodec.PersistentPacketClientSession
import calebxzau.rdi.mc.zstdcodec.PersistentPacketHash
import calebxzau.rdi.mc.zstdcodec.PersistentPacketKind
import calebxzau.rdi.mc.zstdcodec.PersistentPacketOffer
import calebxzau.rdi.mc.zstdcodec.PersistentPacketQuery
import calebxzau.rdi.mc.zstdcodec.PersistentPacketStore
import calebxzau.rdi.mc.zstdcodec.ZstdCompressionPipeline
import calebxzau.rdi.mc.zstdcodec.v21.PersistentPacketPayloads21
import calebxzhou.rdi.mc.common.RDI
import net.minecraft.network.Connection
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import org.apache.logging.log4j.LogManager
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Client side of persistent packets: the recipe and tag tables of each host stay on disk, so a later
 * join restores them from the server's short reference or delta instead of downloading them again.
 *
 * The server asks during configuration, before it sends the tag table. The answer is built in the
 * background and sent once the session is attached to the decoder; the server waits until then.
 */
object RClientPersistentPackets {
    private val logger = LogManager.getLogger("RDI Client Persistent Packets")
    private val kinds = listOf(PersistentPacketKind.RECIPES, PersistentPacketKind.TAGS)

    /** One more than the client offers, so a delta base survives one update of the server's tables. */
    private const val KEEP_PER_KIND = PersistentPacketClientSession.HELD_PER_KIND + 1

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RDI persistent packet cache").apply { isDaemon = true }
    }

    fun register(event: RegisterPayloadHandlersEvent) {
        event.registrar(PersistentPacketPayloads21.VERSION)
            .optional()
            .configurationToClient(PersistentPacketPayloads21.Query.TYPE, PersistentPacketPayloads21.Query.STREAM_CODEC, ::onQuery)
            .configurationToServer(PersistentPacketPayloads21.Answer.TYPE, PersistentPacketPayloads21.Answer.STREAM_CODEC) { _, _ -> }
    }

    /** Runs on the client thread. */
    private fun onQuery(payload: PersistentPacketPayloads21.Query, context: IPayloadContext) {
        val connection = context.connection()
        val serverVersion = PersistentPacketQuery.decodeQuery(payload.body).getOrElse { error ->
            logger.error("Ignoring a malformed persistent packet query; keeping plain join packets", error)
            answer(connection, PersistentPacketOffer(PersistentPacketQuery.VERSION, emptyList()))
            return
        }
        if (serverVersion != PersistentPacketQuery.VERSION) {
            logger.info("Server persistent packet version {} differs from {}; keeping plain join packets", serverVersion, PersistentPacketQuery.VERSION)
            answer(connection, PersistentPacketOffer(PersistentPacketQuery.VERSION, emptyList()))
            return
        }
        executor.execute {
            val offer = runCatching { prepare(connection) }.getOrElse { error ->
                logger.error("Failed to prepare persistent packets; keeping plain join packets", error)
                PersistentPacketOffer(PersistentPacketQuery.VERSION, emptyList())
            }
            answer(connection, offer)
        }
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

    /** An empty offer of another version tells the server this client keeps plain join packets. */
    private fun answer(connection: Connection, offer: PersistentPacketOffer) {
        connection.send(ServerboundCustomPayloadPacket(PersistentPacketPayloads21.Answer(offer.encode())))
    }

    /** The launcher passes rdi.host.id for room joins; other multiplayer joins share one directory. */
    private fun root(): Path = Path.of("rdi", "hosts", RDI.HOST_ID ?: "shared", "pcache")
}
